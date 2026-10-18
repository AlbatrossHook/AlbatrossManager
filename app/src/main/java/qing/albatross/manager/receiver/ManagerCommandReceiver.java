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
package qing.albatross.manager.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import qing.albatross.manager.data.ConfigManager;
import qing.albatross.manager.data.PluginDatabaseHelper;
import qing.albatross.manager.data.PluginRuleDatabaseHelper;
import qing.albatross.manager.data.ServerDatabaseHelper;
import qing.albatross.manager.plugin.PluginDelegate;
import qing.albatross.manager.plugin.PluginInstaller;
import qing.albatross.manager.utils.AppUtils;
import qing.albatross.manager.utils.CoreBootstrap;
import qing.albatross.manager.utils.CoreInstaller;
import qing.albatross.manager.utils.FileUtils;
import qing.albatross.manager.utils.LogUtils;
import qing.albatross.manager.utils.RuntimeOptions;
import qing.albatross.manager.utils.SELinuxManager;
import qing.albatross.manager.utils.ServerManager;
import qing.albatross.manager.utils.SystemUtils;

/**
 * adb/脚本驱动用的命令入口，例如：
 * adb shell am broadcast -a qing.albatross.manager.action.INSTALL_CORE \
 *     -p qing.albatross.manager --es path /data/local/tmp/core.zip
 *
 * 结果通过 logcat 输出（tag: AlbManagerAction）：
 *   MANAGER_ACTION start: action=...
 *   MANAGER_ACTION success: action=..., detail
 *   MANAGER_ACTION failed: action=..., error
 * 每个命令都在后台线程执行，处理完立即结束广播。
 */
public class ManagerCommandReceiver extends BroadcastReceiver {

  private static final String TAG = "AlbManagerAction";
  private static final String PREFIX = "qing.albatross.manager.action.";

  public static final String ACTION_INSTALL_CORE = PREFIX + "INSTALL_CORE";
  public static final String ACTION_LOAD_CORE = PREFIX + "LOAD_CORE";
  public static final String ACTION_START_SERVER = PREFIX + "START_SERVER";
  public static final String ACTION_STOP_SERVER = PREFIX + "STOP_SERVER";
  public static final String ACTION_INSTALL_PLUGIN = PREFIX + "INSTALL_PLUGIN";
  public static final String ACTION_SET_RULE = PREFIX + "SET_RULE";
  public static final String ACTION_SET_OPTION = PREFIX + "SET_OPTION";
  public static final String ACTION_INJECT = PREFIX + "INJECT";
  public static final String ACTION_STATUS = PREFIX + "STATUS";
  //以下对应界面上的其余操作
  public static final String ACTION_SELECT_CORE = PREFIX + "SELECT_CORE";
  public static final String ACTION_DELETE_CORE = PREFIX + "DELETE_CORE";
  public static final String ACTION_SET_ROOT_PATH = PREFIX + "SET_ROOT_PATH";
  public static final String ACTION_SET_SERVER_ADDRESS = PREFIX + "SET_SERVER_ADDRESS";
  public static final String ACTION_SET_SU_PATH = PREFIX + "SET_SU_PATH";
  public static final String ACTION_KERNEL_HIDE = PREFIX + "KERNEL_HIDE";
  public static final String ACTION_SET_PLUGIN_ENABLED = PREFIX + "SET_PLUGIN_ENABLED";
  public static final String ACTION_DELETE_PLUGIN = PREFIX + "DELETE_PLUGIN";
  public static final String ACTION_SET_PLUGIN_PARAMS = PREFIX + "SET_PLUGIN_PARAMS";
  public static final String ACTION_SYNC_PLUGINS = PREFIX + "SYNC_PLUGINS";
  public static final String ACTION_OPEN_APP = PREFIX + "OPEN_APP";
  public static final String ACTION_FORCE_STOP_APP = PREFIX + "FORCE_STOP_APP";
  public static final String ACTION_FREEZE_APP = PREFIX + "FREEZE_APP";
  public static final String ACTION_UNFREEZE_APP = PREFIX + "UNFREEZE_APP";
  public static final String ACTION_APP_INFO = PREFIX + "APP_INFO";

