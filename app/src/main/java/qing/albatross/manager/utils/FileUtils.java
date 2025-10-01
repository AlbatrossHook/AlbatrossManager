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

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 文件操作工具类
 */
public class FileUtils {
  private static final String TAG = "FileUtils";
  private static final int BUFFER_SIZE = 8192;

  /**
   * 创建目录
   */
  public static boolean createDirectory(File dir) {
    if (dir.exists()) {
      return dir.isDirectory();
    }
    return dir.mkdirs();
  }

  /**
   * 复制文件
   */
  public static boolean copyFile(File source, File dest) {
    if (!source.exists() || !source.isFile()) {
      Log.e(TAG, "源文件不存在或不是文件: " + source.getAbsolutePath());
      return false;
    }
    try (InputStream in = new FileInputStream(source);
         OutputStream out = new FileOutputStream(dest)) {

      byte[] buffer = new byte[BUFFER_SIZE];
      int length;
      while ((length = in.read(buffer)) > 0) {
        out.write(buffer, 0, length);
      }
      // 保留源文件的最后修改时间
      dest.setLastModified(source.lastModified());
      Os.chmod(dest.getCanonicalPath(), 0444);
//      dest.setReadOnly();
      return true;
    } catch (IOException e) {
      Log.e(TAG, "复制文件失败: " + e.getMessage(), e);
      return false;
    } catch (ErrnoException e) {
      Log.d(TAG, "设置失败：" + e.getMessage());
      dest.setReadOnly();
      return true;
    }
  }

  /**
   * 设置文件可执行权限
   */
  public static boolean setExecutable(File file) {
    if (file == null || !file.exists()) {
      return false;
    }
    return file.setExecutable(true, false);
  }

  /**
   * 读取文件内容为字符串
   */
  public static String readFileToString(File file) {
    if (!file.exists() || !file.isFile()) {
      Log.e(TAG, "文件不存在或不是文件: " + file.getAbsolutePath());
      return null;
    }
    StringBuilder content = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(new FileInputStream(file)))) {
      String line;
      while ((line = reader.readLine()) != null) {
        content.append(line).append("\n");
      }
      // 移除最后一个换行符
      if (content.length() > 0) {
        content.deleteCharAt(content.length() - 1);
      }
      return content.toString();
    } catch (IOException e) {
      Log.e(TAG, "读取文件失败: " + e.getMessage(), e);
      return null;
    }
  }

  /**
   * 删除文件
   */
  public static boolean deleteFile(File file) {
    if (file == null || !file.exists()) {
      return true;
    }

    if (file.isDirectory()) {
      return deleteDirectory(file);
    }

    return file.delete();
  }

  /**
   * 删除目录及其内容
   */
  public static boolean deleteDirectory(File dir) {
    if (dir == null || !dir.exists() || !dir.isDirectory()) {
      return true;
    }

    File[] files = dir.listFiles();
    if (files != null) {
      for (File file : files) {
        if (!deleteFile(file)) {
          Log.w(TAG, "无法删除文件: " + file.getAbsolutePath());
        }
      }
    }

    return dir.delete();
  }



  /**
   * 文件信息模型类
   */
  public static class FileInfo {
    public String name;
    public String path;
    public String size;
    public String modifiedTime;
    public boolean isDirectory;
    public int childCount;
    public int depth; // 用于缩进显示

    public FileInfo(String name, String path, String size, String modifiedTime,
                    boolean isDirectory, int childCount, int depth) {
      this.name = name;
      this.path = path;
      this.size = size;
      this.modifiedTime = modifiedTime;
      this.isDirectory = isDirectory;
      this.childCount = childCount;
      this.depth = depth;
    }
  }

  /**
   * 获取目录信息（包括子目录）
   */
  public static List<FileInfo> getDirectoryInfo(File directory) {
    return getDirectoryInfo(directory, 0);
  }

  private static List<FileInfo> getDirectoryInfo(File directory, int depth) {
    List<FileInfo> fileInfos = new ArrayList<>();

    if (!directory.exists() || !directory.isDirectory()) {
      return fileInfos;
    }

    // 添加当前目录
    fileInfos.add(createFileInfo(directory, depth));

    // 获取子文件和目录
    File[] files = directory.listFiles();
    if (files == null) {
      return fileInfos;
    }

    // 排序：目录在前，文件在后
    Arrays.sort(files, (f1, f2) -> {
      if (f1.isDirectory() && !f2.isDirectory()) return -1;
      if (!f1.isDirectory() && f2.isDirectory()) return 1;
      return f1.getName().compareToIgnoreCase(f2.getName());
    });

    // 递归添加子目录和文件
    for (File file : files) {
      if (file.isDirectory()) {
        fileInfos.addAll(getDirectoryInfo(file, depth + 1));
      } else {
        fileInfos.add(createFileInfo(file, depth + 1));
      }
    }

    return fileInfos;
  }

  /**
   * 创建文件信息对象
   */
  private static FileInfo createFileInfo(File file, int depth) {
    String name = file.getName();
    String path = file.getAbsolutePath();
    String size = formatFileSize(file.length());
    String modifiedTime = formatDate(file.lastModified());
    boolean isDirectory = file.isDirectory();
    int childCount = isDirectory ? countChildren(file) : 0;

    return new FileInfo(name, path, size, modifiedTime, isDirectory, childCount, depth);
  }

  /**
   * 计算目录中的子项数量
   */
  private static int countChildren(File directory) {
    File[] files = directory.listFiles();
    return files != null ? files.length : 0;
  }

  /**
   * 格式化文件大小
   */
  public static String formatFileSize(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    } else if (bytes < 1024 * 1024) {
      return String.format("%.2f KB", bytes / 1024.0);
    } else if (bytes < 1024 * 1024 * 1024) {
      return String.format("%.2f MB", bytes / (1024.0 * 1024));
    } else {
      return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
  }

  /**
   * 格式化日期
   */
  public static String formatDate(long timestamp) {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    return sdf.format(new Date(timestamp));
  }
  /**
   * 删除目录及其内容
   */
//  public static boolean deleteDirectory(File dir) {
//    if (dir == null || !dir.exists() || !dir.isDirectory()) {
//      return true;
//    }
//
//    File[] files = dir.listFiles();
//    if (files != null) {
//      for (File file : files) {
//        if (file.isDirectory()) {
//          deleteDirectory(file);
//        } else {
//          file.delete();
//        }
//      }
//    }
//    return dir.delete();
//  }


}
