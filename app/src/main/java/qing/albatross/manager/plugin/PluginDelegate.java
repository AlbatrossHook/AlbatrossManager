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
package qing.albatross.manager.plugin;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.util.List;

import qing.albatross.app.agent.client.DisconnectException;
import qing.albatross.app.agent.client.ShellExecResult;
import qing.albatross.core.Albatross;
import qing.albatross.manager.data.ConfigManager;
import qing.albatross.manager.data.Plugin;
import qing.albatross.manager.data.PluginDatabaseHelper;
import qing.albatross.manager.data.PluginRuleDatabaseHelper;
import qing.albatross.manager.data.ServerDatabaseHelper;
import qing.albatross.manager.utils.RuntimeOptions;
import qing.albatross.plugin.PluginConnection;

public final class PluginDelegate {

  private static final String TAG = "PluginDelegate";
  public static final byte DEX_LOAD_SUCCESS = 20;
  PluginConnection connection;
  boolean isLsposedInjected;
  static PluginDelegate instance;
  //当前服务包的 app_agent.dex 是否支持运行选项（JIT/对抗/内存注入）
  static boolean agentDexSupportOptions = true;

  public static boolean isServerRunning() {
    if (instance != null) {
      boolean closed;
      try {
        closed = instance.connection.isClosed();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      if (closed) {
        disconnection();
        return false;
      }
      return true;
    }
    return false;
  }

  public static PluginDelegate get() {
    return instance;
  }

  /**
   * 把运行选项下发给 app_agent.dex，只影响之后创建的连接与之后发起的注入。
   * 旧服务包的 dex 没有对应接口，此时只置位提示标志，不影响其它功能。
   */
  private static void applyRuntimeOptions(Context context) {
    try {
      int initFlags = RuntimeOptions.initFlags(context);
      int injectFlags = RuntimeOptions.injectFlags(context);
      boolean changed = initFlags != PluginConnection.getInitFlags() ||
          injectFlags != PluginConnection.getInjectFlags();
      PluginConnection.setInitFlags(initFlags);
      PluginConnection.setInjectFlags(injectFlags);
      agentDexSupportOptions = true;
      if (changed) {
        Log.i(TAG, "下发运行选项 initFlags=0x" + Integer.toHexString(initFlags) +
            ", injectFlags=0x" + Integer.toHexString(injectFlags));
      }
    } catch (Throwable e) {
      agentDexSupportOptions = false;
      Log.e(TAG, "当前服务包的 app_agent.dex 不支持运行选项:" + e);
    }
  }

  /**
   * 运行选项改变后重新下发：已连接时重连，让服务端拿到新的 flags
   */
  public static boolean reApplyRuntimeOptions(ServerDatabaseHelper dbHelper, Context context) {
    if (!isServerRunning()) {
      return false;
    }
    try {
      // 尽量复用现有连接：重连会新建会话，连续切换选项时容易把连接搞掉
      instance.connection.pushFlags();
      return true;
    } catch (Throwable e) {
      Log.e(TAG, "在原连接上重下发选项失败，改为重连: " + e);
      disconnection();
      return checkIsRunning(dbHelper, context);
    }
  }

  /**
   * 服务相关操作前调用：没连上就重新建立连接（服务重启后无需手动刷新）
   */
  public static boolean ensureConnected(ServerDatabaseHelper dbHelper, Context context) {
    if (isServerRunning()) {
      return true;
    }
    return checkIsRunning(dbHelper, context);
  }

  public static boolean isAgentDexSupportOptions() {
    return agentDexSupportOptions;
  }

  /**
   * 内核隐藏（rwbp）：需要设备已加载 rwbp.ko，开启后不可撤销
   */
  public static boolean extendKpmHide() {
    if (!isServerRunning())
      return false;
    try {
      return instance.connection.supportExtendKpm();
    } catch (Throwable e) {
      Log.e(TAG, "extend kpm hide fail", e);
      return false;
    }
  }

  public static boolean sync(ServerDatabaseHelper dbHelper, Context context) {
    if (!isServerRunning())
      return false;
    try {
      PluginConnection connection = instance.connection;
      if (connection != null) {
        if (connection.isLsposedInjected()) {
          instance.isLsposedInjected = true;
          Albatross.getMainHandler().post(() -> {
            Toast.makeText(Albatross.currentApplication(), "检测到lspoed注入了，无法使用launch模式，仅支持立即注入到lsposed未注入的app中", Toast.LENGTH_SHORT).show();
          });
          return true;
        }
        instance.isLsposedInjected = false;
        PluginDatabaseHelper databaseHelper = PluginDatabaseHelper.getInstance(context);
        List<Plugin> plugins = databaseHelper.getAllPlugins();
        for (Plugin plugin : plugins) {
          ApplicationInfo applicationInfo;
          try {
            applicationInfo = context.getPackageManager().getApplicationInfo(plugin.getPackageName(), 0);
          } catch (Exception ignore) {
            instance.deletePlugin(plugin.getId());
            continue;
          }
          int pluginId = plugin.getId();
          instance.deletePlugin(pluginId);
          if (plugin.isEnabled()) {
            List<String> packages;
            packages = PluginRuleDatabaseHelper.getInstance(context).getTargetPackages(plugin.getPackageName());
            {
              instance.addPlugin(pluginId, applicationInfo.sourceDir, plugin.getClassName(), plugin.getParams(), plugin.getFlags());
              for (String pkg : packages) {
                instance.addPluginRule(pluginId, pkg);
              }
            }
          }
        }
        return true;
      }
    } catch (Exception e) {
      return false;
    }
    return false;
  }


  public static boolean checkIsRunning(ServerDatabaseHelper dbHelper, Context context) {
    if (isServerRunning())
      return true;
    String lib32 = dbHelper.getCurrentServerInfo().getLib32Path();
    String rootPath = dbHelper.getRootPath();
    if (lib32 != null) {
      lib32 = rootPath + ConfigManager.LIB32_DIR_NAME + "/" + ConfigManager.LIB_NAME;
    }
    try {
      String appAgentPath = rootPath + ConfigManager.APP_AGENT_FILE;
      File agentFile = new File(appAgentPath);
      if (!agentFile.exists()) {

      }
      String SERVER_ADDRESS = ConfigManager.getInstance(context).getServerAddress();
      applyRuntimeOptions(context);
      PluginConnection connection = PluginConnection.create(SERVER_ADDRESS, lib32, appAgentPath, rootPath + ConfigManager.SYSTEM_AGENT_FILE);
      if (connection != null) {
        instance = new PluginDelegate();
        instance.connection = connection;
        instance.isLsposedInjected = connection.isLsposedInjected();
        if (instance.isLsposedInjected) {
          Albatross.getMainHandler().post(() -> {
            Toast.makeText(Albatross.currentApplication(), "检测到lspoed注入了，无法使用launch模式，仅支持立即注入到lsposed未注入的app中", Toast.LENGTH_SHORT).show();
          });
          return true;
        }
        PluginDatabaseHelper databaseHelper = PluginDatabaseHelper.getInstance(context);
        List<Plugin> plugins = databaseHelper.getAllPlugins();
        for (Plugin plugin : plugins) {
          ApplicationInfo applicationInfo;
          try {
            applicationInfo = context.getPackageManager().getApplicationInfo(plugin.getPackageName(), 0);
          } catch (Exception ignore) {
            instance.deletePlugin(plugin.getId());
            continue;
          }
          int pluginId = plugin.getId();
          if (plugin.isEnabled()) {
            List<String> packages;
            packages = PluginRuleDatabaseHelper.getInstance(context).getTargetPackages(plugin.getPackageName());
            if (false && packages.isEmpty()) {
              instance.deletePlugin(pluginId);
            } else {
              instance.addPlugin(pluginId, applicationInfo.sourceDir, plugin.getClassName(), plugin.getParams(), plugin.getFlags());
              for (String pkg : packages) {
                instance.addPluginRule(pluginId, pkg);
              }
            }
          } else {
            instance.deletePlugin(pluginId);
          }
        }
        return true;
      }
    } catch (Exception e) {
      return false;
    }
    return false;
  }


  public static PluginDelegate create(ServerDatabaseHelper dbHelper, Context context) {
    if (instance != null)
      return instance;
    try {
      checkIsRunning(dbHelper, context);
      return instance;
    } catch (Exception e) {
      Log.e("Albatross", "inject", e);
    }
    return null;
  }

  public static String injectPlugin(String targetPackage, String pluginApk, String pluginClass, String param1, int param2) {
    if (instance == null)
      return "服务没有连接";
    return instance.inject(targetPackage, pluginApk, pluginClass, param1, param2);
  }


  public String inject(String targetPackage, String pluginApk, String pluginClass, String param1, int param2) {
    try {
      int res = connection.doInject(targetPackage, pluginApk, pluginClass, param1, param2);
      switch (res) {
        case -2:
          return "找不到对应的进程";
        case -1:
          return "注入失败";
        case DEX_LOAD_SUCCESS:
          return null;
        case DEX_LOAD_SUCCESS + 1:
          return null;
        default:
          return "注入失败:" + res;
      }
    } catch (Throwable e) {
      e.printStackTrace();
      return "err " + e;
    }
  }

  public boolean addSystemPlugin(String pluginApk, String pluginClass, String param1, int param2) {
    try {
      byte res = connection.loadSystemPlugin(pluginApk, pluginClass, param1, param2);
      return res == DEX_LOAD_SUCCESS;
    } catch (Throwable e) {
      e.printStackTrace();
      return false;
    }
  }


  public boolean addPlugin(int pluginId, String pluginApk, String pluginClass, String param1, int param2) {
    try {
      byte res = connection.registerPlugin(pluginId, pluginApk, pluginClass, param1, param2);
      if (res == 0 || res == 1)
        return true;
    } catch (Throwable e) {
      e.printStackTrace();
    }
    return false;
  }

  public boolean modifyPlugin(int pluginId, String pluginClass, String param1, int param2) {
    try {
      byte res = connection.modifyPlugin(pluginId, pluginClass, param1, param2);
      if (res == 0) {
        Log.e("PluginHandler", "get plugin fail");
        return false;
      }
      return true;
    } catch (Throwable e) {
      e.printStackTrace();
    }
    return false;
  }

  public boolean deletePluginRule(int pluginId, String targetPkg) {
    try {
      return connection.deletePluginRule(pluginId, targetPkg);
    } catch (Throwable e) {
      e.printStackTrace();
      return false;
    }
  }

  public boolean addPluginRule(int pluginId, String targetPkg) {
    try {
      if (isLsposedInjected) {
        Albatross.getMainHandler().post(() -> {
          Toast.makeText(Albatross.currentApplication(), "检测到lspoed注入了，无法使用launch模式，仅支持立即注入到lsposed未注入的app中", Toast.LENGTH_SHORT).show();
        });
        return false;
      }
      byte res = connection.addPluginRule(pluginId, targetPkg);
      if (res != 0) {
        Albatross.log("add plugin for " + targetPkg + " fail:" + res);
        return false;
      }
      return true;
    } catch (Throwable e) {
      e.printStackTrace();
      return false;
    }
  }

  public byte deletePlugin(int pluginId) {
    try {
      return connection.deletePlugin(pluginId);
    } catch (Exception e) {
      return 0;
    }
  }

  public static boolean stopServer() {
    if (instance == null)
      return false;
    try {
      instance.connection.stopServer();
      instance = null;
      return true;
    } catch (Exception e) {
      Log.e("PluginHandler", "stop fail", e);
    }
    instance = null;
    return false;

  }

  public static void disconnection() {
    if (instance != null) {
      instance.connection.disconnection();
      instance = null;
    }
  }

  public boolean freezeApp(String packageName) {
    try {
      ShellExecResult result = connection.shell("pm disable " + packageName);
      if (result != null) {
        return result.exitCode == 0;
      }
    } catch (DisconnectException e) {
      disconnection();
    }

    return false;
  }

  public boolean unfreezeApp(String packageName) {
    try {
      ShellExecResult result = connection.shell("pm enable " + packageName);
      if (result != null) {
        return result.exitCode == 0;
      }
    } catch (DisconnectException e) {
      disconnection();
    }

    return false;
  }

  /**
   * 通过服务端(root)执行 shell 命令
   */
  public String shell(String command) {
    try {
      ShellExecResult result = connection.shell(command);
      return result == null ? null : result.stdout;
    } catch (DisconnectException e) {
      disconnection();
      return null;
    }
  }

  public boolean forceStopApp(String packageName) {
    ShellExecResult result;
    try {
      result = connection.shell("am force-stop " + packageName);
      if (result != null) {
        return result.exitCode == 0;
      }
    } catch (DisconnectException e) {
      disconnection();
    }
    return false;
  }

  public String getAppProcesses(String packageName) throws PackageManager.NameNotFoundException {
    return connection.getPackageProcess(packageName);
  }


}
