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
package qing.albatross.manager.activity;

import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_AUTHOR;
import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_CLASS;
import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_DESCRIPTION;
import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_KEY;
import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_NAME;
import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_SUPPORT_APPS;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.ArraySet;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import qing.albatross.manager.R;
import qing.albatross.manager.adapter.PluginListAdapter;
import qing.albatross.manager.data.Plugin;
import qing.albatross.manager.data.PluginDatabaseHelper;
import qing.albatross.manager.model.AppInfo;
import qing.albatross.manager.plugin.PluginDelegate;

public class PluginListActivity extends AppCompatActivity {


  private RecyclerView recyclerView;
  private PluginListAdapter adapter;
  private ProgressBar progressBar;
  private TextView tvEmptyState;
  private EditText etSearch;
  private ImageView ivClearSearch;
  private View emptyStateContainer;

  private final List<AppInfo> allApps = new ArrayList<>();
  private final List<AppInfo> filteredApps = new ArrayList<>();
  private PluginDatabaseHelper pluginDb;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Set<AppInfo> toAddPlugin = new ArraySet<>();

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_plugin_list);
    // 初始化视图
    initViews();
    // 初始化数据库
    pluginDb = PluginDatabaseHelper.getInstance(this);
    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null)
      actionBar.setDisplayHomeAsUpEnabled(true); // 如果需要返回按钮
    // 加载应用列表
    loadAppList();
    // 设置搜索功能
    setupSearch();
  }

  private void initViews() {
    ImageView ivBack = findViewById(R.id.iv_back);
    ivBack.setOnClickListener(v -> finish());

    // 初始化列表和状态视图
    recyclerView = findViewById(R.id.recycler_view);
    progressBar = findViewById(R.id.progress_bar);
    tvEmptyState = findViewById(R.id.tv_empty_state);
    emptyStateContainer = findViewById(R.id.empty_state_container);
    etSearch = findViewById(R.id.et_search);
    ivClearSearch = findViewById(R.id.iv_clear_search);

    // 配置RecyclerView
    recyclerView.setLayoutManager(new LinearLayoutManager(this));
    adapter = new PluginListAdapter(this, new ArrayList<>(), toAddPlugin);
    recyclerView.setAdapter(adapter);

    Button addPluginBtn = findViewById(R.id.btn_add_selected);
    addPluginBtn.setOnClickListener(v -> {
      addPlugin();
    });
  }

  private void setupSearch() {
    // 搜索文本变化监听
    etSearch.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        filterApps(s.toString());
      }

      @Override
      public void afterTextChanged(Editable s) {
      }
    });

    // 清除搜索按钮
    ivClearSearch.setOnClickListener(v -> {
      etSearch.setText("");
    });
  }

  private void filterApps(String query) {
    filteredApps.clear();
    if (query.isEmpty()) {
      filteredApps.addAll(allApps);
    } else {
      String lowerCaseQuery = query.toLowerCase(Locale.getDefault());
      for (AppInfo app : allApps) {
        // 匹配应用名或包名
        if (app.getAppName().toLowerCase(Locale.getDefault()).contains(lowerCaseQuery) ||
            app.getPackageName().toLowerCase(Locale.getDefault()).contains(lowerCaseQuery)) {
          filteredApps.add(app);
        }
      }
    }
    adapter.updateData(filteredApps);
    if (filteredApps.isEmpty()) {
      tvEmptyState.setText(getString(R.string.no_apps_found, query));
      emptyStateContainer.setVisibility(View.VISIBLE);
      recyclerView.setVisibility(View.GONE);
    } else {
      emptyStateContainer.setVisibility(View.GONE);
      recyclerView.setVisibility(View.VISIBLE);
    }
  }

  /**
   * 加载设备上的应用列表
   */
  private void loadAppList() {
    progressBar.setVisibility(View.VISIBLE);
    recyclerView.setVisibility(View.GONE);
    emptyStateContainer.setVisibility(View.GONE);

    executor.execute(() -> {
      // 获取所有已安装的应用
      List<PackageInfo> packages = getPackageManager().getInstalledPackages(PackageManager.GET_META_DATA);
      allApps.clear();
      for (PackageInfo pkg : packages) {
        // 过滤系统应用
        if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
          AppInfo appInfo = createAppInfo(pkg);
          if (appInfo != null)
            allApps.add(appInfo);
        }
      }
      // 在主线程更新UI
      handler.post(() -> {
        progressBar.setVisibility(View.GONE);
        if (allApps.isEmpty()) {
          tvEmptyState.setText(R.string.no_apps_available);
          emptyStateContainer.setVisibility(View.VISIBLE);
        } else {
          recyclerView.setVisibility(View.VISIBLE);
          // 初始显示所有应用
          filterApps(etSearch.getText().toString());
        }
      });
    });
  }

  /**
   * 创建应用信息对象
   */
  private AppInfo createAppInfo(PackageInfo pkg) {
    boolean isPlugin = false;
    ApplicationInfo applicationInfo = pkg.applicationInfo;
    Bundle metaData = applicationInfo.metaData;
    try {
      if (metaData != null) {
        isPlugin = metaData.getBoolean(ALBATROSS_PLUGIN_KEY, false);
      }
    } catch (Exception e) {
      // 忽略异常
    }
    if (!isPlugin) {
      return null;
    }
    AppInfo appInfo = new AppInfo();
    appInfo.setPackageName(pkg.packageName);
    appInfo.metaData = metaData;
    // 获取应用名称
    try {
      appInfo.setAppName(getPackageManager().getApplicationLabel(applicationInfo).toString());
    } catch (Exception e) {
      appInfo.setAppName(pkg.packageName);
    }
    appInfo.setAppIcon(applicationInfo.loadIcon(getPackageManager()));
    appInfo.sourceDex = applicationInfo.sourceDir;
    // 获取应用版本
    appInfo.setVersionName(pkg.versionName);
    appInfo.setPlugin(isPlugin);
    Plugin existingPlugin = pluginDb.getPluginByPackage(pkg.packageName);
    appInfo.setAdded(existingPlugin != null);
    return appInfo;
  }


  private void addPlugin() {
    if (toAddPlugin.isEmpty())
      return;
    executor.execute(() -> {
      int successCount = 0;
      try {
        for (AppInfo a : toAddPlugin) {
          Plugin plugin = new Plugin();
          plugin.setPackageName(a.getPackageName());
          // 从meta-data获取插件名称，默认为应用名称
          Bundle metaData = a.metaData;
          if (metaData != null) {
            String pluginName = metaData.getString(ALBATROSS_PLUGIN_NAME);
            plugin.setName(pluginName != null ? pluginName : a.getAppName());
            // 从meta-data获取其他信息
            String className = metaData.getString(ALBATROSS_PLUGIN_CLASS, "unknown");
            plugin.setClassName(className);
            plugin.setDescription(metaData.getString(ALBATROSS_PLUGIN_DESCRIPTION, getString(R.string.no_description)));
            plugin.setAuthor(metaData.getString(ALBATROSS_PLUGIN_AUTHOR, getString(R.string.unknown)));
            plugin.setSupportApps(metaData.getString(ALBATROSS_PLUGIN_SUPPORT_APPS, ""));
            plugin.setEnabled(true);
            long result = pluginDb.addPlugin(plugin);
            if (result != -1) {
              plugin.setId(result);
              successCount += 1;
              a.setAdded(true);
              PluginDelegate pluginDelegate = PluginDelegate.get();
              if (pluginDelegate != null) {
                pluginDelegate.addPlugin(plugin.getId(), a.sourceDex, plugin.getClassName(), plugin.getParams(), plugin.getFlags());
              }
            }
          }

        }
        if (successCount > 0) {
          toAddPlugin.clear();
          handler.post(() -> {
            Toast.makeText(this, getString(R.string.plugin_add_success), Toast.LENGTH_SHORT).show();
            // 更新列表状态
            adapter.notifyDataSetChanged();
          });
        }
      } catch (Exception e) {
        handler.post(() -> {
          Toast.makeText(this, getString(R.string.plugin_info_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        });
      }
    });
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    // 关闭线程池
    if (executor != null && !executor.isShutdown()) {
      executor.shutdown();
    }
  }
}
