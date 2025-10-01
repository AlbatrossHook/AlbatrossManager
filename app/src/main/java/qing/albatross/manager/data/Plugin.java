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
package qing.albatross.manager.data;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * 插件数据模型
 */
public class Plugin implements Parcelable {
  private long id;
  private String name;
  private String packageName;
  private String className;
  private String description;
  private String author;
  private String appVersion;
  private boolean isEnabled;
  private String supportApps;
  private int appUid;     // 应用UID
  private boolean isFrozen; // 是否冻结

  // 新增配置参数
  private String params;  // 字符串参数
  private int flags;     // 数值参数

  public Plugin() {
  }

  public Plugin(String name, String packageName, String className,
                String description, String author, String appVersion, boolean isEnabled) {
    this.name = name;
    this.packageName = packageName;
    this.className = className;
    this.description = description;
    this.author = author;
    this.appVersion = appVersion;
    this.isEnabled = isEnabled;
    this.params = "";  // 默认空字符串
    this.flags = 0;   // 默认0
  }

  protected Plugin(Parcel in) {
    id = in.readLong();
    name = in.readString();
    packageName = in.readString();
    className = in.readString();
    description = in.readString();
    author = in.readString();
    appVersion = in.readString();
    isEnabled = in.readByte() != 0;
    params = in.readString();
    flags = in.readInt();
    supportApps = in.readString();
  }

  public static final Creator<Plugin> CREATOR = new Creator<Plugin>() {
    @Override
    public Plugin createFromParcel(Parcel in) {
      return new Plugin(in);
    }

    @Override
    public Plugin[] newArray(int size) {
      return new Plugin[size];
    }
  };

  // Getters and Setters
  public int getId() {
    return (int) id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getPackageName() {
    return packageName;
  }

  public void setPackageName(String packageName) {
    this.packageName = packageName;
  }

  public String getClassName() {
    return className;
  }

  public void setClassName(String className) {
    this.className = className;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getAuthor() {
    return author;
  }

  public void setAuthor(String author) {
    this.author = author;
  }

  public String getAppVersion() {
    return appVersion;
  }

  public void setAppVersion(String appVersion) {
    this.appVersion = appVersion;
  }

  public boolean isEnabled() {
    return isEnabled;
  }

  public void setEnabled(boolean enabled) {
    isEnabled = enabled;
  }

  public String getParams() {
    return params;
  }

  public void setParams(String param1) {
    this.params = param1;
  }

  public int getFlags() {
    return flags;
  }

  public void setFlags(int flags) {
    this.flags = flags;
  }

  public String getSupportedApps() {
    return supportApps != null ? supportApps : "";
  }

  public void setSupportApps(String supportApps) {
    this.supportApps = supportApps;

  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeLong(id);
    dest.writeString(name);
    dest.writeString(packageName);
    dest.writeString(className);
    dest.writeString(description);
    dest.writeString(author);
    dest.writeString(appVersion);
    dest.writeByte((byte) (isEnabled ? 1 : 0));
    dest.writeString(params);
    dest.writeInt(this.flags);
    dest.writeString(supportApps);
  }
}
