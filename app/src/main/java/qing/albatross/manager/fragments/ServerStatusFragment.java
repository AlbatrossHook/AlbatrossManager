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
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import qing.albatross.core.Albatross;
import qing.albatross.manager.R;
import qing.albatross.manager.activity.MainActivity;
import qing.albatross.manager.data.ConfigManager;
import qing.albatross.manager.data.ServerDatabaseHelper;
import qing.albatross.manager.model.ServerInfo;
import qing.albatross.manager.plugin.PluginDelegate;
import qing.albatross.manager.utils.ClassLoaderUtils;
import qing.albatross.manager.utils.SELinuxManager;
import qing.albatross.manager.utils.ServerManager;
import qing.albatross.manager.utils.SystemUtils;

public class ServerStatusFragment extends Fragment {

  private TextView serverStatusText;
  private TextView coreStatusText;
  private TextView engineFeature;
  private Button toggleServerButton;
  private Button checkCoreButton;
  private TextView tvDeviceModel;
  private boolean isServerRunning = false;

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
    tvDeviceModel = view.findViewById(R.id.tv_device_model);
    dbHelper = ServerDatabaseHelper.getInstance(requireContext());
    serverManager = ServerManager.getInstance(getContext());

    String currentVersion = dbHelper.getCurrentServerVersion();
    if (currentVersion != null) {
      engineVersionText.setText(currentVersion);
      checkCoreButton.setOnClickListener(v -> checkCoreAvailability());
      toggleServerButton.setOnClickListener(v -> toggleServerStatus());
    } else {
      engineVersionText.setText(getString(R.string.core_not_available));
      engineVersionText.setTextColor(getResources().getColor(R.color.red));
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
          serverStatusText.setTextColor(getResources().getColor(R.color.green));
          toggleServerButton.setText(getString(R.string.stop_server));
        } else {
          serverStatusText.setText(getString(R.string.server_not_running));
          serverStatusText.setTextColor(getResources().getColor(R.color.red));
          toggleServerButton.setText(getString(R.string.start_server));
        }
      });
    });
  }

  ServerInfo serverInfo;
  Boolean isCoreAvailable = null;

  private void checkCoreAvailability() {
    // 更新按钮状态，防止重复点击
    checkCoreButton.setEnabled(false);
    checkCoreButton.setText(getString(R.string.checking));
    executor.execute(() -> {
      ServerInfo currentServerInfo = dbHelper.getCurrentServerInfo();
      serverInfo = currentServerInfo;
      String libPath = serverInfo.getLibPath();
      String agentDir = serverInfo.getAgentPath();

      String dexPath = agentDir + "/" + "app_agent.dex";
      File agentDirFile = new File(dexPath);
      File libFile = new File(libPath);
      String feature = null;
      String reason = null;
      if (agentDirFile.exists() && libFile.exists()) {
        int i = libPath.lastIndexOf('/');
        String libDir = libPath.substring(0, i);
        String libName = libPath.substring(i + 4, libPath.length() - 3);
        try {
          try {
            ClassLoaderUtils.patchClassLoader(this.getClass().getClassLoader(), dexPath, libDir);
            isCoreAvailable = Albatross.loadLibrary(libName, 0);
          } catch (Throwable e) {
            reason = "init fail:" + e;
          }
          if (isCoreAvailable) {
            ConfigManager.getInstance(getContext()).saveCoreState(true);
            feature = Albatross.supportFeatures();
            PluginDelegate delegate = PluginDelegate.create(dbHelper, requireContext());
            if (delegate != null) {
              isServerRunning = true;
            }
          }
        } catch (Throwable e) {
          reason = e.toString();
        }
      } else {
        reason = "file not exists";
      }
      // 在主线程更新UI
      String finalReason = reason;
      String finalFeature = feature;
      handler.post(() -> {
        checkCoreButton.setText(getString(R.string.finish_core_availability));
        if (isCoreAvailable != null && isCoreAvailable) {
          coreStatusText.setText(getString(R.string.core_available));
          coreStatusText.setTextColor(getResources().getColor(R.color.green));
          if (finalFeature != null) {
            engineFeature.setText(finalFeature);
          }
          checkServerStatus(false);
        } else {
          coreStatusText.setText(getString(R.string.core_not_supported));
          coreStatusText.setTextColor(getResources().getColor(R.color.red));
          Toast.makeText(requireContext(), "init fail:" + finalReason, Toast.LENGTH_LONG).show();
        }
      });
    });
  }

  private void toggleServerStatus() {
    toggleServerButton.setEnabled(false);
    if (!isServerRunning)
      toggleServerButton.setText("启动中..");
    else
      toggleServerButton.setText("关闭服务中..");
    executor.execute(() -> {
      try {
        if (!isServerRunning) {
          serverManager.startServer();
        } else {
          serverManager.stopServer();
        }
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      handler.post(() -> {
        checkServerStatus(false);
        toggleServerButton.setEnabled(true);
      });
    });
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
    tvSelinux.setText(SELinuxManager.checkByRestrictedDirectories() ? "Enforcing" : "Permissive");
    // 显示引擎版本（假设从本地配置获取）
//    String engineVersion = getEngineVersion();
//    tvEngineVersion.setText(engineVersion);
    // 检查Root状态（耗时操作，在子线程执行）
    executor.execute(() -> {
      boolean isRooted = serverManager.isDeviceRooted();
      requireActivity().runOnUiThread(() -> {
        tvRootStatus.setText(isRooted ? getString(R.string.root_obtained) : getString(R.string.root_not_obtained));
        tvRootStatus.setTextColor(isRooted ?
            getResources().getColor(R.color.green) :
            getResources().getColor(R.color.red));
      });
    });
  }


}
