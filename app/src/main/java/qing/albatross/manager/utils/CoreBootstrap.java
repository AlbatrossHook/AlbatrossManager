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

import java.io.File;

import qing.albatross.core.Albatross;
import qing.albatross.manager.R;
import qing.albatross.manager.data.ConfigManager;
import qing.albatross.manager.data.PluginDatabaseHelper;
import qing.albatross.manager.data.ServerDatabaseHelper;
import qing.albatross.manager.model.ServerInfo;
import qing.albatross.manager.plugin.PluginDelegate;

/**
 * 在当前进程里加载核心（libalbatross_base + app_agent.dex），并建立到服务的连接。
 * 界面和服务端广播都走这里，避免两套加载逻辑。
 */
public final class CoreBootstrap {

  private CoreBootstrap() {
  }

  public static class Result {
    public final boolean ok;
    public final String features;
    //核心已加载但服务未连接时也返回 ok，connected 表示是否连上服务
    public final boolean connected;
    public final String error;

    Result(boolean ok, String features, boolean connected, String error) {
      this.ok = ok;
      this.features = features;
      this.connected = connected;
      this.error = error;
    }
  }

  //核心只需要加载一次，重复 patchClassLoader 会往 dexElements 里反复追加
  private static boolean coreLoaded;

  public static boolean isCoreLoaded() {
    return coreLoaded;
  }

  public static Result load(Context context) {
    ServerDatabaseHelper dbHelper = ServerDatabaseHelper.getInstance(context);
    //setCurrentServerVersionInternal 里会取 ConfigManager，先保证单例建好
    ConfigManager configManager = ConfigManager.getInstance(context);
    ServerInfo serverInfo = dbHelper.getCurrentServerInfo();
    if (serverInfo == null) {
      return new Result(false, null, false, context.getString(R.string.server_not_found));
    }
    String libPath = serverInfo.getLibPath();
    String dexPath = serverInfo.getAgentPath() + "/" + ConfigManager.APP_AGENT_FILE;
    if (!new File(dexPath).exists() || !new File(libPath).exists()) {
      return new Result(false, null, false, "file not exists:" + dexPath);
    }
    int i = libPath.lastIndexOf('/');
    String libDir = libPath.substring(0, i);
    String libName = libPath.substring(i + 4, libPath.length() - 3);
    try {
      if (!coreLoaded) {
        ClassLoaderUtils.patchClassLoader(CoreBootstrap.class.getClassLoader(), dexPath, libDir);
      }
      if (!Albatross.loadLibrary(libName, 0)) {
        return new Result(false, null, false, "loadLibrary fail");
      }
      coreLoaded = true;
      configManager.saveCoreState(true);
      String features = Albatross.supportFeatures();
      PluginDelegate delegate = PluginDelegate.create(dbHelper, context);
      return new Result(true, features, delegate != null, null);
    } catch (Throwable e) {
      return new Result(false, null, false, e.toString());
    }
  }
}
