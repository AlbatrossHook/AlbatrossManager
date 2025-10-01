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
package qing.albatross.manager.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import qing.albatross.manager.R;
import qing.albatross.manager.data.AppInfo;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.AppViewHolder> {
    private List<AppInfo> appList;
    private OnAppClickListener onAppClickListener;

    public interface OnAppClickListener {
        void onAppClick(AppInfo app);
    }

    public AppListAdapter(List<AppInfo> appList, OnAppClickListener listener) {
        this.appList = appList;
        this.onAppClickListener = listener;
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app_list, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppInfo app = appList.get(position);
        holder.bind(app);
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    class AppViewHolder extends RecyclerView.ViewHolder {
        private ImageView ivAppIcon;
        private TextView tvAppName;
        private TextView tvPackageName;
        private TextView tvVersion;
        private TextView tvSystemTag;

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAppIcon = itemView.findViewById(R.id.iv_app_icon);
            tvAppName = itemView.findViewById(R.id.tv_app_name);
            tvPackageName = itemView.findViewById(R.id.tv_package_name);
            tvVersion = itemView.findViewById(R.id.tv_version);
            tvSystemTag = itemView.findViewById(R.id.tv_system_tag);

            itemView.setOnClickListener(v -> {
                if (onAppClickListener != null) {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        onAppClickListener.onAppClick(appList.get(position));
                    }
                }
            });
        }

        public void bind(AppInfo app) {
            ivAppIcon.setImageDrawable(app.getIcon());
            tvAppName.setText(app.getAppName());
            tvPackageName.setText(app.getPackageName());
            tvVersion.setText("v" + app.getVersionName() + " (" + app.getVersionCode() + ")");
            
            // 显示系统应用标签
            if (app.isSystemApp()) {
                tvSystemTag.setVisibility(View.VISIBLE);
                tvSystemTag.setText("系统应用");
            } else {
                tvSystemTag.setVisibility(View.GONE);
            }
        }
    }
}