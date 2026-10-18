# Albatross (open source) Magisk module

刷入（Magisk / APatch / KernelSU 通用）并重启后，模块会：

1. 安装内置的 **AlbatrossManager** release APK（内容一致则跳过）
2. 把内置的 **Albatross 核心包**导入 Manager（按设备 ABI 释放 albatross_server / libalbatross_base.so / agent dex）
3. 以 **root 直接启动 albatross_server**（不经过 Manager 的 su，开机不会弹授权框）
4. 让 Manager 加载核心并连上服务（模块带 `sepolicy.rule` 放行 `untrusted_app` 连服务端 socket）
5. 可选自动打开 Manager 界面（`LAUNCH_UI=1`）
6. 启动后做 60 秒健康窗口观察：若本模块的启动导致系统异常/反复重启，会逐级降级
   （关高风险开关 → 只装不启 → 写 `disable` 自动停用），恢复用 `action.sh guard-reset`

本模块不内置插件 APK。插件请自行安装后，在 Manager 界面或通过 `INSTALL_PLUGIN` 广播注册；
插件参考实现见 `https://github.com/AlbatrossHook/HideApp`。

## 配置

改 `config.env`（模块目录下），或复制到 `/data/adb/albatross/config.env` 再改
（后者重刷模块不会丢）。改完重启生效，也可以点模块的「操作」按钮立即重跑。

常用项：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `AUTO_START` | 1 | 开机自动拉起运行环境；0 则只安装不启动 |
| `LAUNCH_UI` | 0 | 1=开机自动打开 Manager 界面 |
| `INSTALL_MANAGER` | 1 | 是否自动安装/更新 Manager APK |
| `ENABLE_JIT` | 1 | 0=注入进程禁用 ART JIT |
| `ENABLE_ANTI_DETECTION` | 0 | 1=注入进程启用反检测 |
| `ENABLE_MEMFD_INJECT` | 0 | 1=memfd 内存注入 |
| `ENABLE_KPM_HIDE` | 0 | 1=尝试内核级隐藏（需 rwbp.ko） |
| `ROOT_PATH` | 空 | 空=沿用 Manager 配置（默认 `/data/local/tmp/albatross/`） |
| `SERVER_ADDRESS` | 空 | 空=自动派生 `albatross_<设备序列号>`（须字母开头） |

## 日志与排错

- 运行日志：`/data/adb/albatross/run.log`，同时打进 logcat（tag `AlbModule`）
- 服务日志：`<ROOT_PATH>/albatross_manager.log`（默认 `/data/local/tmp/albatross/`）
- 有锁屏密码的设备：开机后需要**先解锁一次**，模块才能写 App 数据目录；没等到解锁会在下次开机重试
- APK 安装失败提示签名冲突时，先手动卸载同名应用再点「操作」重跑

## 卸载

在模块管理里卸载会停掉 albatross_server 并清理临时文件；
AlbatrossManager 与插件 APK 不会被卸载，需要的话自行在系统里卸载。
