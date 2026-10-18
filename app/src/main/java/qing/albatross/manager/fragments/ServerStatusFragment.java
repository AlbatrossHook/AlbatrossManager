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

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.android.material.color.MaterialColors;

import qing.albatross.manager.R;
import qing.albatross.manager.activity.MainActivity;
import qing.albatross.manager.data.ConfigManager;
import qing.albatross.manager.data.ServerDatabaseHelper;
import qing.albatross.manager.plugin.PluginDelegate;
import qing.albatross.manager.utils.CoreBootstrap;
import qing.albatross.manager.utils.RuntimeOptions;
import qing.albatross.manager.utils.SELinuxManager;
import qing.albatross.manager.utils.ServerManager;
import qing.albatross.manager.utils.SystemUtils;

public class ServerStatusFragment extends Fragment {

  private TextView serverStatusText;
  private TextView coreStatusText;
  private TextView engineFeature;
  private TextView tvServerCost;
  private TextView tvRuntimeOptions;
  private Button toggleServerButton;
  private Button checkCoreButton;
  private ProgressBar serverProgress;
  private TextView tvDeviceModel;
  private boolean isServerRunning = false;
  private boolean autoStartTriggered = false;

  private ServerManager serverManager;
  ExecutorService executor = Executors.newSingleThreadExecutor();
  ServerDatabaseHelper dbHelper;

