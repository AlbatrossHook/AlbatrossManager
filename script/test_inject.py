#!/usr/bin/env python3
"""模拟器注入测试：通过 AlbatrossManager 的广播接口注入 plugin_demo 插件。

流程与界面操作一致：
  1. 安装 release Manager、导入核心、启动 albatross_server、LOAD_CORE
  2. 安装 plugin_demo 插件 APK
  3. 启动目标应用，注册插件并设置目标规则（INSTALL_PLUGIN / SET_RULE）
  4. 发送 INJECT，让 Manager 调服务端注入目标进程
  5. 校验目标进程 maps 里出现 libalbatross、Albatross 日志出现 DemoPlugin，
     并用 albatross-python 连接被注入进程的 RPC（get_package_name/getuid）

参考 AlbatrossServer/test/launch_fast_test.py 的注入与客户端校验方式。

用法：
  ./script/test_inject.py --device emulator-5560
  ./script/test_inject.py --device emulator-5560 --target qing.albatross.demo
  ./script/test_inject.py --device emulator-5560 --skip-setup   # 复用已就绪的 Manager/服务
"""

import argparse
import re
import sys
import time
import zipfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
MANAGER_ROOT = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))
import pack_release as pr  # noqa: E402

DEFAULT_PLUGIN = (MANAGER_ROOT.parent / "AlbatrossServer" / "albatross-java" / "plugin_demo"
                  / "build" / "outputs" / "apk" / "debug" / "plugin_demo-debug.apk")
DEFAULT_PLUGIN_PKG = "qing.albatross.injector"
DEFAULT_TARGET = "qing.albatross.demo"


def read_core_version(core_zip):
    with zipfile.ZipFile(core_zip) as zf:
        return zf.read("resource/version").decode("utf-8").strip()


def find_manager_apk(manager_root):
    out = manager_root / "app" / "build" / "outputs" / "apk" / "release"
    apks = sorted(out.glob("AlbatrossManager-*.apk"), key=lambda p: p.stat().st_mtime)
    if not apks:
        pr.die("找不到 release APK: %s（先运行 script/pack_release.py 构建）" % out)
    return apks[-1]


def find_core_zip(manager_root):
    out = manager_root / "build" / "open_source_pack"
    cores = sorted(out.glob("core_open_*.zip"), key=lambda p: p.stat().st_mtime)
    if not cores:
        pr.die("找不到核心包: %s（先运行 script/pack_release.py）" % out)
    return cores[-1]


def target_component(serial, target):
    out = pr.adb_shell(serial, "cmd package resolve-activity --brief %s | tail -1" % target,
                       check=False).stdout or ""
    for line in reversed(out.splitlines()):
        line = line.strip()
        if "/" in line and " " not in line:
            return line
    return None


def start_target(serial, target):
    component = target_component(serial, target)
    if not component:
        pr.die("目标应用没有可启动的 Activity: %s" % target)
    pr.adb_shell(serial, "am force-stop %s" % target, check=False)
    pr.adb_shell(serial, "am start -W -n %s" % component, check=False)
    deadline = time.time() + 20
    while time.time() < deadline:
        pid = (pr.adb_shell(serial, "pidof %s" % target, check=False).stdout or "").strip()
        if pid:
            return pid.split()[0]
        time.sleep(1)
    pr.die("目标应用启动后没有进程: %s" % target)


def verify_maps(serial, pid):
    out = pr.adb_su(serial, "grep -i 'libalbatross' /proc/%s/maps" % pid, check=False).stdout or ""
    if "libalbatross" not in out:
        pr.die("目标进程 %s 的 maps 里没有 libalbatross:\n%s" % (pid, out))
    pr.info("maps 已加载 libalbatross: %s" % out.splitlines()[0].strip())
    return out


def verify_logs(serial):
    out = pr.adb_shell(serial, "logcat -d -s Albatross:V", check=False).stdout or ""
    hits = [l for l in out.splitlines() if "DemoPlugin" in l]
    if not hits:
        pr.die("Albatross 日志里没有 DemoPlugin 加载记录:\n%s" % out[-3000:])
    for line in hits[-5:]:
        pr.info("logcat: %s" % line.strip())


