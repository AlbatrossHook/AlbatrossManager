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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import qing.albatross.manager.R;
import qing.albatross.manager.adapter.ServerVersionAdapter;
import qing.albatross.manager.data.ConfigManager;
import qing.albatross.manager.data.ServerDatabaseHelper;
import qing.albatross.manager.model.ServerInfo;
import qing.albatross.manager.utils.ArchitectureUtils;
import qing.albatross.manager.utils.FileUtils;
import qing.albatross.manager.utils.ServerManager;
import qing.albatross.manager.utils.ZipExtractor;

public class SettingsFragment extends Fragment implements ServerVersionAdapter.OnVersionActionListener {

  private static final int REQUEST_CODE_SELECT_ZIP = 1002;
  private RecyclerView rvServerVersions;
  private ServerVersionAdapter versionAdapter;
  private LinearLayout tvNoVersions;
  private EditText etRootPath;
  private Button btnSaveRootPath;
  private ServerDatabaseHelper dbHelper;
  private ExecutorService executor = Executors.newSingleThreadExecutor();
  private String currentVersion;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_settings, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    // 初始化数据库
    dbHelper = ServerDatabaseHelper.getInstance(requireContext());
    // 初始化视图
    rvServerVersions = view.findViewById(R.id.rv_server_versions);
    tvNoVersions = view.findViewById(R.id.tv_no_versions);
    etRootPath = view.findViewById(R.id.et_root_path);
    EditText etServerAddress = view.findViewById(R.id.et_address_path);
    TextView etSuPath = view.findViewById(R.id.et_su_path);
    btnSaveRootPath = view.findViewById(R.id.btn_save_root_path);
    Button btnImportServer = view.findViewById(R.id.btn_import_server);
    Button btnSuSave = view.findViewById(R.id.btn_save_su_path);
    Button btnSaveAddress = view.findViewById(R.id.btn_save_server_address);

    ConfigManager configManager = ConfigManager.getInstance(getContext());
    etServerAddress.setText(configManager.getServerAddress());

    // 初始化列表
    rvServerVersions.setLayoutManager(new LinearLayoutManager(requireContext()));
    versionAdapter = new ServerVersionAdapter(this);
    rvServerVersions.setAdapter(versionAdapter);

    // 加载服务版本列表
    loadServerVersions();

    String suPath = configManager.getSuFilePath();
    etSuPath.setText(suPath);
    // 加载Root路径配置
    loadRootPath();
    // 导入服务按钮点击事件
    btnImportServer.setOnClickListener(v -> {
      selectServerZip();
    });
    btnSuSave.setOnClickListener(v -> {
      String string = etSuPath.getText().toString().trim();
      if (string.length() > 1)
        configManager.saveSuFilePath(string);
      else
        Toast.makeText(requireContext(), getString(R.string.enter_correct_su_path), Toast.LENGTH_SHORT).show();
    });
    btnSaveAddress.setOnClickListener(v -> {
      String address = etServerAddress.getText().toString().trim();
      if (address.length() > 1) {
        configManager.saveServerAddress(address);
      } else {
        Toast.makeText(requireContext(), getString(R.string.enter_correct_server_path), Toast.LENGTH_SHORT).show();
      }
    });

