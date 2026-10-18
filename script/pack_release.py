#!/usr/bin/env python3
"""打包开源版 Albatross 核心包 / Magisk 模块，构建 release APK 并在设备上测试。

参考框架仓库的 script/pack_core.py，但不在本机编译 native：
运行资产直接取开源版 AlbatrossServer 仓库的 resource/ 目录（agent dex + 4 ABI 的
albatross_server / libalbatross_base.so）。

核心包结构与 Manager 的 CoreInstaller 对齐（zip 内顶层为 resource/）：
    resource/version
    resource/description
    resource/agent/app_agent.dex
    resource/agent/system_server.dex
    resource/jniLibs/<abi>/albatross_server
    resource/jniLibs/<abi>/libalbatross_base.so

用法示例：
    # 只打包（使用 ../AlbatrossServer/resource）
    ./script/pack_release.py

    # 构建 release APK（签名参数也可用环境变量传入）
    ./script/pack_release.py --build-apk \
        --keystore /path/qing.jks \
        --store-password ... --key-alias key0 --key-password ...

    # 构建并在模拟器上测试 release（不测试 debug）
    ./script/pack_release.py --build-apk --test-device emulator-5560

    # 追加 Magisk 模块安装 + 重启验证（会改动设备的 /data/adb，测试后自动清理）
    ./script/pack_release.py --build-apk --test-device emulator-5560 --test-module

产物在 build/open_source_pack/ 下。
"""

import argparse
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
import zipfile
from datetime import datetime
from pathlib import Path

DEFAULT_ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"]
MANAGER_PKG = "qing.albatross.manager"
ACTION_PREFIX = "qing.albatross.manager.action."
ACTION_LOG_TAG = "AlbManagerAction"
MODULE_ID = "albatross_open"


class PackError(RuntimeError):
    pass


def info(msg):
    print("[pack] " + msg, flush=True)


def warn(msg):
    print("[pack] WARN: " + msg, file=sys.stderr, flush=True)


def die(msg):
    raise PackError(msg)


def run(cmd, cwd=None, env=None, timeout=300, check=True, capture=False):
    info("$ " + " ".join(str(c) for c in cmd))
    cp = subprocess.run(
        [str(c) for c in cmd],
        cwd=str(cwd) if cwd else None,
        env=env,
        timeout=timeout,
        text=True,
        capture_output=capture,
    )
    if capture and (not check or cp.returncode != 0):
        if cp.stdout:
            print(cp.stdout, end="")
        if cp.stderr:
            print(cp.stderr, end="", file=sys.stderr)
    if check and cp.returncode != 0:
        die("命令失败(%d): %s" % (cp.returncode, " ".join(str(c) for c in cmd)))
    return cp


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_version_catalog(path):
    text = Path(path).read_text(encoding="utf-8")
    out = {}
    for key in ("versionCode", "versionName"):
        m = re.search(r'^%s\s*=\s*"([^"]+)"' % key, text, re.M)
        if not m:
            die("版本目录里找不到 %s: %s" % (key, path))
        out[key] = m.group(1)
    return out


def zip_tree(src_dir, out_zip, arc_root=None):
    src_dir = Path(src_dir)
    out_zip = Path(out_zip)
    out_zip.parent.mkdir(parents=True, exist_ok=True)
    if out_zip.exists():
        out_zip.unlink()
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(src_dir):
            dirs.sort()
            for name in sorted(files):
                full = Path(root) / name
                rel = full.relative_to(src_dir)
                arc = str(Path(arc_root) / rel) if arc_root else str(rel)
                zf.write(full, arc)


def zip_entries(path):
    with zipfile.ZipFile(path) as zf:
        return zf.namelist()


def require_files(paths):
    missing = [str(p) for p in paths if not Path(p).is_file()]
    if missing:
        die("缺少文件:\n  " + "\n  ".join(missing))


# ---------------------------------------------------------------------------
# 核心包
# ---------------------------------------------------------------------------

