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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CPU架构检测工具类，用于获取设备支持的CPU架构信息
 */
public class ArchitectureUtils {
  private static final String TAG = "ArchitectureUtils";

  // 常见的CPU架构类型
  public static final String ARCH_ARM64_V8A = "arm64-v8a";
  public static final String ARCH_ARMEABI_V7A = "armeabi-v7a";
  public static final String ARCH_ARMEABI = "armeabi";
  public static final String ARCH_X86_64 = "x86_64";
  public static final String ARCH_X86 = "x86";
  public static final String ARCH_MIPS64 = "mips64";
  public static final String ARCH_MIPS = "mips";

  /**
   * 判断设备是否为64位架构
   *
   * @return true为64位，false为32位
   */
  public static boolean is64Bit() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      // 64位设备会有64位的ABI
      for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
        if (abi != null && !abi.isEmpty()) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * 获取设备支持的所有CPU架构列表
   *
   * @return 架构列表，按优先级排序
   */
  public static List<String> getSupportedArchitectures() {
    List<String> supportedArchs = new ArrayList<>();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      // 先添加64位架构
      supportedArchs.addAll(Arrays.asList(Build.SUPPORTED_64_BIT_ABIS));
      // 再添加32位架构
      supportedArchs.addAll(Arrays.asList(Build.SUPPORTED_32_BIT_ABIS));
    } else {
      // 旧版本系统只支持32位
      supportedArchs.add(Build.CPU_ABI);
      if (Build.CPU_ABI2 != null && !Build.CPU_ABI2.isEmpty()) {
        supportedArchs.add(Build.CPU_ABI2);
      }
    }

    // 过滤空值并返回
    List<String> result = new ArrayList<>();
    for (String arch : supportedArchs) {
      if (arch != null && !arch.isEmpty() && !result.contains(arch)) {
        result.add(arch);
      }
    }
    return result;
  }

  /**
   * 获取设备的主架构（优先级最高的架构）
   *
   * @return 主架构名称
   */
  public static String getPrimaryArchitecture() {
    List<String> supportedArchs = getSupportedArchitectures();
    if (!supportedArchs.isEmpty()) {
      return supportedArchs.get(0);
    }

    //  fallback：如果无法获取，返回常见的默认架构
    Log.w(TAG, "无法获取设备主架构，使用默认值");
    return ARCH_ARM64_V8A;
  }

  /**
   * 获取设备支持的32位架构（如果设备是64位且支持32位）
   *
   * @return 32位架构名称，不支持则返回null
   */
  public static String get32BitArchitecture() {
    if (!is64Bit()) {
      return null; // 不是64位设备，返回null
    }

    // 查找32位架构
    List<String> supportedArchs = getSupportedArchitectures();
    for (String arch : supportedArchs) {
      if (is32BitArchitecture(arch)) {
        return arch;
      }
    }
    return null;
  }

  /**
   * 判断架构是否为32位
   *
   * @param arch 架构名称
   * @return true为32位架构
   */
  private static boolean is32BitArchitecture(String arch) {
    return arch.equals(ARCH_ARMEABI) ||
        arch.equals(ARCH_ARMEABI_V7A) ||
        arch.equals(ARCH_X86) ||
        arch.equals(ARCH_MIPS);
  }

  /**
   * 判断架构是否为64位
   *
   * @param arch 架构名称
   * @return true为64位架构
   */
  public static boolean is64BitArchitecture(String arch) {
    return arch.equals(ARCH_ARM64_V8A) ||
        arch.equals(ARCH_X86_64) ||
        arch.equals(ARCH_MIPS64);
  }

  /**
   * 检查指定架构是否被设备支持
   *
   * @param arch 架构名称
   * @return true为支持
   */
  public static boolean isArchitectureSupported(String arch) {
    return getSupportedArchitectures().contains(arch);
  }

  /**
   * 将架构名称转换为易读的显示名称
   *
   * @param arch 架构名称
   * @return 易读的架构名称
   */
  public static String getArchitectureDisplayName(String arch) {
    switch (arch) {
      case ARCH_ARM64_V8A:
        return "ARM 64位 (arm64-v8a)";
      case ARCH_ARMEABI_V7A:
        return "ARM 32位 (armeabi-v7a)";
      case ARCH_ARMEABI:
        return "ARM 32位 (armeabi)";
      case ARCH_X86_64:
        return "x86 64位 (x86_64)";
      case ARCH_X86:
        return "x86 32位 (x86)";
      case ARCH_MIPS64:
        return "MIPS 64位 (mips64)";
      case ARCH_MIPS:
        return "MIPS 32位 (mips)";
      default:
        return arch;
    }
  }
}
