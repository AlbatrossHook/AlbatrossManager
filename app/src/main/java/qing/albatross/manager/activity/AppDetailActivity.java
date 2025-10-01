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
package qing.albatross.manager.activity;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.google.android.material.tabs.TabLayoutMediator;

import com.google.android.material.tabs.TabLayout;

import androidx.viewpager2.widget.ViewPager2;

import qing.albatross.manager.R;
import qing.albatross.manager.fragments.AppComponentsFragment;
import qing.albatross.manager.fragments.AppInfoFragment;
import qing.albatross.manager.fragments.AppLogFragment;
import qing.albatross.manager.fragments.AppStorageFragment;

public class AppDetailActivity extends AppCompatActivity {
  public static final String EXTRA_PACKAGE_NAME = "package_name";
  private TabLayout tabLayout;
  private ViewPager2 viewPager;
  private String packageName;
  private PackageInfo packageInfo;
  private ApplicationInfo applicationInfo;

  // Tab标题
  private String[] tabTitles;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_app_detail);

    // 初始化视图
    tabLayout = findViewById(R.id.tab_layout);
    viewPager = findViewById(R.id.view_pager);

    // 获取传递的包名
    packageName = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
    if (packageName == null || packageName.isEmpty()) {
      Toast.makeText(this, getString(R.string.invalid_app_info), Toast.LENGTH_SHORT).show();
      finish();
      return;
    }

    // 初始化tab标题
    tabTitles = new String[]{
        getString(R.string.app_info),
        getString(R.string.app_components),
        getString(R.string.app_storage),
        getString(R.string.app_log)
    };

    // 获取应用信息
    try {
      packageInfo = getPackageManager().getPackageInfo(packageName,
          PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES |
              PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS |
              PackageManager.GET_META_DATA | PackageManager.GET_SIGNATURES);
      applicationInfo = packageInfo.applicationInfo;
    } catch (PackageManager.NameNotFoundException e) {
      e.printStackTrace();
      Toast.makeText(this, getString(R.string.cannot_get_app_info), Toast.LENGTH_SHORT).show();
      finish();
      return;
    }
    // 配置ActionBar
//    setSupportActionBar(binding.toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
      try {
        String appName = getPackageManager().getApplicationLabel(applicationInfo).toString();
        getSupportActionBar().setTitle(appName);
      } catch (Exception e) {
        getSupportActionBar().setTitle(packageName);
      }
    }

    // 初始化ViewPager
    viewPager.setAdapter(new AppDetailPagerAdapter(this));
    viewPager.setOffscreenPageLimit(3);
    // 关联TabLayout和ViewPager
    new TabLayoutMediator(tabLayout, viewPager,
        (tab, position) -> tab.setText(tabTitles[position])).attach();
  }


  private class AppDetailPagerAdapter extends FragmentStateAdapter {
    public AppDetailPagerAdapter(@NonNull AppCompatActivity activity) {
      super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
      switch (position) {
        case 0:
          return AppInfoFragment.newInstance(packageName);
        case 1:
          return AppComponentsFragment.newInstance(packageName);
        case 2:
          return AppStorageFragment.newInstance();
        case 3:
          return AppLogFragment.newInstance();
        default:
          return AppInfoFragment.newInstance(packageName);
      }
    }

    @Override
    public int getItemCount() {
      return tabTitles.length;
    }
  }

  public PackageInfo getPackageInfo() {
    return packageInfo;
  }

  public ApplicationInfo getTargetAppInfo() {
    return applicationInfo;
  }

  public String getTargetPackage() {
    return packageName;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }
}
