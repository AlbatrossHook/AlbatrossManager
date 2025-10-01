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

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志读取工具类，用于读取插件运行日志
 */
public class LogReader {
  private static final String TAG = "LogReader";
  private static final String LOG_DIR = "/data/data/com.example.magisklike/logs/";
  private final String pluginPackage;
  private long lastReadPosition = 0;

  public LogReader(String pluginPackage) {
    this.pluginPackage = pluginPackage;
    ensureLogDirExists();
  }

  // 确保日志目录存在
  private void ensureLogDirExists() {
    File dir = new File(LOG_DIR);
    if (!dir.exists()) {
      dir.mkdirs();
    }
  }

  // 获取日志文件路径
  private String getLogFilePath() {
    return LOG_DIR + pluginPackage.replace(".", "_") + ".log";
  }

  /**
   * 读取新增的日志内容
   */
  public List<String> readNewLogs() {
    List<String> newLogs = new ArrayList<>();
    File logFile = new File(getLogFilePath());

    if (!logFile.exists()) {
      return newLogs;
    }

    try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
      // 移动到上次读取的位置
      reader.skip(lastReadPosition);

      String line;
      while ((line = reader.readLine()) != null) {
        newLogs.add(line);
      }

      // 记录当前读取位置
      lastReadPosition = reader.skip(0); // 获取当前文件指针位置

    } catch (IOException e) {
      Log.e(TAG, "读取日志失败: " + e.getMessage());
    }

    return newLogs;
  }

  /**
   * 清空日志文件
   */
  public boolean clearLogs() {
    File logFile = new File(getLogFilePath());
    return logFile.exists() && logFile.delete();
  }
}