    // 保存Root路径
    btnSaveRootPath.setOnClickListener(v -> {
      String rootPath = etRootPath.getText().toString().trim();
      if (rootPath.isEmpty()) {
        Toast.makeText(requireContext(), getString(R.string.enter_root_path_message), Toast.LENGTH_SHORT).show();
        return;
      }

      if (!rootPath.endsWith("/")) {
        rootPath += "/";
      }

      String finalRootPath = rootPath;
      executor.execute(() -> {
        try {
          // 确保数据库连接正常
          dbHelper.ensureDatabaseOpen();
          
          dbHelper.saveRootPath(finalRootPath);
          requireActivity().runOnUiThread(() ->
              Toast.makeText(requireContext(), getString(R.string.root_path_saved_success), Toast.LENGTH_SHORT).show()
          );
        } catch (Exception e) {
          e.printStackTrace();
          requireActivity().runOnUiThread(() ->
              Toast.makeText(requireContext(), "保存路径失败: " + e.getMessage(), Toast.LENGTH_SHORT).show()
          );
        }
      });
    });
  }

  /**
   * 选择服务ZIP包
   */
  private void selectServerZip() {
    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
    intent.setType("application/zip");
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    filePickerLauncher.launch(intent);
  }


  ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(),
      result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
          Uri uri = result.getData().getData();
          if (uri != null) {
            processServerZip(uri);
          }
        }
      }
  );


  /**
   * 加载服务版本列表
   */
  private void loadServerVersions() {
    executor.execute(() -> {
      try {
        // 确保数据库连接正常
        dbHelper.ensureDatabaseOpen();
        
        List<ServerInfo> serverVersions = dbHelper.getAllServerVersions();
        currentVersion = dbHelper.getCurrentServerVersion();

        requireActivity().runOnUiThread(() -> {
          if (serverVersions.isEmpty()) {
            rvServerVersions.setVisibility(View.GONE);
            tvNoVersions.setVisibility(View.VISIBLE);
          } else {
            rvServerVersions.setVisibility(View.VISIBLE);
            tvNoVersions.setVisibility(View.GONE);
            versionAdapter.setServerVersions(serverVersions, currentVersion);
          }
        });
      } catch (Exception e) {
        e.printStackTrace();
        requireActivity().runOnUiThread(() -> {
          Toast.makeText(requireContext(), "加载服务版本失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        });
      }
    });
  }

  /**
   * 加载Root路径配置
   */
  private void loadRootPath() {
    executor.execute(() -> {
      try {
        // 确保数据库连接正常
        dbHelper.ensureDatabaseOpen();
        
        String rootPath = dbHelper.getRootPath();
        if (rootPath == null || rootPath.isEmpty()) {
          rootPath = "/data/local/tmp/"; // 默认路径
        }
        String finalRootPath = rootPath;
        requireActivity().runOnUiThread(() -> {
          etRootPath.setText(finalRootPath);
        });
      } catch (Exception e) {
        e.printStackTrace();
        requireActivity().runOnUiThread(() -> {
          etRootPath.setText("/data/local/tmp/"); // 使用默认路径
        });
      }
    });
  }

  /**
   * 处理选中的服务ZIP包
   */
  private void processServerZip(Uri zipUri) {
    // 显示加载对话框
    AlertDialog progressDialog = new AlertDialog.Builder(requireContext())
        .setMessage(getString(R.string.processing_server_components))
        .setCancelable(false)
        .create();
    progressDialog.show();

    executor.execute(() -> {
      try {
        // 创建临时解压目录
        File tempDir = new File(requireContext().getCacheDir(), "server_temp");
        FileUtils.deleteDirectory(tempDir);
        FileUtils.createDirectory(tempDir);

        // 解压ZIP文件
        boolean extractSuccess = ZipExtractor.extract(
            requireContext(),
            zipUri,
            tempDir.getAbsolutePath()
        );

        if (!extractSuccess) {
          showError(getString(R.string.zip_extract_failed_message));
          return;
        }
        File resourceDir = new File(tempDir, "resource");
        if (resourceDir.exists()) {
          tempDir = resourceDir;
        }
        // 读取版本信息
        File versionFile = new File(tempDir, "version");
        if (!versionFile.exists()) {
          showError(getString(R.string.zip_incomplete_message));
          return;
        }

        String version = FileUtils.readFileToString(versionFile);
        if (version == null || version.isEmpty()) {
          showError(getString(R.string.version_read_failed_message));
          return;
        }

        // 新增：读取描述信息
        String description = "";
        File descriptionFile = new File(tempDir, "description");
        if (descriptionFile.exists()) {
          description = FileUtils.readFileToString(descriptionFile);
          // 如果描述为空，设置默认文本
          if (description == null) description = getString(R.string.no_description_info);
        } else {
          description = getString(R.string.no_description_info);
        }

        // 检查版本是否已存在
        boolean versionExists = dbHelper.checkVersionExists(version);

        if (versionExists) {
          // 提示用户是否覆盖
          String finalDescription = description;
          File finalTempDir = tempDir;
          requireActivity().runOnUiThread(() -> {
            progressDialog.dismiss();
            new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.version_exists_title))
                .setMessage(getString(R.string.version_exists_message_format, version))
                .setPositiveButton(getString(R.string.overwrite_button), (dialog, which) -> {
                  saveServerVersion(finalTempDir, version, finalDescription, true);
                })
                .setNegativeButton(getString(R.string.cancel_button), null)
                .show();
          });
        } else {
          // 直接保存新版本
          saveServerVersion(tempDir, version, description, false);
          requireActivity().runOnUiThread(progressDialog::dismiss);
        }

      } catch (Exception e) {
        e.printStackTrace();
        showError(getString(R.string.processing_failed_format, e.getMessage()));
      } finally {
        if (progressDialog.isShowing()) {
          requireActivity().runOnUiThread(progressDialog::dismiss);
        }
      }
    });
  }


  /**
   * 保存服务版本
   */
  private void saveServerVersion(File tempDir, String version, String description, boolean overwrite) {
    executor.execute(() -> {
      try {
        // 获取设备架构
        String primaryArch = ArchitectureUtils.getPrimaryArchitecture();
//        List<String> supportedArchs = ArchitectureUtils.getSupportedArchitectures();
        // 创建版本存储目录
        Context requireContext = requireContext();
        File versionDir = new File(requireContext.getFilesDir(), "servers/" + version);
        if (overwrite) {
          // 删除现有版本
          FileUtils.deleteDirectory(versionDir);
          dbHelper.deleteServerVersion(version);
        }
        FileUtils.createDirectory(versionDir);
        // 复制主架构文件
        File jniLibsDir = new File(tempDir, "jniLibs");
        boolean primaryCopySuccess = copyArchitectureFiles(jniLibsDir, primaryArch, versionDir);
        if (!primaryCopySuccess) {
          showError(getString(R.string.no_arch_files_format, primaryArch));
          FileUtils.deleteDirectory(versionDir);
          return;
        }
        // 处理32位兼容
        boolean is64Bit = ArchitectureUtils.is64Bit();
        String secondaryArch = ArchitectureUtils.get32BitArchitecture();
        boolean secondaryCopied = false;
        if (is64Bit && secondaryArch != null) {
          secondaryCopied = copyArchitectureFiles(jniLibsDir, secondaryArch,
              new File(versionDir, ConfigManager.LIB32_DIR_NAME));
        }
        // 复制agent文件
        File agentDir = new File(tempDir, "agent");
        File destAgentDir = new File(versionDir, "agent");
        FileUtils.createDirectory(destAgentDir);

        FileUtils.copyFile(new File(agentDir, ConfigManager.APP_AGENT_FILE),
            new File(destAgentDir, ConfigManager.APP_AGENT_FILE));
        FileUtils.copyFile(new File(agentDir, ConfigManager.SYSTEM_AGENT_FILE),
            new File(destAgentDir, ConfigManager.SYSTEM_AGENT_FILE));

        // 保存到数据库（设置描述信息）
        ServerInfo serverInfo = new ServerInfo();
        serverInfo.setVersion(version);
        serverInfo.setDescription(description); // 新增：设置描述信息
        serverInfo.setPrimaryArchitecture(primaryArch);
        serverInfo.setSupport32Bit(secondaryCopied);
        serverInfo.setServerPath(new File(versionDir, "albatross_server").getAbsolutePath());
        serverInfo.setLibPath(new File(versionDir, ConfigManager.LIB_NAME).getAbsolutePath());
        serverInfo.setLib32Path(secondaryCopied ?
            new File(versionDir, ConfigManager.LIB32_DIR_NAME + "/" + ConfigManager.LIB_NAME).getAbsolutePath() : null);
        serverInfo.setAgentPath(destAgentDir.getAbsolutePath());

        // 如果是第一个版本，自动设为当前版本
        String extraPrompt = null;
        if (dbHelper.getAllServerVersions().isEmpty()) {
          dbHelper.addServerVersion(serverInfo, true);
          extraPrompt = getString(R.string.core_import_success_default_format, version);
        } else {
          dbHelper.addServerVersion(serverInfo, false);
        }
        // 刷新列表
        String finalExtraPrompt = extraPrompt;
        requireActivity().runOnUiThread(() -> {
          loadServerVersions();
          if (finalExtraPrompt != null) {
            Toast.makeText(requireContext, finalExtraPrompt, Toast.LENGTH_LONG).show();
            Intent intent = requireContext.getPackageManager().getLaunchIntentForPackage(requireContext.getPackageName());
            if (intent != null) {
              intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
              requireContext.startActivity(intent);
              requireActivity().finish();
            } else {
              System.exit(0);
            }
          } else
            Toast.makeText(requireContext, getString(R.string.core_import_success_format, version),
                Toast.LENGTH_SHORT).show();
        });

      } catch (Exception e) {
        e.printStackTrace();
        showError(getString(R.string.save_failed_format, e.getMessage()));
      }
    });
  }

  /**
   * 复制特定架构的文件
   */
  private boolean copyArchitectureFiles(File jniLibsDir, String arch, File destDir) {
    File archDir = new File(jniLibsDir, arch);
    if (!archDir.exists() || !archDir.isDirectory()) {
      return false;
    }
    FileUtils.createDirectory(destDir);
    // 复制可执行文件
    File serverFile = new File(archDir, "albatross_server");
    if (!serverFile.exists()) {
      return false;
    }
    File destServerFile = new File(destDir, "albatross_server");
    if (!FileUtils.copyFile(serverFile, destServerFile)) {
      return false;
    }
    // 设置可执行权限
    FileUtils.setExecutable(destServerFile);
    // 复制库文件
    File libFile = new File(archDir, "libalbatross_base.so");
    if (!libFile.exists()) {
      return false;
    }
    File destLibFile = new File(destDir, ConfigManager.LIB_NAME);
    return FileUtils.copyFile(libFile, destLibFile);
  }

  /**
   * 显示错误信息
   */
  private void showError(String message) {
    requireActivity().runOnUiThread(() ->
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    );
  }

  /**
   * 切换服务版本
   */
  @Override
  public void onVersionSwitch(ServerInfo serverInfo) {
    new AlertDialog.Builder(requireContext())
        .setTitle(getString(R.string.switch_version_title))
        .setMessage(getString(R.string.switch_version_confirm_format, serverInfo.getVersion()))
        .setPositiveButton(getString(R.string.confirm_button), (dialog, which) -> {
          executor.execute(() -> {
            dbHelper.setCurrentServerVersion(serverInfo.getVersion());
            ServerManager serverManager = ServerManager.getInstance(getContext());
            serverManager.stopServer();
            requireActivity().runOnUiThread(() -> {
              loadServerVersions();
              showRestartDialog();
            });
          });
        })
        .setNegativeButton("取消", null)
        .show();
  }

  // 在SettingsFragment中实现查看完整描述的接口方法
  @Override
  public void onShowMoreDescription(ServerInfo serverInfo) {
    // 创建显示完整描述的对话框
    new AlertDialog.Builder(requireContext())
        .setTitle(getString(R.string.version_description_title_format, serverInfo.getVersion()))
        .setMessage(serverInfo.getDescription())
        .setPositiveButton(getString(R.string.close_button), null)
        // 允许复制描述内容
        .setNeutralButton(getString(R.string.copy_description_button), (dialog, which) -> {
          android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
              requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
          android.content.ClipData clip = android.content.ClipData.newPlainText(
              getString(R.string.server_version_description), serverInfo.getDescription());
          clipboard.setPrimaryClip(clip);
          Toast.makeText(requireContext(), getString(R.string.description_copied), Toast.LENGTH_SHORT).show();
        })
        .show();
  }


  /**
   * 删除服务版本
   */
  @Override
  public void onVersionDelete(ServerInfo serverInfo) {
    // 不能删除当前正在使用的版本
    if (serverInfo.getVersion().equals(currentVersion)) {
      Toast.makeText(requireContext(), getString(R.string.cannot_delete_current_version), Toast.LENGTH_SHORT).show();
      return;
    }

    new AlertDialog.Builder(requireContext())
        .setTitle(getString(R.string.delete_version_title))
        .setMessage(getString(R.string.delete_version_confirm_format, serverInfo.getVersion()))
        .setPositiveButton(getString(R.string.delete_confirm_button), (dialog, which) -> {
          executor.execute(() -> {
            // 删除文件
            File versionDir = new File(requireContext().getFilesDir(),
                "servers/" + serverInfo.getVersion());
            FileUtils.deleteDirectory(versionDir);

            // 从数据库删除
            dbHelper.deleteServerVersion(serverInfo.getVersion());

            requireActivity().runOnUiThread(this::loadServerVersions);
          });
        })
        .setNegativeButton("取消", null)
        .show();
  }

  /**
   * 显示重启应用对话框
   */
  private void showRestartDialog() {
    new AlertDialog.Builder(requireContext())
        .setTitle(getString(R.string.version_switched_title))
        .setMessage(getString(R.string.restart_app_for_version_switch))
        .setPositiveButton(getString(R.string.restart_now_button), (dialog, which) -> {
          // 重启应用
          System.exit(0);
//          Intent intent = requireContext().getPackageManager()
//              .getLaunchIntentForPackage(requireContext().getPackageName());
//          if (intent != null) {
//            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
//            requireContext().startActivity(intent);
//            requireActivity().finish();
//          }
        })
        .setNegativeButton(getString(R.string.restart_later_button), null)
        .show();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    executor.shutdown();
  }
}
