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

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import qing.albatross.manager.R;
import qing.albatross.manager.activity.AppDetailActivity;
import qing.albatross.manager.activity.MainActivity;
import qing.albatross.manager.adapter.AppListAdapter;
import qing.albatross.manager.data.AppInfo;

public class AppListFragment extends Fragment {
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private View layoutEmpty;
    private AppListAdapter adapter;
    private List<AppInfo> appList;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_app_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 初始化视图
        recyclerView = view.findViewById(R.id.recycler_view_apps);
        progressBar = view.findViewById(R.id.progress_bar);
        layoutEmpty = view.findViewById(R.id.layout_empty);
        
        // 设置RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        
        // 初始化应用列表
        appList = new ArrayList<>();
        adapter = new AppListAdapter(appList, this::onAppClick);
        recyclerView.setAdapter(adapter);
        
        // 加载应用列表
        loadAppList();
    }

    private void loadAppList() {
        progressBar.setVisibility(View.VISIBLE);
        
        // 在后台线程加载应用列表
        new Thread(() -> {
            List<AppInfo> apps = getInstalledApps();
            
            // 切换到主线程更新UI
            MainActivity activity = (MainActivity) getActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    appList.clear();
                    appList.addAll(apps);
                    adapter.notifyDataSetChanged();
                    progressBar.setVisibility(View.GONE);
                    
                    // 更新应用数量
                    activity.updateAppCount(apps.size());
                    
                    // 显示/隐藏空状态
                    if (apps.isEmpty()) {
                        layoutEmpty.setVisibility(View.VISIBLE);
                        recyclerView.setVisibility(View.GONE);
                    } else {
                        layoutEmpty.setVisibility(View.GONE);
                        recyclerView.setVisibility(View.VISIBLE);
                    }
                });
            }
        }).start();
    }

    private List<AppInfo> getInstalledApps() {
        List<AppInfo> apps = new ArrayList<>();
        PackageManager pm = getContext().getPackageManager();
        
        try {
            List<PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_META_DATA);
            for (PackageInfo packageInfo : packages) {
                ApplicationInfo appInfo = packageInfo.applicationInfo;
                // 过滤掉系统应用（可选）
                if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    AppInfo app = new AppInfo();
                    app.setPackageName(packageInfo.packageName);
                    app.setAppName(pm.getApplicationLabel(appInfo).toString());
                    app.setVersionName(packageInfo.versionName);
                    app.setVersionCode(packageInfo.versionCode);
                    app.setIcon(pm.getApplicationIcon(appInfo));
                    app.setInstallTime(packageInfo.firstInstallTime);
                    app.setUpdateTime(packageInfo.lastUpdateTime);
                    app.setSystemApp((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    apps.add(app);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), getString(R.string.load_app_list_failed_format, e.getMessage()), Toast.LENGTH_SHORT).show();
                });
            }
        }
        
        return apps;
    }

    private void onAppClick(AppInfo app) {
        // 启动AppDetailActivity
        Intent intent = new Intent(getContext(), AppDetailActivity.class);
        intent.putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME, app.getPackageName());
        startActivity(intent);
    }

//    public void refreshAppList() {
//        loadAppList();
//    }
}
