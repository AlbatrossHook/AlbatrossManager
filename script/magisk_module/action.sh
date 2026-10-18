#!/system/bin/sh
# 模块操作入口：管理器「操作」按钮 / WebUI（ksu.exec）/ adb 三处共用，root 运行。
#
# 无参数（或 action）= 老行为：立即重新配置一遍 + 状态 + 最近日志（管理器「操作」按钮用）
#
# 子命令：
#   status              机器可读状态（k=v + guard 事件 + 最近一次状态快照），WebUI 用
#   log [run|server] [n] 日志尾部（run=模块日志，server=服务日志，默认 run 30 行）
#   start               启动运行环境（装环境 + 起服务 + 让 Manager 连上）
#   stop                停掉本模块启动的 albatross_server
#   setup-now           重新配置一遍（「操作」按钮的核心动作）
#   open-manager        打开 AlbatrossManager 界面
#   get-config          列出有效配置（模块 config.env → 用户覆盖 → guard 降级）
#   set-config KEY=VAL  写入用户覆盖配置（白名单键，重刷模块不丢）
#   guard-status        保护机制状态
#   guard-reset         复位保护（清坏启动计数、删 disable、清降级配置）
#   guard-disable       立即停用模块（写 disable，下次开机不再启动）
#   guard-enable        恢复模块（删 disable）

MODDIR=${MODDIR:-${0%/*}}
STATE_DIR=/data/adb/albatross
mkdir -p "$STATE_DIR" 2>/dev/null
if [ ! -w "$STATE_DIR" ]; then
  STATE_DIR=/data/local/tmp/albatross-module
  mkdir -p "$STATE_DIR" 2>/dev/null
fi

SETUP="$MODDIR/setup.sh"
GUARD="$MODDIR/guard.sh"
CONFIG_USER="$STATE_DIR/config.env"
DEGRADE="$STATE_DIR/guard/degrade.env"
RUNTIME="$STATE_DIR/runtime"
mkdir -p "$RUNTIME" 2>/dev/null
AM=/system/bin/am

MGR_PKG=$(grep -m1 '^MGR_PKG=' "$MODDIR/module_info.sh" 2>/dev/null | cut -d"'" -f2)
[ -n "$MGR_PKG" ] || MGR_PKG=qing.albatross.manager
CORE_VERSION=$(grep -m1 '^CORE_VERSION=' "$MODDIR/module_info.sh" 2>/dev/null | cut -d"'" -f2)
MODULE_VERSION=$(grep -m1 '^MODULE_VERSION=' "$MODDIR/module_info.sh" 2>/dev/null | cut -d"'" -f2)

# 有效配置：与 setup.sh 的加载顺序一致（模块 config.env → 用户覆盖 → guard 降级，后面的覆盖前面的）
[ -f "$MODDIR/config.env" ] && . "$MODDIR/config.env"
[ -f "$CONFIG_USER" ] && . "$CONFIG_USER"
[ -f "$DEGRADE" ] && . "$DEGRADE"
: "${AUTO_START:=1}"; : "${LAUNCH_UI:=0}"
: "${INSTALL_MANAGER:=1}"; : "${INSTALL_PLUGIN:=1}"
: "${ENABLE_JIT:=1}"; : "${ENABLE_ANTI_DETECTION:=0}"; : "${ENABLE_MEMFD_INJECT:=0}"; : "${ENABLE_KPM_HIDE:=0}"
: "${PLUGIN_TARGETS:=}"; : "${ROOT_PATH:=}"; : "${SERVER_ADDRESS:=}"; : "${SERVER_ADDRESS_AUTO:=1}"
: "${GUARD_ENABLE:=1}"; : "${GUARD_MAX_BAD_BOOTS:=3}"; : "${GUARD_EARLY_REBOOT_SECONDS:=300}"
: "${GUARD_HEALTH_SECONDS:=60}"; : "${GUARD_DISABLE_ON_LIMIT:=1}"
: "${SERVER_ADDRESS_AUTO:=1}"

CONFIG_KEYS="AUTO_START LAUNCH_UI INSTALL_MANAGER INSTALL_PLUGIN ENABLE_JIT ENABLE_ANTI_DETECTION ENABLE_MEMFD_INJECT ENABLE_KPM_HIDE PLUGIN_TARGETS ROOT_PATH SERVER_ADDRESS BROADCAST_TIMEOUT GUARD_ENABLE GUARD_MAX_BAD_BOOTS GUARD_EARLY_REBOOT_SECONDS GUARD_HEALTH_SECONDS GUARD_DISABLE_ON_LIMIT"

server_pids() { pidof albatross_server 2>/dev/null | tr -d '\r'; }
our_root() {
  _r=$(cat "$STATE_DIR/root.path" 2>/dev/null)
  [ -n "$_r" ] || _r=/data/local/tmp/albatross/
  case "$_r" in */) ;; *) _r="$_r/" ;; esac
  printf '%s' "$_r"
}
our_server_pids() {
  _root=$(our_root)
  for _p in $(server_pids); do
    [ "$(readlink "/proc/$_p/exe" 2>/dev/null)" = "${_root}albatross_server" ] && printf '%s ' "$_p"
  done
}

