#!/system/bin/sh
# Albatross 运行环境一键配置（root 执行，幂等，可重复跑）
#
# 由 service.sh（开机）/ customize.sh（刷入）/ action.sh（手动）调用：
#   1. 安装 AlbatrossManager 与插件 APK（内容 sha256 一致则跳过）
#   2. 把内置核心包交给 Manager 导入（广播 INSTALL_CORE），核心文件落到 App 私有目录
#   3. 以 root 直接启动 albatross_server（不走 Manager 的交互式 su，避免开机弹授权框）
#   4. 广播 LOAD_CORE 让 Manager 进程加载核心并连上服务
#   5. 注册插件、按 config.env 设置规则与运行选项，可选自动打开界面
#
# 用法: setup.sh [boot|now]
#   boot(默认) 等 sys.boot_completed 且用户解锁后再干活（有锁屏密码的机器必须解锁一次）
#   now        立即执行（刷入模块 / 手动触发时）
#
# 模块自身的路径与版本信息在 module_info.sh（打包时生成），配置见 config.env。

MODDIR=${MODDIR:-${0%/*}}
MODE=${1:-boot}

if [ ! -f "$MODDIR/module_info.sh" ]; then
  echo "setup.sh: 缺少 $MODDIR/module_info.sh"
  exit 1
fi
. "$MODDIR/module_info.sh"

: "${MGR_PKG:=}"
: "${PLUGIN_PKG:=}"
: "${CORE_ZIP:=}"
: "${CORE_VERSION:=}"
if [ -z "$MGR_PKG" ] || [ -z "$CORE_VERSION" ] || [ -z "$CORE_ZIP" ]; then
  echo "setup.sh: module_info.sh 不完整"
  exit 1
fi

STATE_DIR=/data/adb/albatross
mkdir -p "$STATE_DIR" 2>/dev/null
if [ ! -w "$STATE_DIR" ]; then
  # 非 Magisk 环境（比如 adb root 的模拟器）没有 /data/adb，退回 tmp
  STATE_DIR=/data/local/tmp/albatross-module
  mkdir -p "$STATE_DIR" 2>/dev/null
fi
LOG_FILE="$STATE_DIR/run.log"
TAG=AlbModule
ACTION_PREFIX=qing.albatross.manager.action.

AM=/system/bin/am
PM=/system/bin/pm
LOGCAT=/system/bin/logcat
DUMPSYS=/system/bin/dumpsys
# 用绝对路径的 toybox：restorecon 会把标签打回不带 per-package 类别的 app_data_file:s0，
# 这里要的是"照抄 App 自己目录的上下文"，所以用 stat -c %C + chcon
STAT_BIN=/system/bin/stat
[ -x "$STAT_BIN" ] || STAT_BIN=stat
CHCON_BIN=""
if [ -x /system/bin/chcon ]; then
  CHCON_BIN=/system/bin/chcon
elif command -v chcon >/dev/null 2>&1; then
  CHCON_BIN=chcon
fi

# ---------- 基础工具 ----------

log() {
  _line="[$(date '+%m-%d %H:%M:%S')] $*"
  echo "$_line"
  /system/bin/log -t "$TAG" "$_line" 2>/dev/null
  return 0
}

warn() { log "WARN: $*"; }

now_sec() { date '+%s'; }

# 配置：模块里的 config.env 是默认值，/data/adb/albatross/config.env 可覆盖（重刷模块不丢）
[ -f "$MODDIR/config.env" ] && . "$MODDIR/config.env"
[ -f "$STATE_DIR/config.env" ] && . "$STATE_DIR/config.env"
# guard 的降级配置优先级最高（自动收敛时写进去的，见 guard.sh）
[ -f "$STATE_DIR/guard/degrade.env" ] && . "$STATE_DIR/guard/degrade.env"
: "${AUTO_START:=1}"
: "${LAUNCH_UI:=0}"
: "${INSTALL_MANAGER:=1}"
: "${INSTALL_PLUGIN:=1}"
: "${ENABLE_JIT:=1}"
: "${ENABLE_ANTI_DETECTION:=0}"
: "${ENABLE_MEMFD_INJECT:=0}"
: "${ENABLE_KPM_HIDE:=0}"
: "${PLUGIN_TARGETS:=}"
: "${ROOT_PATH:=}"
: "${SERVER_ADDRESS:=}"
: "${SERVER_ADDRESS_AUTO:=1}"
: "${BROADCAST_TIMEOUT:=60}"
: "${GUARD_ENABLE:=1}"
# MODE=start（WebUI/action.sh 的"启动服务"）无视降级/配置，强制拉起
if [ "$MODE" = "start" ]; then
  AUTO_START=1
fi

is_on() {
  case "$1" in
    1|true|on|yes) return 0 ;;
    *) return 1 ;;
  esac
}

# 监听地址：没显式配置时用"设备序列号"派生一个唯一名字（面具装到哪台机器就哪台，
# 避免多台设备/多个 server 撞同一个抽象 socket 名）。
# 注意服务端把"首位是数字"的参数当 TCP 端口（src/server/albatross_server_entry.cpp），
# 所以必须带 albatross_ 前缀。
derive_server_address() {
  _sn=$(/system/bin/getprop ro.serialno 2>/dev/null)
  [ -n "$_sn" ] || _sn=$(/system/bin/getprop ro.boot.serialno 2>/dev/null)
  [ -n "$_sn" ] || _sn=$(/system/bin/getprop ro.product.model 2>/dev/null)
  _sn=$(printf '%s' "$_sn" | tr -cd 'A-Za-z0-9._-')
  [ -n "$_sn" ] || return 1
  printf 'albatross_%s' "$_sn"
}

# 取文件摘要：优先 sha256sum，退回 md5sum
SHA_TOOL=""
for _t in sha256sum md5sum; do
  if [ -x "/system/bin/$_t" ]; then SHA_TOOL="/system/bin/$_t"; break; fi
  if command -v "$_t" >/dev/null 2>&1; then SHA_TOOL="$_t"; break; fi
done

sha_of() {
  [ -n "$SHA_TOOL" ] || return 1
  [ -f "$1" ] || return 1
  "$SHA_TOOL" "$1" 2>/dev/null | cut -d' ' -f1
}

# 只留一条 setup.sh 在跑（手动触发与开机触发可能撞上）
acquire_lock() {
  _lock="$STATE_DIR/lock"
  if [ ! -d "$STATE_DIR" ]; then
    # 状态目录不可用就不加锁（只读环境里也要能跑）
    return 0
  fi
  if mkdir "$_lock" 2>/dev/null; then
    echo "$$ $(now_sec)" > "$_lock/pid"
    return 0
  fi
  _old_pid=$(awk '{print $1}' "$_lock/pid" 2>/dev/null)
  _old_time=$(awk '{print $2}' "$_lock/pid" 2>/dev/null)
  if [ -n "$_old_pid" ] && kill -0 "$_old_pid" 2>/dev/null; then
    # 进程还在，但超过 10 分钟没动静就当成卡死的残留（pid 可能被复用）
    if [ -n "$_old_time" ] && [ $(( $(now_sec) - _old_time )) -lt 600 ]; then
      return 1
    fi
  fi
  rm -rf "$_lock" 2>/dev/null
  if mkdir "$_lock" 2>/dev/null; then
    echo "$$ $(now_sec)" > "$_lock/pid"
    return 0
  fi
  return 1
}

release_lock() { rm -rf "$STATE_DIR/lock" 2>/dev/null; }

server_pid() { pidof albatross_server 2>/dev/null | tr -d '\r'; }

# 只列出 exe 指向 <root>albatross_server 的进程（区分设备上别人起的 albatross_server）
server_pids_at() {
  _root_dir="$1"
  for _p in $(server_pid); do
    _exe=$(readlink "/proc/$_p/exe" 2>/dev/null)
    if [ "$_exe" = "${_root_dir}albatross_server" ]; then
      printf '%s ' "$_p"
    fi
  done
}

# 广播一个 Manager 动作并等终态日志；成功返回 0，失败/超时返回 1，结果行打在 stdout
manager_action() {
  _action="$1"
  shift
  _try=0
  while [ "$_try" -lt 2 ]; do
  _before=$("$LOGCAT" -d -s AlbManagerAction:V 2>/dev/null | grep -c "action=$_action" || true)
  _am_out=$("$AM" broadcast -a "${ACTION_PREFIX}${_action}" -f 0x20 -p "$MGR_PKG" "$@" 2>&1)
  case "$_am_out" in
    *"Broadcast completed"*) ;;
    *) warn "am broadcast ${_action}: $_am_out" ;;
  esac
  _deadline=$(( $(now_sec) + BROADCAST_TIMEOUT ))
  _start=$(now_sec)
  _started=0
  while [ "$(now_sec)" -lt "$_deadline" ]; do
    _lines=$("$LOGCAT" -d -s AlbManagerAction:V 2>/dev/null | grep -F "action=$_action")
    _count=$(printf '%s\n' "$_lines" | grep -c .)
    [ "${_count:-0}" -gt "${_before:-0}" ] && _started=1
    # realme/OPPO/一加 会把后台 App 冻住：广播只入队、连 start 行都不会有。
    # 15 秒毫无动静就先重启 App 解冻再重发（已经在跑的动作不打断，等满超时）
    if [ "$_try" = 1 ] && [ "$_started" = 0 ] && [ $(( $(now_sec) - _start )) -ge 15 ]; then
      break
    fi
    if [ "${_count:-0}" -gt "${_before:-0}" ]; then
      _last=$(printf '%s\n' "$_lines" | tail -1)
      case "$_last" in
        *"MANAGER_ACTION success: action=$_action"*)
          echo "$_last"
          return 0
          ;;
        *"MANAGER_ACTION failed: action=$_action"*)
          echo "$_last"
          return 1
          ;;
      esac
    fi
    sleep 1
  done
  _try=$((_try + 1))
  if [ "$_try" -ge 2 ]; then
    break
  fi
  warn "${_action} 无响应（可能被系统冻结），重启 Manager 解冻后重试"
  $AM force-stop "$MGR_PKG" >/dev/null 2>&1
  sleep 3
done
  echo "timeout: action=$_action"
  return 1
}

# 未配置监听地址且允许自动：用设备序列号派生，并写进用户配置（WebUI 里能看到/可改）
if [ -z "$SERVER_ADDRESS" ] && is_on "$SERVER_ADDRESS_AUTO"; then
  _derived_addr=$(derive_server_address)
  if [ -n "$_derived_addr" ]; then
    SERVER_ADDRESS="$_derived_addr"
    if ! grep -q '^SERVER_ADDRESS=' "$STATE_DIR/config.env" 2>/dev/null; then
      mkdir -p "$STATE_DIR" 2>/dev/null
      printf 'SERVER_ADDRESS=%s\n' "$_derived_addr" >> "$STATE_DIR/config.env" 2>/dev/null
    fi
  fi
fi

# 从动作结果行里取字段：status_field "serverRunning=true, coreVersion=153" coreVersion -> 153
status_field() {
  printf '%s' "$1" | sed -n "s/.*$2=\([^,]*\).*/\1/p"
}

# ---------- 各步骤 ----------

# 安装 APK：内容与已装的一致就跳过
ensure_apk() {
  _pkg="$1"
  _rel="$2"
  _name="$3"
  _apk="$MODDIR/$_rel"
  if [ ! -f "$_apk" ]; then
    warn "模块内缺少 $_name ($_rel)"
    return 1
  fi
  _want=$(sha_of "$_apk")
  _installed=$("$PM" path "$_pkg" 2>/dev/null | head -1 | sed 's/^package://' | tr -d '\r')
  if [ -n "$_installed" ] && [ -f "$_installed" ] && [ -n "$_want" ] && [ "$(sha_of "$_installed")" = "$_want" ]; then
    log "$_name 已是最新，跳过安装"
    return 0
  fi
  _out=$("$PM" install -r "$_apk" 2>&1)
  case "$_out" in
    *Success*)
      log "$_name 安装成功"
      return 0
      ;;
    *UPDATE_INCOMPATIBLE*|*signatures*)
      warn "$_name 安装失败（设备上已有其它签名的同包名应用）: $_out"
      warn "请手动卸载 $_pkg 后重试，或用同一签名重新打包"
      return 1
      ;;
    *)
      warn "$_name 安装失败: $_out"
      return 1
      ;;
  esac
}

