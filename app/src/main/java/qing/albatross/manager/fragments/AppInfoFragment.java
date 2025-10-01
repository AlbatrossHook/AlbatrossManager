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

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.Button;
import android.widget.TextView;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;


import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import qing.albatross.manager.activity.AppDetailActivity;
import qing.albatross.manager.utils.AppUtils;
import qing.albatross.manager.utils.BackupManager;
import qing.albatross.manager.R;

public class AppInfoFragment extends Fragment {
  private static final String ARG_PACKAGE_NAME = "package_name";
  private ImageView ivAppIcon;
  private TextView tvAppName;
  private TextView tvPackageNameValue;
  private TextView tvVersionNameValue;
  private TextView tvVersionCodeValue;
  private TextView tvAppPathValue;
  private TextView tvDataPathValue;
  private TextView tvInstallTimeValue;
  private TextView tvUpdateTimeValue;
  private TextView tvAppTypeValue;
  private TextView tvAppUidValue;
  private TextView tvFreezeStatusValue;
  private TextView tvSignatureValue;
  private TextView tvProcessesValue;
  private Button btnOpenApp;
  private Button btnCloseApp;
  private Button btnFreezeApp;
  private Button btnBackupApp;
  private Button btnRestoreApp;
  private String packageName;
  private PackageInfo packageInfo;
  private ApplicationInfo applicationInfo;
  private boolean isAppFrozen = false;

  public static AppInfoFragment newInstance(String packageName) {
    AppInfoFragment fragment = new AppInfoFragment();
    Bundle args = new Bundle();
    args.putString(ARG_PACKAGE_NAME, packageName);
    fragment.setArguments(args);
    return fragment;
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (getArguments() != null) {
      packageName = getArguments().getString(ARG_PACKAGE_NAME);
    }

    // 从Activity获取应用信息
    if (getActivity() instanceof AppDetailActivity) {
      AppDetailActivity activity = (AppDetailActivity) getActivity();
      packageInfo = activity.getPackageInfo();
      applicationInfo = activity.getTargetAppInfo();
    } else {
      // 备选方案：直接获取
      try {
        packageInfo = requireContext().getPackageManager().getPackageInfo(packageName,
            PackageManager.GET_SIGNATURES);
        applicationInfo = packageInfo.applicationInfo;
      } catch (PackageManager.NameNotFoundException e) {
        e.printStackTrace();
      }
    }
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.fragment_app_info, container, false);
    // 初始化视图
    ivAppIcon = view.findViewById(R.id.iv_app_icon);
    tvAppName = view.findViewById(R.id.tv_app_name);
    tvPackageNameValue = view.findViewById(R.id.tv_package_name_value);
    tvVersionNameValue = view.findViewById(R.id.tv_version_name_value);
    tvVersionCodeValue = view.findViewById(R.id.tv_version_code_value);
    tvAppPathValue = view.findViewById(R.id.tv_app_path_value);
    tvDataPathValue = view.findViewById(R.id.tv_data_path_value);
    tvInstallTimeValue = view.findViewById(R.id.tv_install_time_value);
    tvUpdateTimeValue = view.findViewById(R.id.tv_update_time_value);
    tvAppTypeValue = view.findViewById(R.id.tv_app_type_value);
    tvAppUidValue = view.findViewById(R.id.tv_app_uid_value);
    tvFreezeStatusValue = view.findViewById(R.id.tv_freeze_status_value);
    tvSignatureValue = view.findViewById(R.id.tv_signature_value);
    tvProcessesValue = view.findViewById(R.id.tv_processes_value);
    btnOpenApp = view.findViewById(R.id.btn_open_app);
    btnCloseApp = view.findViewById(R.id.btn_close_app);
    btnFreezeApp = view.findViewById(R.id.btn_freeze_app);
    btnBackupApp = view.findViewById(R.id.btn_backup_app);
    btnRestoreApp = view.findViewById(R.id.btn_restore_app);
    return view;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    if (packageInfo == null || applicationInfo == null) {
      tvPackageNameValue.setText(getString(R.string.cannot_get_app_info));
      return;
    }

    // 初始化应用信息展示
    initAppInfo();