# ---------- 子命令 ----------

do_status() {
  _root=$(our_root)
  _ours=$(our_server_pids)
  printf '__ALB_STATUS__\n'
  printf 'module_version=%s\n' "${MODULE_VERSION:-unknown}"
  printf 'core_version=%s\n' "${CORE_VERSION:-unknown}"
  printf 'server=%s\n' "$([ -n "${_ours// /}" ] && echo 1 || echo 0)"
  printf 'server_pid=%s\n' "${_ours% }"
  printf 'root_path=%s\n' "$_root"
  printf 'manager_installed=%s\n' "$([ -n "$(/system/bin/pm path "$MGR_PKG" 2>/dev/null | head -1)" ] && echo 1 || echo 0)"
  # 最近一次配置时观察到的 Manager 侧状态（由 setup.sh 落盘）
  if [ -f "$RUNTIME/status.snapshot" ]; then
    grep -oE "(serverRunning|coreVersion|plugins|rules|coreLoaded|kpmHide|serverAddress)=[^,]*" "$RUNTIME/status.snapshot" 2>/dev/null
  fi
  printf 'config_auto_start=%s\n' "$AUTO_START"
  printf 'config_launch_ui=%s\n' "$LAUNCH_UI"
  printf 'config_jit=%s\n' "$ENABLE_JIT"
  printf 'config_anti_detection=%s\n' "$ENABLE_ANTI_DETECTION"
  printf 'config_memfd_inject=%s\n' "$ENABLE_MEMFD_INJECT"
  printf 'config_kpm_hide=%s\n' "$ENABLE_KPM_HIDE"
  printf 'config_plugin_targets=%s\n' "$PLUGIN_TARGETS"
  printf 'config_server_address=%s\n' "$SERVER_ADDRESS"
  printf 'config_server_address_auto=%s\n' "$SERVER_ADDRESS_AUTO"
  printf 'config_root_path=%s\n' "$ROOT_PATH"
  printf 'degraded=%s\n' "$([ -f "$DEGRADE" ] && echo 1 || echo 0)"
  printf 'disabled=%s\n' "$([ -f "$MODDIR/disable" ] && echo 1 || echo 0)"
  if [ -f "$GUARD" ]; then
    sh "$GUARD" status 2>/dev/null | sed '1d; s/^/guard_/'
  fi
  return 0
}

do_log() {
  _which="$1"
  _n="$2"
  case "$_n" in ''|*[!0-9]*) _n=30 ;; esac
  printf '__ALB_LOG__\n'
  case "$_which" in
    server)
      _root=$(our_root)
      tail -n "$_n" "${_root}albatross_manager.log" 2>/dev/null || echo "(没有服务日志: ${_root}albatross_manager.log)"
      ;;
    *)
      tail -n "$_n" "$STATE_DIR/run.log" 2>/dev/null || echo "(没有模块日志: $STATE_DIR/run.log)"
      ;;
  esac
  return 0
}

do_get_config() {
  printf '__ALB_CFG__\n'
  for _k in $CONFIG_KEYS; do
    eval "_v=\${$_k:-}"
    printf '%s=%s\n' "$_k" "$_v"
  done
  return 0
}

is_valid_key() {
  for _k in $CONFIG_KEYS; do
    [ "$_k" = "$1" ] && return 0
  done
  return 1
}

