/*
 * Copyright 2025 QingWan (qingwanmail@foxmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package qing.albatross.manager.utils;


import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import qing.albatross.manager.R;
import qing.albatross.manager.data.ConfigManager;
import qing.albatross.manager.data.ServerDatabaseHelper;
import qing.albatross.manager.plugin.PluginDelegate;
import qing.albatross.manager.model.ServerInfo;

public class ServerManager {

  private static final String TAG = "ServerManager";
  //服务启动后探测的首次间隔与最大间隔，失败时按 1.5 倍退避
  private static final long PROBE_FIRST_INTERVAL_MS = 80;
  private static final long PROBE_MAX_INTERVAL_MS = 600;
  //启动最长等待时间
  private static final long START_TIMEOUT_MS = 15000;
  //超过该时间仍未连上，就先确认一下服务进程是否还活着
  private static final long ALIVE_CHECK_AFTER_MS = 2500;
  //su 辅助命令的超时，避免授权弹窗导致一直等待
  private static final long SU_COMMAND_TIMEOUT_MS = 3000;
  private static final int MAX_CAPTURED_OUTPUT = 8 * 1024;
  private static final Pattern PID_PATTERN = Pattern.compile("\\b\\d{2,}\\b");
  //su 命令成功时回显的标记
  private static final String SU_SUCCESS_TAG = "__ALB_SU_OK__";
  //su 候选路径：现代 Magisk 把 su 挂在 /system_ext/bin（API30+）/ /debug_ramdisk，经典路径排后面。
  //候选一律要"真的能用"才算数（/debug_ramdisk/su 在 SELinux 下 app 往往不可执行）
  private static final String[] SU_CANDIDATES = {
      "/system/bin/su", "/system_ext/bin/su", "/debug_ramdisk/su", "/system/xbin/su", "/sbin/su",
      "/system/su", "/system/bin/.ext/su", "/system/usr/we-need-root/su",
      "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su"};
  //`su -v` 快速判别：MagiskSU/KernelSU 由客户端本地应答（不弹授权框），userdebug 的假 su 会直接报参数错
  private static final long SU_PROBE_FAST_TIMEOUT_MS = 1500;
  //`su -c id` 兜底：真起一次 root，首次可能弹授权框，给一点时间
  private static final long SU_PROBE_DEEP_TIMEOUT_MS = 5000;

  private static ServerManager instance;
  private final Context context;
  //  private final FileStorageManager fileStorage;
  private final ConfigManager configManager;
  private Process serverProcess;
  private boolean isServerRunning = false;
  private volatile long lastStartCostMs = 0;
  private volatile String lastStartError;

  private final ServerDatabaseHelper dbHelper;


  private ServerManager(Context context) {
    this.context = context.getApplicationContext();
    this.dbHelper = ServerDatabaseHelper.getInstance(this.context);
    this.configManager = ConfigManager.getInstance(context);
  }

  public static synchronized ServerManager getInstance(Context context) {
    if (instance == null) {
      instance = new ServerManager(context);
    }
    return instance;
  }


  /**
   * 构建服务启动命令
   */
  private List<String> buildStartCommands(ServerInfo serverInfo, String rootPath, boolean launch) {
    List<String> commands = new ArrayList<>();
    // 创建服务运行目录
    commands.add("mkdir -p " + rootPath);
    // 复制服务文件到目标路径
//    String serverFileName = "albatross_server";
    String libFileName = "libalbatross_base.so";
    String serverPath = serverInfo.getServerPath();
    String serverFileDst = rootPath + "albatross_server";
    commands.add("cp " + serverPath + " " + serverFileDst);
    String agentPath = serverInfo.getAgentPath();
    commands.add("cp " + serverInfo.getLibPath() + " " + rootPath + libFileName);
    // 如果有32位库文件，也进行复制
    if (serverInfo.isSupport32Bit() && serverInfo.getLib32Path() != null) {
      commands.add("mkdir -p " + rootPath + "32bit/");
      commands.add("cp " + serverInfo.getLib32Path() + " " + rootPath + "32bit/" + libFileName);
    }
    String appAgentPath = rootPath + "app_agent.dex";
    String systemAgent = rootPath + "system_server.dex";
    commands.add("cp " + agentPath + "/app_agent.dex " + appAgentPath);
    commands.add("cp " + agentPath + "/system_server.dex " + systemAgent);
    commands.add("chmod 444 " + appAgentPath);
    commands.add("chmod 444 " + systemAgent);
    // 设置文件权限
    commands.add("chmod 755 " + serverFileDst);
    commands.add("chmod 644 " + rootPath + libFileName);
    String SERVER_ADDRESS = ConfigManager.getInstance(context).getServerAddress();
    if (launch) {
      // 设置LD_LIBRARY_PATH，确保能找到库文件
      commands.add("export LD_LIBRARY_PATH=" + rootPath + ":$LD_LIBRARY_PATH");
      // 启动服务（后台运行）
      commands.add("nohup " + serverFileDst + " " + SERVER_ADDRESS + " >" + rootPath + "albatross_manager.log 2>&1 &");
    } else {
      commands.add("echo success");
    }
    commands.add("exit");
    return commands;
  }

  /**
   * 启动服务（使用当前选中的版本）
   */
  public boolean startServer() {
    if (isServerRunning) {
      Log.i(TAG, "服务已在运行中");
      return true;
    }
    if (!isDeviceRooted()) {
      Log.e(TAG, "未获取Root权限，无法启动服务");
      showToast(context.getString(R.string.server_no_root));
      return false;
    }
    String suPath = configManager.getSuFilePath();
    // 获取当前选中的服务版本信息
    ServerInfo currentServer = dbHelper.getCurrentServerInfo();
    if (currentServer == null) {
      Log.e(TAG, "未找到当前服务版本信息");
      showToast(context.getString(R.string.server_not_found));
      return false;
    }
    // 获取Root路径配置
    String rootPath = dbHelper.getRootPath();
    Process suProcess = null;
    lastStartError = null;
    lastStartCostMs = 0;
    try {
      List<String> commands = buildStartCommands(currentServer, rootPath, true);
      // 使用交互式SU启动服务
      suProcess = Runtime.getRuntime().exec(suPath);
      DataOutputStream outputStream = new DataOutputStream(suProcess.getOutputStream());
      // 命令可能在复制文件时失败，其输出用于启动失败时定位问题
      StringBuilder suOutput = new StringBuilder();
      startOutputReaders(suProcess, suOutput);
      // 执行所有命令
      for (String cmd : commands) {
        outputStream.writeBytes(cmd + "\n");
        outputStream.flush();
      }
      // 命令写入后立即开始探测，服务就绪即刻返回，不再固定等待
      if (waitForServer()) {
        isServerRunning = true;
        Log.i(TAG, "服务启动成功, 版本: " + currentServer.getVersion() + ", 耗时:" + lastStartCostMs + "ms");
        showToast(context.getString(R.string.server_start_success_cost,
            currentServer.getVersion(), lastStartCostMs / 1000f));
        serverProcess = suProcess;
        return true;
      }
      lastStartError = buildFailureReason(rootPath, suOutput.toString());
      Log.e(TAG, "服务启动失败:" + lastStartError);
      showToast(context.getString(R.string.server_start_failed_reason, lastStartError));
      return false;
    } catch (Throwable e) {
      lastStartError = e.toString();
      Log.e(TAG, "服务启动异常: " + e.getMessage(), e);
      showToast(context.getString(R.string.server_start_exception_format, e.getMessage()));
      if (suProcess != null) {
        startOutputReaders(suProcess, null);
      }
      return false;
    }
  }

  /**
   * 轮询等待服务就绪。连接失败会立刻返回（socket 不存在/拒绝），所以这里可以密集探测，
   * 正常情况下服务就绪后几十毫秒内即可返回。
   */
  private boolean waitForServer() {
    long start = SystemClock.elapsedRealtime();
    long interval = PROBE_FIRST_INTERVAL_MS;
    boolean aliveChecked = false;
    while (true) {
      if (PluginDelegate.checkIsRunning(dbHelper, context)) {
        lastStartCostMs = SystemClock.elapsedRealtime() - start;
        return true;
      }
      long elapsed = SystemClock.elapsedRealtime() - start;
      if (elapsed >= START_TIMEOUT_MS) {
        lastStartCostMs = elapsed;
        return false;
      }
      if (!aliveChecked && elapsed >= ALIVE_CHECK_AFTER_MS && !isServerProcessAlive()) {
        // 进程已经不在了，再等下去也不会起来
        lastStartCostMs = elapsed;
        return false;
      }
      aliveChecked = true;
      try {
        Thread.sleep(Math.min(interval, START_TIMEOUT_MS - elapsed));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
      interval = Math.min(interval * 3 / 2, PROBE_MAX_INTERVAL_MS);
    }
  }

  /**
   * 服务进程是否存活，用于启动失败时提前结束等待
   */
  private boolean isServerProcessAlive() {
    // 判断不了时按存活处理，避免误判导致提前失败
    String result = execSuCommand("pidof albatross_server");
    if (result == null) {
      return true;
    }
    // pidof 输出的是 pid 列表，su 可能把提示信息混在输出里，只认其中的数字
    return PID_PATTERN.matcher(result).find();
  }

  /**
   * 启动失败的原因：su 的输出与服务日志尾部
   */
  private String buildFailureReason(String rootPath, String suOutput) {
    StringBuilder reason = new StringBuilder();
    String log = execSuCommand("tail -c 1500 " + rootPath + "albatross_manager.log");
    if (log != null && log.trim().length() > 0) {
      reason.append(log.trim());
    }
    if (suOutput != null && suOutput.trim().length() > 0) {
      if (reason.length() > 0) {
        reason.append('\n');
      }
      reason.append(suOutput.trim());
    }
    if (reason.length() == 0) {
      reason.append(context.getString(R.string.server_start_no_output));
    }
    return reason.length() > 600 ? reason.substring(reason.length() - 600) : reason.toString();
  }

  /**
   * 用 su 执行一条命令并返回输出，失败或超时返回 null
   */
  private String execSuCommand(String command) {
    String suPath = configManager.getSuFilePath();
    if (suPath == null) {
      return null;
    }
    Process process = null;
    try {
      process = new ProcessBuilder(suPath, "-c", command)
          .redirectErrorStream(true)
          .start();
      // su 可能因为没有授权而一直等待，不能无限等下去
      if (!process.waitFor(SU_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        Log.w(TAG, "命令执行超时:" + command);
        return null;
      }
      // 进程已退出，此时读取输出不会阻塞
      StringBuilder output = new StringBuilder();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append('\n');
        }
      }
      return output.toString();
    } catch (Throwable e) {
      Log.w(TAG, "执行命令失败:" + command, e);
      return null;
    } finally {
      if (process != null) {
        process.destroy();
      }
    }
  }

  /**
   * 服务上次启动耗时(ms)，未启动过或正在启动时为 0
   */
  public long getLastStartCostMs() {
    return lastStartCostMs;
  }

  /**
   * 用配置的 su 执行一条命令并返回输出，失败或超时返回 null
   */
  public String runSuCommand(String command) {
    return execSuCommand(command);
  }

  /**
   * 用配置的 su 执行一条命令，成功(退出码 0)返回 true
   */
  public boolean runSuChecked(String command) {
    String suPath = configManager.getSuFilePath();
    if (suPath == null) {
      Log.e(TAG, "未配置su文件，无法执行: " + command);
      return false;
    }
    String result = execSuCommand(command + " && echo " + SU_SUCCESS_TAG);
    boolean ok = result != null && result.contains(SU_SUCCESS_TAG);
    if (!ok) {
      Log.e(TAG, "su 执行失败: " + command + " -> " + result);
    }
    return ok;
  }

  /**
   * 用 su 安装/覆盖安装一个 apk，供插件自动安装使用
   */
  public boolean installPackage(String apkPath) {
    if (!isDeviceRooted()) {
      Log.e(TAG, "未获取Root权限，无法安装: " + apkPath);
      return false;
    }
    String result = execSuCommand("pm install -r " + apkPath);
    boolean ok = result != null && result.contains("Success");
    Log.i(TAG, "pm install " + apkPath + " -> " + (ok ? "success" : String.valueOf(result)));
    return ok;
  }

  public boolean installServer() {
    // 检查Root权限
    if (!isDeviceRooted()) {
      Log.e(TAG, "未获取Root权限，无法启动服务");
      showToast(context.getString(R.string.server_no_root));
      return false;
    }
    String suPath = configManager.getSuFilePath();
    // 获取当前选中的服务版本信息
    ServerInfo currentServer = dbHelper.getCurrentServerInfo();
    if (currentServer == null) {
      Log.e(TAG, "未找到当前服务版本信息");
      showToast(context.getString(R.string.server_not_found));
      return false;
    }
    // 获取Root路径配置
    String rootPath = dbHelper.getRootPath();
    try {
      List<String> commands = buildStartCommands(currentServer, rootPath, false);
      // 使用交互式SU启动服务
      Process suProcess = Runtime.getRuntime().exec(suPath);
      DataOutputStream outputStream = new DataOutputStream(suProcess.getOutputStream());
      // 执行所有命令
      for (String cmd : commands) {
        outputStream.writeBytes(cmd + "\n");
        outputStream.flush();
      }
      int code = suProcess.waitFor();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(suProcess.getInputStream()))) {
        String bytesRead;
        while ((bytesRead = reader.readLine()) != null) {
          if (bytesRead.contains("success"))
            return true;
        }
      }
      return false;
    } catch (Throwable e) {
      Log.e(TAG, "服务启动异常: " + e.getMessage(), e);
      showToast(context.getString(R.string.server_start_error));
      return false;
    }
  }

  /**
   * 检查服务是否正在运行
   */
  public boolean checkServerRunning() {
    isServerRunning = PluginDelegate.isServerRunning();
    return isServerRunning;
//    try {
//      Process process = Runtime.getRuntime().exec(configManager.getSuFilePath() + " -c pidof albatross_server");
//      // 读取输出
//      process.waitFor();
//      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
//        String bytesRead = reader.readLine();
//        if (bytesRead == null || bytesRead.length() < 2)
//          return false;
//        return Integer.parseInt(bytesRead) > 0; // 有输出表示进程存在
//      }
//    } catch (IOException | InterruptedException e) {
//      Log.e(TAG, "检查服务状态异常: " + e.getMessage(), e);
//      return false;
//    }
  }


  /**
   * 停止服务
   */
  public boolean stopServer() {
    if (!isServerRunning) {
      Log.i(TAG, "服务未在运行");
      return true;
    }
    try {
      // 优先让服务自己退出，失败时兜底 kill
      if (!PluginDelegate.stopServer()) {
        Log.w(TAG, "RPC 停止失败，改用 kill");
        execSuCommand("kill $(pidof albatross_server)");
      }
      if (waitForServerStopped()) {
        isServerRunning = false;
        if (serverProcess != null) {
          serverProcess.destroy();
          serverProcess = null;
        }
        Log.i(TAG, "服务已停止");
        showToast(context.getString(R.string.server_stopped));
        return true;
      }
      Log.e(TAG, "服务停止失败，进程仍然存在");
      showToast(context.getString(R.string.server_stop_error));
      return false;
    } catch (Throwable e) {
      Log.e(TAG, "停止服务器异常: " + e.getMessage(), e);
      showToast(context.getString(R.string.server_stop_error));
      return false;
    }
  }

  /**
   * 确认服务进程已经退出，避免界面显示与实际状态不一致
   */
  private boolean waitForServerStopped() {
    for (int i = 0; i < 3; i++) {
      String result = execSuCommand("pidof albatross_server");
      // 查不到进程、或者查询本身失败（su 无响应），都按已停止处理，停止请求已经发出去了
      if (result == null || !PID_PATTERN.matcher(result).find()) {
        return true;
      }
      try {
        Thread.sleep(150);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }


  /**
   * 读取进程输出的线程
   */
  private void startOutputReaders(Process process) {
    startOutputReaders(process, null);
  }

  /**
   * 读取进程输出，sink 不为空时把输出保存下来（启动失败时用于定位问题）
   */
  private void startOutputReaders(Process process, StringBuilder sink) {
    // 读取标准输出
    new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          Log.d(TAG, "Server输出: " + line);
          appendOutput(sink, line);
          // 可以在这里处理服务器输出
        }
      } catch (IOException e) {
        Log.e(TAG, "读取Server输出失败: " + e.getMessage());
      }
    }).start();
    // 读取错误输出
    new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          Log.e(TAG, "Server错误: " + line);
          appendOutput(sink, line);
          // 可以在这里处理服务器错误
        }
      } catch (IOException e) {
        Log.e(TAG, "读取Server错误输出失败: " + e.getMessage());
      }
    }).start();
  }

  private static void appendOutput(StringBuilder sink, String line) {
    if (sink == null) {
      return;
    }
    synchronized (sink) {
      if (sink.length() < MAX_CAPTURED_OUTPUT) {
        sink.append(line).append('\n');
      }
    }
  }

  /**
   * 检查设备是否已 root。
   * <p>
   * 候选 su **必须真的可用**才算数：先 `su -v` 快速判别（由 su 客户端本地应答，不触发授权弹窗，
   * 顺手排除 userdebug 的假 su 以及 SELinux 下不可执行的路径），都不行再 `su -c id` 真探测一次
   * （MagiskSU 首次会弹授权框，授权成功后以后都走快速路径）。
   * 只判"文件存在"是不够的——旧逻辑会把 /system/xbin/su 这种假 su 存下来，后面所有 su 调用都失败。
   */
  public boolean isDeviceRooted() {
    List<String> candidates = suCandidates();
    for (String path : candidates) {
      if (isSuVersionOk(path)) {
        rememberSuPath(path);
        return true;
      }
    }
    for (String path : candidates) {
      if (isSuIdOk(path)) {
        rememberSuPath(path);
        return true;
      }
    }
    String found = whichSu();
    if (isSuVersionOk(found) || isSuIdOk(found)) {
      rememberSuPath(found);
      return true;
    }
    Log.w(TAG, "没有找到可用的 su（候选都试过：" + candidates + "）");
    return false;
  }

  /** 候选顺序：已配置的路径 > 内置候选（去重） */
  private List<String> suCandidates() {
    List<String> list = new ArrayList<>();
    String configured = configManager.getSuFilePath();
    if (configured != null && !configured.isEmpty()) {
      list.add(configured);
    }
    for (String path : SU_CANDIDATES) {
      if (!list.contains(path)) {
        list.add(path);
      }
    }
    return list;
  }

  private void rememberSuPath(String path) {
    if (path != null && !path.equals(configManager.getSuFilePath())) {
      configManager.saveSuFilePath(path);
    }
  }

  /** `su -v` 有版本串输出且退出码 0 → 是可用的 su（MagiskSU/KernelSU 等） */
  private boolean isSuVersionOk(String suPath) {
    if (!isExecutablePath(suPath)) {
      return false;
    }
    String out = runSuProbe(suPath, SU_PROBE_FAST_TIMEOUT_MS, "-v");
    return out != null && !out.trim().isEmpty();
  }

  /** `su -c id` 能拿到 uid=0 → 真能用（可能弹一次授权框） */
  private boolean isSuIdOk(String suPath) {
    if (!isExecutablePath(suPath)) {
      return false;
    }
    String out = runSuProbe(suPath, SU_PROBE_DEEP_TIMEOUT_MS, "-c", "id");
    return out != null && out.contains("uid=0");
  }

  private boolean isExecutablePath(String path) {
    if (path == null || path.isEmpty()) {
      return false;
    }
    File file = new File(path);
    return file.exists() && file.canExecute();
  }

  /** 跑一条 su 探测命令；超时/异常返回 null（探测只用于判可用性，失败不当异常往上抛） */
  private String runSuProbe(String suPath, long timeoutMs, String... args) {
    List<String> command = new ArrayList<>();
    command.add(suPath);
    for (String arg : args) {
      command.add(arg);
    }
    Process process = null;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
      if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        Log.d(TAG, "su 探测超时: " + suPath);
        return null;
      }
      // 进程已退出，此时读取输出不会阻塞
      StringBuilder output = new StringBuilder();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append('\n');
        }
      }
      return output.toString();
    } catch (Throwable e) {
      Log.d(TAG, "su 探测失败: " + suPath + " -> " + e.getMessage());
      return null;
    } finally {
      if (process != null) {
        process.destroy();
      }
    }
  }

  private String whichSu() {
    try {
      Process process = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "which su"});
      BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
      String line = in.readLine();
      return line == null ? null : line.trim();
    } catch (IOException e) {
      Log.d(TAG, "检查su命令失败: " + e.getMessage());
      return null;
    }
  }

  /**
   * 显示Toast消息
   */
  private void showToast(String message) {
    // 判断当前线程是否为主线程
    if (Looper.myLooper() == Looper.getMainLooper()) {
      // 已经在主线程，直接显示Toast
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    } else {
      // 不在主线程，通过Handler切换到主线程
      new Handler(Looper.getMainLooper()).post(() -> {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
      });
    }
  }


}
