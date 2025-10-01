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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class LogUtils {
  private static final String TAG = "LogUtils";

  /**
   * 获取应用进程ID
   */
  public static String getPidForPackage(String packageName) {
    try {
      // 执行ps命令查找包名对应的PID
      Process process = Runtime.getRuntime().exec("ps");
      BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream()));

      String line;
      while ((line = reader.readLine()) != null) {
        if (line.contains(packageName)) {
          // 解析PID（通常是第二个字段）
          String[] parts = line.trim().split("\\s+");
          if (parts.length >= 2) {
            return parts[1];
          }
        }
      }

      // 如果没找到运行的进程，返回空
      return "";

    } catch (IOException e) {
      e.printStackTrace();
      return "";
    }
  }

  /**
   * 清除应用日志
   */
  public static void clearLog() {
    try {
      Runtime.getRuntime().exec("logcat -c");
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
