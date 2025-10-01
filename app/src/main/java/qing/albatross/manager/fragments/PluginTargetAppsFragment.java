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

import static qing.albatross.manager.data.Const.PKG_SYSTEM_SERVER;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import qing.albatross.manager.R;
import qing.albatross.manager.activity.AppDetailActivity;
import qing.albatross.manager.adapter.AppSelectAdapter;
import qing.albatross.manager.data.Plugin;
import qing.albatross.manager.data.PluginRuleDatabaseHelper;
import qing.albatross.manager.model.AppInfo;
import qing.albatross.manager.plugin.PluginDelegate;
import qing.albatross.manager.utils.AppUtils;
import qing.albatross.manager.utils.ServerManager;

public class PluginTargetAppsFragment extends Fragment {
  private static final String ARG_PLUGIN_PACKAGE = "plugin_package";
  private String pluginPackage;

  private ProgressBar progressBar;
  private TextView tvEmpty;
  private AppSelectAdapter adapter;
  private PluginRuleDatabaseHelper ruleDb;
  private List<AppInfo> allApps;

  private boolean showSystemApps = false;
  private boolean filterPlugin = true;
  private final List<AppInfo> filteredApps = new ArrayList<>();
  private EditText etSearch;
  RecyclerView recyclerView;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private ImageView ivClearSearch;

  // 工具栏按钮
  private Button btnMenu;
  private PopupWindow popupMenu;

  private Plugin plugin;