  Handler handler = new Handler(Looper.getMainLooper());

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
                           Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.fragment_server_status, container, false);

    serverStatusText = view.findViewById(R.id.server_status_text);
    coreStatusText = view.findViewById(R.id.tv_server_status);
    toggleServerButton = view.findViewById(R.id.toggle_server_button);
    checkCoreButton = view.findViewById(R.id.check_core_button);
    TextView engineVersionText = view.findViewById(R.id.tv_engine_version);
    engineFeature = view.findViewById(R.id.tv_engine_support);
    tvServerCost = view.findViewById(R.id.tv_server_cost);
    tvRuntimeOptions = view.findViewById(R.id.tv_runtime_options);
    serverProgress = view.findViewById(R.id.server_progress);
    tvDeviceModel = view.findViewById(R.id.tv_device_model);
    dbHelper = ServerDatabaseHelper.getInstance(requireContext());
    serverManager = ServerManager.getInstance(getContext());
    tvRuntimeOptions.setText(getString(R.string.option_state_title) + " " + RuntimeOptions.describe(
        requireContext(), getString(R.string.state_on), getString(R.string.state_off)));

    String currentVersion = dbHelper.getCurrentServerVersion();
    if (currentVersion != null) {
      engineVersionText.setText(currentVersion);
      checkCoreButton.setOnClickListener(v -> checkCoreAvailability());
      toggleServerButton.setOnClickListener(v -> toggleServerStatus());
    } else {
      engineVersionText.setText(getString(R.string.core_not_available));
      engineVersionText.setTextColor(MaterialColors.getColor(engineVersionText, androidx.appcompat.R.attr.colorError));
      checkCoreButton.setText(getString(R.string.core_no_modules));
    }
    if (ConfigManager.getInstance(getContext()).getCoreState()) {
      checkCoreAvailability();
    }
    // 初始化状态显示
    coreStatusText.setText(getString(R.string.checking));
    serverStatusText.setText(getString(R.string.checking));
    MainActivity mainActivity = (MainActivity) getActivity();
    assert mainActivity != null;
    mainActivity.setOnRefreshListener(() -> checkServerStatus(true));
    loadDeviceInfo(view);

    return view;
  }

  private void checkServerStatus(boolean sync) {

    executor.execute(() -> {
      isServerRunning = serverManager.checkServerRunning();
      if (isServerRunning && sync) {
        isServerRunning = PluginDelegate.sync(dbHelper, requireContext());
      }
      handler.post(() -> {
        if (isServerRunning) {
          serverStatusText.setText(getString(R.string.server_running));
          serverStatusText.setTextColor(MaterialColors.getColor(serverStatusText, R.attr.colorSuccess));
          toggleServerButton.setText(getString(R.string.stop_server));
          long cost = serverManager.getLastStartCostMs();
          if (cost > 0) {
            tvServerCost.setText(getString(R.string.server_start_cost_format, cost / 1000f));
            tvServerCost.setVisibility(View.VISIBLE);
          }
        } else {
          serverStatusText.setText(getString(R.string.server_not_running));
          serverStatusText.setTextColor(MaterialColors.getColor(serverStatusText, androidx.appcompat.R.attr.colorError));
          toggleServerButton.setText(getString(R.string.start_server));
          tvServerCost.setVisibility(View.GONE);
        }
        autoStartServerIfNeeded();
      });
    });
  }

  Boolean isCoreAvailable = null;

  private void checkCoreAvailability() {
    // 更新按钮状态，防止重复点击
    checkCoreButton.setEnabled(false);
    checkCoreButton.setText(getString(R.string.checking));
    executor.execute(() -> {
      CoreBootstrap.Result result = CoreBootstrap.load(requireContext());
      isCoreAvailable = result.ok;
      if (result.ok && result.connected) {
        isServerRunning = true;
      }
      handler.post(() -> {
        checkCoreButton.setText(getString(R.string.finish_core_availability));
        if (result.ok) {
          coreStatusText.setText(getString(R.string.core_available));
          coreStatusText.setTextColor(MaterialColors.getColor(coreStatusText, R.attr.colorSuccess));
          if (result.features != null) {
            engineFeature.setText(result.features);
          }
          checkServerStatus(false);
        } else {
          coreStatusText.setText(getString(R.string.core_not_supported));
          coreStatusText.setTextColor(MaterialColors.getColor(coreStatusText, androidx.appcompat.R.attr.colorError));
          Toast.makeText(requireContext(), getString(R.string.core_init_fail_format, result.error), Toast.LENGTH_LONG).show();
        }
      });
    });
  }

  private void toggleServerStatus() {
    toggleServerButton.setEnabled(false);
    boolean starting = !isServerRunning;
    toggleServerButton.setText(starting ? getString(R.string.server_starting) : getString(R.string.server_stopping));
    serverProgress.setVisibility(View.VISIBLE);
    executor.execute(() -> {
      if (starting) {
        serverManager.startServer();
      } else {
        serverManager.stopServer();
      }
      handler.post(() -> {
        serverProgress.setVisibility(View.GONE);
        toggleServerButton.setEnabled(true);
        checkServerStatus(false);
      });
    });
  }

  /**
   * 打开应用且核心可用时按设置自动拉起服务，只自动执行一次，避免手动停止后又被拉起
   */
  private void autoStartServerIfNeeded() {
    if (autoStartTriggered || isServerRunning || !isAdded()) {
      return;
    }
    if (!ConfigManager.getInstance(requireContext()).isAutoStartServerEnabled()) {
      return;
    }
    autoStartTriggered = true;
    toggleServerStatus();
  }


  private void loadDeviceInfo(View view) {
    TextView tvAndroidVersion = view.findViewById(R.id.tv_android_version);
    // 显示Android版本（主线程直接获取）
    String androidVersion = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    tvAndroidVersion.setText(androidVersion);
    // 显示手机型号（主线程直接获取）
    String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
    tvDeviceModel.setText(deviceModel);
    // 显示内核版本
//    String kernelVersion = SystemUtils.getKernelVersion();
//    tvKernelVersion.setText(kernelVersion);
    TextView tvCpuArchitecture = view.findViewById(R.id.tv_cpu_architecture);
    // 显示CPU架构
    String cpuArch = SystemUtils.getCpuArchitecture();
    tvCpuArchitecture.setText(cpuArch);
    TextView tvRootStatus = view.findViewById(R.id.tv_root_status);
    TextView tvSelinux = view.findViewById(R.id.tv_selinux_status);
    boolean enforcing = SELinuxManager.checkByRestrictedDirectories();
    tvSelinux.setText(enforcing ? getString(R.string.selinux_enforcing) : getString(R.string.selinux_permissive));
    tvSelinux.setTextColor(MaterialColors.getColor(tvSelinux, enforcing ? R.attr.colorSuccess : R.attr.colorWarning));
    // 显示引擎版本（假设从本地配置获取）
//    String engineVersion = getEngineVersion();
//    tvEngineVersion.setText(engineVersion);
    // 检查Root状态（耗时操作，在子线程执行）
    executor.execute(() -> {
      boolean isRooted = serverManager.isDeviceRooted();
      requireActivity().runOnUiThread(() -> {
        tvRootStatus.setText(isRooted ? getString(R.string.root_obtained) : getString(R.string.root_not_obtained));
        tvRootStatus.setTextColor(MaterialColors.getColor(tvRootStatus,
            isRooted ? R.attr.colorSuccess : androidx.appcompat.R.attr.colorError));
      });
    });
  }


}