# 把 root 建出来的东西修成 App 自己的属主与 SELinux 上下文。
# root 建的文件是 root:root，标签只有 app_data_file:s0（没有 per-package 类别）：
# App 在里面建不了子目录，表现为 INSTALL_CORE 报"未找到适用于 <arch> 架构的文件"（真机实测）。
fix_app_owned() {
  _path="$1"
  _ref="$2"      # 参照目录：用它的属主与上下文
  _recursive="$3"
  [ -e "$_path" ] || return 0
  _want_owner=$("$STAT_BIN" -c '%u:%g' "$_ref" 2>/dev/null)
  _now_owner=$("$STAT_BIN" -c '%u:%g' "$_path" 2>/dev/null)
  if [ -n "$_want_owner" ] && [ "$_now_owner" != "$_want_owner" ]; then
    if [ "$_recursive" = 1 ]; then
      chown -R "$_want_owner" "$_path" 2>/dev/null
    else
      chown "$_want_owner" "$_path" 2>/dev/null
    fi
  fi
  if [ -n "$CHCON_BIN" ]; then
    _want_ctx=$("$STAT_BIN" -c '%C' "$_ref" 2>/dev/null)
    _now_ctx=$("$STAT_BIN" -c '%C' "$_path" 2>/dev/null)
    if [ -n "$_want_ctx" ] && [ "$_now_ctx" != "$_want_ctx" ]; then
      if [ "$_recursive" = 1 ]; then
        "$CHCON_BIN" -R "$_want_ctx" "$_path" 2>/dev/null
      else
        "$CHCON_BIN" "$_want_ctx" "$_path" 2>/dev/null
      fi
    fi
  fi
  return 0
}

