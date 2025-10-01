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
package qing.albatross.manager.model;

import static qing.albatross.manager.data.Const.PKG_SYSTEM_SERVER;

import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;

/**
 * 应用信息数据模型，存储应用的基本信息和插件相关属性
 */
public class AppInfo {
  private String packageName;       // 应用包名
  private String appName;          // 应用名称
  private String versionName;      // 应用版本
  private Drawable appIcon;        // 应用图标
  public boolean isPlugin;        // 是否为有效插件
  private boolean isAdded;         // 是否已添加到插件列表
  public Bundle metaData;
  public boolean isSystem;
  public String sourceDex;


  // 构造函数
  public AppInfo(String packageName, String appName, String versionName, Drawable appIcon) {
    this.packageName = packageName;
    this.appName = appName;
    this.versionName = versionName;
    this.appIcon = appIcon;
    this.isPlugin = false;
    this.isAdded = false;
  }

  public AppInfo() {
  }

  // Getter和Setter方法
  public String getPackageName() {
    return packageName;
  }

  public void setPackageName(String packageName) {
    this.packageName = packageName;
  }

  public String getAppName() {
    return appName;
  }

  public void setAppName(String appName) {
    this.appName = appName;
  }

  public String getVersionName() {
    return versionName;
  }

  public void setVersionName(String versionName) {
    this.versionName = versionName;
  }

  public Drawable getAppIcon() {
    return appIcon;
  }

  public void setAppIcon(Drawable appIcon) {
    this.appIcon = appIcon;
  }

  public boolean isPlugin() {
    return isPlugin;
  }

  public void setPlugin(boolean plugin) {
    isPlugin = plugin;
  }

  public boolean isAdded() {
    return isAdded;
  }

  public void setAdded(boolean added) {
    isAdded = added;
  }

  public boolean isSelected() {
    return isAdded;
  }

  public void setSelected(boolean added) {
    isAdded = added;
  }

  static AppInfo systemServerInfo;

  public static AppInfo getSystemServer() {
    if (systemServerInfo != null)
      return systemServerInfo;
    systemServerInfo = new AppInfo();
    systemServerInfo.packageName = PKG_SYSTEM_SERVER;
    systemServerInfo.appName = "SystemProcess";
    systemServerInfo.versionName = Build.VERSION.SDK;
    systemServerInfo.isSystem = true;
    return systemServerInfo;

  }

}
