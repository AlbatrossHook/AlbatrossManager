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

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;


import java.util.List;
import java.util.Set;

import qing.albatross.manager.R;
import qing.albatross.manager.model.AppInfo;

public class PluginListAdapter extends RecyclerView.Adapter<PluginListAdapter.AppViewHolder> {

  final private Context context;
  private List<AppInfo> appList;

  private final Set<AppInfo> toAddPlugins;


  public PluginListAdapter(Context context, List<AppInfo> appList, Set<AppInfo> toAddPlugins) {
    this.context = context;
    this.appList = appList;
    this.toAddPlugins = toAddPlugins;
  }

  @NonNull
  @Override
  public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View view = LayoutInflater.from(context)
        .inflate(R.layout.item_app, parent, false);
    return new AppViewHolder(view);
  }


  private void showPluginActionMenu(AppInfo appInfo, CompoundButton anchorView) {
    try {
      // 使用传入的视图作为锚点
      PopupMenu popup = new PopupMenu(context, anchorView);
      popup.getMenuInflater().inflate(R.menu.app_item_menu, popup.getMenu());

      popup.setOnMenuItemClickListener(item -> {
        int id = item.getItemId();
        if (id == R.id.action_add_anyway) {
          // 强制添加为插件
          toAddPlugins.add(appInfo);
          anchorView.setChecked(true);
          return true;
        } else if (id == R.id.action_cancel) {
          popup.dismiss();
          anchorView.setChecked(false);
          toAddPlugins.remove(appInfo);
          return true;
        }
        return false;
      });
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        popup.setForceShowIcon(true);
      }
      popup.show();
    } catch (Exception e) {
      Log.d("AppListAdapter", "显示菜单失败: " + e.getMessage());
    }
  }

  @Override
  public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
    AppInfo appInfo = appList.get(position);
    if (appInfo == null) return;

    // 设置应用图标和名称
    holder.ivAppIcon.setImageDrawable(appInfo.getAppIcon());
    holder.tvAppName.setText(appInfo.getAppName());

    // 设置包名和版本
    holder.tvPackageName.setText(appInfo.getPackageName());
    holder.tvVersion.setText(appInfo.getVersionName());

    // 标记插件应用
    boolean isPlugin = appInfo.isPlugin();
    holder.tvPluginTag.setVisibility(isPlugin ? View.VISIBLE : View.GONE);

    boolean added = appInfo.isAdded();
    CheckBox cbSelect = holder.cbSelect;
    cbSelect.setChecked(added);
    boolean isEnabled = (!added) && isPlugin;
    cbSelect.setEnabled(isEnabled);
    if (isEnabled) {
      cbSelect.setOnCheckedChangeListener((buttonView, isChecked) -> {
        if (isChecked) {
          toAddPlugins.add(appInfo);
        } else {
          toAddPlugins.remove(appInfo);
        }
      });

    } else {
      if (!isPlugin && !added) {
        holder.itemView.setOnLongClickListener((buttonView) -> {
          if (!toAddPlugins.contains(appInfo)) {
            showPluginActionMenu(appInfo, cbSelect);
          } else {
            toAddPlugins.remove(appInfo);
            cbSelect.setChecked(false);
          }
          return true;
        });
      }
    }
  }

  @Override
  public int getItemCount() {
    return appList == null ? 0 : appList.size();
  }

  /**
   * 更新数据集
   */
  public void updateData(List<AppInfo> newList) {
    this.appList = newList;
    notifyDataSetChanged();
  }

  /**
   * 视图持有者
   */
  static class AppViewHolder extends RecyclerView.ViewHolder {
    ImageView ivAppIcon;
    TextView tvAppName;
    TextView tvPackageName;
    TextView tvVersion;
    TextView tvPluginTag;
    CheckBox cbSelect;

    AppViewHolder(View itemView) {
      super(itemView);
      ivAppIcon = itemView.findViewById(R.id.iv_app_icon);
      tvAppName = itemView.findViewById(R.id.tv_app_name);
      tvPackageName = itemView.findViewById(R.id.tv_package_name);
      tvVersion = itemView.findViewById(R.id.tv_version);
      tvPluginTag = itemView.findViewById(R.id.tv_plugin_tag);
      cbSelect = itemView.findViewById(R.id.cb_select);
    }
  }
}
