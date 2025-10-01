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

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Switch;
import android.widget.Button;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import qing.albatross.manager.activity.AppDetailActivity;
import qing.albatross.manager.utils.LogUtils;
import qing.albatross.manager.R;

public class AppLogFragment extends Fragment {
  private SwitchCompat switchMonitor;
  private Button btnClearLog;
  private TextView tvLogOutput;
  private ScrollView scrollView;
  private boolean isMonitoring = false;
  private Process logcatProcess;
  private ExecutorService executor = Executors.newSingleThreadExecutor();
  private Handler mainHandler = new Handler(Looper.getMainLooper());

  public static AppLogFragment newInstance() {
    return new AppLogFragment();
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.fragment_app_log, container, false);

    // 初始化视图
    switchMonitor = view.findViewById(R.id.switch_monitor);
    btnClearLog = view.findViewById(R.id.btn_clear_log);
    tvLogOutput = view.findViewById(R.id.tv_log_output);
    scrollView = view.findViewById(R.id.scroll_view);

    return view;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    // 日志监听开关
    switchMonitor.setOnCheckedChangeListener((buttonView, isChecked) -> {
      if (isChecked) {
        startLogMonitoring();
      } else {
        stopLogMonitoring();
      }
    });

    // 清空日志按钮
    btnClearLog.setOnClickListener(v -> tvLogOutput.setText(""));
  }

  /**
   * 开始监听应用日志
   */
  private void startLogMonitoring() {
    if (isMonitoring) return;

    isMonitoring = true;
    tvLogOutput.append("开始监听日志...\n");

    if (getActivity() instanceof AppDetailActivity) {
      String packageName = ((AppDetailActivity) getActivity()).getTargetPackage();
      executor.execute(() -> {
        try {
          // 执行logcat命令过滤指定应用的日志
          String pid = LogUtils.getPidForPackage(packageName);
          if (pid.isEmpty()) {
            mainHandler.post(() -> {
              tvLogOutput.append("应用未运行，无法监听日志\n");
            });
            return;
          }

          logcatProcess = Runtime.getRuntime().exec("logcat --pid=" + pid);

          BufferedReader reader = new BufferedReader(
              new InputStreamReader(logcatProcess.getInputStream()));

          String line;
          while (isMonitoring && (line = reader.readLine()) != null) {
            final String logLine = line;
            mainHandler.post(() -> {
              // 添加日志到显示
              tvLogOutput.append(logLine + "\n");
              // 滚动到底部
              scrollView.fullScroll(View.FOCUS_DOWN);
            });
          }

        } catch (IOException e) {
          e.printStackTrace();
          mainHandler.post(() -> {
            tvLogOutput.append("日志监听失败: " + e.getMessage() + "\n");
          });
        }
      });
    } else {
      tvLogOutput.append("无法获取应用信息\n");
    }
  }

  /**
   * 停止监听应用日志
   */
  private void stopLogMonitoring() {
    isMonitoring = false;
    if (logcatProcess != null) {
      logcatProcess.destroy();
      logcatProcess = null;
    }
    if (tvLogOutput != null)
      tvLogOutput.append("已停止日志监听\n");
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    stopLogMonitoring();
    executor.shutdown();
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    switchMonitor = null;
    btnClearLog = null;
    tvLogOutput = null;
    scrollView = null;
  }
}
