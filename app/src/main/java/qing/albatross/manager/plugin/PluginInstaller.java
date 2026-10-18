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

import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_AUTHOR;
import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_CLASS;
import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_DESCRIPTION;
import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_KEY;
import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_NAME;
import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_SUPPORT_APPS;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.util.List;

import qing.albatross.manager.R;
import qing.albatross.manager.data.Plugin;
import qing.albatross.manager.data.PluginDatabaseHelper;
import qing.albatross.manager.data.PluginRuleDatabaseHelper;
import qing.albatross.manager.data.ServerDatabaseHelper;
import qing.albatross.manager.utils.ServerManager;

/**
 * 插件注册：把插件 APK（已安装或给路径安装）写进插件库、配置目标应用规则，
 * 并把结果同步给服务端。界面与广播命令共用。
 */
public final class PluginInstaller {

  private static final String TAG = "PluginInstaller";

  private PluginInstaller() {
  }

  public static class Result {
    public final boolean ok;
    public final String packageName;
    public final String className;
    public final int pluginId;
    public final int targetCount;
    public final boolean synced;
    public final String error;

    Result(boolean ok, String packageName, String className, int pluginId, int targetCount,
           boolean synced, String error) {
      this.ok = ok;
      this.packageName = packageName;
      this.className = className;
      this.pluginId = pluginId;
      this.targetCount = targetCount;
      this.synced = synced;
      this.error = error;
    }
  }

  /**
   * 注册插件。packageName 与 apkPath 至少给一个：
   * 只给 apkPath 时若插件包未安装，会先用 su 安装。
   *
   * @param targets 目标应用包名，null 表示不改动已有规则，空列表表示清空规则
   */
  public static Result install(Context context, String packageName, String apkPath, String className,
                               String param1, int param2, List<String> targets, boolean enable) {
    return install(context, packageName, apkPath, className, param1, param2, targets, enable, false);
  }

  /**
   * @param force 允许添加没有 albatross_plugin 元数据的普通应用（界面上的"强制添加"）
   */
  public static Result install(Context context, String packageName, String apkPath, String className,
                               String param1, int param2, List<String> targets, boolean enable,
                               boolean force) {
    PackageManager packageManager = context.getPackageManager();
    String pkg = packageName;
    if (pkg == null && apkPath != null) {
      PackageInfo archive = packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA);
      if (archive != null) {
        pkg = archive.packageName;
      }
    }
    if (pkg == null) {
      return new Result(false, null, null, -1, 0, false, context.getString(R.string.plugin_package_required));
    }
    if (!isInstalled(packageManager, pkg)) {
      if (apkPath == null || !new File(apkPath).exists()) {
        return new Result(false, pkg, null, -1, 0, false,
            context.getString(R.string.plugin_not_installed_format, pkg));
      }
      boolean installed = ServerManager.getInstance(context).installPackage(apkPath);
      if (!installed && !isInstalled(packageManager, pkg)) {
        return new Result(false, pkg, null, -1, 0, false,
            context.getString(R.string.plugin_install_failed_format, apkPath));
      }
    }
    Bundle metaData = null;
    try {
      ApplicationInfo applicationInfo = packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
      metaData = applicationInfo.metaData;
    } catch (Throwable e) {
      return new Result(false, pkg, null, -1, 0, false,
          context.getString(R.string.plugin_not_installed_format, pkg));
    }
    boolean isPlugin = metaData != null && metaData.getBoolean(ALBATROSS_PLUGIN_KEY, false);
    if (!isPlugin && !force) {
      return new Result(false, pkg, null, -1, 0, false,
          context.getString(R.string.plugin_metadata_missing_format, pkg));
    }
    String pluginClass = className;
    if (pluginClass == null) {
      pluginClass = isPlugin ? metaData.getString(ALBATROSS_PLUGIN_CLASS, "unknown") : "unknown";
    }
    String pluginName = isPlugin ? metaData.getString(ALBATROSS_PLUGIN_NAME) : null;
    if (pluginName == null) {
      try {
        pluginName = packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString();
      } catch (Throwable ignore) {
        pluginName = pkg;
      }
    }

