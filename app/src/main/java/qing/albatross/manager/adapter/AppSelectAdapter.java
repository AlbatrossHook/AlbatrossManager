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

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Point;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import qing.albatross.manager.R;
import qing.albatross.manager.model.AppInfo;

/**
 * 应用选择适配器，用于选择插件生效的应用
 */
public class AppSelectAdapter extends RecyclerView.Adapter<AppSelectAdapter.ViewHolder> {
  private List<AppInfo> appList;
  private final OnAppSelectListener listener;
  private AppActionListener appActionListener;
  private final AppClickListener clickListener;

  public interface OnAppSelectListener {
    void onAppSelected(AppInfo appInfo);
  }



  public interface AppActionListener {
    void inject(AppInfo appInfo);
    void disablePlugin(AppInfo appInfo);
    void closeApp(AppInfo appInfo);
    void openApp(AppInfo appInfo);
  }

  public interface AppClickListener {
    void click(AppInfo appInfo);
  }


  public AppSelectAdapter(OnAppSelectListener listener,AppActionListener appActionListener, AppClickListener clickListener) {
    this.listener = listener;
    this.appActionListener = appActionListener;
    this.clickListener = clickListener;
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View view = LayoutInflater.from(parent.getContext())
        .inflate(R.layout.item_app_select, parent, false);
    return new ViewHolder(view);
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
    AppInfo appInfo = appList.get(position);
    if (appInfo == null) return;

    holder.ivIcon.setImageDrawable(appInfo.getAppIcon());
    holder.tvName.setText(appInfo.getAppName());
    holder.tvPackage.setText(appInfo.getPackageName());
    CheckBox cbSelect = holder.cbSelect;
    boolean selected = appInfo.isSelected();
    cbSelect.setChecked(selected);
    Context context = holder.itemView.getContext();

    // 点击复选框切换选择状态
    cbSelect.setOnClickListener(v -> {
      if (!appInfo.isSelected()) {
        new AlertDialog.Builder(v.getContext())
            .setTitle("注入确认")
            .setMessage(context.getString(R.string.injection_warning_message))
            .setPositiveButton("确认", (dialog, which) -> {
              listener.onAppSelected(appInfo);
            })
            .setNegativeButton("取消", (d, which) -> {
              cbSelect.setChecked(false);
            }).show();
      } else
        listener.onAppSelected(appInfo);

    });
    if (appActionListener == null) {
      holder.tvMoreActions.setVisibility(View.GONE);
    } else {
      holder.tvMoreActions.setVisibility(View.VISIBLE);
      holder.tvMoreActions.setOnClickListener(v -> {
        showAppActionsMenu(v, appInfo);
      });
    }
    if (appInfo.isSystem) {
      holder.tvTag.setVisibility(View.VISIBLE);
      holder.tvTag.setText("sys");
    } else if (appInfo.isPlugin) {
      holder.tvTag.setVisibility(View.VISIBLE);
      holder.tvTag.setText("plugin");
    } else {
      holder.tvTag.setVisibility(View.GONE);
    }
    holder.ivIcon.setOnClickListener((v) -> {
      clickListener.click(appInfo);

    });
  }

  @Override
  public int getItemCount() {
    return appList == null ? 0 : appList.size();
  }

  public void setAppList(List<AppInfo> appList) {
    this.appList = appList;
    notifyDataSetChanged();
  }

  private void showAppActionsMenu(View anchorView, AppInfo appInfo) {
    Context context = anchorView.getContext();
    View menuView = LayoutInflater.from(context).inflate(R.layout.popup_menu_app_actions, null);
    
    // 获取菜单项
    LinearLayout itemInject = menuView.findViewById(R.id.item_inject);
    LinearLayout itemUnload = menuView.findViewById(R.id.item_unload);
    LinearLayout itemDisable = menuView.findViewById(R.id.item_disable);
    LinearLayout itemOpen = menuView.findViewById(R.id.item_open);
    
    // 设置点击事件
    itemInject.setOnClickListener(v -> {
      if (appActionListener != null) {
        appActionListener.inject(appInfo);
      }
    });
    
    itemUnload.setOnClickListener(v -> {
      if (appActionListener != null) {
        appActionListener.disablePlugin(appInfo);
      }
    });
    
    itemDisable.setOnClickListener(v -> {
      if (appActionListener != null) {
        appActionListener.closeApp(appInfo);
      }
    });
    
    itemOpen.setOnClickListener(v -> {
      if (appActionListener != null) {
        appActionListener.openApp(appInfo);
      }
    });
    
    // 创建弹出窗口
    PopupWindow popupWindow = new PopupWindow(menuView,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        true);
    popupWindow.setBackgroundDrawable(context.getResources().getDrawable(android.R.drawable.dialog_holo_light_frame));
    popupWindow.setElevation(8f);
    
    // 智能显示菜单
    showPopupMenuWithPositioning(context, popupWindow, anchorView);
  }

  /**
   * 智能显示弹出菜单，根据屏幕位置调整显示方向
   */
  private void showPopupMenuWithPositioning(Context context, PopupWindow popupWindow, View anchorView) {
    // 获取屏幕尺寸
    WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    Point screenSize = new Point();
    windowManager.getDefaultDisplay().getSize(screenSize);
    int screenHeight = screenSize.y;
    
    // 获取锚点视图在屏幕中的位置
    int[] anchorLocation = new int[2];
    anchorView.getLocationOnScreen(anchorLocation);
    int anchorY = anchorLocation[1];
    int anchorHeight = anchorView.getHeight();
    
    // 获取弹出菜单的实际高度
    View menuView = popupWindow.getContentView();
    menuView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    int menuHeight = menuView.getMeasuredHeight();
    
    // 如果测量失败，使用估算值
    if (menuHeight <= 0) {
      // 估算弹出菜单的高度（根据菜单项数量）
      float density = context.getResources().getDisplayMetrics().density;
      menuHeight = (int) ((4 * 48 + 16) * density); // 4个菜单项 * 48dp + padding
    }
    
    // 计算锚点下方剩余空间
    int spaceBelow = screenHeight - anchorY - anchorHeight;
    
    // 添加一些边距，确保菜单不会紧贴屏幕边缘
    int margin = (int) (16 * context.getResources().getDisplayMetrics().density);
    
    // 如果下方空间不足，则向上显示
    if (spaceBelow < menuHeight + margin) {
      // 向上显示，计算Y偏移量
      int yOffset = -menuHeight;
      popupWindow.showAsDropDown(anchorView, 0, yOffset);
    } else {
      // 向下显示（默认行为）
      popupWindow.showAsDropDown(anchorView, 0, 0);
    }
  }

  public static class ViewHolder extends RecyclerView.ViewHolder {
    ImageView ivIcon;
    TextView tvName;
    TextView tvPackage;
    TextView tvTag;
    TextView tvMoreActions;
    CheckBox cbSelect;

    public ViewHolder(View itemView) {
      super(itemView);
      ivIcon = itemView.findViewById(R.id.iv_app_icon);
      tvName = itemView.findViewById(R.id.tv_app_name);
      tvPackage = itemView.findViewById(R.id.tv_package_name);
      cbSelect = itemView.findViewById(R.id.cb_select);
      tvTag = itemView.findViewById(R.id.tv_app_tag);
      tvMoreActions = itemView.findViewById(R.id.tv_more_actions);
    }
  }
}
