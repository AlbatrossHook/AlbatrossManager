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

import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import qing.albatross.manager.activity.AppDetailActivity;
import qing.albatross.manager.adapter.ComponentAdapter;
import qing.albatross.manager.R;
import androidx.recyclerview.widget.RecyclerView;

public class AppComponentsFragment extends Fragment {
  private static final String ARG_PACKAGE_NAME = "package_name";
  private RecyclerView recyclerView;
  private TextView tvMetaData;
  private ComponentAdapter adapter;
  private final Map<String, List<String>> componentsMap = new HashMap<>();
  private String packageName;

  public static AppComponentsFragment newInstance(String packageName) {
    AppComponentsFragment fragment = new AppComponentsFragment();
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
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.fragment_app_components, container, false);
    
    // 初始化视图
    recyclerView = view.findViewById(R.id.recycler_view);
    tvMetaData = view.findViewById(R.id.tv_meta_data);
    
    return view;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    // 初始化RecyclerView
    adapter = new ComponentAdapter();
    recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
    recyclerView.setAdapter(adapter);

    // 加载组件信息
    if (getActivity() instanceof AppDetailActivity) {
      AppDetailActivity activity = (AppDetailActivity) getActivity();
      loadComponents(activity);
      loadMetaData(activity);
    }
  }

  /**
   * 加载应用组件信息
   */
  private void loadComponents(AppDetailActivity activity) {
    // 活动(Activity)
    List<String> activities = new ArrayList<>();
    PackageInfo packageInfo = activity.getPackageInfo();
    if (packageInfo.activities != null) {
      for (ActivityInfo activityInfo : packageInfo.activities) {
        activities.add(activityInfo.name);
      }
    }
    componentsMap.put("活动(Activity)", activities);

    // 服务(Service)
    List<String> services = new ArrayList<>();
    if (packageInfo.services != null) {
      for (ServiceInfo serviceInfo : packageInfo.services) {
        services.add(serviceInfo.name);
      }
    }
    componentsMap.put("服务(Service)", services);

    // 接收器(Receiver)
    List<String> receivers = new ArrayList<>();
    if (packageInfo.receivers != null) {
      for (ComponentInfo receiverInfo : packageInfo.receivers) {
        receivers.add(receiverInfo.name);
      }
    }
    componentsMap.put("接收器(Receiver)", receivers);

    // 内容提供者(Provider)
    List<String> providers = new ArrayList<>();
    if (packageInfo.providers != null) {
      for (ProviderInfo providerInfo : packageInfo.providers) {
        providers.add(providerInfo.name);
      }
    }
    componentsMap.put("内容提供者(Provider)", providers);

    adapter.setData(componentsMap);
  }

  /**
   * 加载Meta-Data信息
   */
  private void loadMetaData(AppDetailActivity activity) {
    if (activity.getTargetAppInfo().metaData != null) {
      StringBuilder metaDataStr = new StringBuilder();
      for (String key : activity.getTargetAppInfo().metaData.keySet()) {
        Object value = activity.getTargetAppInfo().metaData.get(key);
        metaDataStr.append(key).append(": ").append(value).append("\n");
      }
      tvMetaData.setText(metaDataStr.toString());
    } else {
      tvMetaData.setText("无Meta-Data信息");
    }
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    recyclerView = null;
    tvMetaData = null;
  }
}