def pack_core(args):
    resource = args.resource.resolve()
    if not resource.is_dir():
        die("resource 目录不存在: %s" % resource)
    server_version_file = args.server_root.resolve() / "VERSION"
    version = args.core_version
    if not version:
        if not server_version_file.is_file():
            die("找不到 %s，请用 --core-version 指定核心版本" % server_version_file)
        version = server_version_file.read_text(encoding="utf-8").strip()
    if not version:
        die("核心版本为空")

    abis = [a.strip() for a in args.abis.split(",") if a.strip()]
    for abi in abis:
        if abi not in DEFAULT_ABIS:
            die("不支持的 ABI: %s（可选 %s）" % (abi, ", ".join(DEFAULT_ABIS)))
    required = [
        resource / "agent" / "app_agent.dex",
        resource / "agent" / "system_server.dex",
    ]
    for abi in abis:
        required.append(resource / "jniLibs" / abi / "albatross_server")
        required.append(resource / "jniLibs" / abi / "libalbatross_base.so")
    require_files(required)

    stage = args.out / "core_stage" / "resource"
    if stage.parent.exists():
        shutil.rmtree(stage.parent)
    (stage / "agent").mkdir(parents=True)
    shutil.copy2(resource / "agent" / "app_agent.dex", stage / "agent" / "app_agent.dex")
    shutil.copy2(resource / "agent" / "system_server.dex", stage / "agent" / "system_server.dex")
    for abi in abis:
        dst = stage / "jniLibs" / abi
        dst.mkdir(parents=True)
        shutil.copy2(resource / "jniLibs" / abi / "albatross_server", dst / "albatross_server")
        shutil.copy2(resource / "jniLibs" / abi / "libalbatross_base.so", dst / "libalbatross_base.so")
        os.chmod(dst / "albatross_server", 0o755)
        os.chmod(dst / "libalbatross_base.so", 0o644)

    (stage / "version").write_text(version + "\n", encoding="utf-8")
    description = "open source resource %s\nsource: %s\npacked: %s\nabis: %s" % (
        version, resource, datetime.now().strftime("%Y-%m-%d %H:%M:%S"), ", ".join(abis))
    (stage / "description").write_text(description + "\n", encoding="utf-8")

    out_zip = args.out / ("core_open_%s_%s.zip" % (version, datetime.now().strftime("%Y%m%d")))
    zip_tree(stage, out_zip, arc_root="resource")
    entries = zip_entries(out_zip)
    for need in ("resource/version", "resource/agent/app_agent.dex",
                 "resource/agent/system_server.dex"):
        if need not in entries:
            die("核心包缺少 %s: %s" % (need, out_zip))
    for abi in abis:
        for name in ("albatross_server", "libalbatross_base.so"):
            need = "resource/jniLibs/%s/%s" % (abi, name)
            if need not in entries:
                die("核心包缺少 %s: %s" % (need, out_zip))
    info("核心包: %s (%d 个文件, %.2f MB, sha256=%s)" % (
        out_zip, len(entries), out_zip.stat().st_size / 1048576, sha256_file(out_zip)))
    return {"version": version, "zip": out_zip, "sha256": sha256_file(out_zip), "abis": abis}


# ---------------------------------------------------------------------------
# release APK
# ---------------------------------------------------------------------------

def sdk_dir(args):
    if args.sdk:
        return Path(args.sdk)
    env = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if env:
        return Path(env)
    local = args.manager_root / "local.properties"
    if local.is_file():
        for line in local.read_text(encoding="utf-8", errors="ignore").splitlines():
            if line.strip().startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip().replace("\\\\", "/"))
    die("找不到 Android SDK：请用 --sdk 指定，或在 local.properties 里配置 sdk.dir")


def build_tool(args, name):
    root = sdk_dir(args) / "build-tools"
    if not root.is_dir():
        die("找不到 build-tools: %s" % root)
    versions = sorted([p for p in root.iterdir() if p.is_dir()])
    for v in reversed(versions):
        candidate = v / name
        if candidate.is_file():
            return candidate
    die("找不到 build-tools 工具: %s" % name)


def locate_apk(args, version_name):
    out_dir = args.manager_root / "app" / "build" / "outputs" / "apk" / "release"
    candidates = [
        out_dir / ("AlbatrossManager-%s.apk" % version_name),
        out_dir / "app-release.apk",
    ]
    for c in candidates:
        if c.is_file():
            return c
    die("release APK 不存在，先去掉 --skip-build 构建: %s" % out_dir)


def verify_apk(args, apk):
    aapt2 = build_tool(args, "aapt2")
    out = run([aapt2, "dump", "badging", apk], capture=True, check=True).stdout
    package = version_code = version_name = None
    for line in out.splitlines():
        if line.startswith("package:"):
            package = re.search(r"name='([^']+)'", line).group(1)
            version_code = re.search(r"versionCode='([^']+)'", line).group(1)
            version_name = re.search(r"versionName='([^']+)'", line).group(1)
            break
    if package != MANAGER_PKG:
        die("APK 包名是 %s，期望 %s" % (package, MANAGER_PKG))
    apksigner = build_tool(args, "apksigner")
    sig = run([apksigner, "verify", "--print-certs", apk], capture=True, check=True).stdout
    cert = ""
    for line in sig.splitlines():
        if "SHA-256" in line:
            cert = line.strip()
            break
    if not cert:
        die("APK 未签名: %s" % apk)
    info("APK 校验: package=%s versionCode=%s versionName=%s" % (package, version_code, version_name))
    info("签名: %s" % cert)
    return {"zip": apk, "package": package, "version_code": version_code,
            "version_name": version_name, "sha256": sha256_file(apk)}


