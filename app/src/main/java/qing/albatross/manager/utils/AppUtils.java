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

import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_KEY;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import qing.albatross.manager.model.AppInfo;
import qing.albatross.manager.plugin.PluginDelegate;

/**
 * 应用工具类，提供获取已安装应用、启动应用等功能
 */
public class AppUtils {


  /**
   * 获取设备上所有已安装的第三方应用
   *
   * @param context 上下文
   * @return 应用信息列表
   */
  public static List<AppInfo> getInstalledApps(Context context) {
    List<AppInfo> appList = new ArrayList<>();
    PackageManager packageManager = context.getPackageManager();
    List<PackageInfo> packages;
    Intent intent = new Intent();
    intent.setAction(Intent.ACTION_MAIN);
    intent.addCategory(Intent.CATEGORY_LAUNCHER);
    String contextPackageName = context.getPackageName();
    // 得到包含应用信息的列表
    List<ResolveInfo> resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.GET_META_DATA);
    for (ResolveInfo resolveInfo : resolveInfos) {
      ApplicationInfo applicationInfo = resolveInfo.activityInfo.applicationInfo;
      if (applicationInfo.packageName.equals(contextPackageName))
        continue;
      Bundle metaData = applicationInfo.metaData;
      boolean isPlugin = false;
      if (metaData != null && metaData.containsKey(ALBATROSS_PLUGIN_KEY)) {
        isPlugin = true;
      }
      boolean isSystem = (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
      AppInfo appInfo = new AppInfo();
      appInfo.isSystem = isSystem;
      appInfo.isPlugin = isPlugin;
//      Drawable icon = resolveInfo.loadIcon(packageManager);
//      appInfo.setAppIcon(icon);
      String appName = resolveInfo.loadLabel(packageManager).toString();
      appInfo.setAppName(appName);
      appInfo.setAppIcon(packageManager.getApplicationIcon(applicationInfo));
      appInfo.setPackageName(applicationInfo.packageName);
      appList.add(appInfo);
    }
    return appList;
  }


  /**
   * 创建AppInfo对象
   */
  public static AppInfo createAppInfo(Context context, PackageInfo packageInfo, PackageManager packageManager) {
    AppInfo appInfo = new AppInfo();
    ApplicationInfo applicationInfo = packageInfo.applicationInfo;
    appInfo.setPackageName(packageInfo.packageName);
    appInfo.setVersionName(packageInfo.versionName);
    // 获取应用名称和图标
    appInfo.setAppName(packageManager.getApplicationLabel(applicationInfo).toString());
    appInfo.setAppIcon(packageManager.getApplicationIcon(applicationInfo));

    return appInfo;
  }

