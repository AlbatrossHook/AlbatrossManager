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
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import qing.albatross.manager.R;
import qing.albatross.manager.data.ConfigManager;
import qing.albatross.manager.data.ServerDatabaseHelper;
import qing.albatross.manager.plugin.PluginDelegate;
import qing.albatross.manager.model.ServerInfo;

public class ServerManager {

  private static final String TAG = "ServerManager";
  private static ServerManager instance;
  private final Context context;
  //  private final FileStorageManager fileStorage;
  private final ConfigManager configManager;
  private Process serverProcess;
  private boolean isServerRunning = false;

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
    try {
      List<String> commands = buildStartCommands(currentServer, rootPath, true);
      // 使用交互式SU启动服务
      suProcess = Runtime.getRuntime().exec(suPath);
      DataOutputStream outputStream = new DataOutputStream(suProcess.getOutputStream());
      // 执行所有命令
      for (String cmd : commands) {
        outputStream.writeBytes(cmd + "\n");
        outputStream.flush();
      }
      for (int i = 0; i < 2; i++) {
        Thread.sleep(10000);
        if (PluginDelegate.checkIsRunning(dbHelper, context))
          break;
      }
      isServerRunning = checkServerRunning();
      if (isServerRunning) {
        Log.i(TAG, "服务启动成功，版本: " + currentServer.getVersion());
        showToast(context.getString(R.string.server_start_success));
        serverProcess = suProcess;
        return true;
      } else {
        Log.e(TAG, "服务启动失败，无法验证运行状态");
        showToast(context.getString(R.string.server_start_failed));
        return false;
      }
    } catch (Throwable e) {
      Log.e(TAG, "服务启动异常: " + e.getMessage(), e);
      showToast(context.getString(R.string.server_start_exception_format, e.getMessage()));
      if (suProcess != null) {
        startOutputReaders(suProcess);
      }
      return false;
    }
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
      if (PluginDelegate.stopServer())
        return true;
      // 查找并杀死服务进程
      Process process = Runtime.getRuntime().exec(configManager.getSuFilePath() + " -c kill $(pidof albatross_server)");
      int result = process.waitFor();
      if (result == 0) {
        isServerRunning = false;
        if (serverProcess != null) {
          serverProcess.destroy();
          serverProcess = null;
        }
        Log.i(TAG, "服务已停止");
        showToast(context.getString(R.string.server_stopped));
        return true;
      } else {
        Log.e(TAG, "停止服务器命令执行失败，返回码: " + result);
        return false;
      }
    } catch (IOException | InterruptedException e) {
      Log.e(TAG, "停止服务器异常: " + e.getMessage(), e);
      showToast(context.getString(R.string.server_stop_error));
      return false;
    }
  }


  /**
   * 读取进程输出的线程
   */
  private void startOutputReaders(Process process) {
    // 读取标准输出
    new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          Log.d(TAG, "Server输出: " + line);
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
          // 可以在这里处理服务器错误
        }
      } catch (IOException e) {
        Log.e(TAG, "读取Server错误输出失败: " + e.getMessage());
      }
    }).start();
  }

  /**
   * 检查设备是否已root
   */
  public boolean isDeviceRooted() {
    String suPath = configManager.getSuFilePath();
    if (suPath != null && new File(suPath).exists()) {
      return true;
    }
    // 检查常见的su路径
    String[] suPaths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/system/bin/.ext/su", "/system/usr/we-need-root/su", "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su"};
    for (String path : suPaths) {
      File suFile = new File(path);
      if (suFile.exists()) {
        // 保存找到的SU路径
        configManager.saveSuFilePath(path);
        return true;
      }
    }
    try {
      Process process = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "which su"});
      BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
      String s = in.readLine();
      if (s != null) {
        configManager.saveSuFilePath(s.trim());
        return true;
      }
    } catch (IOException e) {
      Log.d(TAG, "检查su命令失败: " + e.getMessage());
    }
    return false;
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