def build_release(args):
    catalog = parse_version_catalog(args.manager_root / "gradle" / "libs.versions.toml")
    if args.skip_build:
        apk = locate_apk(args, catalog["versionName"])
        info("跳过构建，使用已有 APK: %s" % apk)
        return verify_apk(args, apk)

    keystore = Path(args.keystore or os.environ.get("RELEASE_STORE_FILE", "")).expanduser()
    store_pwd = args.store_password or os.environ.get("RELEASE_STORE_PASSWORD")
    key_alias = args.key_alias or os.environ.get("RELEASE_KEY_ALIAS")
    key_pwd = args.key_password or os.environ.get("RELEASE_KEY_PASSWORD")
    if not keystore.is_file():
        die("找不到签名 keystore: %s（用 --keystore 指定）" % keystore)
    for name, value in (("RELEASE_STORE_PASSWORD", store_pwd), ("RELEASE_KEY_ALIAS", key_alias),
                        ("RELEASE_KEY_PASSWORD", key_pwd)):
        if not value:
            die("缺少签名参数 %s（--store-password/--key-alias/--key-password 或环境变量）" % name)
    env = os.environ.copy()
    env.update({
        "RELEASE_STORE_FILE": str(keystore.resolve()),
        "RELEASE_STORE_PASSWORD": store_pwd,
        "RELEASE_KEY_ALIAS": key_alias,
        "RELEASE_KEY_PASSWORD": key_pwd,
    })
    gradle = args.manager_root / "gradlew"
    cmd = [gradle, ":app:assembleRelease"]
    if args.offline:
        cmd.append("--offline")
    if args.rerun_tasks:
        cmd.append("--rerun-tasks")
    run(cmd, cwd=args.manager_root, env=env, timeout=900)
    apk = locate_apk(args, catalog["versionName"])
    return verify_apk(args, apk)


# ---------------------------------------------------------------------------
# Magisk 模块
# ---------------------------------------------------------------------------

def assemble_module(args, core, apk):
    template = args.manager_root / "script" / "magisk_module"
    if not (template / "module.prop").is_file():
        die("缺少 Magisk 模板: %s" % template)
    stage = args.out / "module"
    if stage.exists():
        shutil.rmtree(stage)
    (stage / "payload").mkdir(parents=True)

    for name in ("module.prop", "config.env", "customize.sh", "service.sh", "setup.sh",
                 "guard.sh", "action.sh", "uninstall.sh", "sepolicy.rule", "README.md"):
        src = template / name
        if not src.is_file():
            die("模板缺少 %s" % src)
        shutil.copy2(src, stage / name)
        if name.endswith(".sh"):
            os.chmod(stage / name, 0o755)

    core_name = core["zip"].name
    apk_name = apk["zip"].name
    shutil.copy2(core["zip"], stage / "payload" / core_name)
    shutil.copy2(apk["zip"], stage / "payload" / apk_name)

    module_version = "v%s-%s" % (core["version"], apk["version_name"])
    core_num = int(re.sub(r"[^0-9]", "", core["version"])[:6] or "0")
    module_code = core_num * 100000 + int(apk["version_code"])
    module_info = (
        "# generated by script/pack_release.py, do not edit\n"
        "MODULE_VERSION='%s'\n"
        "CORE_VERSION='%s'\n"
        "CORE_ZIP='payload/%s'\n"
        "MGR_PKG='%s'\n"
        "MGR_APK='payload/%s'\n"
        "PLUGIN_PKG=''\n"
        "PLUGIN_APK=''\n"
        "BUILD_TIME='%s'\n" % (
            module_version, core["version"], core_name, MANAGER_PKG, apk_name,
            datetime.now().strftime("%Y-%m-%d %H:%M:%S")))
    (stage / "module_info.sh").write_text(module_info, encoding="utf-8")
    os.chmod(stage / "module_info.sh", 0o644)

    prop = (template / "module.prop").read_text(encoding="utf-8")
    description = ("Open source Albatross runtime: installs AlbatrossManager %s, imports core %s "
                   "and starts albatross_server as root." % (apk["version_name"], core["version"]))
    prop = (prop.replace("__MODULE_VERSION__", module_version)
                .replace("__MODULE_VERSION_CODE__", str(module_code))
                .replace("__DESCRIPTION__", description))
    if re.search(r"^id=" + re.escape(MODULE_ID) + r"$", prop, re.M) is None:
        die("module.prop 的 id 必须是 %s" % MODULE_ID)
    (stage / "module.prop").write_text(prop, encoding="utf-8")

    for sh in stage.glob("*.sh"):
        cp = run(["sh", "-n", sh], check=False, capture=True)
        if cp.returncode != 0:
            die("shell 语法错误: %s" % sh)

    out_zip = args.out / ("albatross_open_module_%s_%s.zip" % (
        module_version, datetime.now().strftime("%Y%m%d")))
    zip_tree(stage, out_zip)
    entries = zip_entries(out_zip)
    for need in ("module.prop", "module_info.sh", "setup.sh", "service.sh",
                 "payload/" + core_name, "payload/" + apk_name):
        if need not in entries:
            die("模块 zip 缺少 %s: %s" % (need, out_zip))
    info("Magisk 模块: %s (%d 个文件, %.2f MB, sha256=%s)" % (
        out_zip, len(entries), out_zip.stat().st_size / 1048576, sha256_file(out_zip)))
    return {"zip": out_zip, "version": module_version, "version_code": module_code,
            "sha256": sha256_file(out_zip)}


