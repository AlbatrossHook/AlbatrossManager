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

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import qing.albatross.manager.R;
import qing.albatross.manager.adapter.ServerVersionAdapter;
import qing.albatross.manager.data.ConfigManager;
import qing.albatross.manager.data.ServerDatabaseHelper;
import qing.albatross.manager.model.ServerInfo;
import qing.albatross.manager.plugin.PluginDelegate;
import qing.albatross.manager.utils.CoreInstaller;
import qing.albatross.manager.utils.FileUtils;
import qing.albatross.manager.utils.RuntimeOptions;
import qing.albatross.manager.utils.ServerManager;

public class SettingsFragment extends Fragment implements ServerVersionAdapter.OnVersionActionListener {

  private static final int REQUEST_CODE_SELECT_ZIP = 1002;
  private RecyclerView rvServerVersions;
  private ServerVersionAdapter versionAdapter;
  private LinearLayout tvNoVersions;
  private EditText etRootPath;
  private Button btnSaveRootPath;
  private Button btnKernelHide;
  private SwitchCompat swJit;
  private SwitchCompat swAntiDetection;
  private SwitchCompat swMemfdInject;
  private SwitchCompat swAutoStart;
  private TextView tvKernelHideState;
  private TextView tvOptionState;
  private ServerDatabaseHelper dbHelper;
  private ExecutorService executor = Executors.newSingleThreadExecutor();
  private String currentVersion;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_settings, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    // 初始化数据库
    dbHelper = ServerDatabaseHelper.getInstance(requireContext());
    // 初始化视图
    rvServerVersions = view.findViewById(R.id.rv_server_versions);
    tvNoVersions = view.findViewById(R.id.tv_no_versions);
    etRootPath = view.findViewById(R.id.et_root_path);
    EditText etServerAddress = view.findViewById(R.id.et_address_path);
    TextView etSuPath = view.findViewById(R.id.et_su_path);
    btnSaveRootPath = view.findViewById(R.id.btn_save_root_path);
    Button btnImportServer = view.findViewById(R.id.btn_import_server);
    Button btnSuSave = view.findViewById(R.id.btn_save_su_path);
    Button btnSaveAddress = view.findViewById(R.id.btn_save_server_address);
    swJit = view.findViewById(R.id.sw_jit);
    swAntiDetection = view.findViewById(R.id.sw_anti_detection);
    swMemfdInject = view.findViewById(R.id.sw_memfd_inject);
    swAutoStart = view.findViewById(R.id.sw_auto_start);
    btnKernelHide = view.findViewById(R.id.btn_kernel_hide);
    tvKernelHideState = view.findViewById(R.id.tv_kernel_hide_state);
    tvOptionState = view.findViewById(R.id.tv_option_state);

    ConfigManager configManager = ConfigManager.getInstance(getContext());
    etServerAddress.setText(configManager.getServerAddress());
    setupRuntimeOptions(view, configManager);

    // 初始化列表
    rvServerVersions.setLayoutManager(new LinearLayoutManager(requireContext()));
    versionAdapter = new ServerVersionAdapter(this);
    rvServerVersions.setAdapter(versionAdapter);

    // 加载服务版本列表
    loadServerVersions();

    String suPath = configManager.getSuFilePath();
    etSuPath.setText(suPath);
    // 加载Root路径配置
    loadRootPath();
    // 导入服务按钮点击事件
    btnImportServer.setOnClickListener(v -> {
      selectServerZip();
    });
    btnSuSave.setOnClickListener(v -> {
      String string = etSuPath.getText().toString().trim();
      if (string.length() > 1)
        configManager.saveSuFilePath(string);
      else
        Toast.makeText(requireContext(), getString(R.string.enter_correct_su_path), Toast.LENGTH_SHORT).show();
    });
    btnSaveAddress.setOnClickListener(v -> {
      String address = etServerAddress.getText().toString().trim();
      if (address.length() > 1) {
        configManager.saveServerAddress(address);
      } else {
        Toast.makeText(requireContext(), getString(R.string.enter_correct_server_path), Toast.LENGTH_SHORT).show();
      }
    });