  private static final ExecutorService executor = Executors.newSingleThreadExecutor();

  @Override
  public void onReceive(Context context, Intent intent) {
    final String action = intent.getAction();
    if (action == null || !action.startsWith(PREFIX)) {
      return;
    }
    final Context appContext = context.getApplicationContext();
    //命令可能要解压/拷贝大文件，不能在广播回调里同步做完
    final PendingResult pendingResult = goAsync();
    final Intent data = new Intent(intent);
    executor.execute(() -> {
      String detail = null;
      String error = null;
      Log.i(TAG, "MANAGER_ACTION start: action=" + shortAction(action) + extraSummary(data));
      try {
        detail = handle(appContext, action, data);
      } catch (Throwable e) {
        Log.e(TAG, "MANAGER_ACTION exception: action=" + shortAction(action), e);
        error = e.toString();
      } finally {
        if (error == null && detail != null && detail.startsWith("!")) {
          error = detail.substring(1);
          detail = null;
        }
        if (error == null) {
          Log.i(TAG, "MANAGER_ACTION success: action=" + shortAction(action) +
              (detail == null || detail.isEmpty() ? "" : ", " + detail));
        } else {
          Log.e(TAG, "MANAGER_ACTION failed: action=" + shortAction(action) + ", error=" + error);
        }
        try {
          pendingResult.finish();
        } catch (Throwable ignore) {
        }
      }
    });
  }