    // 初始化操作按钮
    initActionButtons();
  }

  /**
   * 初始化应用信息展示
   */
  private void initAppInfo() {
    // 应用图标和名称
    try {
      android.graphics.drawable.Drawable appIcon = requireContext().getPackageManager().getApplicationIcon(applicationInfo);
      ivAppIcon.setImageDrawable(appIcon);
    } catch (Exception e) {
      ivAppIcon.setImageResource(R.drawable.ic_launcher_foreground);
    }
    try {
      String appName = requireContext().getPackageManager().getApplicationLabel(applicationInfo).toString();
      tvAppName.setText(appName);
    } catch (Exception e) {
      tvAppName.setText(packageName);
    }

    // 包名
    tvPackageNameValue.setText(packageName);

    // 版本名称和版本号
    tvVersionNameValue.setText(packageInfo.versionName != null ? packageInfo.versionName : getString(R.string.unknown));
    tvVersionCodeValue.setText(String.valueOf(packageInfo.versionCode));

    // 应用路径
    tvAppPathValue.setText(applicationInfo.sourceDir);

    // 数据路径
    tvDataPathValue.setText(applicationInfo.dataDir);

    // 安装时间
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    tvInstallTimeValue.setText(sdf.format(new Date(packageInfo.firstInstallTime)));

    // 最后更新时间
    tvUpdateTimeValue.setText(sdf.format(new Date(packageInfo.lastUpdateTime)));

    // 签名信息
    String signature = AppUtils.getSignatureHash(packageInfo);
    tvSignatureValue.setText(signature);

    // 应用进程
    new Thread(() -> {
      String processes = AppUtils.getAppProcesses(packageName);
      requireActivity().runOnUiThread(() -> {
        tvProcessesValue.setText(processes);
      });
    }).start();
    // 应用类型
    String appType = (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ?
        getString(R.string.system_app) : getString(R.string.user_app);
    tvAppTypeValue.setText(appType);

    // 应用UID
    int appUid = applicationInfo.uid;
    tvAppUidValue.setText(appUid != -1 ? String.valueOf(appUid) : getString(R.string.unknown));

    boolean frozen = !applicationInfo.enabled;
    requireActivity().runOnUiThread(() -> {
      isAppFrozen = frozen;
      tvFreezeStatusValue.setText(frozen ? getString(R.string.app_frozen_status) : getString(R.string.app_normal_status));
      updateFreezeButton();
    });
  }

  /**
   * 初始化操作按钮
   */
  private void initActionButtons() {
    // 打开应用
    btnOpenApp.setOnClickListener(v -> {
      try {
        Intent intent = requireContext().getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          startActivity(intent);
          Toast.makeText(requireContext(), getString(R.string.app_opened), Toast.LENGTH_SHORT).show();
        } else {
          Toast.makeText(requireContext(), getString(R.string.app_cannot_open), Toast.LENGTH_SHORT).show();
        }
      } catch (Exception e) {
        Toast.makeText(requireContext(), getString(R.string.app_cannot_open), Toast.LENGTH_SHORT).show();
      }
    });

    // 关闭应用
    btnCloseApp.setOnClickListener(v -> {
      new Thread(() -> {
        boolean result = AppUtils.forceStopApp(requireContext(), packageName);
        requireActivity().runOnUiThread(() -> {
          Toast.makeText(requireContext(), result ? getString(R.string.app_closed) : getString(R.string.app_close_failed), Toast.LENGTH_SHORT).show();
          // 刷新进程信息
          refreshProcessInfo();
        });
      }).start();
    });

    // 冻结/解冻应用
    btnFreezeApp.setOnClickListener(v -> {
      new Thread(() -> {
        boolean result;
        if (isAppFrozen) {
          result = AppUtils.unfreezeApp(requireContext(), packageName);
        } else {
          result = AppUtils.freezeApp(requireContext(), packageName);
        }
        requireActivity().runOnUiThread(() -> {
          if (result) {
            isAppFrozen = !isAppFrozen;
            tvFreezeStatusValue.setText(isAppFrozen ? getString(R.string.app_frozen_status) : getString(R.string.app_normal_status));
            updateFreezeButton();
            Toast.makeText(requireContext(), isAppFrozen ? getString(R.string.app_frozen) : getString(R.string.app_unfrozen), Toast.LENGTH_SHORT).show();
          } else {
            Toast.makeText(requireContext(), isAppFrozen ? getString(R.string.app_unfreeze_failed) : getString(R.string.app_freeze_failed), Toast.LENGTH_SHORT).show();
          }
        });
      }).start();
    });

    // 备份应用
    btnBackupApp.setOnClickListener(v -> showPasswordDialog(true));

    // 还原应用
    btnRestoreApp.setOnClickListener(v -> showPasswordDialog(false));
  }

  /**
   * 刷新进程信息
   */
  private void refreshProcessInfo() {
    new Thread(() -> {
      String processes = AppUtils.getAppProcesses(packageName);
      requireActivity().runOnUiThread(() -> {
        tvProcessesValue.setText(processes);
      });
    }).start();
  }

  /**
   * 更新冻结按钮状态
   */
  private void updateFreezeButton() {
    if (isAppFrozen) {
      btnFreezeApp.setText(getString(R.string.unfreeze_app));
      btnFreezeApp.setBackgroundTintList(requireContext().getColorStateList(R.color.success));
    } else {
      btnFreezeApp.setText(getString(R.string.freeze_app));
      btnFreezeApp.setBackgroundTintList(requireContext().getColorStateList(R.color.error));
    }
  }

  /**
   * 显示密码输入对话框
   */
  private void showPasswordDialog(boolean isBackup) {
    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    builder.setTitle(isBackup ? getString(R.string.backup_password_title) : getString(R.string.restore_password_title));

    // 创建输入框
    final EditText input = new EditText(requireContext());
    input.setHint(isBackup ? getString(R.string.backup_password_hint) : getString(R.string.restore_password_hint));
    builder.setView(input);

    // 设置按钮
    builder.setPositiveButton(getString(R.string.confirm), (dialog, which) -> {
      String password = input.getText().toString().trim();
      if (isBackup) {
        performBackup(password);
      } else {
        performRestore(password);
      }
    });

    builder.setNegativeButton(getString(R.string.cancel), (dialog, which) -> dialog.cancel());

    builder.show();
  }

  /**
   * 执行备份操作
   */
  private void performBackup(String password) {
    // 显示处理中提示
    Toast.makeText(requireContext(), getString(R.string.backup_processing), Toast.LENGTH_SHORT).show();

    new Thread(() -> {
      try {
        // 调用备份管理器
        boolean success = BackupManager.backupApp(requireContext(), packageName, password);
        requireActivity().runOnUiThread(() -> {
          if (success) {
            Toast.makeText(requireContext(), getString(R.string.backup_success), Toast.LENGTH_SHORT).show();
          } else {
            Toast.makeText(requireContext(), getString(R.string.backup_failed), Toast.LENGTH_SHORT).show();
          }
        });
      } catch (Exception e) {
        e.printStackTrace();
        requireActivity().runOnUiThread(() -> {
          Toast.makeText(requireContext(), getString(R.string.backup_failed), Toast.LENGTH_SHORT).show();
        });
      }
    }).start();
  }

  /**
   * 执行还原操作
   */
  private void performRestore(String password) {
    // 显示处理中提示
    Toast.makeText(requireContext(), getString(R.string.restore_processing), Toast.LENGTH_SHORT).show();

    new Thread(() -> {
      try {
        // 调用备份管理器
        boolean success = BackupManager.restoreApp(requireContext(), packageName, password);
        requireActivity().runOnUiThread(() -> {
          if (success) {
            Toast.makeText(requireContext(), getString(R.string.restore_success), Toast.LENGTH_SHORT).show();
            // 还原成功后刷新应用信息
            initAppInfo();
          } else {
            Toast.makeText(requireContext(), getString(R.string.restore_failed), Toast.LENGTH_SHORT).show();
          }
        });
      } catch (Exception e) {
        e.printStackTrace();
        requireActivity().runOnUiThread(() -> {
          Toast.makeText(requireContext(), getString(R.string.restore_failed), Toast.LENGTH_SHORT).show();
        });
      }
    }).start();
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    ivAppIcon = null;
    tvAppName = null;
    tvPackageNameValue = null;
    tvVersionNameValue = null;
    tvVersionCodeValue = null;
    tvAppPathValue = null;
    tvDataPathValue = null;
    tvInstallTimeValue = null;
    tvUpdateTimeValue = null;
    tvAppTypeValue = null;
    tvAppUidValue = null;
    tvFreezeStatusValue = null;
    tvSignatureValue = null;
    tvProcessesValue = null;
    btnOpenApp = null;
    btnCloseApp = null;
    btnFreezeApp = null;
    btnBackupApp = null;
    btnRestoreApp = null;
  }
}