    // 保存Root路径
    btnSaveRootPath.setOnClickListener(v -> {
      String rootPath = etRootPath.getText().toString().trim();
      if (rootPath.isEmpty()) {
        Toast.makeText(requireContext(), getString(R.string.enter_root_path_message), Toast.LENGTH_SHORT).show();
        return;
      }

      if (!rootPath.endsWith("/")) {
        rootPath += "/";
      }

      String finalRootPath = rootPath;
      executor.execute(() -> {
        try {
          // 确保数据库连接正常
          dbHelper.ensureDatabaseOpen();
          
          dbHelper.saveRootPath(finalRootPath);
          requireActivity().runOnUiThread(() ->
              Toast.makeText(requireContext(), getString(R.string.root_path_saved_success), Toast.LENGTH_SHORT).show()
          );
        } catch (Exception e) {
          e.printStackTrace();
          requireActivity().runOnUiThread(() ->
              Toast.makeText(requireContext(), getString(R.string.save_root_path_failed_format, e.getMessage()), Toast.LENGTH_SHORT).show()
          );
        }
      });
    });
  }

  /**
   * 运行选项：开关直接读写配置，改动后重新下发给已连接的服务
   */
  private void setupRuntimeOptions(View view, ConfigManager configManager) {
    swJit.setChecked(configManager.isJitEnabled());
    swAntiDetection.setChecked(configManager.isAntiDetectionEnabled());
    swMemfdInject.setChecked(configManager.isMemfdInjectEnabled());
    swAutoStart.setChecked(configManager.isAutoStartServerEnabled());
    tvKernelHideState.setText(configManager.getKpmHideState() ?
        R.string.option_kernel_hide_on : R.string.option_kernel_hide_off);
    updateOptionState();

    swJit.setOnCheckedChangeListener((buttonView, isChecked) -> {
      configManager.setJitEnabled(isChecked);
      onRuntimeOptionChanged();
    });
    swAntiDetection.setOnCheckedChangeListener((buttonView, isChecked) -> {
      configManager.setAntiDetectionEnabled(isChecked);
      onRuntimeOptionChanged();
    });
    swMemfdInject.setOnCheckedChangeListener((buttonView, isChecked) -> {
      configManager.setMemfdInjectEnabled(isChecked);
      onRuntimeOptionChanged();
    });
    swAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
      configManager.setAutoStartServerEnabled(isChecked);
      updateOptionState();
    });
    btnKernelHide.setOnClickListener(v -> runKernelHide());
    //整行点击等同于切换开关
    view.findViewById(R.id.row_jit).setOnClickListener(v -> swJit.toggle());
    view.findViewById(R.id.row_anti_detection).setOnClickListener(v -> swAntiDetection.toggle());
    view.findViewById(R.id.row_memfd_inject).setOnClickListener(v -> swMemfdInject.toggle());
    view.findViewById(R.id.row_auto_start).setOnClickListener(v -> swAutoStart.toggle());
  }

  private void updateOptionState() {
    tvOptionState.setText(getString(R.string.option_state_title) + " " + RuntimeOptions.describe(
        requireContext(), getString(R.string.state_on), getString(R.string.state_off)));
  }

  /**
   * 选项改变后重连服务，让服务端拿到新的注入 flag
   */
  private void onRuntimeOptionChanged() {
    updateOptionState();
    executor.execute(() -> {
      boolean reApplied = PluginDelegate.reApplyRuntimeOptions(dbHelper, requireContext());
      String message;
      if (reApplied && !PluginDelegate.isAgentDexSupportOptions()) {
        message = getString(R.string.option_need_new_agent);
      } else {
        message = getString(R.string.option_applied);
      }
      if (isAdded()) {
        requireActivity().runOnUiThread(() ->
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show());
      }
    });
  }

  /**
   * 内核隐藏：需要设备加载了 rwbp.ko，开启后不可撤销
   */
  private void runKernelHide() {
    btnKernelHide.setEnabled(false);
    btnKernelHide.setText(R.string.option_kernel_hide_running);
    executor.execute(() -> {
      boolean hidden = PluginDelegate.extendKpmHide();
      if (hidden) {
        ConfigManager.getInstance(requireContext()).setKpmHideState(true);
      }
      if (!isAdded()) {
        return;
      }
      requireActivity().runOnUiThread(() -> {
        btnKernelHide.setEnabled(true);
        btnKernelHide.setText(R.string.option_kernel_hide_button);
        tvKernelHideState.setText(hidden ?
            R.string.option_kernel_hide_on : R.string.option_kernel_hide_off);
        Toast.makeText(requireContext(), hidden ?
            R.string.option_kernel_hide_success : R.string.option_kernel_hide_failed, Toast.LENGTH_SHORT).show();
      });
    });
  }

  /**
   * 选择服务ZIP包
   */
  private void selectServerZip() {    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
    intent.setType("application/zip");
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    filePickerLauncher.launch(intent);
  }


  ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(),
      result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
          Uri uri = result.getData().getData();
          if (uri != null) {
            processServerZip(uri);
          }
        }
      }
  );


  /**
   * 加载服务版本列表
   */
  private void loadServerVersions() {
    executor.execute(() -> {
      try {
        // 确保数据库连接正常
        dbHelper.ensureDatabaseOpen();
        
        List<ServerInfo> serverVersions = dbHelper.getAllServerVersions();
        currentVersion = dbHelper.getCurrentServerVersion();

        requireActivity().runOnUiThread(() -> {
          if (serverVersions.isEmpty()) {
            rvServerVersions.setVisibility(View.GONE);
            tvNoVersions.setVisibility(View.VISIBLE);
          } else {
            rvServerVersions.setVisibility(View.VISIBLE);
            tvNoVersions.setVisibility(View.GONE);
            versionAdapter.setServerVersions(serverVersions, currentVersion);
          }
        });
      } catch (Exception e) {
        e.printStackTrace();
        requireActivity().runOnUiThread(() -> {
          Toast.makeText(requireContext(), getString(R.string.load_server_versions_failed_format, e.getMessage()), Toast.LENGTH_SHORT).show();
        });
      }
    });
  }

  /**
   * 加载Root路径配置
   */
  private void loadRootPath() {
    executor.execute(() -> {
      try {
        // 确保数据库连接正常
        dbHelper.ensureDatabaseOpen();
        
        String rootPath = dbHelper.getRootPath();
        if (rootPath == null || rootPath.isEmpty()) {
          rootPath = "/data/local/tmp/"; // 默认路径
        }
        String finalRootPath = rootPath;
        requireActivity().runOnUiThread(() -> {
          etRootPath.setText(finalRootPath);
        });
      } catch (Exception e) {
        e.printStackTrace();
        requireActivity().runOnUiThread(() -> {
          etRootPath.setText("/data/local/tmp/"); // 使用默认路径
        });
      }
    });
  }

  /**
   * 处理选中的服务ZIP包
   */
  private void processServerZip(Uri zipUri) {
    // 显示加载对话框
    AlertDialog progressDialog = new MaterialAlertDialogBuilder(requireContext())
        .setMessage(getString(R.string.processing_server_components))
        .setCancelable(false)
        .create();
    progressDialog.show();

    executor.execute(() -> {
      try {
        CoreInstaller.Package packageInfo = CoreInstaller.extract(requireContext(), zipUri);
        if (!packageInfo.isOk()) {
          showError(packageInfo.error);
          return;
        }
        if (dbHelper.checkVersionExists(packageInfo.version)) {
          // 提示用户是否覆盖
          requireActivity().runOnUiThread(() -> {
            progressDialog.dismiss();
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.version_exists_title))
                .setMessage(getString(R.string.version_exists_message_format, packageInfo.version))
                .setPositiveButton(getString(R.string.overwrite_button), (dialog, which) -> {
                  saveServerVersion(packageInfo, true);
                })
                .setNegativeButton(getString(R.string.cancel_button), null)
                .show();
          });
        } else {
          // 直接保存新版本
          saveServerVersion(packageInfo, false);
          requireActivity().runOnUiThread(progressDialog::dismiss);
        }
      } catch (Exception e) {
        e.printStackTrace();
        showError(getString(R.string.processing_failed_format, e.getMessage()));
      } finally {
        if (progressDialog.isShowing()) {
          requireActivity().runOnUiThread(progressDialog::dismiss);
        }
      }
    });
  }


  /**
   * 保存服务版本（导入逻辑在 CoreInstaller 里，这里只负责界面反馈）
   */
  private void saveServerVersion(CoreInstaller.Package packageInfo, boolean overwrite) {
    executor.execute(() -> {
      CoreInstaller.Result result = CoreInstaller.installExtracted(requireContext(), packageInfo,
          overwrite, true);
      if (!result.ok) {
        showError(result.error);
        return;
      }
      requireActivity().runOnUiThread(() -> {
        loadServerVersions();
        if (result.firstVersion) {
          // 第一个版本直接设为当前，需要重启应用重新加载核心
          Toast.makeText(requireContext(),
              getString(R.string.core_import_success_default_format, result.version), Toast.LENGTH_LONG).show();
          Intent intent = requireContext().getPackageManager().getLaunchIntentForPackage(requireContext().getPackageName());
          if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            requireContext().startActivity(intent);
            requireActivity().finish();
          } else {
            System.exit(0);
          }
        } else {
          Toast.makeText(requireContext(), getString(R.string.core_import_success_format, result.version),
              Toast.LENGTH_SHORT).show();
        }
      });
    });
  }

  /**
   * 显示错误信息
   */
  private void showError(String message) {
    requireActivity().runOnUiThread(() ->
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    );
  }

  /**
   * 切换服务版本
   */
  @Override
  public void onVersionSwitch(ServerInfo serverInfo) {
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle(getString(R.string.switch_version_title))
        .setMessage(getString(R.string.switch_version_confirm_format, serverInfo.getVersion()))
        .setPositiveButton(getString(R.string.confirm_button), (dialog, which) -> {
          executor.execute(() -> {
            dbHelper.setCurrentServerVersion(serverInfo.getVersion());
            ServerManager serverManager = ServerManager.getInstance(getContext());
            serverManager.stopServer();
            requireActivity().runOnUiThread(() -> {
              loadServerVersions();
              showRestartDialog();
            });
          });
        })
        .setNegativeButton(getString(R.string.cancel_button), null)
        .show();
  }

  // 在SettingsFragment中实现查看完整描述的接口方法
  @Override
  public void onShowMoreDescription(ServerInfo serverInfo) {
    // 创建显示完整描述的对话框
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle(getString(R.string.version_description_title_format, serverInfo.getVersion()))
        .setMessage(serverInfo.getDescription())
        .setPositiveButton(getString(R.string.close_button), null)
        // 允许复制描述内容
        .setNeutralButton(getString(R.string.copy_description_button), (dialog, which) -> {
          android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
              requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
          android.content.ClipData clip = android.content.ClipData.newPlainText(
              getString(R.string.server_version_description), serverInfo.getDescription());
          clipboard.setPrimaryClip(clip);
          Toast.makeText(requireContext(), getString(R.string.description_copied), Toast.LENGTH_SHORT).show();
        })
        .show();
  }


  /**
   * 删除服务版本
   */
  @Override
  public void onVersionDelete(ServerInfo serverInfo) {
    // 不能删除当前正在使用的版本
    if (serverInfo.getVersion().equals(currentVersion)) {
      Toast.makeText(requireContext(), getString(R.string.cannot_delete_current_version), Toast.LENGTH_SHORT).show();
      return;
    }

    new MaterialAlertDialogBuilder(requireContext())
        .setTitle(getString(R.string.delete_version_title))
        .setMessage(getString(R.string.delete_version_confirm_format, serverInfo.getVersion()))
        .setPositiveButton(getString(R.string.delete_confirm_button), (dialog, which) -> {
          executor.execute(() -> {
            // 删除文件
            File versionDir = new File(requireContext().getFilesDir(),
                "servers/" + serverInfo.getVersion());
            FileUtils.deleteDirectory(versionDir);

            // 从数据库删除
            dbHelper.deleteServerVersion(serverInfo.getVersion());

            requireActivity().runOnUiThread(this::loadServerVersions);
          });
        })
        .setNegativeButton(getString(R.string.cancel_button), null)
        .show();
  }

  /**
   * 显示重启应用对话框
   */
  private void showRestartDialog() {
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle(getString(R.string.version_switched_title))
        .setMessage(getString(R.string.restart_app_for_version_switch))
        .setPositiveButton(getString(R.string.restart_now_button), (dialog, which) -> {
          // 重启应用
          System.exit(0);
//          Intent intent = requireContext().getPackageManager()
//              .getLaunchIntentForPackage(requireContext().getPackageName());
//          if (intent != null) {
//            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
//            requireContext().startActivity(intent);
//            requireActivity().finish();
//          }
        })
        .setNegativeButton(getString(R.string.restart_later_button), null)
        .show();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    executor.shutdown();
  }
}