  /**
   * 返回 null/空串表示成功，返回以 '!' 开头的串表示失败原因
   */
  private String handle(Context context, String action, Intent intent) {
    switch (action) {
      case ACTION_INSTALL_CORE: {
        String path = intent.getStringExtra("path");
        if (path == null) {
          return "!missing extra: path";
        }
        boolean overwrite = intent.getBooleanExtra("overwrite", true);
        boolean setCurrent = intent.getBooleanExtra("set_current", true);
        CoreInstaller.Result result = CoreInstaller.install(context, new File(path), overwrite, setCurrent);
        if (!result.ok) {
          return "!" + result.error;
        }
        ServerDatabaseHelper dbHelper = ServerDatabaseHelper.getInstance(context);
        return "version=" + result.version + ", current=" + dbHelper.getCurrentServerVersion() +
            ", first=" + result.firstVersion;
      }
      case ACTION_LOAD_CORE: {
        CoreBootstrap.Result result = CoreBootstrap.load(context);
        if (!result.ok) {
          return "!" + result.error;
        }
        return "features=" + result.features + ", serverConnected=" + result.connected;
      }
      case ACTION_START_SERVER: {
        ServerManager serverManager = ServerManager.getInstance(context);
        if (!serverManager.startServer()) {
          return "!start server fail";
        }
        return "costMs=" + serverManager.getLastStartCostMs();
      }
      case ACTION_STOP_SERVER: {
        ServerManager serverManager = ServerManager.getInstance(context);
        return serverManager.stopServer() ? "stopped" : "!stop server fail";
      }
      case ACTION_INSTALL_PLUGIN: {
        String pkg = intent.getStringExtra("package");
        String apkPath = intent.getStringExtra("path");
        String className = intent.getStringExtra("class");
        String param1 = intent.getStringExtra("param1");
        int param2 = intent.getIntExtra("param2", 0);
        boolean enable = intent.getBooleanExtra("enable", true);
        boolean force = intent.getBooleanExtra("force", false);
        List<String> targets = split(intent.getStringExtra("targets"));
        PluginInstaller.Result result = PluginInstaller.install(context, pkg, apkPath, className,
            param1, param2, targets, enable, force);
        if (!result.ok) {
          return "!" + result.error;
        }
        return "plugin=" + result.packageName + ", class=" + result.className +
            ", id=" + result.pluginId + ", targets=" + result.targetCount +
            ", serverSynced=" + result.synced;
      }
      case ACTION_SET_PLUGIN_ENABLED: {
        String pkg = intent.getStringExtra("package");
        if (pkg == null) {
          return "!missing extra: package";
        }
        PluginInstaller.Result result = PluginInstaller.setEnabled(context, pkg,
            intent.getBooleanExtra("enable", true));
        if (!result.ok) {
          return "!" + result.error;
        }
        return "plugin=" + result.packageName + ", enabled=" + intent.getBooleanExtra("enable", true) +
            ", targets=" + result.targetCount + ", serverSynced=" + result.synced;
      }
      case ACTION_DELETE_PLUGIN: {
        String pkg = intent.getStringExtra("package");
        if (pkg == null) {
          return "!missing extra: package";
        }
        PluginInstaller.Result result = PluginInstaller.delete(context, pkg);
        return result.ok ? "plugin=" + result.packageName + " deleted, serverSynced=" + result.synced
            : "!" + result.error;
      }
      case ACTION_SET_PLUGIN_PARAMS: {
        String pkg = intent.getStringExtra("package");
        if (pkg == null) {
          return "!missing extra: package";
        }
        int param2 = intent.getIntExtra("param2", 0);
        PluginInstaller.Result result = PluginInstaller.setParams(context, pkg,
            intent.getStringExtra("class"), intent.getStringExtra("param1"), param2);
        if (!result.ok) {
          return "!" + result.error;
        }
        return "plugin=" + result.packageName + ", class=" + result.className +
            ", param1=" + intent.getStringExtra("param1") + ", param2=" + param2;
      }
      case ACTION_SYNC_PLUGINS: {
        boolean synced = PluginInstaller.sync(context);
        return synced ? "plugins synced to server" : "!sync fail(server not running?)";
      }
      case ACTION_SELECT_CORE: {
        String version = intent.getStringExtra("version");
        if (version == null) {
          return "!missing extra: version";
        }
        ServerDatabaseHelper dbHelper = ServerDatabaseHelper.getInstance(context);
        if (!dbHelper.checkVersionExists(version)) {
          return "!version not found: " + version;
        }
        dbHelper.setCurrentServerVersion(version);
        //切换版本后要重启进程才能加载新核心，界面同样提示重启
        ServerManager.getInstance(context).stopServer();
        return "current=" + dbHelper.getCurrentServerVersion() + ", 需要重启进程后再 LOAD_CORE";
      }
      case ACTION_DELETE_CORE: {
        String version = intent.getStringExtra("version");
        if (version == null) {
          return "!missing extra: version";
        }
        ServerDatabaseHelper dbHelper = ServerDatabaseHelper.getInstance(context);
        if (version.equals(dbHelper.getCurrentServerVersion())) {
          return "!cannot delete current version: " + version;
        }
        if (!dbHelper.checkVersionExists(version)) {
          return "!version not found: " + version;
        }
        FileUtils.deleteDirectory(new File(context.getFilesDir(), "servers/" + version));
        dbHelper.deleteServerVersion(version);
        return "version=" + version + ", remain=" + dbHelper.getAllServerVersions().size();
      }
      case ACTION_SET_ROOT_PATH: {
        String path = intent.getStringExtra("path");
        if (path == null || path.trim().isEmpty()) {
          return "!missing extra: path";
        }
        ServerDatabaseHelper dbHelper = ServerDatabaseHelper.getInstance(context);
        dbHelper.saveRootPath(path.trim());
        return "rootPath=" + dbHelper.getRootPath();
      }
      case ACTION_SET_SERVER_ADDRESS: {
        String address = intent.getStringExtra("address");
        if (address == null || address.trim().length() < 2) {
          return "!invalid extra: address";
        }
        ConfigManager configManager = ConfigManager.getInstance(context);
        configManager.saveServerAddress(address.trim());
        return "serverAddress=" + configManager.getServerAddress() + ", 需要重启服务生效";
      }
      case ACTION_SET_SU_PATH: {
        String path = intent.getStringExtra("path");
        if (path == null || path.trim().length() < 2) {
          return "!invalid extra: path";
        }
        ConfigManager configManager = ConfigManager.getInstance(context);
        configManager.saveSuFilePath(path.trim());
        return "suPath=" + configManager.getSuFilePath();
      }
      case ACTION_KERNEL_HIDE: {
        boolean hidden = PluginDelegate.extendKpmHide();
        ConfigManager.getInstance(context).setKpmHideState(hidden);
        return hidden ? "kernel hide enabled" :
            "!kernel hide 未生效(需要 rwbp.ko 且服务在运行)";
      }
      case ACTION_OPEN_APP: {
        String pkg = intent.getStringExtra("package");
        if (pkg == null) {
          return "!missing extra: package";
        }
        AppUtils.launchApp(context, pkg);
        String pids = waitAppProcess(context, pkg, 3000);
        String via = "intent";
        if (pids.isEmpty()) {
          // 后台启动 Activity 会被系统拦，而且 app 自身常常拿不到可用的 su，
          // 这里优先借服务端(root)的 shell 拉起，再退回本机 su
          PluginDelegate delegate = PluginDelegate.get();
          if (delegate != null) {
            String component = firstComponentLine(delegate.shell("cmd package resolve-activity --brief " + pkg));
            if (component != null && delegate.shell("am start -n " + component) != null) {
              via = "server shell";
            }
          }
          if (pids.isEmpty() && !"server shell".equals(via)) {
            String suResult = ServerManager.getInstance(context)
                .runSuCommand("cmd package resolve-activity --brief " + pkg + " | tail -1");
            String component = firstComponentLine(suResult);
            if (component != null &&
                ServerManager.getInstance(context).runSuChecked("am start -n " + component)) {
              via = "su shell";
            }
          }
          pids = waitAppProcess(context, pkg, 5000);
        }
        return "opened " + pkg + " via " + via +
            (pids.isEmpty() ? ", 未检测到进程" : ", pids=" + pids);
      }
      case ACTION_FORCE_STOP_APP: {
        String pkg = intent.getStringExtra("package");
        if (pkg == null) {
          return "!missing extra: package";
        }
        boolean ok = AppUtils.forceStopApp(context, pkg);
        return ok ? "force-stopped " + pkg : "!force-stop fail: " + pkg;
      }
      case ACTION_FREEZE_APP: {
        String pkg = intent.getStringExtra("package");
        if (pkg == null) {
          return "!missing extra: package";
        }
        boolean ok = AppUtils.freezeApp(context, pkg);
        return ok ? "frozen " + pkg : "!freeze fail: " + pkg;
      }
      case ACTION_UNFREEZE_APP: {
        String pkg = intent.getStringExtra("package");
        if (pkg == null) {
          return "!missing extra: package";
        }
        boolean ok = AppUtils.unfreezeApp(context, pkg);
        return ok ? "unfrozen " + pkg : "!unfreeze fail: " + pkg;
      }
      case ACTION_APP_INFO: {
        String pkg = intent.getStringExtra("package");
        if (pkg == null) {
          return "!missing extra: package";
        }
        return appInfo(context, pkg);
      }
      case ACTION_SET_RULE: {
        String pkg = intent.getStringExtra("package");
        if (pkg == null) {
          return "!missing extra: package";
        }
        String mode = intent.getStringExtra("mode");
        if (mode == null) {
          mode = "replace";
        }
        PluginInstaller.Result result = PluginInstaller.setRule(context, pkg,
            split(intent.getStringExtra("targets")), mode);
        if (!result.ok) {
          return "!" + result.error;
        }
        return "plugin=" + result.packageName + ", mode=" + mode + ", targets=" + result.targetCount +
            ", serverSynced=" + result.synced;
      }
      case ACTION_SET_OPTION: {
        String key = intent.getStringExtra("key");
        if (key == null) {
          return "!missing extra: key";
        }
        ConfigManager configManager = ConfigManager.getInstance(context);
        if (!configManager.setOptionByKey(key, intent.getBooleanExtra("value", true))) {
          return "!unknown option: " + key;
        }
        ServerDatabaseHelper dbHelper = ServerDatabaseHelper.getInstance(context);
        boolean reApplied = PluginDelegate.reApplyRuntimeOptions(dbHelper, context);
        return "option=" + key + "=" + configManager.getOptionByKey(key) +
            ", reconnect=" + reApplied + ", initFlags=0x" +
            Integer.toHexString(RuntimeOptions.initFlags(context)) +
            ", injectFlags=0x" + Integer.toHexString(RuntimeOptions.injectFlags(context));
      }
      case ACTION_INJECT: {
        String target = intent.getStringExtra("target");
        if (target == null) {
          return "!missing extra: target";
        }
        String pluginPackage = intent.getStringExtra("plugin");
        PluginDatabaseHelper pluginDb = PluginDatabaseHelper.getInstance(context);
        PluginRuleDatabaseHelper ruleDb = PluginRuleDatabaseHelper.getInstance(context);
        String pluginPkg = pluginPackage;
        if (pluginPkg == null) {
          for (qing.albatross.manager.data.Plugin candidate : pluginDb.getAllPlugins()) {
            if (ruleDb.getTargetPackages(candidate.getPackageName()).contains(target)) {
              pluginPkg = candidate.getPackageName();
              break;
            }
          }
        }
        if (pluginPkg == null) {
          return "!no plugin rule for target: " + target;
        }
        qing.albatross.manager.data.Plugin plugin = pluginDb.getPluginByPackage(pluginPkg);
        if (plugin == null) {
          return "!plugin not added: " + pluginPkg;
        }
        String dex;
        try {
          dex = context.getPackageManager().getPackageInfo(pluginPkg, 0).applicationInfo.sourceDir;
        } catch (Throwable e) {
          return "!plugin package not installed: " + pluginPkg;
        }
        String error = PluginDelegate.injectPlugin(target, dex, plugin.getClassName(),
            plugin.getParams(), plugin.getFlags());
        if (error != null) {
          return "!" + error;
        }
        return "inject " + target + " with " + pluginPkg;
      }
      case ACTION_STATUS: {
        ServerDatabaseHelper dbHelper = ServerDatabaseHelper.getInstance(context);
        PluginDatabaseHelper pluginDb = PluginDatabaseHelper.getInstance(context);
        PluginRuleDatabaseHelper ruleDb = PluginRuleDatabaseHelper.getInstance(context);
        List<qing.albatross.manager.data.Plugin> plugins = pluginDb.getAllPlugins();
        int ruleCount = 0;
        for (qing.albatross.manager.data.Plugin plugin : plugins) {
          ruleCount += ruleDb.getTargetPackages(plugin.getPackageName()).size();
        }
        ConfigManager configManager = ConfigManager.getInstance(context);
        ServerManager serverManager = ServerManager.getInstance(context);
        StringBuilder builder = new StringBuilder();
        builder.append("serverRunning=").append(PluginDelegate.isServerRunning());
        builder.append(", coreVersion=").append(dbHelper.getCurrentServerVersion());
        builder.append(", versions=").append(dbHelper.getAllServerVersions().size());
        builder.append(", rootPath=").append(dbHelper.getRootPath());
        builder.append(", serverAddress=").append(configManager.getServerAddress());
        builder.append(", options=").append(RuntimeOptions.describe(context, "on", "off"));
        builder.append(", plugins=").append(plugins.size());
        builder.append(", rules=").append(ruleCount);
        builder.append(", agentDexOptions=").append(PluginDelegate.isAgentDexSupportOptions());
        builder.append(", coreLoaded=").append(CoreBootstrap.isCoreLoaded());
        builder.append(", kpmHide=").append(configManager.getKpmHideState());
        builder.append(", rooted=").append(serverManager.isDeviceRooted());
        builder.append(", suPath=").append(configManager.getSuFilePath());
        builder.append(", lastStartCostMs=").append(serverManager.getLastStartCostMs());
        builder.append(", android=").append(android.os.Build.VERSION.RELEASE)
            .append('(').append(android.os.Build.VERSION.SDK_INT).append(')');
        builder.append(", model=").append(android.os.Build.MANUFACTURER).append(' ')
            .append(android.os.Build.MODEL);
        builder.append(", arch=").append(SystemUtils.getCpuArchitecture());
        builder.append(", selinux=").append(SELinuxManager.checkByRestrictedDirectories() ?
            "enforcing" : "permissive");
        return builder.toString();
      }
      default:
        return "!unknown action: " + action;
    }
  }

