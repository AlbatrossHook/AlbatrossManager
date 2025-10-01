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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import qing.albatross.manager.R;

public class ComponentAdapter extends RecyclerView.Adapter<ComponentAdapter.ComponentViewHolder> {
  private List<Map.Entry<String, List<String>>> components = new ArrayList<>();

  public void setData(Map<String, List<String>> data) {
    components.clear();
    components.addAll(data.entrySet());
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public ComponentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View view = LayoutInflater.from(parent.getContext())
        .inflate(R.layout.item_component_group, parent, false);
    return new ComponentViewHolder(view);
  }

  @Override
  public void onBindViewHolder(@NonNull ComponentViewHolder holder, int position) {
    Map.Entry<String, List<String>> entry = components.get(position);
    String groupName = entry.getKey();
    List<String> componentList = entry.getValue();

    // 设置组名称
    holder.tvGroupName.setText(groupName + " (" + componentList.size() + ")");

    // 设置组件列表
    StringBuilder componentsStr = new StringBuilder();
    for (String component : componentList) {
      componentsStr.append(component).append("\n");
    }
    holder.tvComponents.setText(componentsStr.toString().trim());
  }

  @Override
  public int getItemCount() {
    return components.size();
  }

  static class ComponentViewHolder extends RecyclerView.ViewHolder {
    TextView tvGroupName;
    TextView tvComponents;

    ComponentViewHolder(View itemView) {
      super(itemView);
      tvGroupName = itemView.findViewById(R.id.tv_group_name);
      tvComponents = itemView.findViewById(R.id.tv_components);
    }
  }
}