# 把文件放到 App 一定读得到的位置，回显最终路径。
# 优先用 App 的 cache/：installd 装机时就建好，属主与 per-package SELinux 类别都对；
# files/ 在 Android 14+ 是懒创建的，root 抢先建会把属主/标签建坏（见 fix_app_owned），
# 所以只借用不新建；外部 /sdcard/Android/data/<pkg> 由 MediaProvider 建成 root:ext_data_rw 2770，
# root 放进去的文件 App 读不到，只作最后兜底。
stage_for_app() {
  _src="$1"
  _name="$2"
  _app="/data/data/$MGR_PKG"
  # App 自己要在 files/ 下建 servers/<ver>/，被我们（或旧版本模块）建坏过就先修回来
  [ -d "$_app/files" ] && fix_app_owned "$_app/files" "$_app" 0
  for _base in "$_app/cache" "$_app/files" "/storage/emulated/0/Android/data/$MGR_PKG/files"; do
    [ -d "$_base" ] || continue
    _dir="$_base/magisk"
    mkdir -p "$_dir" 2>/dev/null || continue
    cp -f "$_src" "$_dir/$_name" 2>/dev/null || continue
    fix_app_owned "$_dir" "$_base" 1
    chmod 755 "$_dir" 2>/dev/null
    chmod 644 "$_dir/$_name" 2>/dev/null
    printf '%s' "$_dir/$_name"
    return 0
  done
  return 1
}

