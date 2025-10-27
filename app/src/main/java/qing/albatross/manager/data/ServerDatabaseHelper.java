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
package qing.albatross.manager.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import qing.albatross.manager.model.ServerInfo;

public class ServerDatabaseHelper extends SQLiteOpenHelper {

  private static final String DATABASE_NAME = "server_versions.db";
  private static final int DATABASE_VERSION = 2; // 升级数据库版本
  private static ServerDatabaseHelper instance;

  // 读写锁，用于控制多线程访问
  private final ReadWriteLock lock = new ReentrantReadWriteLock();

  // 服务版本表 - 新增描述字段
  private static final String TABLE_SERVER_VERSIONS = "server_versions";
  private static final String COLUMN_VERSION = "version";
  private static final String COLUMN_DESCRIPTION = "description"; // 新增：版本描述
  private static final String COLUMN_PRIMARY_ARCH = "primary_arch";
  private static final String COLUMN_SUPPORT_32BIT = "support_32bit";
  private static final String COLUMN_SERVER_PATH = "server_path";
  private static final String COLUMN_LIB_PATH = "lib_path";
  private static final String COLUMN_LIB32_PATH = "lib32_path";
  private static final String COLUMN_AGENT_PATH = "agent_path";
  private static final String COLUMN_IMPORT_TIME = "import_time";

  // 配置表
  private static final String TABLE_CONFIG = "server_config";
  private static final String COLUMN_CONFIG_KEY = "config_key";
  private static final String COLUMN_CONFIG_VALUE = "config_value";
  private static final String KEY_CURRENT_VERSION = "current_version";
  private static final String KEY_ROOT_PATH = "root_path";

  private ServerDatabaseHelper(Context context) {
    super(context, DATABASE_NAME, null, DATABASE_VERSION);
  }

