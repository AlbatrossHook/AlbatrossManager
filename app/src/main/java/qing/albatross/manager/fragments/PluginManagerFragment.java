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
package qing.albatross.manager.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import qing.albatross.manager.R;
import qing.albatross.manager.activity.PluginDetailActivity;
import qing.albatross.manager.activity.PluginListActivity;
import qing.albatross.manager.adapter.PluginAdapter;
import qing.albatross.manager.data.Plugin;
import qing.albatross.manager.data.PluginDatabaseHelper;

public class PluginManagerFragment extends Fragment {

  private RecyclerView recyclerView;
  private PluginAdapter pluginAdapter;
  private LinearLayout tvEmptyState;
//  private Button btnAddPlugin;

  private PluginDatabaseHelper dbHelper;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_plugin_manager, container, false);
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setHasOptionsMenu(true);
  }

  private void setupActionBar() {
    AppCompatActivity activity = (AppCompatActivity) requireActivity();
    ActionBar actionBar = activity.getSupportActionBar();
    if (actionBar != null) {
      actionBar.setTitle(R.string.plugin_manager); // 使用字符串资源
      actionBar.setDisplayHomeAsUpEnabled(false); // 如果需要返回按钮
    }
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    // 初始化视图
    recyclerView = view.findViewById(R.id.recycler_view_plugins);
    tvEmptyState = view.findViewById(R.id.tv_empty_state);
//    btnAddPlugin = view.findViewById(R.id.btn_add_plugin);
    setupActionBar();
    // 初始化数据库
    dbHelper = PluginDatabaseHelper.getInstance(requireContext());
    // 初始化列表
    initRecyclerView();
    // 加载插件数据
    loadPlugins();

  }

  private void initRecyclerView() {
    recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
    pluginAdapter = new PluginAdapter(plugin -> {
      // 切换插件启用状态
      boolean newState = !plugin.isEnabled();
      dbHelper.updatePluginState(plugin, newState);
      plugin.setEnabled(newState);
      pluginAdapter.notifyDataSetChanged();
    }, plugin -> {
      // 删除插件
      dbHelper.deletePlugin(plugin);
      loadPlugins();
    }, plugin -> {
      Intent intent = new Intent(requireContext(), PluginDetailActivity.class);
      // 传递插件包名作为参数
      intent.putExtra(PluginDetailActivity.EXTRA_PLUGIN_PACKAGE, plugin);
      startActivity(intent);
    });
    recyclerView.setAdapter(pluginAdapter);
  }

  private void loadPlugins() {
    List<Plugin> plugins = dbHelper.getAllPlugins();
    if (plugins.isEmpty()) {
      recyclerView.setVisibility(View.GONE);
      tvEmptyState.setVisibility(View.VISIBLE);
    } else {
      recyclerView.setVisibility(View.VISIBLE);
      tvEmptyState.setVisibility(View.GONE);
      pluginAdapter.setPlugins(plugins);
    }
  }
  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == R.id.action_add_plugin) {
      Intent intent = new Intent(requireContext(), PluginListActivity.class);
      startActivity(intent);
      return true;
    } else if (item.getItemId() == android.R.id.home) {
      // 处理返回按钮
      requireActivity().onBackPressed();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
    inflater.inflate(R.menu.menu_plugin_manager, menu);
    super.onCreateOptionsMenu(menu, inflater);
  }


  @Override
  public void onResume() {
    super.onResume();
    // 从添加插件页面返回时刷新列表
    loadPlugins();
  }
}