ensure_core() {
  _version_dir="/data/data/$MGR_PKG/files/servers/$CORE_VERSION"
  _applied=$(cat "$STATE_DIR/core.applied" 2>/dev/null)
  _sha=$(sha_of "$MODDIR/$CORE_ZIP")
  if [ -d "$_version_dir" ] && [ -n "$_sha" ] && [ "$_applied" = "$_sha" ]; then
    log "核心 v$CORE_VERSION 已导入，跳过"
    return 0
  fi
  _staged=$(stage_for_app "$MODDIR/$CORE_ZIP" "$(basename "$CORE_ZIP")") || {
    warn "无法把核心包放到 App 可读位置"
    return 1
  }
  # 校验落地副本完整（拷贝/传输被截断时，导入会以"未找到适用于 <arch> 架构的文件"这种怪错收场）
  if [ -n "$_sha" ]; then
    _staged_sha=$(sha_of "$_staged")
    if [ -n "$_staged_sha" ] && [ "$_staged_sha" != "$_sha" ]; then
      warn "核心包副本校验失败（$_staged），重新拷贝一次"
      _staged=$(stage_for_app "$MODDIR/$CORE_ZIP" "$(basename "$CORE_ZIP")") || {
        warn "重新拷贝仍然失败"
        return 1
      }
      _staged_sha=$(sha_of "$_staged")
      [ "$_staged_sha" = "$_sha" ] || {
        warn "核心包副本仍然是坏的（$_staged_sha != $_sha）"
        return 1
      }
    fi
  fi
  log "导入核心 v$CORE_VERSION: $_staged"
  _out=$(manager_action INSTALL_CORE --es path "$_staged" --ez overwrite true --ez set_current true) || {
    warn "INSTALL_CORE 失败: $_out"
    return 1
  }
  _current=$(status_field "$_out" current)
  if [ "$_current" != "$CORE_VERSION" ]; then
    warn "INSTALL_CORE 后当前核心是 $_current，期望 $CORE_VERSION（界面里可能手动切过版本）"
  fi
  [ -n "$_sha" ] && printf '%s' "$_sha" > "$STATE_DIR/core.applied"
  rm -f "$_staged" 2>/dev/null
  log "核心导入完成: $_out"
  return 0
}