  /**
   * 检查应用是否已安装
   *
   * @param context     上下文
   * @param packageName 应用包名
   * @return 是否安装
   */
  public static boolean isAppInstalled(Context context, String packageName) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getPackageManager().getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        );
      } else {
        //noinspection deprecation
        context.getPackageManager().getPackageInfo(
            packageName,
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        );
      }
      return true;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }

  /**
   * 启动应用
   *
   * @param context     上下文
   * @param packageName 应用包名
   * @return 是否启动成功
   */
  public static boolean launchApp(Context context, String packageName) {
    if (!isAppInstalled(context, packageName)) {
      return false;
    }

    try {
      Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
      if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return false;
  }

  /**
   * 启动应用的特定Activity
   *
   * @param context      上下文
   * @param packageName  应用包名
   * @param activityName Activity完整类名
   * @return 是否启动成功
   */
  public static boolean launchAppActivity(Context context, String packageName, String activityName) {
    try {
      Intent intent = new Intent();
      intent.setClassName(packageName, activityName);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      context.startActivity(intent);
      return true;
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
  }


  private static final String TAG = "AppUtils";

  /**
   * 获取应用签名的哈希值
   */
  public static String getSignatureHash(PackageInfo packageInfo) {
    if (packageInfo.signatures == null || packageInfo.signatures.length == 0) {
      return "无签名信息";
    }

    Signature signature = packageInfo.signatures[0];
    try {
      MessageDigest md = MessageDigest.getInstance("SHA1");
      md.update(signature.toByteArray());
      byte[] digest = md.digest();

      // 转换为十六进制字符串
      StringBuilder hexString = new StringBuilder();
      for (byte b : digest) {
        String hex = Integer.toHexString(0xFF & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
      return "获取签名失败: " + e.getMessage();
    }
  }

  /**
   * 获取应用的运行进程
   */
  public static String getAppProcesses(String packageName) {
    PluginDelegate delegate = PluginDelegate.get();
    if (delegate == null) {
      return "需要连接到服务才能获取进程信息";
    }
    try {
      String processes = delegate.getAppProcesses(packageName);
      if (processes == null || processes.isEmpty()) {
        return "应用未运行";
      }
      return processes;
    } catch (PackageManager.NameNotFoundException e) {
      return "应用" + packageName + "信息获取失败,可能没有安装";
    } catch (Throwable e) {
      e.printStackTrace();
      return "获取进程信息失败: " + e.getMessage();
    }
  }

  /**
   * 强制停止应用（需要Root权限）
   */
  public static boolean forceStopApp(Context context, String packageName) {
    PluginDelegate delegate = PluginDelegate.get();
    if (delegate != null) {
      if (delegate.forceStopApp(packageName))
        return true;
    }
    if (!ServerManager.getInstance(context).isDeviceRooted()) {
      Log.e(TAG, "没有Root权限，无法强制停止应用");
      return false;
    }
    try {
      // 执行su命令强制停止应用
      Process process = Runtime.getRuntime().exec("su");
      DataOutputStream os = new DataOutputStream(process.getOutputStream());
      os.writeBytes("am force-stop " + packageName + "\n");
      os.writeBytes("exit\n");
      os.flush();
      int result = process.waitFor();
      return result == 0;
    } catch (IOException | InterruptedException e) {
      e.printStackTrace();
      return false;
    }
  }

  /**
   * 冻结应用（需要Root权限）
   */
  public static boolean freezeApp(Context context, String packageName) {
    PluginDelegate delegate = PluginDelegate.get();
    if (delegate != null) {
      if (delegate.freezeApp(packageName))
        return true;
    }
    if (!ServerManager.getInstance(context).isDeviceRooted()) {
      Log.e(TAG, "没有Root权限，无法冻结应用");
      return false;
    }
    try {
      // 执行su命令冻结应用
      Process process = Runtime.getRuntime().exec("su");
      DataOutputStream os = new DataOutputStream(process.getOutputStream());
      os.writeBytes("pm disable " + packageName + "\n");
      os.writeBytes("exit\n");
      os.flush();
      int result = process.waitFor();
      return result == 0;
    } catch (IOException | InterruptedException e) {
      e.printStackTrace();
      return false;
    }
  }

  /**
   * 解冻应用（需要Root权限）
   */
  public static boolean unfreezeApp(Context context, String packageName) {
    PluginDelegate delegate = PluginDelegate.get();
    if (delegate != null) {
      if (delegate.unfreezeApp(packageName))
        return true;
    }
    if (!ServerManager.getInstance(context).isDeviceRooted()) {
      Log.e(TAG, "没有Root权限，无法解冻应用");
      return false;
    }
    try {
      Process process = Runtime.getRuntime().exec("su");
      DataOutputStream os = new DataOutputStream(process.getOutputStream());
      os.writeBytes("pm enable " + packageName + "\n");
      os.writeBytes("exit\n");
      os.flush();
      int result = process.waitFor();
      return result == 0;
    } catch (IOException | InterruptedException e) {
      e.printStackTrace();
      return false;
    }
  }

  /**
   * 检查应用是否被冻结
   */
  public static boolean isAppFrozen(Context context, String packageName) {
    try {
      Process process = Runtime.getRuntime().exec("su");
      DataOutputStream os = new DataOutputStream(process.getOutputStream());
      os.writeBytes("pm list packages -d | grep " + packageName + "\n");
      os.writeBytes("exit\n");
      os.flush();
      int result = process.waitFor();
      return result == 0;
    } catch (IOException | InterruptedException e) {
      e.printStackTrace();
      return false;
    }
  }

}
