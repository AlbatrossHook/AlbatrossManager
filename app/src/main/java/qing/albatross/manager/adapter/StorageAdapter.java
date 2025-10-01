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

import qing.albatross.manager.R;
import qing.albatross.manager.utils.FileUtils;

public class StorageAdapter extends RecyclerView.Adapter<StorageAdapter.StorageViewHolder> {
  private List<FileUtils.FileInfo> fileInfos = new ArrayList<>();

  public void setData(List<FileUtils.FileInfo> data) {
    fileInfos.clear();
    fileInfos.addAll(data);
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public StorageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View view = LayoutInflater.from(parent.getContext())
        .inflate(R.layout.item_storage, parent, false);
    return new StorageViewHolder(view);
  }

  @Override
  public void onBindViewHolder(@NonNull StorageViewHolder holder, int position) {
    FileUtils.FileInfo fileInfo = fileInfos.get(position);

    // 设置图标
    holder.ivIcon.setText(fileInfo.isDirectory ? "📁" : "📄");

    // 设置文件名
    holder.tvFileName.setText(fileInfo.name);

    // 设置文件大小和修改时间
    String detail = fileInfo.isDirectory ?
        "目录 | " + fileInfo.childCount + " 个项目" :
        fileInfo.size + " | 修改于 " + fileInfo.modifiedTime;
    holder.tvFileDetail.setText(detail);

    // 根据深度设置缩进
    int paddingLeft = fileInfo.depth * 32;
    holder.itemView.setPadding(paddingLeft, 8, 16, 8);
  }

  @Override
  public int getItemCount() {
    return fileInfos.size();
  }

  static class StorageViewHolder extends RecyclerView.ViewHolder {
    TextView ivIcon;
    TextView tvFileName;
    TextView tvFileDetail;

    StorageViewHolder(View itemView) {
      super(itemView);
      ivIcon = itemView.findViewById(R.id.iv_icon);
      tvFileName = itemView.findViewById(R.id.tv_file_name);
      tvFileDetail = itemView.findViewById(R.id.tv_file_detail);
    }
  }
}