  public static synchronized ServerDatabaseHelper getInstance(Context context) {
    if (instance == null) {
      instance = new ServerDatabaseHelper(context.getApplicationContext());
    }
    return instance;
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    // 创建服务版本表（包含描述字段）
    String createVersionsTable = "CREATE TABLE " + TABLE_SERVER_VERSIONS + " (" +
        COLUMN_VERSION + " TEXT PRIMARY KEY, " +
        COLUMN_DESCRIPTION + " TEXT, " + // 新增描述字段
        COLUMN_PRIMARY_ARCH + " TEXT NOT NULL, " +
        COLUMN_SUPPORT_32BIT + " INTEGER DEFAULT 0, " +
        COLUMN_SERVER_PATH + " TEXT NOT NULL, " +
        COLUMN_LIB_PATH + " TEXT NOT NULL, " +
        COLUMN_LIB32_PATH + " TEXT, " +
        COLUMN_AGENT_PATH + " TEXT NOT NULL, " +
        COLUMN_IMPORT_TIME + " INTEGER DEFAULT 0)";
    db.execSQL(createVersionsTable);

    // 创建配置表
    String createConfigTable = "CREATE TABLE " + TABLE_CONFIG + " (" +
        COLUMN_CONFIG_KEY + " TEXT PRIMARY KEY, " +
        COLUMN_CONFIG_VALUE + " TEXT)";
    db.execSQL(createConfigTable);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    // 数据库升级：为旧版本添加描述字段
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE " + TABLE_SERVER_VERSIONS +
          " ADD COLUMN " + COLUMN_DESCRIPTION + " TEXT");
    }
  }

  /**
   * 添加服务版本（包含描述信息）
   */
  public void addServerVersion(ServerInfo serverInfo, boolean setAsCurrent) {
    lock.writeLock().lock();
    try {
      SQLiteDatabase db = this.getWritableDatabase();

      ContentValues values = new ContentValues();
      values.put(COLUMN_VERSION, serverInfo.getVersion());
      values.put(COLUMN_DESCRIPTION, serverInfo.getDescription()); // 保存描述信息
      values.put(COLUMN_PRIMARY_ARCH, serverInfo.getPrimaryArchitecture());
      values.put(COLUMN_SUPPORT_32BIT, serverInfo.isSupport32Bit() ? 1 : 0);
      values.put(COLUMN_SERVER_PATH, serverInfo.getServerPath());
      values.put(COLUMN_LIB_PATH, serverInfo.getLibPath());
      values.put(COLUMN_LIB32_PATH, serverInfo.getLib32Path());
      values.put(COLUMN_AGENT_PATH, serverInfo.getAgentPath());
      values.put(COLUMN_IMPORT_TIME, System.currentTimeMillis());

      db.insertWithOnConflict(TABLE_SERVER_VERSIONS, null, values,
          SQLiteDatabase.CONFLICT_REPLACE);

      // 如果需要设为当前版本
      if (setAsCurrent) {
        setCurrentServerVersionInternal(serverInfo.getVersion(), db);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * 获取所有服务版本（包含描述信息）
   */
  public List<ServerInfo> getAllServerVersions() {
    lock.readLock().lock();
    try {
      List<ServerInfo> versions = new ArrayList<>();
      String selectQuery = "SELECT * FROM " + TABLE_SERVER_VERSIONS +
          " ORDER BY " + COLUMN_IMPORT_TIME + " DESC";

      SQLiteDatabase db = this.getReadableDatabase();
      Cursor cursor = db.rawQuery(selectQuery, null);

      if (cursor.moveToFirst()) {
        do {
          ServerInfo serverInfo = new ServerInfo();
          serverInfo.setVersion(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_VERSION)));
          serverInfo.setDescription(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DESCRIPTION))); // 读取描述
          serverInfo.setPrimaryArchitecture(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PRIMARY_ARCH)));
          serverInfo.setSupport32Bit(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SUPPORT_32BIT)) == 1);
          serverInfo.setServerPath(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SERVER_PATH)));
          serverInfo.setLibPath(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LIB_PATH)));
          serverInfo.setLib32Path(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LIB32_PATH)));
          serverInfo.setAgentPath(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_AGENT_PATH)));
          serverInfo.setImportTime(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_IMPORT_TIME)));

          versions.add(serverInfo);
        } while (cursor.moveToNext());
      }

      cursor.close();
      return versions;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * 获取当前使用的服务版本信息（包含描述）
   */
  public ServerInfo getCurrentServerInfo() {
    lock.readLock().lock();
    try {
      String currentVersion = getCurrentServerVersionInternal();
      if (currentVersion == null) return null;

      SQLiteDatabase db = this.getReadableDatabase();
      Cursor cursor = db.query(
          TABLE_SERVER_VERSIONS,
          null,
          COLUMN_VERSION + " = ?",
          new String[]{currentVersion},
          null, null, null
      );

      ServerInfo serverInfo = null;
      if (cursor.moveToFirst()) {
        serverInfo = new ServerInfo();
        serverInfo.setVersion(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_VERSION)));
        serverInfo.setDescription(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DESCRIPTION))); // 读取描述
        serverInfo.setPrimaryArchitecture(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PRIMARY_ARCH)));
        serverInfo.setSupport32Bit(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SUPPORT_32BIT)) == 1);
        serverInfo.setServerPath(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SERVER_PATH)));
        serverInfo.setLibPath(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LIB_PATH)));
        serverInfo.setLib32Path(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LIB32_PATH)));
        serverInfo.setAgentPath(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_AGENT_PATH)));
        serverInfo.setImportTime(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_IMPORT_TIME)));
      }

      cursor.close();
      return serverInfo;
    } finally {
      lock.readLock().unlock();
    }
  }

  // 其他方法保持不变...
  public void setCurrentServerVersion(String version) {
    lock.writeLock().lock();
    try {
      SQLiteDatabase db = this.getWritableDatabase();
      setCurrentServerVersionInternal(version, db);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * 内部方法：设置当前服务器版本（不获取锁，供内部调用）
   */
  private void setCurrentServerVersionInternal(String version, SQLiteDatabase db) {
    ContentValues values = new ContentValues();
    values.put(COLUMN_CONFIG_KEY, KEY_CURRENT_VERSION);
    values.put(COLUMN_CONFIG_VALUE, version);

    db.insertWithOnConflict(TABLE_CONFIG, null, values,
        SQLiteDatabase.CONFLICT_REPLACE);
    ConfigManager.getInstance(null).saveCoreState(false);
  }

  public String getCurrentServerVersion() {
    lock.readLock().lock();
    try {
      return getCurrentServerVersionInternal();
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * 内部方法：获取当前服务器版本（不获取锁，供内部调用）
   */
  private String getCurrentServerVersionInternal() {
    SQLiteDatabase db = this.getReadableDatabase();
    Cursor cursor = db.query(
        TABLE_CONFIG,
        new String[]{COLUMN_CONFIG_VALUE},
        COLUMN_CONFIG_KEY + " = ?",
        new String[]{KEY_CURRENT_VERSION},
        null, null, null
    );

    String version = null;
    if (cursor.moveToFirst()) {
      version = cursor.getString(0);
    }

    cursor.close();
    return version;
  }

  public boolean checkVersionExists(String version) {
    lock.readLock().lock();
    try {
      SQLiteDatabase db = this.getReadableDatabase();
      Cursor cursor = db.query(
          TABLE_SERVER_VERSIONS,
          new String[]{COLUMN_VERSION},
          COLUMN_VERSION + " = ?",
          new String[]{version},
          null, null, null
      );
      boolean exists = cursor.getCount() > 0;
      cursor.close();
      return exists;
    } finally {
      lock.readLock().unlock();
    }
  }

  public void deleteServerVersion(String version) {
    lock.writeLock().lock();
    try {
      SQLiteDatabase db = this.getWritableDatabase();
      db.delete(TABLE_SERVER_VERSIONS, COLUMN_VERSION + " = ?", new String[]{version});
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void saveRootPath(String path) {
    lock.writeLock().lock();
    try {
      if (!path.endsWith("/"))
        path += "/";
      SQLiteDatabase db = this.getWritableDatabase();
      ContentValues values = new ContentValues();
      values.put(COLUMN_CONFIG_KEY, KEY_ROOT_PATH);
      values.put(COLUMN_CONFIG_VALUE, path);
      db.insertWithOnConflict(TABLE_CONFIG, null, values,
          SQLiteDatabase.CONFLICT_REPLACE);
    } finally {
      lock.writeLock().unlock();
    }
  }

  public String getRootPath() {
    lock.readLock().lock();
    try {
      SQLiteDatabase db = this.getReadableDatabase();
      Cursor cursor = db.query(
          TABLE_CONFIG,
          new String[]{COLUMN_CONFIG_VALUE},
          COLUMN_CONFIG_KEY + " = ?",
          new String[]{KEY_ROOT_PATH},
          null, null, null
      );
      String path = null;
      if (cursor.moveToFirst()) {
        path = cursor.getString(0);
      } else {
        path = "/data/local/tmp/albatross/";
      }
      cursor.close();
      return path;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * 关闭数据库连接（谨慎使用，通常不需要手动调用）
   */
  public synchronized void closeDatabase() {
    if (instance != null) {
      try {
        SQLiteDatabase db = getWritableDatabase();
        if (db.isOpen()) {
          db.close();
        }
      } catch (Exception e) {
        // 忽略关闭时的异常
      }
    }
  }

  /**
   * 确保数据库连接可用
   */
  public synchronized void ensureDatabaseOpen() {
    try {
      SQLiteDatabase db = getReadableDatabase();
      if (!db.isOpen()) {
        // 如果数据库未打开，重新获取连接
        db = getReadableDatabase();
      }
    } catch (Exception e) {
      // 如果出现异常，尝试重新创建连接
      try {
        SQLiteDatabase db = getWritableDatabase();
      } catch (Exception ex) {
        // 忽略异常
      }
    }
  }
}
