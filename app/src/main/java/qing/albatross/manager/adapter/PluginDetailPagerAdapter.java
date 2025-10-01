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

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import qing.albatross.manager.data.Plugin;
import qing.albatross.manager.fragments.PluginConfigFragment;
import qing.albatross.manager.fragments.PluginLogFragment;
import qing.albatross.manager.fragments.PluginTargetAppsFragment;


public class PluginDetailPagerAdapter extends FragmentStateAdapter {
  private final Plugin plugin;

  public PluginDetailPagerAdapter(@NonNull FragmentActivity fragmentActivity, Plugin plugin) {
    super(fragmentActivity);
    this.plugin = plugin;
  }

  @NonNull
  @Override
  public Fragment createFragment(int position) {
    switch (position) {
      case 0:
        return PluginConfigFragment.newInstance(plugin);
      case 1:
        return PluginTargetAppsFragment.newInstance(plugin);
      case 2:
        return PluginLogFragment.newInstance(plugin.getPackageName());
      default:
        return PluginConfigFragment.newInstance(plugin);
    }
  }

  @Override
  public int getItemCount() {
    return 3; // 三个标签页
  }
}