# root 直接拉起 albatross_server：复刻 ServerManager 的启动序列（cp+chmod+LD_LIBRARY_PATH）
ensure_server() {
  _status=$(manager_action STATUS) || {
    warn "STATUS 失败: $_status"
    return 1
  }
  _core=$(status_field "$_status" coreVersion)
  _root=$(status_field "$_status" rootPath)
  _addr=$(status_field "$_status" serverAddress)
  # config.env 里显式指定的根路径/监听地址：与 Manager 的配置保持一致
  if [ -n "$ROOT_PATH" ]; then
    case "$ROOT_PATH" in
      */) _want_root="$ROOT_PATH" ;;
      *) _want_root="$ROOT_PATH/" ;;
    esac
    if [ "$_root" != "$_want_root" ]; then
      _out=$(manager_action SET_ROOT_PATH --es path "$_want_root") || warn "SET_ROOT_PATH 失败: $_out"
    fi
    _root="$_want_root"
  fi
  [ -n "$_root" ] || _root=/data/local/tmp/albatross/
  case "$_root" in
    */) ;;
    *) _root="$_root/" ;;
  esac
  # 落盘有效 root 与服务状态快照：guard.sh / action.sh / WebUI 都读这两份
  printf '%s' "$_root" > "$STATE_DIR/root.path" 2>/dev/null
  mkdir -p "$STATE_DIR/runtime" 2>/dev/null
  printf '%s\n' "$_status" > "$STATE_DIR/runtime/status.snapshot" 2>/dev/null
  if [ -n "$SERVER_ADDRESS" ] && [ "$_addr" != "$SERVER_ADDRESS" ]; then
    _out=$(manager_action SET_SERVER_ADDRESS --es address "$SERVER_ADDRESS") || warn "SET_SERVER_ADDRESS 失败: $_out"
    _addr="$SERVER_ADDRESS"
  fi
  [ -n "$_addr" ] || _addr=albatross_manager

  # 以 Manager 的实际连接状态为准：设备上可能还跑着别的 albatross_server（不同监听名），
  # pidof 有结果不代表我们的环境就绪
  case "$_status" in
    *"serverRunning=true"*)
      log "服务已就绪（Manager 已连上 $_addr）"
      return 0
      ;;
  esac

  _pids=$(server_pid)
  for _p in $_pids; do
    _exe=$(readlink "/proc/$_p/exe" 2>/dev/null)
    if [ "$_exe" = "${_root}albatross_server" ]; then
      # 是我们自己 rootPath 下的服务，但 Manager 连不上：当成卡死的旧实例重启
      log "停掉未连上的旧服务 (pid=$_p, exe=$_exe)"
      kill "$_p" 2>/dev/null
      sleep 1
    else
      # 别人的服务（别的路径/监听名），不要动它，我们另起一个
      log "检测到其它 albatross_server (pid=$_p, exe=${_exe:-unknown})，保持不动"
    fi
  done

  [ -n "$_core" ] || {
    warn "还没安装核心，无法启动服务"
    return 1
  }
  _vdir="/data/data/$MGR_PKG/files/servers/$_core"
  if [ ! -d "$_vdir" ]; then
    warn "核心目录不存在: $_vdir"
    return 1
  fi
  mkdir -p "$_root" 2>/dev/null
  cp -f "$_vdir/albatross_server" "$_root/albatross_server" || warn "复制 albatross_server 失败（继续用现有文件）"
  cp -f "$_vdir/libalbatross_base.so" "$_root/libalbatross_base.so" || {
    warn "复制 libalbatross_base.so 失败"
    return 1
  }
  if [ -f "$_vdir/32bit/libalbatross_base.so" ]; then
    mkdir -p "$_root/32bit"
    cp -f "$_vdir/32bit/libalbatross_base.so" "$_root/32bit/libalbatross_base.so" 2>/dev/null
  fi
  cp -f "$_vdir/agent/app_agent.dex" "$_root/app_agent.dex"
  cp -f "$_vdir/agent/system_server.dex" "$_root/system_server.dex"
  chmod 755 "$_root/albatross_server" 2>/dev/null
  chmod 644 "$_root/libalbatross_base.so" 2>/dev/null
  chmod 444 "$_root/app_agent.dex" "$_root/system_server.dex" 2>/dev/null
  if [ -x /system/bin/setsid ]; then
    _launcher=setsid
  elif [ -x /system/bin/nohup ]; then
    _launcher=nohup
  else
    _launcher=
  fi
  # 记录启动前的 system_server pid：guard 的健康检查用它判断"服务起来后系统有没有换过 system_server"
  pidof system_server 2>/dev/null | tr -d '\r' > "$STATE_DIR/runtime/ss.pid" 2>/dev/null
  log "启动服务: ${_root}albatross_server $_addr (root=$_root)"
  cd "$_root" && LD_LIBRARY_PATH="$_root" $_launcher ./albatross_server "$_addr" </dev/null >>"$_root/albatross_manager.log" 2>&1 &
  _deadline=$(( $(now_sec) + 10 ))
  while [ "$(now_sec)" -lt "$_deadline" ]; do
    _mine=$(server_pids_at "$_root")
    if [ -n "$_mine" ]; then
      log "albatross_server 启动成功 (pid=$_mine)"
      return 0
    fi
    sleep 1
  done
  warn "albatross_server 未能启动，日志尾部："
  tail -n 10 "$_root/albatross_manager.log" 2>/dev/null
  return 1
}

