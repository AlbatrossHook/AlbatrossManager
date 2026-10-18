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

import qing.albatross.app.agent.client.Const;
import qing.albatross.core.Albatross;
import qing.albatross.manager.R;
import qing.albatross.manager.data.ConfigManager;

/**
 * 把设置页的运行选项翻译成注入目标进程时使用的 flag。
 * 这里是设置与 flags 之间唯一的转换点，注入相关代码都从这里取值。
 */
public class RuntimeOptions {

  private RuntimeOptions() {
  }

  /**
   * 注入目标进程时下发的 albatross init flags：
   * 关闭 JIT 后目标进程按解释执行运行（更稳但更慢），对抗模式会隐藏注入的 so 并让代码走 memfd。
   */
  public static int initFlags(Context context) {
    ConfigManager configManager = ConfigManager.getInstance(context);
    int flags = 0;
    if (!configManager.isJitEnabled()) {
      flags |= Albatross.FLAG_DISABLE_JIT;
    }
    if (configManager.isAntiDetectionEnabled()) {
      flags |= Albatross.FLAG_ANTI_DETECTION;
    }
    return flags;
  }

  /**
   * 注入方式 flag，见 src/server/pipe.h 的 InjectFlag
   */
  public static int injectFlags(Context context) {
    int flags = Const.INJECT_DEFAULT;
    if (ConfigManager.getInstance(context).isMemfdInjectEnabled()) {
      flags |= Const.INJECT_MEMFD;
    }
    return flags;
  }

  /**
   * 界面上展示的选项摘要
   */
  public static String describe(Context context, String on, String off) {
    ConfigManager configManager = ConfigManager.getInstance(context);
    StringBuilder builder = new StringBuilder();
    builder.append(context.getString(R.string.option_jit)).append(' ')
        .append(configManager.isJitEnabled() ? on : off);
    builder.append("   ").append(context.getString(R.string.option_anti_detection)).append(' ')
        .append(configManager.isAntiDetectionEnabled() ? on : off);
    builder.append("   ").append(context.getString(R.string.option_memfd_inject)).append(' ')
        .append(configManager.isMemfdInjectEnabled() ? on : off);
    return builder.toString();
  }
}
