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

import java.io.File;

import qing.albatross.manager.R;
import qing.albatross.manager.data.ConfigManager;
import qing.albatross.manager.data.ServerDatabaseHelper;
import qing.albatross.manager.model.ServerInfo;

/**
 * 核心（服务包）导入：解压 zip、按设备架构落盘、写版本库。
 * 不弹窗不提示，界面与广播命令都用它，错误通过返回值返回。
 */
public final class CoreInstaller {

  private CoreInstaller() {
  }

  /**
   * zip 里待安装的内容
   */
  public static class Package {
    public final File rootDir;
    public final String version;
    public final String description;
    public final String error;

    Package(File rootDir, String version, String description, String error) {
      this.rootDir = rootDir;
      this.version = version;
      this.description = description;
      this.error = error;
    }

    public boolean isOk() {
      return error == null;
    }
  }

  public static class Result {
    public final boolean ok;
    public final String version;
    public final String error;
    //是否被设为当前版本，界面据此提示需要重启
    public final boolean setAsCurrent;
    //是否是导入的第一个版本
    public final boolean firstVersion;

    Result(boolean ok, String version, String error, boolean setAsCurrent, boolean firstVersion) {
      this.ok = ok;
      this.version = version;
      this.error = error;
      this.setAsCurrent = setAsCurrent;
      this.firstVersion = firstVersion;
    }
  }

  private static Package fail(Context context, int stringRes) {
    return new Package(null, null, null, context.getString(stringRes));
  }

  /**
   * 解压 zip 并读取版本信息，供调用方先判断版本是否已存在
   */
  public static Package extract(Context context, Uri zipUri) {
    //ServerDatabaseHelper 内部会取 ConfigManager，先保证单例已建好
    ConfigManager.getInstance(context);
    File tempDir = new File(context.getCacheDir(), "server_temp");
    try {
      FileUtils.deleteDirectory(tempDir);
      FileUtils.createDirectory(tempDir);
      if (!ZipExtractor.extract(context, zipUri, tempDir.getAbsolutePath())) {
        return fail(context, R.string.zip_extract_failed_message);
      }
      File rootDir = tempDir;
      File resourceDir = new File(tempDir, "resource");
      if (resourceDir.exists()) {
        rootDir = resourceDir;
      }
      File versionFile = new File(rootDir, "version");
      if (!versionFile.exists()) {
        return fail(context, R.string.zip_incomplete_message);
      }
      String version = FileUtils.readFileToString(versionFile);
      if (version == null || version.trim().isEmpty()) {
        return fail(context, R.string.version_read_failed_message);
      }
      String description = context.getString(R.string.no_description_info);
      File descriptionFile = new File(rootDir, "description");
      if (descriptionFile.exists()) {
        String read = FileUtils.readFileToString(descriptionFile);
        if (read != null && !read.trim().isEmpty()) {
          description = read.trim();
        }
      }
      return new Package(rootDir, version.trim(), description, null);
    } catch (Throwable e) {
      return new Package(null, null, null, context.getString(R.string.processing_failed_format, e.toString()));
    }
  }

