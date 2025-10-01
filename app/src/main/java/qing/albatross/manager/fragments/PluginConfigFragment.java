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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import qing.albatross.manager.R;
import qing.albatross.manager.activity.PluginDetailActivity;
import qing.albatross.manager.data.Plugin;


public class PluginConfigFragment extends Fragment {
  private static final String ARG_PLUGIN = "plugin";

  private Plugin plugin;
  private EditText etParam1;
  private EditText etParam2;
  private ImageView ivPluginIcon;
  private Switch switchPluginEnabled;
  private LinearLayout layoutUninstallWarning;
  private TextView tvUninstallWarning;
  private Button btnCopyParam1;

  public static PluginConfigFragment newInstance(Plugin plugin) {
    PluginConfigFragment fragment = new PluginConfigFragment();
    Bundle args = new Bundle();
    args.putParcelable(ARG_PLUGIN, plugin);
    fragment.setArguments(args);
    return fragment;
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (getArguments() != null) {
      plugin = getArguments().getParcelable(ARG_PLUGIN);
    }
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
                           Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_plugin_config, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    // 初始化视图
    initViews(view);
    // 填充数据
    populateData();
    // 检查应用是否已卸载
    checkAppInstalled();
    // 设置监听器
    setupListeners();
  }

  private void initViews(View view) {
    ivPluginIcon = view.findViewById(R.id.iv_plugin_icon);
    switchPluginEnabled = view.findViewById(R.id.switch_plugin_enabled);
    layoutUninstallWarning = view.findViewById(R.id.layout_uninstall_warning);
    tvUninstallWarning = view.findViewById(R.id.tv_uninstall_warning);
    etParam1 = view.findViewById(R.id.et_param1);
    etParam2 = view.findViewById(R.id.et_param2);
    btnCopyParam1 = view.findViewById(R.id.btn_copy_param1);
  }

  private void populateData() {
    if (plugin != null) {
      // 设置插件基本信息
      TextView tvPluginName = getView().findViewById(R.id.tv_plugin_name);
      TextView tv_plugin_class = getView().findViewById(R.id.tv_plugin_class);
      TextView tvPluginAuthor = getView().findViewById(R.id.tv_plugin_author);
      TextView tvPluginDescription = getView().findViewById(R.id.tv_plugin_description);
      TextView tv_plugin_package = getView().findViewById(R.id.tv_plugin_package);

      tvPluginName.setText(plugin.getName() != null ? plugin.getName() : getString(R.string.unknown));
      tv_plugin_class.setText("class: " + (plugin.getClassName() != null ? plugin.getClassName() : getString(R.string.unknown)));
      tvPluginAuthor.setText("作者: " + (plugin.getAuthor() != null ? plugin.getAuthor() : getString(R.string.unknown)));
      tvPluginDescription.setText("描述: " + (plugin.getDescription() != null ? plugin.getDescription() : getString(R.string.no_description)));
      tv_plugin_package.setText("包名: " +plugin.getPackageName());
      // 设置插件图标
      loadPluginIcon();

      // 设置开关状态
      switchPluginEnabled.setChecked(plugin.isEnabled());

      // 填充已有参数
      etParam1.setText(plugin.getParams() != null ? plugin.getParams() : "");
      etParam2.setText(String.valueOf(plugin.getFlags()));
    }
  }

  private void loadPluginIcon() {
    try {
      Drawable appIcon = getContext().getPackageManager().getApplicationIcon(plugin.getPackageName());
      ivPluginIcon.setImageDrawable(appIcon);
    } catch (PackageManager.NameNotFoundException e) {
      // 使用默认图标
      ivPluginIcon.setImageResource(R.drawable.ic_plugins);
    }
  }

  private void checkAppInstalled() {
    if (plugin != null) {
      try {
        PackageInfo packageInfo = getContext().getPackageManager().getPackageInfo(plugin.getPackageName(), 0);
        // 应用已安装，隐藏警告
        layoutUninstallWarning.setVisibility(View.GONE);
        TextView tvPluginVersion = getView().findViewById(R.id.tv_plugin_version);
        tvPluginVersion.setText("版本: " + packageInfo.versionName);
      } catch (PackageManager.NameNotFoundException e) {
        // 应用未安装，显示警告
        layoutUninstallWarning.setVisibility(View.VISIBLE);
        tvUninstallWarning.setText("插件应用 " + plugin.getPackageName() + " 已卸载，无法正常使用");
      }
    }
  }

  private void setupListeners() {
    Button btnSave = getView().findViewById(R.id.btn_save_config);
    Button btnLaunchPlugin = getView().findViewById(R.id.btn_launch_plugin);

    btnSave.setOnClickListener(v -> saveConfig());

    btnLaunchPlugin.setOnClickListener(v -> {
      if (getActivity() instanceof PluginDetailActivity) {
        ((PluginDetailActivity) getActivity()).launchPluginConfig(p -> {
          etParam1.setText(plugin.getParams());
          etParam2.setText(String.valueOf(plugin.getFlags()));
          TextView tvPluginVersion = getView().findViewById(R.id.tv_plugin_class);
          tvPluginVersion.setText("class: " + plugin.getClassName());
        });
      }
    });

    // 复制参数1按钮
    btnCopyParam1.setOnClickListener(v -> copyParam1ToClipboard());

    // 开关监听器
    switchPluginEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
      if (plugin != null) {
        plugin.setEnabled(isChecked);
        // 保存状态到数据库
        if (getActivity() instanceof PluginDetailActivity) {
          ((PluginDetailActivity) getActivity()).updatePluginEnabled(plugin, isChecked);
        }
        Toast.makeText(getContext(), isChecked ? getString(R.string.plugin_enabled_status) : getString(R.string.plugin_disabled_status), Toast.LENGTH_SHORT).show();
      }
    });
  }

  private void copyParam1ToClipboard() {
    String param1Text = etParam1.getText().toString().trim();
    if (param1Text.isEmpty()) {
      Toast.makeText(getContext(), getString(R.string.param1_empty_cannot_copy), Toast.LENGTH_SHORT).show();
      return;
    }
    ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
    ClipData clip = ClipData.newPlainText("插件参数1", param1Text);
    clipboard.setPrimaryClip(clip);
    Toast.makeText(getContext(), getString(R.string.param1_copied_to_clipboard), Toast.LENGTH_SHORT).show();
  }

  private void saveConfig() {
    String param1 = etParam1.getText().toString().trim();
    String param2Str = etParam2.getText().toString().trim();
    if (param1.isEmpty()) {
      Toast.makeText(getContext(), getString(R.string.enter_param1_message), Toast.LENGTH_SHORT).show();
      return;
    }
    int param2;
    try {
      param2 = Integer.parseInt(param2Str);
    } catch (NumberFormatException e) {
      Toast.makeText(getContext(), getString(R.string.param2_must_be_number_message), Toast.LENGTH_SHORT).show();
      return;
    }
    // 保存配置
    if (getActivity() instanceof PluginDetailActivity) {
      ((PluginDetailActivity) getActivity()).savePluginConfig(null, param1, param2);
      Toast.makeText(getContext(), getString(R.string.config_saved_message), Toast.LENGTH_SHORT).show();
    }
  }
}
