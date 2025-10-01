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
import android.net.Uri;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZIP文件解压工具类
 */
public class ZipExtractor {
  private static final String TAG = "ZipExtractor";
  private static final int BUFFER_SIZE = 8192;

  /**
   * 解压ZIP文件到目标目录
   * @param context 上下文
   * @param zipUri ZIP文件的Uri
   * @param targetDir 目标目录
   * @return 是否解压成功
   */
  public static boolean extract(Context context, Uri zipUri, String targetDir) {
    try (InputStream inputStream = context.getContentResolver().openInputStream(zipUri);
         ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(inputStream))) {

      if (inputStream == null) {
        Log.e(TAG, "无法打开ZIP文件输入流");
        return false;
      }

      ZipEntry entry;
      while ((entry = zipIn.getNextEntry()) != null) {
        String entryPath = entry.getName();
        File entryFile = new File(targetDir, entryPath);

        // 创建父目录
        if (!entryFile.getParentFile().exists()) {
          if (!entryFile.getParentFile().mkdirs()) {
            Log.e(TAG, "无法创建目录: " + entryFile.getParentFile().getAbsolutePath());
            return false;
          }
        }

        // 如果是目录，跳过
        if (entry.isDirectory()) {
          if (!entryFile.exists() && !entryFile.mkdirs()) {
            Log.e(TAG, "无法创建目录: " + entryFile.getAbsolutePath());
            return false;
          }
          continue;
        }

        // 解压文件
        try (BufferedOutputStream bos = new BufferedOutputStream(
            new FileOutputStream(entryFile))) {

          byte[] buffer = new byte[BUFFER_SIZE];
          int bytesRead;
          while ((bytesRead = zipIn.read(buffer)) != -1) {
            bos.write(buffer, 0, bytesRead);
          }
        }
        // 保留文件权限
        entryFile.setLastModified(entry.getTime());
        if ((entry.getMethod() & ZipEntry.STORED) == 0) {
          // 对于压缩的条目，设置默认权限
          entryFile.setReadable(true, false);
          entryFile.setWritable(true, true);
          entryFile.setExecutable(entry.getName().contains("albatross_server"), false);
        }
      }

      zipIn.closeEntry();
      return true;

    } catch (IOException e) {
      Log.e(TAG, "ZIP解压失败: " + e.getMessage(), e);
      return false;
    }
  }
}
