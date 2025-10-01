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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import qing.albatross.manager.data.ConfigManager;

public class SELinuxManager {

  public static final String TAG = "SELinuxManager";

  // 受SELinux严格管控的典型目录列表
  private static final String[] RESTRICTED_PATHS = {
      "/sys/fs/selinux/",              // SELinux文件系统根目录
//      "/proc/self/attr/current",       // 进程的SELinux上下文
      "/dev/block/bootdevice/by-name/", //  boot分区设备节点
      "/sys/class/block/",             // 块设备信息
  };

  /**
   * 检查是否能访问受SELinux严格管控的目录
   *
   * @return true: 很可能启用了SELinux, false: 很可能未启用
   */
  public static boolean checkByRestrictedDirectories() {

    int restrictedCount = 0;
    int accessibleCount = 0;

    for (String path : RESTRICTED_PATHS) {
      File file = new File(path);
      boolean exists = file.exists();

      // 检查文件/目录是否存在
      if (!exists) {
        continue;
      }

      // 检查是否可读取
      boolean canRead = checkReadable(file);

      if (canRead) {
        accessibleCount++;
        Log.d(TAG, "可访问受限制路径: " + path);
      } else {
        restrictedCount++;
        Log.d(TAG, "无法访问受限制路径: " + path);
      }
    }

    // 决策逻辑：如果大多数受限制路径无法访问，则很可能启用了SELinux
    if (restrictedCount == 0) {
      return false; // 所有受检路径都可访问，很可能未启用SELinux
    } else if (accessibleCount == 0) {
      return true;  // 所有受检路径都不可访问，很可能启用了SELinux
    } else {
      // 大多数路径受限制则判断为启用
      return restrictedCount > accessibleCount;
    }
  }

  /**
   * 检查文件是否真正可读取（不仅是canRead()方法）
   */
  private static boolean checkReadable(File file) {
    if (!file.canRead()) {
      return false;
    }

    // 对于文件，尝试实际读取内容
    if (file.isFile()) {
      InputStream is = null;
      try {
        is = new FileInputStream(file);
        if (is.read() != -1) {
          return true;
        }
        return true; // 即使内容为空，能打开也算可访问
      } catch (IOException e) {
        return false;
      } finally {
        if (is != null) {
          try {
            is.close();
          } catch (IOException e) {
            // 忽略关闭异常
          }
        }
      }
    }
    // 对于目录，检查是否能列出内容
    if (file.isDirectory()) {
      File[] files = file.listFiles();
      return files != null;
    }

    return false;
  }

  /**
   * 开启SELinux（切换到enforcing模式）
   *
   * @return 操作是否成功
   */
  public static boolean enableSELinux() {
    return setSELinuxStatus(true);
  }

  /**
   * 关闭SELinux（切换到permissive模式）
   *
   * @return 操作是否成功
   */
  public static boolean disableSELinux() {
    return setSELinuxStatus(false);
  }

  /**
   * 设置SELinux状态
   *
   * @param enable true为开启(enforcing)，false为关闭(permissive)
   * @return 操作是否成功
   */
  private static boolean setSELinuxStatus(boolean enable) {
    Process process = null;
    OutputStream outputStream = null;
    InputStream inputStream = null;

    try {
      // 执行su命令获取root权限
      process = Runtime.getRuntime().exec(ConfigManager.getInstance(null).getSuFilePath());
      outputStream = process.getOutputStream();
      // 发送设置SELinux状态的命令
      String command = "setenforce " + (enable ? "1" : "0") + "\n";
      outputStream.write(command.getBytes());
      outputStream.flush();

      // 发送退出命令
      outputStream.write("exit\n".getBytes());
      outputStream.flush();

      // 等待命令执行完成
      int result = process.waitFor();

      // 检查命令执行结果，0表示成功
      return result == 0;

    } catch (Exception e) {
      e.printStackTrace();
      return false;
    } finally {
      try {
        if (outputStream != null) outputStream.close();
        if (inputStream != null) inputStream.close();
        if (process != null) process.destroy();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
}