  /**
   * 导入已解压的服务包
   */
  public static Result installExtracted(Context context, Package packageInfo, boolean overwrite, boolean setCurrent) {
    if (packageInfo == null || !packageInfo.isOk()) {
      return new Result(false, null, packageInfo == null ? "no package" : packageInfo.error, false, false);
    }
    String version = packageInfo.version;
    ServerDatabaseHelper dbHelper = ServerDatabaseHelper.getInstance(context);
    try {
      dbHelper.ensureDatabaseOpen();
      boolean exists = dbHelper.checkVersionExists(version);
      if (exists && !overwrite) {
        return new Result(false, version, context.getString(R.string.version_exists_message_format, version), false, false);
      }
      String primaryArch = ArchitectureUtils.getPrimaryArchitecture();
      File versionDir = new File(context.getFilesDir(), "servers/" + version);
      if (exists) {
        FileUtils.deleteDirectory(versionDir);
        dbHelper.deleteServerVersion(version);
      }
      FileUtils.createDirectory(versionDir);
      File jniLibsDir = new File(packageInfo.rootDir, "jniLibs");
      if (!copyArchitectureFiles(jniLibsDir, primaryArch, versionDir)) {
        FileUtils.deleteDirectory(versionDir);
        return new Result(false, version, context.getString(R.string.no_arch_files_format, primaryArch), false, false);
      }
      boolean secondaryCopied = false;
      if (ArchitectureUtils.is64Bit()) {
        String secondaryArch = ArchitectureUtils.get32BitArchitecture();
        if (secondaryArch != null) {
          secondaryCopied = copyArchitectureFiles(jniLibsDir, secondaryArch,
              new File(versionDir, ConfigManager.LIB32_DIR_NAME));
        }
      }
      File agentDir = new File(packageInfo.rootDir, "agent");
      File destAgentDir = new File(versionDir, "agent");
      FileUtils.createDirectory(destAgentDir);
      if (!copyAgentFile(agentDir, destAgentDir, ConfigManager.APP_AGENT_FILE)) {
        FileUtils.deleteDirectory(versionDir);
        return new Result(false, version, context.getString(R.string.missing_agent_file_format,
            ConfigManager.APP_AGENT_FILE), false, false);
      }
      if (!copyAgentFile(agentDir, destAgentDir, ConfigManager.SYSTEM_AGENT_FILE)) {
        FileUtils.deleteDirectory(versionDir);
        return new Result(false, version, context.getString(R.string.missing_agent_file_format,
            ConfigManager.SYSTEM_AGENT_FILE), false, false);
      }
      ServerInfo serverInfo = new ServerInfo();
      serverInfo.setVersion(version);
      serverInfo.setDescription(packageInfo.description);
      serverInfo.setPrimaryArchitecture(primaryArch);
      serverInfo.setSupport32Bit(secondaryCopied);
      serverInfo.setServerPath(new File(versionDir, "albatross_server").getAbsolutePath());
      serverInfo.setLibPath(new File(versionDir, ConfigManager.LIB_NAME).getAbsolutePath());
      serverInfo.setLib32Path(secondaryCopied ?
          new File(versionDir, ConfigManager.LIB32_DIR_NAME + "/" + ConfigManager.LIB_NAME).getAbsolutePath() : null);
      serverInfo.setAgentPath(destAgentDir.getAbsolutePath());

      boolean firstVersion = dbHelper.getAllServerVersions().isEmpty();
      boolean asCurrent = setCurrent || firstVersion;
      dbHelper.addServerVersion(serverInfo, asCurrent);
      return new Result(true, version, null, asCurrent, firstVersion);
    } catch (Throwable e) {
      return new Result(false, version, context.getString(R.string.save_failed_format, e.toString()), false, false);
    } finally {
      FileUtils.deleteDirectory(new File(context.getCacheDir(), "server_temp"));
    }
  }

  /**
   * zip 文件一步导入
   */
  public static Result install(Context context, File zipFile, boolean overwrite, boolean setCurrent) {
    if (zipFile == null || !zipFile.exists()) {
      return new Result(false, null, context.getString(R.string.zip_not_found_format,
          zipFile == null ? "null" : zipFile.getAbsolutePath()), false, false);
    }
    Package packageInfo = extract(context, Uri.fromFile(zipFile));
    return installExtracted(context, packageInfo, overwrite, setCurrent);
  }

  private static boolean copyAgentFile(File agentDir, File destAgentDir, String fileName) {
    File src = new File(agentDir, fileName);
    if (!src.exists()) {
      return false;
    }
    return FileUtils.copyFile(src, new File(destAgentDir, fileName));
  }

  private static boolean copyArchitectureFiles(File jniLibsDir, String arch, File destDir) {
    File archDir = new File(jniLibsDir, arch);
    if (!archDir.exists() || !archDir.isDirectory()) {
      return false;
    }
    FileUtils.createDirectory(destDir);
    File serverFile = new File(archDir, "albatross_server");
    if (!serverFile.exists()) {
      return false;
    }
    File destServerFile = new File(destDir, "albatross_server");
    if (!FileUtils.copyFile(serverFile, destServerFile)) {
      return false;
    }
    FileUtils.setExecutable(destServerFile);
    File libFile = new File(archDir, ConfigManager.LIB_NAME);
    if (!libFile.exists()) {
      return false;
    }
    return FileUtils.copyFile(libFile, new File(destDir, ConfigManager.LIB_NAME));
  }
}