do_set_config() {
  _kv="$1"
  _key=${_kv%%=*}
  _val=${_kv#*=}
  if [ "$_key" = "$_kv" ] || ! is_valid_key "$_key"; then
    printf '不支持的配置键: %s\n' "$_key"
    return 1
  fi
  case "$_key" in
    SERVER_ADDRESS)
      # 空值 = 改回"自动（设备序列号）"
      if [ -z "$_val" ]; then
        sed '/^SERVER_ADDRESS=/d' "$CONFIG_USER" > "$CONFIG_USER.tmp" 2>/dev/null &&
          mv -f "$CONFIG_USER.tmp" "$CONFIG_USER"
        printf 'SERVER_ADDRESS 已改回自动（用设备序列号派生）\n'
        return 0
      fi
      case "$_val" in
        [A-Za-z]*) ;;
        *) printf '监听地址要以字母开头（纯数字会被服务端当成 TCP 端口）: %s\n' "$_val"; return 1 ;;
      esac
      case "$_val" in
        *[!A-Za-z0-9._-]*) printf '值里有非法字符: %s\n' "$_val"; return 1 ;;
      esac
      ;;
    PLUGIN_TARGETS|ROOT_PATH)
      case "$_val" in
        *[!A-Za-z0-9._:/,-]*) printf '值里有非法字符: %s\n' "$_val"; return 1 ;;
      esac
      ;;
    AUTO_START|LAUNCH_UI|INSTALL_MANAGER|INSTALL_PLUGIN|ENABLE_JIT|ENABLE_ANTI_DETECTION|ENABLE_MEMFD_INJECT|ENABLE_KPM_HIDE|GUARD_ENABLE|GUARD_DISABLE_ON_LIMIT)
      case "$_val" in 0|1|true|false|on|off) ;; *) printf '开关只能是 0/1: %s\n' "$_val"; return 1 ;; esac
      ;;
    *)
      case "$_val" in ''|*[!0-9]*) printf '数值非法: %s\n' "$_val"; return 1 ;; esac
      ;;
  esac
  mkdir -p "$STATE_DIR" 2>/dev/null
  touch "$CONFIG_USER" 2>/dev/null
  if grep -q "^${_key}=" "$CONFIG_USER" 2>/dev/null; then
    sed "s|^${_key}=.*$|${_key}=${_val}|" "$CONFIG_USER" > "$CONFIG_USER.tmp" 2>/dev/null &&
      mv -f "$CONFIG_USER.tmp" "$CONFIG_USER"
  else
    printf '%s=%s\n' "$_key" "$_val" >> "$CONFIG_USER"
  fi
  printf '%s=%s 已写入 %s（重新配置或重启后生效）\n' "$_key" "$_val" "$CONFIG_USER"
  return 0
}

do_start() { sh "$SETUP" start; }
do_stop() { sh "$SETUP" stop; }
do_setup_now() { sh "$SETUP" now; }

do_open_manager() {
  _out=$("$AM" start --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$MGR_PKG" 2>&1)
  printf '%s\n' "$_out"
  return 0
}

do_guard() { sh "$GUARD" "$1"; }

# ---------- 分发 ----------

CMD="$1"
shift 2>/dev/null
case "$CMD" in
  ''|action)
    printf '=== 重新配置 Albatross 运行环境\n'
    sh "$SETUP" now
    printf '\n=== albatross_server 进程\n'
    _p=$(our_server_pids)
    if [ -n "${_p// /}" ]; then printf '%s\n' "${_p% }"; else printf '(未运行)\n'; fi
    printf '\n=== 最近日志 (%s/run.log)\n' "$STATE_DIR"
    tail -n 30 "$STATE_DIR/run.log" 2>/dev/null || printf '(暂无日志)\n'
    ;;
  status) do_status ;;
  log) do_log "$1" "$2" ;;
  start) do_start ;;
  stop) do_stop ;;
  setup-now) do_setup_now ;;
  open-manager) do_open_manager ;;
  get-config) do_get_config ;;
  set-config) do_set_config "$1" ;;
  guard-status) do_guard status ;;
  guard-reset) do_guard reset ;;
  guard-disable) do_guard disable ;;
  guard-enable) do_guard enable ;;
  *)
    printf '用法: action.sh [status|log [run|server] [n]|start|stop|setup-now|open-manager|get-config|set-config KEY=VAL|guard-status|guard-reset|guard-disable|guard-enable]\n'
    exit 2
    ;;
esac
