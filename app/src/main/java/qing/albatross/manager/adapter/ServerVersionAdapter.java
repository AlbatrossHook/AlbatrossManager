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
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;


import java.util.List;

import qing.albatross.manager.R;
import qing.albatross.manager.model.ServerInfo;

public class ServerVersionAdapter extends RecyclerView.Adapter<ServerVersionAdapter.VersionViewHolder> {

  private List<ServerInfo> serverVersions;
  private String currentVersion;
  private OnVersionActionListener listener;

  public interface OnVersionActionListener {
    void onVersionSwitch(ServerInfo serverInfo);

    void onVersionDelete(ServerInfo serverInfo);

    void onShowMoreDescription(ServerInfo serverInfo); // 新增：查看完整描述
  }

  public ServerVersionAdapter(OnVersionActionListener listener) {
    this.listener = listener;
  }

  public void setServerVersions(List<ServerInfo> versions, String current) {
    this.serverVersions = versions;
    this.currentVersion = current;
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public VersionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View view = LayoutInflater.from(parent.getContext())
        .inflate(R.layout.item_server_version, parent, false);
    return new VersionViewHolder(view);
  }

  @Override
  public void onBindViewHolder(@NonNull VersionViewHolder holder, int position) {
    ServerInfo serverInfo = serverVersions.get(position);
    if (serverInfo == null) return;

    // 版本信息
    holder.tvVersion.setText(serverInfo.getVersion());

    // 架构信息
    StringBuilder archInfo = new StringBuilder();
    archInfo.append("架构: ").append(serverInfo.getPrimaryArchitecture());
    if (serverInfo.isSupport32Bit()) {
      archInfo.append(" (支持32位)");
    }
    holder.tvArchitecture.setText(archInfo.toString());

    // 新增：描述信息处理
    String description = serverInfo.getDescription();
    holder.tvDescription.setText(description);

    // 如果描述过长，显示"查看全部"按钮
    if (description != null && description.length() > 100) { // 超过100字符视为长描述
      holder.btnShowMore.setVisibility(View.VISIBLE);
      holder.btnShowMore.setOnClickListener(v -> {
        if (listener != null) {
          listener.onShowMoreDescription(serverInfo);
        }
      });
    } else {
      holder.btnShowMore.setVisibility(View.GONE);
    }

    // 标记当前使用的版本
    boolean isCurrent = serverInfo.getVersion().equals(currentVersion);
    holder.tvCurrent.setVisibility(isCurrent ? View.VISIBLE : View.GONE);

    // 按钮状态
    holder.btnSwitch.setEnabled(!isCurrent);
    holder.btnSwitch.setOnClickListener(v -> {
      if (listener != null) {
        listener.onVersionSwitch(serverInfo);
      }
    });

    holder.btnDelete.setOnClickListener(v -> {
      if (listener != null) {
        listener.onVersionDelete(serverInfo);
      }
    });
  }

  @Override
  public int getItemCount() {
    return serverVersions == null ? 0 : serverVersions.size();
  }

  static class VersionViewHolder extends RecyclerView.ViewHolder {
    TextView tvVersion;
    TextView tvArchitecture;
    TextView tvCurrent;
    TextView tvDescription; // 新增：描述信息
    Button btnShowMore; // 新增：查看更多按钮
    Button btnSwitch;
    Button btnDelete;

    VersionViewHolder(View itemView) {
      super(itemView);
      tvVersion = itemView.findViewById(R.id.tv_version);
      tvArchitecture = itemView.findViewById(R.id.tv_architecture);
      tvCurrent = itemView.findViewById(R.id.tv_current);
      tvDescription = itemView.findViewById(R.id.tv_description); // 初始化描述控件
      btnShowMore = itemView.findViewById(R.id.btn_show_more); // 初始化查看更多按钮
      btnSwitch = itemView.findViewById(R.id.btn_switch);
      btnDelete = itemView.findViewById(R.id.btn_delete);
    }
  }
}
