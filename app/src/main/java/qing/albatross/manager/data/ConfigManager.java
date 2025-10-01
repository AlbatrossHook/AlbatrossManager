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

import android.content.Context;
import android.content.SharedPreferences;


public class ConfigManager {

  public static final String APP_AGENT_FILE = "app_agent.dex";
  public static final String SYSTEM_AGENT_FILE = "system_server.dex";
  public static final String LIB32_DIR_NAME = "32bit";
  public static final String LIB_NAME = "libalbatross_base.so";

  private static final String PREFERENCES_NAME = "albatross_manager";
  private static final String KEY_SU_FILE_PATH = "su_file_path";
  private static final String KEY_SERVER_ADDRESS = "server_address";
  private static final String KEY_SERVER_FILE_NAME = "server_file_name";
  private static final String KEY_SYS_AGENT_FILE_NAME = "sys_agent";
  private static final String KEY_APP_AGENT_FILE_NAME = "app_agent";

  private static final String KEY_SERVER_RUNNING_STATE = "server_running_state";
  private static final String KEY_LAST_SERVER_UPDATE = "last_server_update";
  private static final String DEFAULT_SU_PATH = "/system/bin/su";

  private static ConfigManager instance;
  private final SharedPreferences sharedPreferences;

  // 私有构造函数，确保单例
  private ConfigManager(Context context) {
    sharedPreferences = context.getApplicationContext()
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
  }

  // 获取单例实例
  public static synchronized ConfigManager getInstance(Context context) {
    if (instance == null) {
      instance = new ConfigManager(context);
    }
    return instance;
  }

  /**
   * 保存SU文件路径
   */
  public void saveSuFilePath(String path) {
    sharedPreferences.edit().putString(KEY_SU_FILE_PATH, path).apply();
  }

  /**
   * 获取SU文件路径
   * 如果未设置，返回默认路径
   */
  public String getSuFilePath() {
    return sharedPreferences.getString(KEY_SU_FILE_PATH, null);
  }


  public void saveServerFileName(String fileName) {
    sharedPreferences.edit().putString(KEY_SERVER_FILE_NAME, fileName).apply();
  }

  public String getServerAddress() {
    return sharedPreferences.getString(KEY_SERVER_ADDRESS, "albatross_manager");
  }

  public void saveServerAddress(String address) {
    sharedPreferences.edit().putString(KEY_SERVER_ADDRESS, address).apply();
  }

  public String getAppAgentFileName() {
    return sharedPreferences.getString(KEY_APP_AGENT_FILE_NAME, null);
  }

  public void saveSysAgentFileName(String fileName) {
    sharedPreferences.edit().putString(KEY_SYS_AGENT_FILE_NAME, fileName).apply();
  }

  public String getSysAgentFileName() {
    return sharedPreferences.getString(KEY_SYS_AGENT_FILE_NAME, null);
  }

  /**
   * 保存服务运行状态
   */
  public void saveServerRunningState(boolean isRunning) {
    sharedPreferences.edit().putBoolean(KEY_SERVER_RUNNING_STATE, isRunning).apply();
  }

  /**
   * 获取保存的服务运行状态
   * 注意：这只是最后保存的状态，实际状态需要通过ServerManager检查
   */
  public boolean getSavedServerRunningState() {
    return sharedPreferences.getBoolean(KEY_SERVER_RUNNING_STATE, false);
  }

  /**
   * 保存服务最后更新时间
   */
  private void saveLastServerUpdateTime(long timestamp) {
    sharedPreferences.edit().putLong(KEY_LAST_SERVER_UPDATE, timestamp).apply();
  }

  /**
   * 获取服务最后更新时间
   */
  public long getLastServerUpdateTime() {
    return sharedPreferences.getLong(KEY_LAST_SERVER_UPDATE, 0);
  }

  /**
   * 清除所有配置
   */
  public void clearAllConfig() {
    sharedPreferences.edit().clear().apply();
  }

  /**
   * 清除服务相关配置
   */
  public void clearServerConfig() {
    sharedPreferences.edit()
        .remove(KEY_SERVER_ADDRESS)
        .remove(KEY_SERVER_FILE_NAME)
        .remove(KEY_LAST_SERVER_UPDATE)
        .remove(KEY_SERVER_RUNNING_STATE)
        .apply();
  }
}
    