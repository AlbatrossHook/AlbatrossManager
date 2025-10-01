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
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import android.widget.Button;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;


import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import qing.albatross.manager.activity.AppDetailActivity;
import qing.albatross.manager.adapter.StorageAdapter;
import qing.albatross.manager.utils.FileUtils;
import qing.albatross.manager.R;
import androidx.recyclerview.widget.RecyclerView;

public class AppStorageFragment extends Fragment {
  private RecyclerView recyclerView;
  private Button btnReadStorage;
  private ProgressBar progressBar;
  private StorageAdapter adapter;
  private ExecutorService executor = Executors.newSingleThreadExecutor();

  public static AppStorageFragment newInstance() {
    return new AppStorageFragment();
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.fragment_app_storage, container, false);
    
    // 初始化视图
    recyclerView = view.findViewById(R.id.recycler_view);
    btnReadStorage = view.findViewById(R.id.btn_read_storage);
    progressBar = view.findViewById(R.id.progress_bar);
    
    return view;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    // 初始化适配器
    adapter = new StorageAdapter();
    recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
    recyclerView.setAdapter(adapter);

    // 读取存储按钮点击事件
    btnReadStorage.setOnClickListener(v -> {
      if (getActivity() instanceof AppDetailActivity) {
        AppDetailActivity parent = (AppDetailActivity) getActivity();
        loadStorageInfo(parent);
      }
    });
  }

  /**
   * 加载应用存储信息
   */
  private void loadStorageInfo(AppDetailActivity parent) {
    progressBar.setVisibility(View.VISIBLE);
    btnReadStorage.setEnabled(false);

    executor.execute(() -> {
      List<File> storageDirs = new ArrayList<>();

      // 应用私有数据目录
      if (parent.getTargetAppInfo().dataDir != null) {
        storageDirs.add(new File(parent.getTargetAppInfo().dataDir));
      }

      // 应用外部存储目录
      File externalFilesDir = requireContext().getExternalFilesDir(null);
      if (externalFilesDir != null) {
        storageDirs.add(externalFilesDir);
      }

      // 应用缓存目录
//      if (parent.getApplicationInfo().cacheDir != null) {
//        storageDirs.add(parent.getApplicationInfo().cacheDir);
//      }

      // 公共外部存储目录
      File publicDir = Environment.getExternalStorageDirectory();
      if (publicDir != null) {
        storageDirs.add(publicDir);
      }

      // 获取目录信息
      List<FileUtils.FileInfo> fileInfos = new ArrayList<>();
      for (File dir : storageDirs) {
        if (dir.exists() && dir.isDirectory()) {
          fileInfos.addAll(FileUtils.getDirectoryInfo(dir));
        }
      }

      // 更新UI
      requireActivity().runOnUiThread(() -> {
        progressBar.setVisibility(View.GONE);
        btnReadStorage.setEnabled(true);

        if (fileInfos.isEmpty()) {
          Toast.makeText(requireContext(), getString(R.string.storage_not_found_message), Toast.LENGTH_SHORT).show();
        } else {
          adapter.setData(fileInfos);
        }
      });
    });
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    executor.shutdown();
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    recyclerView = null;
    btnReadStorage = null;
    progressBar = null;
  }
}