  private static String shortAction(String action) {
    return action.startsWith(PREFIX) ? action.substring(PREFIX.length()) : action;
  }

  /**
   * 从 `cmd package resolve-activity --brief` 的输出里取组件名
   */
  private static String firstComponentLine(String output) {
    if (output == null) {
      return null;
    }
    String[] lines = output.trim().split("\n");
    for (int i = lines.length - 1; i >= 0; i--) {
      String line = lines[i].trim();
      if (line.contains("/") && !line.contains(" ")) {
        return line;
      }
    }
    return null;
  }

  /**
   * 精确查询进程 pid：不能用 LogUtils.getPidForPackage，它是子串匹配，
   * 会把 qing.albatross.manager.debug 也算成 qing.albatross
   */
  private static String appPids(Context context, String pkg) {
    PluginDelegate delegate = PluginDelegate.get();
    String out = null;
    if (delegate != null) {
      out = delegate.shell("pidof " + pkg);
    }
    if (out == null) {
      out = ServerManager.getInstance(context).runSuCommand("pidof " + pkg);
    }
    if (out == null) {
      out = LogUtils.getPidForPackage(pkg);
    }
    return out == null ? "" : out.trim();
  }

  /**
   * 等应用进程起来，返回 pid 列表（空表示没起来）
   */
  private static String waitAppProcess(Context context, String pkg, long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    String pids = appPids(context, pkg);
    while (pids.isEmpty() && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(300);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      pids = appPids(context, pkg);
    }
    return pids;
  }