# ---------------------------------------------------------------------------
# 设备测试（只测 release）
# ---------------------------------------------------------------------------

def adb_bin():
    return os.environ.get("ADB", "adb")


def adb(serial, *args, timeout=120, check=True):
    cmd = [adb_bin(), "-s", serial] + [str(a) for a in args]
    return run(cmd, timeout=timeout, check=check, capture=True)


def adb_shell(serial, command, timeout=120, check=True):
    return adb(serial, "shell", command, timeout=timeout, check=check)


def adb_su(serial, command, timeout=180, check=True):
    return adb(serial, "shell", "su -c " + shlex.quote(command), timeout=timeout, check=check)


def ensure_device(serial):
    out = adb(serial, "get-state", check=False).stdout
    if "device" not in out:
        die("设备不在线: %s" % serial)
    root = adb_su(serial, "id", check=False).stdout
    if "uid=0" not in root:
        die("设备 %s 没有可用的 root（su -c id 失败）" % serial)
    return root


def wake(serial):
    adb_shell(serial, "input keyevent KEYCODE_WAKEUP", check=False)
    adb_shell(serial, "wm dismiss-keyguard", check=False)
    adb_shell(serial, "svc power stayon true", check=False)


def restore_screen(serial):
    adb_shell(serial, "svc power stayon false", check=False)


def clear_log(serial):
    adb_shell(serial, "logcat -c", check=False)


def wait_action(serial, action, timeout):
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        out = adb_shell(serial, "logcat -d -s %s:V" % ACTION_LOG_TAG, check=False).stdout or ""
        lines = [l for l in out.splitlines() if "action=%s" % action in l]
        for line in reversed(lines):
            last = line
            if "MANAGER_ACTION success: action=%s" % action in line:
                return True, line
            if "MANAGER_ACTION failed: action=%s" % action in line:
                return False, line
        time.sleep(1)
    return False, last or ("timeout after %ds" % timeout)


def broadcast(serial, action, extras=(), timeout=120):
    tail = " ".join("--%s %s %s" % (kind, key, shlex.quote(str(value)))
                    for kind, key, value in extras)
    clear_log(serial)
    adb_shell(serial, "am broadcast -a %s%s -f 0x20 -p %s %s" %
              (ACTION_PREFIX, action, MANAGER_PKG, tail), check=False)
    return wait_action(serial, action, timeout)


def stage_core_for_app(serial, core_zip):
    remote = "/data/local/tmp/" + core_zip.name
    adb(serial, "push", core_zip, remote)
    adb_shell(serial, "chmod 644 " + shlex.quote(remote))
    cache = "/data/data/%s/cache" % MANAGER_PKG
    staged = cache + "/magisk/" + core_zip.name
    adb_su(serial, "mkdir -p " + shlex.quote(cache + "/magisk"))
    adb_su(serial, "cp -f %s %s" % (shlex.quote(remote), shlex.quote(staged)))
    uid = adb_su(serial, "stat -c %%u %s" % shlex.quote(cache)).stdout.strip()
    ctx = adb_su(serial, "stat -c %%C %s" % shlex.quote(cache)).stdout.strip()
    if uid:
        adb_su(serial, "chown %s:%s %s" % (uid, uid, shlex.quote(staged)), check=False)
    adb_su(serial, "chmod 644 " + shlex.quote(staged), check=False)
    if ctx:
        adb_su(serial, "chcon %s %s" % (shlex.quote(ctx), shlex.quote(staged)), check=False)
    info("核心包已放到 App 私有目录: %s (uid=%s, ctx=%s)" % (staged, uid, ctx or "?"))
    return staged


def app_pid(serial):
    return (adb_shell(serial, "pidof %s" % MANAGER_PKG, check=False).stdout or "").strip()


def install_apk(serial, apk_path):
    """push + root pm install：绕过 OPPO/一加等厂商的 USB 安装确认框。"""
    remote = "/data/local/tmp/" + apk_path.name
    adb(serial, "push", apk_path, remote, timeout=300)
    adb_shell(serial, "chmod 644 " + shlex.quote(remote), check=False)
    out = adb_su(serial, "pm install -r -t " + shlex.quote(remote), timeout=300, check=False)
    text = (out.stdout or "") + (out.stderr or "")
    adb_su(serial, "rm -f " + shlex.quote(remote), check=False)
    if "Success" in text:
        return
    warn("root pm install 失败，回退 adb install: %s" % text.strip())
    cp = adb(serial, "install", "-r", "-t", apk_path, timeout=120, check=False)
    if "Success" not in (cp.stdout or ""):
        die("release APK 安装失败: %s" % (cp.stdout or cp.stderr))


def our_server_pids(serial, exe):
    cmd = ('for p in $(pidof albatross_server); do '
           '[ "$(readlink /proc/$p/exe 2>/dev/null)" = "%s" ] && echo $p; done' % exe)
    return (adb_su(serial, cmd, check=False).stdout or "").split()


