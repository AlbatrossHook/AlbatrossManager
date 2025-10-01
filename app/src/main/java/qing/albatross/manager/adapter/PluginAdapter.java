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
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import qing.albatross.manager.R;
import qing.albatross.manager.data.Plugin;

public class PluginAdapter extends RecyclerView.Adapter<PluginAdapter.PluginViewHolder> {

  private List<Plugin> plugins;
  private OnToggleListener toggleListener;
  private OnDeleteListener deleteListener;
  private OnClickListener clickListener;

  public interface OnToggleListener {
    void onToggle(Plugin plugin);
  }

  public interface OnDeleteListener {
    void onDelete(Plugin plugin);
  }

  public interface OnClickListener {
    void onClick(Plugin plugin);
  }


  public PluginAdapter(OnToggleListener toggleListener, OnDeleteListener deleteListener, OnClickListener clickListener) {
    this.toggleListener = toggleListener;
    this.deleteListener = deleteListener;
    this.clickListener = clickListener;
  }

  public void setPlugins(List<Plugin> plugins) {
    this.plugins = plugins;
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public PluginViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View view = LayoutInflater.from(parent.getContext())
        .inflate(R.layout.plugin_item, parent, false);
    return new PluginViewHolder(view);
  }

  @Override
  public void onBindViewHolder(@NonNull PluginViewHolder holder, int position) {
    Plugin plugin = plugins.get(position);
    holder.tvName.setText(plugin.getName());
    holder.tvPackage.setText(plugin.getPackageName());
    holder.tvDescription.setText(plugin.getDescription());
    holder.swEnabled.setChecked(plugin.isEnabled());
    holder.swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
      if (buttonView.isPressed()) { // 避免刷新时触发
        toggleListener.onToggle(plugin);
      }
    });
    holder.btnDelete.setOnClickListener(v -> deleteListener.onDelete(plugin));
    holder.itemView.setOnClickListener(v-> clickListener.onClick(plugin));

  }

  @Override
  public int getItemCount() {
    return plugins == null ? 0 : plugins.size();
  }

  static class PluginViewHolder extends RecyclerView.ViewHolder {
    TextView tvName;
    TextView tvPackage;
    TextView tvDescription;
    Switch swEnabled;
    TextView btnDelete;

    PluginViewHolder(View itemView) {
      super(itemView);
      tvName = itemView.findViewById(R.id.tv_plugin_name);
      tvPackage = itemView.findViewById(R.id.tv_plugin_package);
      tvDescription = itemView.findViewById(R.id.tv_plugin_description);
      swEnabled = itemView.findViewById(R.id.sw_plugin_enabled);
      btnDelete = itemView.findViewById(R.id.btn_delete_plugin);
    }
  }
}
