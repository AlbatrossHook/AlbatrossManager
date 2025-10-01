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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;


import java.util.ArrayList;
import java.util.List;

import qing.albatross.manager.R;
import qing.albatross.manager.utils.LogReader;

public class PluginLogFragment extends Fragment {
  private static final String ARG_PLUGIN_PACKAGE = "plugin_package";
  private String pluginPackage;
  private TextView tvLogContent;
  private Handler handler = new Handler(Looper.getMainLooper());
  private LogReader logReader;
  private boolean isReading = false;

  public static PluginLogFragment newInstance(String pluginPackage) {
    PluginLogFragment fragment = new PluginLogFragment();
    Bundle args = new Bundle();
    args.putString(ARG_PLUGIN_PACKAGE, pluginPackage);
    fragment.setArguments(args);
    return fragment;
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (getArguments() != null) {
      pluginPackage = getArguments().getString(ARG_PLUGIN_PACKAGE);
    }
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
                           Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_plugin_log, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    tvLogContent = view.findViewById(R.id.tv_log_content);

    // 初始化日志读取器
    logReader = new LogReader(pluginPackage);

    // 开始读取日志
    startReadingLogs();
  }

  private void startReadingLogs() {
    isReading = true;
    new Thread(() -> {
      while (isReading && isAdded()) {
        // 读取最新日志
        List<String> newLogs = logReader.readNewLogs();
        if (!newLogs.isEmpty()) {
          updateLogView(newLogs);
        }

        // 休眠一段时间再读取
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }).start();
  }

  private void updateLogView(List<String> newLogs) {
    handler.post(() -> {
      // 保留一定数量的历史日志
      String currentLog = tvLogContent.getText().toString();
      List<String> allLines = new ArrayList<>(List.of(currentLog.split("\n")));
      // 添加新日志
      allLines.addAll(newLogs);
      // 如果日志太多，移除最早的部分
      if (allLines.size() > 1000) {
        allLines = allLines.subList(allLines.size() - 1000, allLines.size());
      }
      // 更新显示
      tvLogContent.setText(String.join("\n", allLines));
      // 滚动到底部
      tvLogContent.scrollTo(0, tvLogContent.getBottom());
    });
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    isReading = false;
    handler.removeCallbacksAndMessages(null);
  }
}