def apply_connect_policy(serial, root_ctx):
    """app(untrusted_app) 连接 root 起的服务端 socket 需要 SELinux connectto。"""
    if "ksu" in root_ctx:
        rule = "allow untrusted_app ksu unix_stream_socket connectto"
        return adb_su(serial, '/data/adb/ksu/bin/ksud sepolicy patch "%s"' % rule, check=False)
    rule = "allow untrusted_app magisk unix_stream_socket connectto"
    adb_su(serial, 'magiskpolicy --live "%s"' % rule, check=False)
    adb_su(serial, 'magiskpolicy --live "allow untrusted_app su unix_stream_socket connectto"',
           check=False)
    return None


def start_server_direct(serial, core_version):
    """真机测试用：像 Magisk 模块一样以 root 直接起服务，不依赖 App 的 su 授权。"""
    safe = re.sub(r"[^A-Za-z0-9._-]", "_", serial)
    root = "/data/local/tmp/albatross_open_test_%s/" % safe
    addr = "albatross_open_test_%s" % safe
    core_dir = "/data/data/%s/files/servers/%s" % (MANAGER_PKG, core_version)

    ok, line = broadcast(serial, "SET_ROOT_PATH", [("es", "path", root)], timeout=60)
    if not ok:
        die("SET_ROOT_PATH 失败: %s" % line)
    ok, line = broadcast(serial, "SET_SERVER_ADDRESS", [("es", "address", addr)], timeout=60)
    if not ok:
        die("SET_SERVER_ADDRESS 失败: %s" % line)

    adb_su(serial, "rm -rf %s && mkdir -p %s" % (shlex.quote(root), shlex.quote(root)))
    for name in ("albatross_server", "libalbatross_base.so"):
        adb_su(serial, "cp -f %s %s" % (shlex.quote(core_dir + "/" + name),
                                        shlex.quote(root + name)))
    for name in ("app_agent.dex", "system_server.dex"):
        adb_su(serial, "cp -f %s %s" % (shlex.quote(core_dir + "/agent/" + name),
                                        shlex.quote(root + name)))
    adb_su(serial, "chmod 755 %s; chmod 644 %s; chmod 444 %s" % (
        shlex.quote(root + "albatross_server"),
        shlex.quote(root + "libalbatross_base.so"),
        shlex.quote(root + "app_agent.dex") + " " + shlex.quote(root + "system_server.dex")))

    launcher = "setsid" if "setsid" in (adb_su(
        serial, "test -x /system/bin/setsid && echo setsid", check=False).stdout or "") else ""
    # KernelSU 的 `su -c` 会一直持有 pty：把整个 su 放到设备端后台，
    # 服务本身再用 setsid 脱离会话，否则 adb shell 会一直等下去。
    start = "cd %s && LD_LIBRARY_PATH=%s %s ./albatross_server %s </dev/null >>%s 2>&1" % (
        shlex.quote(root), shlex.quote(root), launcher, shlex.quote(addr),
        shlex.quote(root + "albatross_server.log"))
    adb(serial, "shell", "su -c %s </dev/null >/dev/null 2>&1 &" % shlex.quote(start),
        timeout=20, check=False)
    deadline = time.time() + 20
    while time.time() < deadline:
        if our_server_pids(serial, root + "albatross_server"):
            info("服务已启动: %s%s %s" % (root, "albatross_server", addr))
            return root, addr
        time.sleep(1)
    log = adb_su(serial, "tail -n 30 " + shlex.quote(root + "albatross_server.log"),
                 check=False).stdout or "(空)"
    stop_server_direct(serial, root)
    die("服务未能启动（root=%s）:\n%s" % (root, log))


def stop_server_direct(serial, root):
    for pid in our_server_pids(serial, root + "albatross_server"):
        adb_su(serial, "kill " + pid, check=False)
    adb_su(serial, "rm -rf " + shlex.quote(root), check=False)


def crash_tail(serial):
    out = adb_shell(serial, "logcat -d -b crash -t 100", check=False).stdout or ""
    if "FATAL EXCEPTION" in out or "beginning of crash" in out:
        return out[-3000:]
    return ""