ensure_connected() {
  _out=$(manager_action LOAD_CORE) || {
    warn "LOAD_CORE 失败: $_out"
    # connect 报 EACCES 基本都是 SELinux 没放行 untrusted_app → magisk 的 connectto
    if "$LOGCAT" -d -s Albatross:V 2>/dev/null | grep -q "connect .* fail:13"; then
      warn "检测到 connect EACCES：SELinux 未放行 untrusted_app → magisk 的 unix_stream_socket connectto"
      warn "本模块的 sepolicy.rule 会在 post-fs-data 阶段被自动加载；若仍失败可手工执行："
      warn "  magiskpolicy --live 'allow untrusted_app magisk unix_stream_socket connectto'"
    fi
    # Manager 的命令队列是单线程的：某个任务卡住后，后续广播会一直排在它后面。
    # 真机实测过这种"进程活着但再也不响应"的情况，重启 App 清队列后重试一次。
    warn "重启 Manager 进程清掉堵塞的命令队列后重试"
    restart_manager_app
    _out=$(manager_action LOAD_CORE) || {
      warn "重试仍然失败: $_out"
      return 1
    }
  }
  case "$_out" in
    *"serverConnected=true"*)
      printf 'connected=1 ts=%s\n' "$(now_sec)" > "$STATE_DIR/runtime/connect.state" 2>/dev/null
      log "Manager 已加载核心并连上服务"
      return 0
      ;;
    *)
      printf 'connected=0 ts=%s\n' "$(now_sec)" > "$STATE_DIR/runtime/connect.state" 2>/dev/null
      warn "Manager 未连上服务: $_out"
      return 1
      ;;
  esac
}

