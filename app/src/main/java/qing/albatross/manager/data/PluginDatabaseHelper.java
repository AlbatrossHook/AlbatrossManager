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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import qing.albatross.manager.plugin.PluginDelegate;

/**
 * 插件数据库帮助类，支持插件信息和配置参数存储
 */
public class PluginDatabaseHelper extends SQLiteOpenHelper {

  private static final String DATABASE_NAME = "plugins.db";
  private static final int DATABASE_VERSION = 3; // 版本升级
  private static PluginDatabaseHelper instance;

  // 插件表
  public static final String TABLE_PLUGINS = "plugins";
  public static final String COLUMN_ID = "_id";
  public static final String COLUMN_NAME = "name";
  public static final String COLUMN_PACKAGE = "package_name";
  public static final String COLUMN_CLASS = "class_name";
  public static final String COLUMN_DESCRIPTION = "description";
  public static final String COLUMN_AUTHOR = "author";
  public static final String COLUMN_APP_VERSION = "app_version";
  public static final String COLUMN_ENABLED = "is_enabled";
  // 新增：插件配置参数
  public static final String COLUMN_PARAM1 = "param1"; // 字符串参数
  public static final String COLUMN_PARAM2 = "param2"; // 数值参数
  public static final String COLUMN_SUPPORTED_APPS = "supported_apps";

  Context context;

  // 私有构造函数，确保单例
  private PluginDatabaseHelper(Context context) {
    super(context, DATABASE_NAME, null, DATABASE_VERSION);
    this.context = context;
  }