def test_release(serial, core, apk, keep_running=False, direct=None):
    info("==== 设备测试（release）：%s ====" % serial)
    is_emulator = serial.startswith("emulator-")
    if direct is None:
        # 模拟器上测试 App 自己的 su + START_SERVER 链路；真机默认用 root 直起服务，
        # 不依赖 App 的 su 授权（也不改设备的 su 白名单）。
        direct = not is_emulator
    root_ctx = ensure_device(serial)
    wake(serial)
    adb_shell(serial, "am force-stop %s" % MANAGER_PKG, check=False)
    existed = bool((adb_shell(serial, "pm path %s" % MANAGER_PKG, check=False).stdout or "").strip())

    # 模拟器上做干净安装；真机保留已有安装与数据（只做 -r 升级，避免清掉用户的版本库/配置）
    if is_emulator:
        adb(serial, "uninstall", MANAGER_PKG, check=False)
        time.sleep(1)
        if (adb_shell(serial, "pm path %s" % MANAGER_PKG, check=False).stdout or "").strip():
            adb_su(serial, "pm uninstall %s" % MANAGER_PKG, check=False)
            time.sleep(1)
        if (adb_shell(serial, "pm path %s" % MANAGER_PKG, check=False).stdout or "").strip():
            adb_su(serial, "pm clear %s" % MANAGER_PKG, check=False)
            warn("卸载 Manager 失败，已清空数据后覆盖安装")
    else:
        info("真机：保留已有 Manager 安装与数据，只做覆盖安装")

    install_apk(serial, apk["zip"])
    adb_shell(serial, "am start -W -n %s/%s.activity.MainActivity" % (MANAGER_PKG, MANAGER_PKG))
    time.sleep(2)
    if not app_pid(serial):
        die("Manager 启动后进程不存在")

    if is_emulator:
        # 模拟器上 Magisk 默认不认识新装的包，su 请求会被直接拒绝；测试前显式授权
        uid = adb_su(serial, "stat -c %%u /data/data/%s" % MANAGER_PKG).stdout.strip()
        if uid:
            sql = ("INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) "
                   "VALUES (%s,2,0,1,1);" % uid)
            grant = adb_su(serial, "magisk --sqlite %s" % shlex.quote(sql), check=False)
            if grant.returncode != 0:
                adb_su(serial, "sqlite3 /data/adb/magisk.db %s" % shlex.quote(sql), check=False)
            info("模拟器上已为 uid=%s 授权 root" % uid)

    orig_root = orig_addr = None
    if direct and existed:
        ok, st = broadcast(serial, "STATUS", timeout=60)
        if ok:
            m = re.search(r"rootPath=([^,]+)", st)
            orig_root = m.group(1).strip() if m else None
            m = re.search(r"serverAddress=([^,]+)", st)
            orig_addr = m.group(1).strip() if m else None
        warn("真机上已有 Manager：测试会导入核心；rootPath/监听地址在结束时恢复"
             "（已导入的测试核心版本会保留在版本库里）")

    staged = stage_core_for_app(serial, core["zip"])
    direct_root = None
    try:
        # 真机走 root 直起服务时也不经过 ServerManager.startServer()，但装核心的广播相同。
        ok, line = broadcast(serial, "INSTALL_CORE", [
            ("es", "path", staged), ("ez", "overwrite", "true"), ("ez", "set_current", "true")],
            timeout=240)
        if not ok:
            die("INSTALL_CORE 失败: %s" % line)
        if "version=%s" % core["version"] not in line:
            die("INSTALL_CORE 结果版本不匹配（期望 %s）: %s" % (core["version"], line))
        info("INSTALL_CORE: %s" % line)

        addr = None
        if direct:
            direct_root, addr = start_server_direct(serial, core["version"])
            ok, line = broadcast(serial, "LOAD_CORE", timeout=240)
            if not ok or "serverConnected=true" not in line:
                warn("LOAD_CORE 未连上服务，补 SELinux connectto 规则后重试: %s" % line)
                apply_connect_policy(serial, root_ctx)
                ok, line = broadcast(serial, "LOAD_CORE", timeout=240)
            if not ok:
                die("LOAD_CORE(连接) 失败: %s" % line)
            if "serverConnected=true" not in line:
                die("LOAD_CORE 成功但未连上服务: %s" % line)
            info("LOAD_CORE(连接): %s" % line)
        else:
            # 先加载核心：Manager 的 ServerManager.startServer() 会经 PluginDelegate 访问
            # PluginConnection，未加载核心时该类还不在 classpath 里（界面流程也是这样：
            # 先"检查核心可用性"再点开始服务）。此时服务未起，serverConnected=false 正常。
            ok, line = broadcast(serial, "LOAD_CORE", timeout=240)
            if not ok:
                die("LOAD_CORE(启动服务前) 失败: %s" % line)
            info("LOAD_CORE(启动服务前): %s" % line)

            ok, line = broadcast(serial, "START_SERVER", timeout=180)
            if not ok:
                die("START_SERVER 失败: %s" % line)
            info("START_SERVER: %s" % line)

            ok, line = broadcast(serial, "LOAD_CORE", timeout=240)
            if not ok:
                # 连接可能被 SELinux 拦（模块的 sepolicy.rule 只有开机才生效），
                # 测试环境临时补 live 规则再试一次。
                warn("LOAD_CORE 失败，尝试临时放行 untrusted_app -> magisk/su 的 socket connectto: %s" % line)
                apply_connect_policy(serial, root_ctx)
                ok, line = broadcast(serial, "LOAD_CORE", timeout=240)
            if not ok:
                die("LOAD_CORE(连接) 失败: %s" % line)
            if "serverConnected=true" not in line:
                die("LOAD_CORE 成功但未连上服务: %s" % line)
            info("LOAD_CORE(连接): %s" % line)

        ok, line = broadcast(serial, "STATUS", timeout=120)
        if not ok:
            die("STATUS 失败: %s" % line)
        needles = ["serverRunning=true", "coreLoaded=true", "coreVersion=%s" % core["version"]]
        if addr:
            needles.append("serverAddress=%s" % addr)
        for needle in needles:
            if needle not in line:
                die("STATUS 缺少 %s: %s" % (needle, line))
        info("STATUS: %s" % line)

        if not app_pid(serial):
            die("测试过程中 Manager 进程退出")
        crash = crash_tail(serial)
        if crash:
            die("测试过程中出现 Java 崩溃:\n%s" % crash)
        info("==== release 设备测试通过（%s） ====" % ("root 直起服务" if direct else "App su + START_SERVER"))
    finally:
        adb_su(serial, "rm -f " + shlex.quote(staged), check=False)
        if direct_root:
            stop_server_direct(serial, direct_root)
        if direct and existed:
            if orig_root:
                broadcast(serial, "SET_ROOT_PATH", [("es", "path", orig_root)], timeout=60)
            if orig_addr:
                broadcast(serial, "SET_SERVER_ADDRESS", [("es", "address", orig_addr)], timeout=60)
        if not keep_running:
            broadcast(serial, "STOP_SERVER", timeout=60)
            adb_shell(serial, "am force-stop %s" % MANAGER_PKG, check=False)
        if not existed and not is_emulator:
            adb(serial, "uninstall", MANAGER_PKG, check=False)
        restore_screen(serial)


