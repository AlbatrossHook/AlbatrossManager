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

import qing.albatross.manager.plugin.PluginDelegate;

/**
 * 插件生效应用规则数据库
 * 存储插件在哪些应用上生效的配置
 */
public class PluginRuleDatabaseHelper extends SQLiteOpenHelper {
  private static final String DATABASE_NAME = "plugin_rules.db";
  private static final int DATABASE_VERSION = 1;
  private static PluginRuleDatabaseHelper instance;

  // 规则表
  public static final String TABLE_RULES = "plugin_rules";
  public static final String COLUMN_ID = "_id";
  public static final String COLUMN_PLUGIN_PACKAGE = "plugin_package";
  public static final String COLUMN_TARGET_PACKAGE = "target_package";
  public Context context;

  private PluginRuleDatabaseHelper(Context context) {
    super(context, DATABASE_NAME, null, DATABASE_VERSION);
    this.context = context;
  }

  public static synchronized PluginRuleDatabaseHelper getInstance(Context context) {
    if (instance == null) {
      instance = new PluginRuleDatabaseHelper(context.getApplicationContext());
    }
    return instance;
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    // 创建规则表
    String createTable = "CREATE TABLE " + TABLE_RULES + " (" +
        COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
        COLUMN_PLUGIN_PACKAGE + " TEXT NOT NULL, " +
        COLUMN_TARGET_PACKAGE + " TEXT NOT NULL, " +
        "UNIQUE(" + COLUMN_PLUGIN_PACKAGE + ", " + COLUMN_TARGET_PACKAGE + ") ON CONFLICT REPLACE)";
    db.execSQL(createTable);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    db.execSQL("DROP TABLE IF EXISTS " + TABLE_RULES);
    onCreate(db);
  }

  /**
   * 添加规则：插件对目标应用生效
   */
  public long addRule(Plugin plugin, String targetPackage) {
    SQLiteDatabase db = this.getWritableDatabase();
    long id;
    String pluginPackageName = plugin.getPackageName();
    try {
      ContentValues values = new ContentValues();
      values.put(COLUMN_PLUGIN_PACKAGE, pluginPackageName);
      values.put(COLUMN_TARGET_PACKAGE, targetPackage);
      id = db.insert(TABLE_RULES, null, values);
    } catch (Exception e) {
      id = 0;
    }
    db.close();
    if (plugin.isEnabled() && id > 0) {
      PluginDelegate handler = PluginDelegate.get();
      if (handler != null) {
        handler.addPluginRule(plugin.getId(), targetPackage);
      }
    }
    return id;
  }

  /**
   * 移除规则：插件对目标应用不生效
   */
  public int removeRule(Plugin plugin, String targetPackage) {
    String pluginPackage = plugin.getPackageName();
    SQLiteDatabase db = this.getWritableDatabase();
    int rowsDeleted = db.delete(
        TABLE_RULES,
        COLUMN_PLUGIN_PACKAGE + " = ? AND " + COLUMN_TARGET_PACKAGE + " = ?",
        new String[]{pluginPackage, targetPackage}
    );
    db.close();
    if (plugin.isEnabled() && rowsDeleted > 0) {
      PluginDelegate handler = PluginDelegate.get();
      if (handler != null) {
        handler.deletePluginRule(plugin.getId(), targetPackage);
      }
    }
    return rowsDeleted;
  }

  /**
   * 获取插件生效的所有应用包名
   */
  public List<String> getTargetPackages(String pluginPackage) {
    List<String> packages = new ArrayList<>();
    SQLiteDatabase db = this.getReadableDatabase();

    Cursor cursor = db.query(
        TABLE_RULES,
        new String[]{COLUMN_TARGET_PACKAGE},
        COLUMN_PLUGIN_PACKAGE + " = ?",
        new String[]{pluginPackage},
        null, null, null
    );

    if (cursor.moveToFirst()) {
      do {
        String targetPackage = cursor.getString(
            cursor.getColumnIndexOrThrow(COLUMN_TARGET_PACKAGE)
        );
        packages.add(targetPackage);
      } while (cursor.moveToNext());
    }

    cursor.close();
    db.close();
    return packages;
  }

  /**
   * 删除插件的所有规则
   */
  public int deleteAllRulesForPlugin(String pluginPackage) {
    SQLiteDatabase db = this.getWritableDatabase();
    int rowsDeleted = db.delete(
        TABLE_RULES,
        COLUMN_PLUGIN_PACKAGE + " = ?",
        new String[]{pluginPackage}
    );
    db.close();
    return rowsDeleted;
  }
}