  public static PluginTargetAppsFragment newInstance(Plugin plugin) {
    PluginTargetAppsFragment fragment = new PluginTargetAppsFragment();
    Bundle args = new Bundle();
    args.putParcelable(ARG_PLUGIN_PACKAGE, plugin);
    fragment.setArguments(args);
    return fragment;
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (getArguments() != null) {
      plugin = getArguments().getParcelable(ARG_PLUGIN_PACKAGE);
      pluginPackage = plugin.getPackageName();
    }
    ruleDb = PluginRuleDatabaseHelper.getInstance(getContext());
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
                           Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_plugin_target_apps, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    recyclerView = view.findViewById(R.id.recycler_view);
    progressBar = view.findViewById(R.id.progress_bar);
    tvEmpty = view.findViewById(R.id.tv_empty);
    etSearch = view.findViewById(R.id.et_search);
    ivClearSearch = view.findViewById(R.id.iv_clear_search);

    // 初始化工具栏按钮
    btnMenu = view.findViewById(R.id.btn_menu);

    recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
    boolean isRunning = ServerManager.getInstance(getContext()).checkServerRunning();
    adapter = new AppSelectAdapter(appInfo -> {
      // 切换应用选中状态
      boolean newState = !appInfo.isSelected();
      appInfo.setSelected(newState);
      // 更新数据库
      if (newState) {
        ruleDb.addRule(plugin, appInfo.getPackageName());
      } else {
        ruleDb.removeRule(plugin, appInfo.getPackageName());
      }
    }, new AppSelectAdapter.AppActionListener() {
      @Override
      public void inject(AppInfo appInfo) {
        // 注入操作
        if (isRunning) {
          try {
            ApplicationInfo targetInfo = getContext().getPackageManager().getApplicationInfo(pluginPackage, 0);
            String res = PluginDelegate.injectPlugin(appInfo.getPackageName(), targetInfo.sourceDir, plugin.getClassName(), plugin.getParams(), plugin.getFlags());
            if (res == null) {
              Toast.makeText(getContext(), getString(R.string.injection_successful), Toast.LENGTH_SHORT).show();
            } else {
              Toast.makeText(getContext(), res, Toast.LENGTH_LONG).show();
            }
          } catch (PackageManager.NameNotFoundException e) {
            Toast.makeText(getContext(), getString(R.string.get_plugin_info_failed), Toast.LENGTH_LONG).show();
          }
        } else {
          Toast.makeText(getContext(), getString(R.string.server_not_running), Toast.LENGTH_SHORT).show();
        }
      }

      @Override
      public void disablePlugin(AppInfo appInfo) {
        PluginDelegate delegate = PluginDelegate.get();
        if (delegate != null) {
          delegate.deletePluginRule(plugin.getId(), appInfo.getPackageName());
        } else
          Toast.makeText(getContext(), getString(R.string.server_not_running), Toast.LENGTH_SHORT).show();
      }

      @Override
      public void closeApp(AppInfo appInfo) {
        PluginDelegate delegate = PluginDelegate.get();
        if (delegate != null) {
          delegate.forceStopApp(appInfo.getPackageName());
        } else
          Toast.makeText(getContext(), getString(R.string.server_not_running), Toast.LENGTH_SHORT).show();
      }

      @Override
      public void openApp(AppInfo appInfo) {
        // 打开应用
        try {
          Intent intent = getContext().getPackageManager().getLaunchIntentForPackage(appInfo.getPackageName());
          if (intent != null) {
            startActivity(intent);
          } else {
            Toast.makeText(getContext(), getString(R.string.cannot_open_app), Toast.LENGTH_SHORT).show();
          }
        } catch (Exception e) {
          Toast.makeText(getContext(), getString(R.string.open_app_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
      }
    }, appInfo -> {
      // 在列表项点击事件中添加
      Intent intent = new Intent(requireContext(), AppDetailActivity.class);
      intent.putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME, appInfo.getPackageName());
      startActivity(intent);
    });
    recyclerView.setAdapter(adapter);
    loadAppList();
    setupSearchListener();
    setupToolbarListeners();
  }

  private void loadAppList() {
    progressBar.setVisibility(View.VISIBLE);
    recyclerView.setVisibility(View.GONE);
    tvEmpty.setVisibility(View.GONE);
    executor.execute(() -> {
      String supportedAppsStr = plugin.getSupportedApps();
      if (supportedAppsStr.isEmpty())
        allApps = AppUtils.getInstalledApps(requireContext());
      else {
        allApps = new ArrayList<>();
        String[] supportedApps = supportedAppsStr.split(",");
        PackageManager packageManager = requireContext().getPackageManager();
        for (String pkg : supportedApps) {
          if (PKG_SYSTEM_SERVER.equals(pkg)) {
            AppInfo appInfo = AppInfo.getSystemServer();
            allApps.add(appInfo);
            showSystemApps = true;
          } else {
            try {
              PackageInfo packageInfo = packageManager.getPackageInfo(pkg, 0);
              allApps.add(AppUtils.createAppInfo(requireContext(), packageInfo, packageManager));
            } catch (PackageManager.NameNotFoundException ignore) {
            }
          }

        }
      }
      // 获取已选中的应用
      List<String> selectedPackages = ruleDb.getTargetPackages(pluginPackage);
      // 标记已选中状态
      for (AppInfo app : allApps) {
        app.setSelected(selectedPackages.contains(app.getPackageName()));
      }
      // 初始过滤（不显示系统应用）
      filterApps("", showSystemApps);
      // 更新UI
      requireActivity().runOnUiThread(() -> {
        progressBar.setVisibility(View.GONE);
        updateEmptyState();
      });
    });
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    ruleDb.close();
    executor.shutdown();
  }

  /**
   * 设置工具栏按钮监听器
   */
  private void setupToolbarListeners() {
    // 菜单按钮点击事件
    btnMenu.setOnClickListener(v -> showPopupMenu());
  }

  /**
   * 显示弹出菜单
   */
  private void showPopupMenu() {
    if (popupMenu == null) {
      createPopupMenu();
    }

    if (popupMenu.isShowing()) {
      popupMenu.dismiss();
    } else {
      popupMenu.showAsDropDown(btnMenu, 0, 0);
    }
  }

  /**
   * 创建弹出菜单
   */
  private void createPopupMenu() {
    View menuView = LayoutInflater.from(getContext()).inflate(R.layout.popup_menu_target_apps, null);

    // 获取菜单项
    LinearLayout itemShowSystem = menuView.findViewById(R.id.item_show_system);
    LinearLayout itemFilterPlugin = menuView.findViewById(R.id.item_filter_plugin);
    LinearLayout itemSelectAll = menuView.findViewById(R.id.item_select_all);
    LinearLayout itemDeselectAll = menuView.findViewById(R.id.item_deselect_all);

    CheckBox cbShowSystem = menuView.findViewById(R.id.cb_show_system);
    CheckBox cbFilterPlugin = menuView.findViewById(R.id.cb_filter_plugin);

    // 设置初始状态
    cbShowSystem.setChecked(showSystemApps);
    cbFilterPlugin.setChecked(filterPlugin);

    // 设置点击事件
    itemShowSystem.setOnClickListener(v -> {
      showSystemApps = !showSystemApps;
      cbShowSystem.setChecked(showSystemApps);
      filterApps(etSearch.getText().toString().trim(), showSystemApps);
    });

    itemFilterPlugin.setOnClickListener(v -> {
      filterPlugin = !filterPlugin;
      cbFilterPlugin.setChecked(filterPlugin);
      filterApps(etSearch.getText().toString().trim(), showSystemApps);
    });

    itemSelectAll.setOnClickListener(v -> {
      selectAll(true);
      popupMenu.dismiss();
    });

    itemDeselectAll.setOnClickListener(v -> {
      selectAll(false);
      popupMenu.dismiss();
    });

    // 创建弹出窗口
    popupMenu = new PopupWindow(menuView,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        true);
    popupMenu.setBackgroundDrawable(getContext().getResources().getDrawable(android.R.drawable.dialog_holo_light_frame));
    popupMenu.setElevation(8f);
  }

  private void selectAll(boolean select) {
    executor.execute(() -> {
      for (AppInfo app : filteredApps) {
        if (app.isSelected() != select) {
          app.setSelected(select);
          if (select) {
            ruleDb.addRule(plugin, app.getPackageName());
          } else {
            ruleDb.removeRule(plugin, app.getPackageName());
          }
        }
      }
      recyclerView.post(() -> {
        adapter.notifyDataSetChanged();
      });
    });
  }

  private void filterApps(String query, boolean showSystem) {
    filteredApps.clear();
    for (AppInfo app : allApps) {
      // 过滤系统应用（如果开关关闭）
      if (!showSystem && app.isSystem) {
        continue;
      }
      if (filterPlugin) {
        // 只显示已安装插件的应用
        if (app.isPlugin) {
          continue;
        }
      }
      if (query.isEmpty() ||
          app.getAppName().toLowerCase().contains(query.toLowerCase()) ||
          app.getPackageName().toLowerCase().contains(query.toLowerCase())) {
        filteredApps.add(app);
      }
    }
    // 更新列表
    requireActivity().runOnUiThread(() -> {
      adapter.setAppList(filteredApps);
      updateEmptyState();
    });
  }


  /**
   * 更新空状态显示
   */
  private void updateEmptyState() {
    if (filteredApps.isEmpty()) {
      recyclerView.setVisibility(View.GONE);
      tvEmpty.setVisibility(View.VISIBLE);
      tvEmpty.setText(getString(R.string.no_apps_found, etSearch.getText().toString().trim()));
    } else {
      recyclerView.setVisibility(View.VISIBLE);
      tvEmpty.setVisibility(View.GONE);
    }
  }

  private void setupSearchListener() {
    // 搜索文本变化监听
    etSearch.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        String query = s.toString().trim();
        filterApps(query, showSystemApps);

        // 显示/隐藏清除按钮
        ivClearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
      }

      @Override
      public void afterTextChanged(Editable s) {
      }
    });

    // 清除搜索按钮
    ivClearSearch.setOnClickListener(v -> {
      etSearch.setText("");
      ivClearSearch.setVisibility(View.GONE);
    });
  }


}