def module_dir(serial):
    for path in ("/data/adb/modules/%s" % MODULE_ID, "/data/adb/modules_update/%s" % MODULE_ID):
        out = adb_su(serial, "test -d %s && echo yes" % shlex.quote(path), check=False).stdout
        if "yes" in out:
            return path
    return None


def wait_boot(serial, timeout=300):
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = adb_shell(serial, "getprop sys.boot_completed", check=False).stdout or ""
        if out.strip() == "1":
            time.sleep(5)
            return
        time.sleep(3)
    die("等待开机超时: %s" % serial)


def test_module(serial, module, reboot=True):
    info("==== Magisk 模块测试：%s ====" % serial)
    ensure_device(serial)
    if not serial.startswith("emulator-"):
        die("--test-module 只在模拟器上自动执行（真机会改 /data/adb 并重启）；真机请手动刷模块验证")
    # 清掉可能的旧模块与旧状态
    adb_su(serial, "rm -rf /data/adb/modules/%s /data/adb/modules_update/%s" % (MODULE_ID, MODULE_ID),
           check=False)
    # 共享状态目录：设备上还有别的 albatross 模块时不要清
    other = adb_su(serial, "test -d /data/adb/modules/albatross_manager && echo yes",
                   check=False).stdout
    if "yes" not in (other or ""):
        adb_su(serial, "rm -rf /data/adb/albatross", check=False)
    adb_shell(serial, "am force-stop %s" % MANAGER_PKG, check=False)
    adb(serial, "uninstall", MANAGER_PKG, check=False)

    remote = "/data/local/tmp/" + module["zip"].name
    adb(serial, "push", module["zip"], remote)
    has_ksud = adb_su(serial, "test -x /data/adb/ksu/bin/ksud && echo yes", check=False).stdout
    if "yes" in (has_ksud or ""):
        out = adb_su(serial, "/data/adb/ksu/bin/ksud module install " + shlex.quote(remote),
                     timeout=900, check=False)
    else:
        out = adb_su(serial, "magisk --install-module " + shlex.quote(remote),
                     timeout=900, check=False)
    if "Done" not in (out.stdout or "") and "done" not in (out.stdout or "").lower():
        warn("模块安装命令输出里没有 Done，继续检查模块目录")
    path = module_dir(serial)
    if not path:
        die("模块安装后找不到 /data/adb/modules(_update)/%s" % MODULE_ID)

    if reboot:
        info("重启设备以走真实模块开机路径（sepolicy.rule 在 post-fs-data 生效）")
        adb(serial, "reboot", check=False)
        time.sleep(10)
        wait_boot(serial, timeout=420)
        path = module_dir(serial)
        if not path:
            die("重启后模块目录消失")
        # 等 setup.sh 完成（最多 180s）
        deadline = time.time() + 180
        while time.time() < deadline:
            log = adb_su(serial, "tail -n 5 /data/adb/albatross/run.log", check=False).stdout or ""
            if "环境就绪" in log or "配置未完成" in log:
                break
            time.sleep(5)
        info("模块运行日志尾部:\n%s" % (adb_su(serial, "tail -n 30 /data/adb/albatross/run.log",
                                              check=False).stdout or "(空)"))

    ok, line = broadcast(serial, "STATUS", timeout=180)
    if not ok:
        die("模块启动后 STATUS 失败: %s" % line)
    if "serverRunning=true" not in line:
        die("模块启动后服务未运行: %s" % line)
    info("STATUS: %s" % line)
    if not app_pid(serial):
        die("模块启动后 Manager 进程不存在")
    crash = crash_tail(serial)
    if crash:
        die("模块启动后出现 Java 崩溃:\n%s" % crash)

    # 清理：停服务、跑 uninstall.sh、移除模块目录
    adb_su(serial, "sh %s/action.sh stop" % shlex.quote(path), check=False)
    adb_su(serial, "sh %s/uninstall.sh" % shlex.quote(path), check=False)
    adb_su(serial, "rm -rf /data/adb/modules/%s /data/adb/modules_update/%s" % (MODULE_ID, MODULE_ID),
           check=False)
    adb_su(serial, "rm -f " + shlex.quote(remote), check=False)
    adb_shell(serial, "am force-stop %s" % MANAGER_PKG, check=False)
    adb(serial, "uninstall", MANAGER_PKG, check=False)
    info("==== Magisk 模块测试通过（已清理模块与测试 APK） ====")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def parse_args():
    here = Path(__file__).resolve().parent
    manager_root = here.parent
    p = argparse.ArgumentParser(description="打包开源版 Albatross 核心包 / Magisk 模块并测试 release")
    p.add_argument("--manager-root", type=Path, default=manager_root)
    p.add_argument("--server-root", type=Path, default=manager_root.parent / "AlbatrossServer")
    p.add_argument("--resource", type=Path, help="覆盖 resource 目录（默认 <server-root>/resource）")
    p.add_argument("--out", type=Path, default=manager_root / "build" / "open_source_pack")
    p.add_argument("--core-version", help="核心版本（默认读 <server-root>/VERSION）")
    p.add_argument("--abis", default=",".join(DEFAULT_ABIS), help="逗号分隔，默认 4 个 ABI")
    p.add_argument("--skip-core", action="store_true")
    p.add_argument("--skip-build", action="store_true")
    p.add_argument("--skip-module", action="store_true")
    p.add_argument("--offline", action="store_true", default=True,
                   help="gradle 离线构建（默认）")
    p.add_argument("--online", dest="offline", action="store_false")
    p.add_argument("--rerun-tasks", action="store_true")
    p.add_argument("--sdk", type=Path, help="Android SDK（默认 local.properties/环境变量）")
    p.add_argument("--keystore", type=Path)
    p.add_argument("--store-password")
    p.add_argument("--key-alias")
    p.add_argument("--key-password")
    p.add_argument("--test-device", help="release 测试设备 serial；不填只打包")
    p.add_argument("--direct-server", dest="direct_server", action="store_true", default=None,
                   help="真机测试时用 root 直起服务（默认：真机=是，模拟器=否）")
    p.add_argument("--no-direct-server", dest="direct_server", action="store_false")
    p.add_argument("--test-module", action="store_true",
                   help="在 --test-device 上安装 Magisk 模块并重启验证（默认会清理）")
    p.add_argument("--no-module-reboot", action="store_true",
                   help="安装模块后不重启（sepolicy 不生效，仅用于快速冒烟）")
    p.add_argument("--keep-running", action="store_true", help="测试后不停服务/不 force-stop")
    args = p.parse_args()
    args.manager_root = args.manager_root.resolve()
    args.server_root = args.server_root.resolve()
    args.resource = (args.resource or (args.server_root / "resource")).resolve()
    args.out = args.out.resolve()
    args.out.mkdir(parents=True, exist_ok=True)
    return args