  /**
   * 应用信息汇总，便于脚本断言（对应界面应用详情页展示的内容）
   */
  private static String appInfo(Context context, String pkg) {
    StringBuilder builder = new StringBuilder();
    builder.append("package=").append(pkg);
    try {
      PackageManager packageManager = context.getPackageManager();
      PackageInfo info = packageManager.getPackageInfo(pkg, 0);
      builder.append(", installed=true");
      builder.append(", uid=").append(info.applicationInfo.uid);
      builder.append(", versionName=").append(info.versionName);
      builder.append(", versionCode=").append(info.getLongVersionCode());
      builder.append(", sourceDir=").append(info.applicationInfo.sourceDir);
      builder.append(", dataDir=").append(info.applicationInfo.dataDir);
      builder.append(", system=").append((info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
    } catch (Throwable e) {
      builder.append(", installed=false");
    }
    builder.append(", frozen=").append(AppUtils.isAppFrozen(context, pkg));
    builder.append(", pids=").append(appPids(context, pkg));
    qing.albatross.manager.data.Plugin plugin =
        PluginDatabaseHelper.getInstance(context).getPluginByPackage(pkg);
    builder.append(", isPlugin=").append(plugin != null);
    if (plugin != null) {
      builder.append(", pluginId=").append(plugin.getId());
      builder.append(", pluginClass=").append(plugin.getClassName());
      builder.append(", pluginEnabled=").append(plugin.isEnabled());
      builder.append(", param1=").append(plugin.getParams());
      builder.append(", param2=").append(plugin.getFlags());
      builder.append(", rules=").append(PluginRuleDatabaseHelper.getInstance(context)
          .getTargetPackages(pkg));
    }
    return builder.toString();
  }

  private static String extraSummary(Intent intent) {
    StringBuilder builder = new StringBuilder();
    for (String key : new String[]{"path", "package", "targets", "key", "mode", "target", "class"}) {
      String value = intent.getStringExtra(key);
      if (value != null) {
        builder.append(' ').append(key).append('=').append(value);
      }
    }
    if (intent.hasExtra("value")) {
      builder.append(" value=").append(intent.getBooleanExtra("value", false));
    }
    return builder.toString();
  }

  private static List<String> split(String value) {
    if (value == null) {
      return null;
    }
    List<String> result = new ArrayList<>();
    for (String item : Arrays.asList(value.split(","))) {
      if (!item.trim().isEmpty()) {
        result.add(item.trim());
      }
    }
    return result;
  }
}