ensure_plugin() {
  _info=$(manager_action APP_INFO --es package "$PLUGIN_PKG")
  case "$_info" in
    *"isPlugin=true"*)
      log "插件已注册: $PLUGIN_PKG"
      ;;
    *)
      _staged=$(stage_for_app "$MODDIR/$PLUGIN_APK" "$(basename "$PLUGIN_APK")") || _staged=""
      if [ -n "$_staged" ]; then
        _out=$(manager_action INSTALL_PLUGIN --es package "$PLUGIN_PKG" --es path "$_staged")
      else
        _out=$(manager_action INSTALL_PLUGIN --es package "$PLUGIN_PKG")
      fi
      case "$_out" in
        *"MANAGER_ACTION success"*)
          log "插件注册完成: $_out"
          ;;
        *)
          warn "插件注册未完成: $_out"
          ;;
      esac
      ;;
  esac
  if [ -n "$PLUGIN_TARGETS" ]; then
    _out=$(manager_action SET_RULE --es package "$PLUGIN_PKG" --es targets "$PLUGIN_TARGETS" --es mode replace) ||
      warn "SET_RULE 失败: $_out"
  fi
  return 0
}

apply_options() {
  _want="jit=$ENABLE_JIT anti_detection=$ENABLE_ANTI_DETECTION memfd_inject=$ENABLE_MEMFD_INJECT auto_start=$AUTO_START"
  _applied=$(cat "$STATE_DIR/options.applied" 2>/dev/null)
  if [ "$_want" = "$_applied" ]; then
    log "运行选项未变化，跳过"
    return 0
  fi
  for _kv in "jit:$ENABLE_JIT" "anti_detection:$ENABLE_ANTI_DETECTION" "memfd_inject:$ENABLE_MEMFD_INJECT" "auto_start:$AUTO_START"; do
    _key=${_kv%%:*}
    _val=${_kv#*:}
    if is_on "$_val"; then _b=true; else _b=false; fi
    _out=$(manager_action SET_OPTION --es key "$_key" --ez value "$_b") ||
      warn "SET_OPTION $_key=$_b 失败: $_out"
  done
  printf '%s' "$_want" > "$STATE_DIR/options.applied"
  log "运行选项已下发: $_want"
  if is_on "$ENABLE_KPM_HIDE"; then
    _out=$(manager_action KERNEL_HIDE) || log "内核隐藏未生效（多数设备没有 rwbp.ko，属正常）: $_out"
  fi
  return 0
}

# 重启 Manager 进程：清掉卡住的任务队列（单线程 executor 的坑）
restart_manager_app() {
  $AM force-stop "$MGR_PKG" >/dev/null 2>&1
  sleep 2
  return 0
}

launch_ui() {
  _out=$("$AM" start --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$MGR_PKG" 2>&1)
  log "打开界面: $_out"
}

# ---------- 等待条件 ----------

is_unlocked() {
  [ "$(/system/bin/getprop sys.user.0.ce_available 2>/dev/null)" = "true" ] && return 0
  _u=$("$DUMPSYS" user 2>/dev/null)
  [ -z "$_u" ] && return 0
  case "$_u" in
    *RUNNING_UNLOCKED*) return 0 ;;
  esac
  return 1
}

wait_boot() {
  _n=0
  while [ "$(/system/bin/getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
    sleep 2
    _n=$((_n + 1))
    if [ "$_n" -gt 300 ]; then
      warn "等待 boot_completed 超时"
      return 1
    fi
  done
  _n=0
  while ! is_unlocked; do
    sleep 3
    _n=$((_n + 1))
    if [ "$_n" -gt 400 ]; then
      warn "等待用户解锁超时（有锁屏密码的设备需要解锁一次，模块下次开机会重试）"
      return 1
    fi
  done
  return 0
}

# ---------- 主流程 ----------

run_setup() {
  if is_on "$INSTALL_MANAGER"; then
    ensure_apk "$MGR_PKG" "$MGR_APK" "AlbatrossManager" || return 1
  fi
  if is_on "$INSTALL_PLUGIN" && [ -n "$PLUGIN_PKG" ] && [ -n "$PLUGIN_APK" ]; then
    ensure_apk "$PLUGIN_PKG" "$PLUGIN_APK" "插件" || warn "插件 APK 未装上，继续"
  fi
  ensure_core || return 1
  if is_on "$INSTALL_PLUGIN" && [ -n "$PLUGIN_PKG" ] && [ -n "$PLUGIN_APK" ]; then
    ensure_plugin
  fi
  if is_on "$AUTO_START"; then
    ensure_server || return 1
    ensure_connected || return 1
    apply_options
  else
    log "AUTO_START=0：只准备环境，不启动服务"
  fi
  return 0
}

# MODE=stop：停掉本模块启动的 albatross_server（只杀 exe 指向我们 rootPath 的实例）
stop_env() {
  _root=$(cat "$STATE_DIR/root.path" 2>/dev/null)
  [ -n "$_root" ] || _root=/data/local/tmp/albatross/
  case "$_root" in
    */) ;;
    *) _root="$_root/" ;;
  esac
  _killed=""
  for _p in $(pidof albatross_server 2>/dev/null | tr -d '\r'); do
    if [ "$(readlink "/proc/$_p/exe" 2>/dev/null)" = "${_root}albatross_server" ]; then
      kill "$_p" 2>/dev/null
      _killed="$_killed $_p"
    fi
  done
  mkdir -p "$STATE_DIR/runtime" 2>/dev/null
  printf 'connected=0 ts=%s\n' "$(now_sec)" > "$STATE_DIR/runtime/connect.state" 2>/dev/null
  if [ -n "$_killed" ]; then
    log "已停止 albatross_server:$_killed"
  else
    log "没有在运行的本模块服务"
  fi
  return 0
}