    PluginDatabaseHelper pluginDb = PluginDatabaseHelper.getInstance(context);
    PluginRuleDatabaseHelper ruleDb = PluginRuleDatabaseHelper.getInstance(context);
    Plugin plugin = pluginDb.getPluginByPackage(pkg);
    if (plugin == null) {
      plugin = new Plugin();
      plugin.setPackageName(pkg);
      plugin.setName(pluginName);
      plugin.setClassName(pluginClass);
      plugin.setDescription(metaData != null ? metaData.getString(ALBATROSS_PLUGIN_DESCRIPTION,
          context.getString(R.string.no_description)) : context.getString(R.string.no_description));
      plugin.setAuthor(metaData != null ? metaData.getString(ALBATROSS_PLUGIN_AUTHOR,
          context.getString(R.string.unknown)) : context.getString(R.string.unknown));
      plugin.setSupportApps(metaData != null ? metaData.getString(ALBATROSS_PLUGIN_SUPPORT_APPS, "") : "");
      plugin.setParams(param1 == null ? "" : param1);
      plugin.setFlags(param2);
      plugin.setEnabled(enable);
      long id = pluginDb.addPlugin(plugin);
      if (id == -1) {
        return new Result(false, pkg, pluginClass, -1, 0, false,
            context.getString(R.string.plugin_save_failed_format, pkg));
      }
      plugin.setId(id);
    } else {
      pluginDb.updatePluginParams(plugin, pluginClass, param1 == null ? plugin.getParams() : param1, param2);
      if (plugin.isEnabled() != enable) {
        pluginDb.updatePluginState(plugin, enable);
        plugin.setEnabled(enable);
      }
    }
    int targetCount = 0;
    if (targets != null) {
      ruleDb.deleteAllRulesForPlugin(pkg);
      for (String target : targets) {
        if (target == null || target.trim().isEmpty() || !plugin.isEnabled()) {
          continue;
        }
        if (ruleDb.addRule(plugin, target.trim()) > 0) {
          targetCount++;
        }
      }
    } else {
      targetCount = ruleDb.getTargetPackages(pkg).size();
    }
    boolean synced = PluginDelegate.sync(ServerDatabaseHelper.getInstance(context), context);
    Log.i(TAG, "register plugin " + pkg + " class=" + pluginClass + " targets=" + targetCount +
        " enabled=" + plugin.isEnabled() + " synced=" + synced);
    return new Result(true, pkg, pluginClass, plugin.getId(), targetCount, synced, null);
  }

  /**
   * 启用/禁用插件（对应界面列表里的开关与详情页的开关）
   */
  public static Result setEnabled(Context context, String pluginPackage, boolean enable) {
    PluginDatabaseHelper pluginDb = PluginDatabaseHelper.getInstance(context);
    Plugin plugin = pluginDb.getPluginByPackage(pluginPackage);
    if (plugin == null) {
      return new Result(false, pluginPackage, null, -1, 0, false,
          context.getString(R.string.plugin_not_added_format, pluginPackage));
    }
    pluginDb.updatePluginState(plugin, enable);
    plugin.setEnabled(enable);
    boolean synced = PluginDelegate.sync(ServerDatabaseHelper.getInstance(context), context);
    return new Result(true, pluginPackage, plugin.getClassName(), plugin.getId(),
        PluginRuleDatabaseHelper.getInstance(context).getTargetPackages(pluginPackage).size(), synced, null);
  }

  /**
   * 删除插件：插件表 + 规则表 + 服务端注销
   */
  public static Result delete(Context context, String pluginPackage) {
    PluginDatabaseHelper pluginDb = PluginDatabaseHelper.getInstance(context);
    Plugin plugin = pluginDb.getPluginByPackage(pluginPackage);
    if (plugin == null) {
      return new Result(false, pluginPackage, null, -1, 0, false,
          context.getString(R.string.plugin_not_added_format, pluginPackage));
    }
    int rows = pluginDb.deletePlugin(plugin);
    boolean synced = PluginDelegate.sync(ServerDatabaseHelper.getInstance(context), context);
    return new Result(rows > 0, pluginPackage, plugin.getClassName(), plugin.getId(), 0, synced,
        rows > 0 ? null : context.getString(R.string.plugin_not_added_format, pluginPackage));
  }

  /**
   * 修改插件配置（class/param1/param2，对应详情页保存配置）
   */
  public static Result setParams(Context context, String pluginPackage, String className,
                                 String param1, int param2) {
    PluginDatabaseHelper pluginDb = PluginDatabaseHelper.getInstance(context);
    Plugin plugin = pluginDb.getPluginByPackage(pluginPackage);
    if (plugin == null) {
      return new Result(false, pluginPackage, null, -1, 0, false,
          context.getString(R.string.plugin_not_added_format, pluginPackage));
    }
    String newClass = className != null ? className : plugin.getClassName();
    String newParams = param1 != null ? param1 : plugin.getParams();
    pluginDb.updatePluginParams(plugin, newClass, newParams, param2);
    return new Result(true, pluginPackage, newClass, plugin.getId(),
        PluginRuleDatabaseHelper.getInstance(context).getTargetPackages(pluginPackage).size(),
        PluginDelegate.isServerRunning(), null);
  }

  /**
   * 把插件库与规则重新下发给服务端（对应界面刷新时的 sync）
   */
  public static boolean sync(Context context) {
    ServerDatabaseHelper dbHelper = ServerDatabaseHelper.getInstance(context);
    // 服务重启过就重新连上，否则这里总是返回失败
    PluginDelegate.ensureConnected(dbHelper, context);
    return PluginDelegate.sync(dbHelper, context);
  }

  /**
   * 只改目标应用规则，mode: replace / add / remove
   */
  public static Result setRule(Context context, String pluginPackage, List<String> targets, String mode) {
    PluginDatabaseHelper pluginDb = PluginDatabaseHelper.getInstance(context);
    Plugin plugin = pluginDb.getPluginByPackage(pluginPackage);
    if (plugin == null) {
      return new Result(false, pluginPackage, null, -1, 0, false,
          context.getString(R.string.plugin_not_added_format, pluginPackage));
    }
    PluginRuleDatabaseHelper ruleDb = PluginRuleDatabaseHelper.getInstance(context);
    if (targets == null) {
      // 没有 targets 时按"清空"处理（adb 传空串会丢参数，所以不要求传空字符串）
      if ("add".equals(mode)) {
        return new Result(false, pluginPackage, plugin.getClassName(), plugin.getId(), 0, false,
            context.getString(R.string.plugin_target_required));
      }
      targets = new java.util.ArrayList<>();
    }
    if ("replace".equals(mode)) {
      ruleDb.deleteAllRulesForPlugin(pluginPackage);
    }
    for (String target : targets) {
      if (target == null || target.trim().isEmpty()) {
        continue;
      }
      String name = target.trim();
      if ("remove".equals(mode)) {
        ruleDb.removeRule(plugin, name);
      } else {
        ruleDb.addRule(plugin, name);
      }
    }
    List<String> current = ruleDb.getTargetPackages(pluginPackage);
    boolean synced = PluginDelegate.sync(ServerDatabaseHelper.getInstance(context), context);
    return new Result(true, pluginPackage, plugin.getClassName(), plugin.getId(), current.size(), synced, null);
  }

  private static boolean isInstalled(PackageManager packageManager, String packageName) {
    try {
      packageManager.getPackageInfo(packageName, 0);
      return true;
    } catch (Throwable e) {
      return false;
    }
  }
}