  // 获取单例实例
  public static synchronized PluginDatabaseHelper getInstance(Context context) {
    if (instance == null) {
      instance = new PluginDatabaseHelper(context.getApplicationContext());
    }
    return instance;
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    // 创建插件表（包含参数列）
    String createTable = "CREATE TABLE " + TABLE_PLUGINS + " (" +
        COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
        COLUMN_NAME + " TEXT NOT NULL, " +
        COLUMN_PACKAGE + " TEXT NOT NULL UNIQUE, " +
        COLUMN_CLASS + " TEXT, " +
        COLUMN_DESCRIPTION + " TEXT, " +
        COLUMN_AUTHOR + " TEXT, " +
        COLUMN_APP_VERSION + " TEXT, " +
        COLUMN_ENABLED + " INTEGER DEFAULT 1, " +
        COLUMN_PARAM1 + " TEXT DEFAULT '', " +  // 参数1默认空字符串
        COLUMN_PARAM2 + " REAL DEFAULT 0, " +
        // 新增列：支持的应用列表（默认空字符串）
        COLUMN_SUPPORTED_APPS + " TEXT DEFAULT '')";
    db.execSQL(createTable);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 3) {
      db.execSQL("ALTER TABLE " + TABLE_PLUGINS +
          " ADD COLUMN " + COLUMN_SUPPORTED_APPS + " TEXT DEFAULT ''");
    }
    if (oldVersion < 2) {
      // 升级到版本2：添加参数列
      db.execSQL("ALTER TABLE " + TABLE_PLUGINS + " RENAME COLUMN  injector TO " + COLUMN_CLASS);
    }
  }

  /**
   * 更新插件配置参数
   */
  public int updatePluginParams(Plugin plugin, String className, String param1, int param2) {
    String packageName = plugin.getPackageName();
    SQLiteDatabase db = this.getWritableDatabase();
    ContentValues values = new ContentValues();
    values.put(COLUMN_PARAM1, param1);
    values.put(COLUMN_PARAM2, param2);
    if (className != null) {
      values.put(COLUMN_CLASS, className);
      plugin.setClassName(className);
    }
    int rowsAffected = db.update(
        TABLE_PLUGINS,
        values,
        COLUMN_PACKAGE + " = ?",
        new String[]{packageName}
    );
    db.close();
    if(plugin.isEnabled() &&rowsAffected>0){
      PluginDelegate handler = PluginDelegate.get();
      if(handler!=null){
        handler.modifyPlugin(plugin.getId(),className,param1,param2);
      }
    }
    return rowsAffected;
  }

  /**
   * 获取插件配置参数
   */
  public Plugin getPluginWithParams(String packageName) {
    SQLiteDatabase db = this.getReadableDatabase();
    Plugin plugin = null;
    Cursor cursor = db.query(
        TABLE_PLUGINS,
        null,
        COLUMN_PACKAGE + " = ?",
        new String[]{packageName},
        null, null, null
    );
    if (cursor.moveToFirst()) {
      plugin = new Plugin();
      plugin.setId(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)));
      plugin.setName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME)));
      plugin.setPackageName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PACKAGE)));
      plugin.setClassName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CLASS)));
      plugin.setDescription(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DESCRIPTION)));
      plugin.setAuthor(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_AUTHOR)));
      plugin.setAppVersion(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_APP_VERSION)));
      plugin.setEnabled(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ENABLED)) == 1);
      // 读取参数
      plugin.setParams(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PARAM1)));
      plugin.setFlags(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_PARAM2)));
      plugin.setSupportApps(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SUPPORTED_APPS)));
    }
    cursor.close();
    db.close();
    return plugin;
  }

  /**
   * 添加新插件
   */
  public long addPlugin(Plugin plugin) {
    SQLiteDatabase db = this.getWritableDatabase();
    ContentValues values = new ContentValues();
    values.put(COLUMN_NAME, plugin.getName());
    values.put(COLUMN_PACKAGE, plugin.getPackageName());
    values.put(COLUMN_CLASS, plugin.getClassName());
    values.put(COLUMN_DESCRIPTION, plugin.getDescription());
    values.put(COLUMN_AUTHOR, plugin.getAuthor());
    values.put(COLUMN_APP_VERSION, plugin.getAppVersion());
    values.put(COLUMN_ENABLED, plugin.isEnabled() ? 1 : 0);
    values.put(COLUMN_PARAM1, plugin.getParams()); // 保存参数1
    values.put(COLUMN_PARAM2, plugin.getFlags()); // 保存参数2
    values.put(COLUMN_SUPPORTED_APPS, plugin.getSupportedApps());
    long id = db.insert(TABLE_PLUGINS, null, values);
    db.close();
    return id;
  }

  /**
   * 获取所有插件
   */
  public List<Plugin> getAllPlugins() {
    List<Plugin> plugins = new ArrayList<>();
    String selectQuery = "SELECT * FROM " + TABLE_PLUGINS;

    SQLiteDatabase db = this.getReadableDatabase();
    Cursor cursor = db.rawQuery(selectQuery, null);
    if (cursor.moveToFirst()) {
      do {
        Plugin plugin = new Plugin();
        plugin.setId(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)));
        plugin.setName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME)));
        plugin.setPackageName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PACKAGE)));
        plugin.setClassName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CLASS)));
        plugin.setDescription(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DESCRIPTION)));
        plugin.setAuthor(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_AUTHOR)));
        plugin.setAppVersion(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_APP_VERSION)));
        plugin.setEnabled(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ENABLED)) == 1);
        plugin.setParams(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PARAM1)));
        plugin.setFlags(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_PARAM2)));
        plugin.setSupportApps(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SUPPORTED_APPS)));
        plugins.add(plugin);
      } while (cursor.moveToNext());
    }
    cursor.close();
    db.close();
    return plugins;
  }

  /**
   * 获取所有启用（enabled 为 True）的插件
   *
   * @return 启用的插件列表
   */
  public List<Plugin> getEnabledPlugins() {
    List<Plugin> enabledPlugins = new ArrayList<>();
    // 查询条件：只选择 enabled 为 1（True）的记录
    String selectQuery = "SELECT * FROM " + TABLE_PLUGINS +
        " WHERE " + COLUMN_ENABLED + " = 1";

    SQLiteDatabase db = this.getReadableDatabase();
    Cursor cursor = db.rawQuery(selectQuery, null);

    if (cursor.moveToFirst()) {
      do {
        // 与 getAllPlugins() 相同的方式解析插件信息
        Plugin plugin = new Plugin();
        plugin.setId(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)));
        plugin.setName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME)));
        plugin.setPackageName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PACKAGE)));
        plugin.setClassName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CLASS)));
        plugin.setDescription(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DESCRIPTION)));
        plugin.setAuthor(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_AUTHOR)));
        plugin.setAppVersion(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_APP_VERSION)));
        // 此时 enabled 必然为 true，但仍保持与原逻辑一致的赋值方式
        plugin.setEnabled(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ENABLED)) == 1);
        plugin.setParams(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PARAM1)));
        plugin.setFlags(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_PARAM2)));

        enabledPlugins.add(plugin);
      } while (cursor.moveToNext());
    }

    // 关闭资源，避免内存泄漏
    cursor.close();
    db.close();

    return enabledPlugins;
  }

  /**
   * 根据包名获取插件（兼容旧版本，不包含参数）
   */
  public Plugin getPluginByPackage(String packageName) {
    return getPluginWithParams(packageName); // 直接使用包含参数的方法
  }

  /**
   * 更新插件启用状态
   */
  public int updatePluginState(Plugin plugin, boolean isEnabled) {
    SQLiteDatabase db = this.getWritableDatabase();
    ContentValues values = new ContentValues();
    String packageName = plugin.getPackageName();
    values.put(COLUMN_ENABLED, isEnabled ? 1 : 0);
    int rowsAffected = db.update(
        TABLE_PLUGINS,
        values,
        COLUMN_PACKAGE + " = ?",
        new String[]{packageName}
    );
    db.close();
    if (rowsAffected > 0) {
      PluginDelegate handler = PluginDelegate.get();
      if (handler != null) {
        PluginRuleDatabaseHelper ruleDb = PluginRuleDatabaseHelper.getInstance(context);
        List<String> packages = ruleDb.getTargetPackages(packageName);
        {
          try {
            int pluginId = plugin.getId();
            if (isEnabled) {
              String pluginDex;
              try {
                pluginDex = context.getPackageManager().getPackageInfo(packageName, 0).applicationInfo.sourceDir;
              } catch (Exception e) {
                deletePlugin(plugin);
                return rowsAffected;
              }
              handler.addPlugin(pluginId, pluginDex, plugin.getClassName(), plugin.getParams(), plugin.getFlags());
              for (String targetPackage : packages) {
                handler.addPluginRule(pluginId, targetPackage);
              }
            } else {
              handler.deletePlugin(pluginId);
            }
          } catch (Throwable ignore) {
          }
        }
      }
    }
    return rowsAffected;
  }

  /**
   * 更新插件启用状态（别名方法）
   */
  public int updatePluginEnabled(Plugin plugin, boolean enabled) {
    return updatePluginState(plugin, enabled);
  }

  /**
   * 删除插件
   */
  public int deletePlugin(Plugin plugin) {
    String packageName = plugin.getPackageName();
    SQLiteDatabase db = this.getWritableDatabase();
    int rowsDeleted = db.delete(
        TABLE_PLUGINS,
        COLUMN_PACKAGE + " = ?",
        new String[]{packageName}
    );
    db.close();
    PluginRuleDatabaseHelper ruleDb = PluginRuleDatabaseHelper.getInstance(context);
    ruleDb.deleteAllRulesForPlugin(packageName);
    PluginDelegate handler = PluginDelegate.get();
    if (handler != null) {
      try {
        handler.deletePlugin(plugin.getId());
      } catch (Throwable ignore) {
      }
    }
    return rowsDeleted;
  }

  public Map<Plugin, List<String>> getPluginEffectiveApps(boolean enabled) {
    Map<Plugin, List<String>> resultMap = new HashMap<>();
    // 获取所有插件
    List<Plugin> allPlugins;
    if (enabled)
      allPlugins = getEnabledPlugins();
    else
      allPlugins = getAllPlugins();
    if (allPlugins.isEmpty()) {
      return resultMap;
    }
    PluginRuleDatabaseHelper ruleDb = PluginRuleDatabaseHelper.getInstance(context);
    // 为每个插件查询生效的应用列表
    for (Plugin plugin : allPlugins) {
      List<String> effectiveApps = ruleDb.getTargetPackages(plugin.getPackageName());
      resultMap.put(plugin, effectiveApps);
    }
    return resultMap;
  }
}
    