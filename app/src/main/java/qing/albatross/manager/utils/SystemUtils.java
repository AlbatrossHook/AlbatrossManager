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

import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * 系统信息工具类，用于获取CPU架构、内核版本等系统信息
 */
public class SystemUtils {
  private static final String TAG = "SystemUtils";

  /**
   * 获取CPU架构信息
   */
  public static String getCpuArchitecture() {
    // 对于64位设备，可能有多个ABI
    String[] abis = Build.SUPPORTED_ABIS;
    if (abis != null && abis.length > 0) {
      StringBuilder sb = new StringBuilder();
      for (String abi : abis) {
        if (sb.length() > 0) {
          sb.append(", ");
        }
        sb.append(abi);
      }
      return sb.toString();
    }
    // 兼容旧版本
    return Build.CPU_ABI + (Build.CPU_ABI2 != null ? ", " + Build.CPU_ABI2 : "");
  }

  /**
   * 获取内核版本
   */
  public static String getKernelVersion() {
    try {
      BufferedReader reader = new BufferedReader(new FileReader("/proc/version"));
      String line = reader.readLine();
      reader.close();

      if (line != null) {
        // 解析内核版本信息，格式通常为: Linux version 4.14.117+ (builder@...) (...)
        return line.split(" ")[2];
      }
    } catch (IOException e) {
      Log.e(TAG, "获取内核版本失败: " + e.getMessage());
    }
    return "未知";
  }
}