def verify_rpc(serial, target, pid):
    """用 albatross-python 连被注入进程的 RPC，确认 agent/插件真的可用。"""
    server_root = MANAGER_ROOT.parent / "AlbatrossServer"
    py_root = server_root / "albatross-python"
    if not py_root.is_dir():
        pr.warn("找不到 albatross-python，跳过 RPC 校验: %s" % py_root)
        return
    sys.path.insert(0, str(py_root))
    try:
        import albatross
        from albatross.app_client import AppClient
    except Exception as e:
        pr.warn("导入 albatross-python 失败，跳过 RPC 校验: %s" % e)
        return
    device = None
    port = None
    app_client = None
    try:
        device = albatross.get_device(serial)
        address = device.client.get_address(int(pid))
        if not address:
            pr.die("服务端没有返回目标进程的 RPC 地址")
        port = device.get_forward_port("localabstract:" + address)
        app_client = AppClient(port, None, "%s:%s" % (target, pid))
        pkg = app_client.get_package_name()
        uid = app_client.getuid()
        if pkg != target:
            pr.die("RPC 返回包名不匹配: %s != %s" % (pkg, target))
        pr.info("RPC 校验通过: package=%s uid=%s address=%s" % (pkg, uid, address))
    finally:
        try:
            if app_client:
                app_client.close()
        except Exception:
            pass
        try:
            if device and port:
                device.remove_forward_port(port)
        except Exception:
            pass
        try:
            if device:
                albatross.destroy()
        except Exception:
            pass


def main():
    ap = argparse.ArgumentParser(description="通过 AlbatrossManager 注入 plugin_demo 并校验")
    ap.add_argument("--device", default="emulator-5560", help="模拟器 serial")
    ap.add_argument("--target", default=DEFAULT_TARGET, help="目标应用包名")
    ap.add_argument("--plugin-apk", type=Path, default=DEFAULT_PLUGIN,
                    help="已签名的 plugin_demo APK（默认用 debug 构建产物）")
    ap.add_argument("--plugin-package", default=DEFAULT_PLUGIN_PKG)
    ap.add_argument("--manager-apk", type=Path, help="release Manager APK（默认取最新构建）")
    ap.add_argument("--core", type=Path, help="核心包（默认取 build/open_source_pack 最新）")
    ap.add_argument("--skip-setup", action="store_true",
                    help="跳过 Manager/核心/服务安装（假定已就绪）")
    ap.add_argument("--no-rpc", action="store_true", help="跳过 albatross-python RPC 校验")
    ap.add_argument("--keep-running", action="store_true", help="测试后保留服务与目标进程")
    args = ap.parse_args()

    serial = args.device
    pr.ensure_device(serial)
    pr.wake(serial)
    apk = args.manager_apk or find_manager_apk(MANAGER_ROOT)
    core_zip = args.core or find_core_zip(MANAGER_ROOT)
    plugin = args.plugin_apk.resolve()
    if not plugin.is_file():
        pr.die("插件 APK 不存在: %s" % plugin)

    if not args.skip_setup:
        core = {"version": read_core_version(core_zip), "zip": core_zip}
        pr.info("准备 Manager + 核心 + 服务: apk=%s core=%s" % (apk.name, core_zip.name))
        pr.test_release(serial, core, {"zip": apk}, keep_running=True)

    pr.info("安装插件: %s" % plugin)
    pr.install_apk(serial, plugin)

    pid = start_target(serial, args.target)
    pr.info("目标进程: %s pid=%s" % (args.target, pid))
    pr.adb_shell(serial, "logcat -c", check=False)

    ok, line = pr.broadcast(serial, "INSTALL_PLUGIN", [
        ("es", "package", args.plugin_package),
        ("es", "targets", args.target),
        ("ez", "enable", "true"),
    ], timeout=180)
    if not ok:
        pr.die("INSTALL_PLUGIN 失败: %s" % line)
    pr.info("INSTALL_PLUGIN: %s" % line)
    if "plugin=%s" % args.plugin_package not in line:
        pr.die("INSTALL_PLUGIN 结果里没有插件包名: %s" % line)

    ok, line = pr.broadcast(serial, "INJECT", [("es", "target", args.target)], timeout=240)
    if not ok:
        pr.die("INJECT 失败: %s" % line)
    pr.info("INJECT: %s" % line)
    if "inject %s" % args.target not in line:
        pr.die("INJECT 结果不符合预期: %s" % line)

    verify_maps(serial, pid)
    verify_logs(serial)
    if not args.no_rpc:
        verify_rpc(serial, args.target, pid)

    if not pr.app_pid(serial):
        pr.die("测试过程中 Manager 进程退出")
    crash = pr.crash_tail(serial)
    if crash:
        pr.die("测试过程中出现 Java 崩溃:\n%s" % crash)

    if not args.keep_running:
        pr.broadcast(serial, "STOP_SERVER", timeout=60)
        pr.adb_shell(serial, "am force-stop %s" % args.target, check=False)
        pr.adb_shell(serial, "am force-stop %s" % pr.MANAGER_PKG, check=False)
    pr.info("==== 注入测试通过：%s <- %s ====" % (args.target, args.plugin_package))


if __name__ == "__main__":
    try:
        main()
    except pr.PackError as e:
        print("[inject-test] 错误: %s" % e, file=sys.stderr)
        sys.exit(1)