main() {
  if [ "$MODE" = "stop" ]; then
    stop_env
    exit 0
  fi
  if ! acquire_lock; then
    log "已有 setup.sh 在运行，本次跳过"
    exit 0
  fi
  trap 'release_lock' EXIT INT TERM HUP
  log "开始配置: mode=$MODE module=$MODULE_VERSION core=$CORE_VERSION pkg=$MGR_PKG"
  if [ "$MODE" = "boot" ]; then
    wait_boot
  fi
  _n=0
  while true; do
    _n=$((_n + 1))
    if run_setup; then
      log "环境就绪"
      # 启动后交给 guard 做健康窗口观察：通过才清"坏启动待定"；异常则收敛并可能降级
      if is_on "$AUTO_START" && [ -f "$MODDIR/guard.sh" ]; then
        _ss=$(cat "$STATE_DIR/runtime/ss.pid" 2>/dev/null)
        if [ "$MODE" = "boot" ]; then
          ( sh "$MODDIR/guard.sh" health "$_ss" --count >>"$STATE_DIR/run.log" 2>&1 ) &
        else
          ( sh "$MODDIR/guard.sh" health "$_ss" >>"$STATE_DIR/run.log" 2>&1 ) &
        fi
      fi
      break
    fi
    if [ "$_n" -ge 3 ]; then
      warn "配置未完成（已尝试 $_n 次），下次开机或点「操作」按钮会重试"
      break
    fi
    log "配置未完成，20 秒后重试（第 $_n 次，先重启 Manager 清队列）"
    restart_manager_app
    sleep 20
  done
  if is_on "$LAUNCH_UI"; then
    launch_ui
  fi
  release_lock
  trap - EXIT INT TERM HUP
  log "结束"
}

main