def main():
    args = parse_args()
    print("manager: %s\nserver:  %s\nresource: %s\nout:     %s" % (
        args.manager_root, args.server_root, args.resource, args.out))
    core = None
    apk = None
    module = None
    if not args.skip_core:
        core = pack_core(args)
    else:
        cores = sorted(args.out.glob("core_open_*.zip"))
        if not cores:
            die("--skip-core 但 %s 下没有 core_open_*.zip" % args.out)
        core_zip = cores[-1]
        with zipfile.ZipFile(core_zip) as zf:
            version = zf.read("resource/version").decode("utf-8").strip()
        core = {"zip": core_zip, "version": args.core_version or version,
                "sha256": sha256_file(core_zip)}
        info("使用已有核心包: %s (version=%s)" % (core["zip"], core["version"]))
    apk = build_release(args)
    if not args.skip_module:
        module = assemble_module(args, core, apk)

    if args.test_device:
        if not core or not core.get("version"):
            die("测试需要核心版本")
        test_release(args.test_device, core, apk, keep_running=args.keep_running,
                     direct=args.direct_server)
        if args.test_module and module:
            test_module(args.test_device, module, reboot=not args.no_module_reboot)

    print("\n==== 产物 ====")
    print("core:   %s" % core["zip"])
    print("apk:    %s" % apk["zip"])
    if module:
        print("module: %s" % module["zip"])
    print("测试:   %s" % ("release " + args.test_device if args.test_device else "未执行（加 --test-device）"))


if __name__ == "__main__":
    try:
        main()
    except PackError as e:
        print("[pack] 错误: %s" % e, file=sys.stderr)
        sys.exit(1)
    except subprocess.TimeoutExpired as e:
        print("[pack] 超时: %s" % e, file=sys.stderr)
        sys.exit(1)
