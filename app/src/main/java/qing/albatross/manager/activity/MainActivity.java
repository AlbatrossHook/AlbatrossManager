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

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import qing.albatross.manager.R;
import qing.albatross.manager.fragments.TabPagerAdapter;

public class MainActivity extends AppCompatActivity {

  private TextView tvActionBarTitle;
  private TextView tvAppCount;
  private ImageButton btnRefresh;
  private FloatingActionButton fabAddPlugin;
  private ViewPager2 viewPager;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // 设置状态栏样式
    setupStatusBar();

    setContentView(R.layout.activity_main);

    // 设置自定义ActionBar
    setupCustomActionBar();

    TabLayout tabLayout = findViewById(R.id.tabLayout);
    viewPager = findViewById(R.id.viewPager);

    // 初始化适配器
    TabPagerAdapter adapter = new TabPagerAdapter(this);
    viewPager.setAdapter(adapter);
    
    // 设置保留所有Fragment实例，避免回收
    viewPager.setOffscreenPageLimit(4);

    // 监听页面切换，更新ActionBar
    viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
      @Override
      public void onPageSelected(int position) {
        super.onPageSelected(position);
        updateActionBar(position);
      }
    });

    // 关联TabLayout和ViewPager2
    new TabLayoutMediator(tabLayout, viewPager, new TabLayoutMediator.TabConfigurationStrategy() {
      @Override
      public void onConfigureTab(@NonNull TabLayout.Tab tab, int position) {
        switch (position) {
          case 0:
            tab.setText(getString(R.string.tab_server));
            tab.setIcon(R.drawable.ic_server);
            break;
          case 1:
            tab.setText(getString(R.string.tab_plugins));
            tab.setIcon(R.drawable.ic_plugins);
            break;
          case 2:
            tab.setText(getString(R.string.app_list_title));
            tab.setIcon(R.drawable.ic_applications);
            break;
          case 3:
            tab.setText(getString(R.string.tab_settings));
            tab.setIcon(R.drawable.ic_settings);
            break;
        }
      }
    }).attach();

    // 初始化ActionBar状态
    updateActionBar(0);
  }

  private void setupStatusBar() {
    // 设置状态栏为透明
    getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);

    // 根据当前主题设置状态栏文字颜色
    int nightModeFlags = getResources().getConfiguration().uiMode &
        android.content.res.Configuration.UI_MODE_NIGHT_MASK;

    if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
      // 暗色模式：白色文字
      getWindow().getDecorView().setSystemUiVisibility(
          View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
              View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
      );
    } else {
      // 浅色模式：黑色文字
      getWindow().getDecorView().setSystemUiVisibility(
          View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
              View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
              View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
      );
    }
  }

  private void setupCustomActionBar() {
    // 获取ActionBar容器
    View actionBarContainer = findViewById(R.id.action_bar_container);
    if (actionBarContainer != null) {
      // 加载自定义ActionBar布局
      LayoutInflater.from(this).inflate(R.layout.custom_action_bar, (LinearLayout) actionBarContainer, true);

      // 初始化ActionBar组件
      tvActionBarTitle = findViewById(R.id.tv_action_bar_title);
      tvAppCount = findViewById(R.id.tv_app_count);
      LinearLayout layoutActionButtons = findViewById(R.id.layout_action_buttons);
      btnRefresh = findViewById(R.id.btn_refresh);
      fabAddPlugin = findViewById(R.id.fab_add_plugin);

      // 设置刷新按钮点击事件
      if (btnRefresh != null) {
        btnRefresh.setOnClickListener(v -> {
          // 刷新服务状态
          refreshServerStatus();
        });
      }
      fabAddPlugin.setOnClickListener(v -> {
        Intent intent = new Intent(this, PluginListActivity.class);
        startActivity(intent);
      });
    }
  }

  private void updateActionBar(int position) {
    if (tvActionBarTitle == null) return;

    // 隐藏所有操作按钮和额外元素
    if (btnRefresh != null) btnRefresh.setVisibility(View.GONE);
    if (fabAddPlugin != null) fabAddPlugin.setVisibility(View.GONE);
    if (tvAppCount != null) tvAppCount.setVisibility(View.GONE);

    switch (position) {
      case 0: // 服务状态
        tvActionBarTitle.setText(getString(R.string.tab_server));
        if (btnRefresh != null) {
          btnRefresh.setVisibility(View.VISIBLE);
        }
        break;
      case 1: // 插件管理
        tvActionBarTitle.setText(getString(R.string.tab_manager));
        if (fabAddPlugin != null) {
          fabAddPlugin.setVisibility(View.VISIBLE);
        }
        break;
      case 2: // 应用列表
        tvActionBarTitle.setText(getString(R.string.app_list_title));
        if (tvAppCount != null) {
          tvAppCount.setVisibility(View.VISIBLE);
        }
        break;
      case 3: // 设置
        tvActionBarTitle.setText(getString(R.string.tab_settings));
        break;
    }
  }

  private OnRefreshListener refreshListener;

  public interface OnRefreshListener {
    void onRefresh();
  }

  public void setOnRefreshListener(OnRefreshListener listener) {
    this.refreshListener = listener;
  }

  private void refreshServerStatus() {
    if (refreshListener != null) {
      refreshListener.onRefresh();
    }
  }

  /**
   * 更新应用数量显示
   *
   * @param count 应用数量
   */
  public void updateAppCount(int count) {
    if (tvAppCount != null) {
      tvAppCount.setText(count + " 个应用");
    }
  }

  /**
   * 获取当前页面位置
   *
   * @return 当前页面位置
   */
  public int getCurrentPagePosition() {
    return viewPager != null ? viewPager.getCurrentItem() : 0;
  }
}