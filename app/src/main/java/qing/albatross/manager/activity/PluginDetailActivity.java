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

import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_CLASS;
import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_FLAGS;
import static qing.albatross.manager.data.Const.ALBATROSS_PLUGIN_PARAMS;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.Objects;

import qing.albatross.manager.R;
import qing.albatross.manager.adapter.PluginDetailPagerAdapter;
import qing.albatross.manager.data.Plugin;
import qing.albatross.manager.data.PluginDatabaseHelper;
import qing.albatross.manager.data.PluginRuleDatabaseHelper;

public class PluginDetailActivity extends AppCompatActivity {
  public static final String EXTRA_PLUGIN_PACKAGE = "plugin_package";
  private static final String TAG = "PluginDetailActivity";

  private Plugin currentPlugin;
  private PluginDatabaseHelper pluginDb;
  private PluginRuleDatabaseHelper ruleDb;
  private ActivityResultLauncher<Intent> activityResultLauncher;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_plugin_detail);

    // 初始化数据库
    pluginDb = PluginDatabaseHelper.getInstance(this);
    ruleDb = PluginRuleDatabaseHelper.getInstance(this);

    currentPlugin = getIntent().getParcelableExtra(EXTRA_PLUGIN_PACKAGE);
    if (currentPlugin == null) {
      finish();
      return;
    }
    // 配置ActionBar
    if (getSupportActionBar() != null) {
      getSupportActionBar().setTitle(currentPlugin.getName());
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }
    // 初始化视图
    initViews();
    activityResultLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
          if (result.getResultCode() == Activity.RESULT_OK) {
            Intent data = result.getData();
            if (data != null) {
              String newParams = data.getStringExtra(ALBATROSS_PLUGIN_PARAMS);
              int flags = currentPlugin.getFlags();
              int newFlags = data.getIntExtra(ALBATROSS_PLUGIN_FLAGS, flags);
              String pluginClass = data.getStringExtra(ALBATROSS_PLUGIN_CLASS);
              if (newParams != null || newFlags != flags || pluginClass != null) {
                savePluginConfig(pluginClass, newParams, newFlags);
                onParamChange.onChange(currentPlugin);
                Toast.makeText(PluginDetailActivity.this, getString(R.string.plugin_config_updated), Toast.LENGTH_SHORT).show();
              }
            }
          } else if (result.getResultCode() == Activity.RESULT_CANCELED) {
            Log.d(TAG, getString(R.string.target_activity_cancelled));
          }
        }
    );
  }

  private void initViews() {
    TabLayout tabLayout = findViewById(R.id.tab_layout);
    ViewPager2 viewPager = findViewById(R.id.view_pager);

    // 设置ViewPager适配器
    PluginDetailPagerAdapter adapter = new PluginDetailPagerAdapter(this, currentPlugin);
    viewPager.setAdapter(adapter);
    viewPager.setOffscreenPageLimit(3);
    // 关联TabLayout和ViewPager
    new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
      switch (position) {
        case 0:
          tab.setText(getString(R.string.app_config));
          tab.setIcon(R.drawable.ic_settings);
          break;
        case 1:
          tab.setText(getString(R.string.app_target));
          tab.setIcon(R.drawable.ic_applications);
          break;
        case 2:
          tab.setText(getString(R.string.app_log_tab));
          tab.setIcon(R.drawable.ic_log);
          break;
      }
    }).attach();
  }

  public interface OnParamChange {
    void onChange(Plugin plugin);
  }

  OnParamChange onParamChange;

  // 启动插件应用进行配置
  public void launchPluginConfig(OnParamChange onParamChange) {
    String pluginPackageName = currentPlugin.getPackageName();
    try {
      Intent intent = new Intent("qing.albatross.PluginConfig");
      intent.setPackage(pluginPackageName);
      ResolveInfo resolveInfo = getPackageManager().resolveActivity(intent, 0);
      if (resolveInfo != null) {
        intent.setClassName(pluginPackageName, resolveInfo.activityInfo.name);
        intent.putExtra("plugin_params", currentPlugin.getParams());
        intent.putExtra("plugin_flags", currentPlugin.getFlags());
        this.onParamChange = onParamChange;
        activityResultLauncher.launch(intent); // 可以安全启动
      } else {
        Toast.makeText(this, getString(R.string.plugin_cannot_start), Toast.LENGTH_SHORT).show();
      }
      return;
    } catch (Exception e) {
      Log.d(TAG, "launch fail", e);
    }
    Toast.makeText(this, getString(R.string.plugin_cannot_start), Toast.LENGTH_SHORT).show();
  }

  // 保存插件配置参数
  public void savePluginConfig(String pluginClass, String params, int flags) {
    String param11 = currentPlugin.getParams();
    if (!Objects.equals(param11, params) || currentPlugin.getFlags() != flags) {
      currentPlugin.setParams(params);
      currentPlugin.setFlags(flags);
      pluginDb.updatePluginParams(currentPlugin, pluginClass, params, flags);
    }
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    pluginDb.close();
    ruleDb.close();
  }

  public Plugin getCurrentPlugin() {
    return currentPlugin;
  }

  // 更新插件启用状态
  public void updatePluginEnabled(Plugin plugin, boolean enabled) {
    plugin.setEnabled(enabled);
    pluginDb.updatePluginEnabled(plugin, enabled);
  }

}
