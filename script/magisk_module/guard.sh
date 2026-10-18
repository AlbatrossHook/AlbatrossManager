#!/system/bin/sh
# Albatross 运行环境模块的"启动自保护"。
#
# 目的：如果本模块的启动把系统搞坏（内核 panic / system_server 挂掉 / 反复重启），
# 要能被发现并自动收敛，而不是一直硬刚：
#   level 0 正常 → level 1 关高风险开关（内核隐藏/对抗/memfd）→ level 2 只装不启服务
#   → level 3 写模块 disable 文件彻底停用（管理器里一键恢复 / action.sh guard-reset）
#
# 判定手段：
#   * 每次开机在 service.sh 最前面跑 `guard.sh begin`：若上次开机我们启动过、
#     但没走完健康窗口，且开机后很短时间（GUARD_EARLY_REBOOT_SECONDS）就重启了，
#     记一次"坏启动"；若能读到 /sys/fs/pstore 里上轮的内核崩溃记录，一并记证据。
#   * 启动完成后再跑 `guard.sh health <启动前 system_server pid>`：等健康窗口
#     （GUARD_HEALTH_SECONDS），确认 boot_completed 还在、system_server 没换 pid、
#     我们起的 albatross_server 还活着、crash buffer 里没有新的 system 进程 tombstone；
#     通过才写 "上一轮 OK"（清 boot_pending），否则按坏启动处理并立刻停掉我们起的服务。
#
# 用法：
#   guard.sh begin      判断本次开机是否允许自启（退出码 0=允许，1=跳过/已禁用）
#   guard.sh ok         健康窗口通过
#   guard.sh health [pre_system_server_pid] [--count]   睡健康窗口后自检（--count 才计坏启动）
#   guard.sh fail "<原因>" [--count]                    手动记一次坏启动并收敛
#   guard.sh status     打印状态（k=v + 事件流水，供 action.sh / WebUI 解析）
#   guard.sh reset      复位：清计数、删 disable、清降级配置（用户手动恢复）
#   guard.sh disable    写模块 disable（立即停用，下次开机不再启动）
#   guard.sh enable     删模块 disable
#
# 配置（config.env）：
#   GUARD_ENABLE=1              总开关，0=不做任何保护判定
#   GUARD_MAX_BAD_BOOTS=3       连续坏启动达到几次就自动禁用
#   GUARD_EARLY_REBOOT_SECONDS=300  上次启动后多久内重启算"疑似启动导致"
#   GUARD_HEALTH_SECONDS=60     启动后健康观察窗口
#   GUARD_DISABLE_ON_LIMIT=1    到限是否写 disable（0=只降级到 level 2）

MODDIR=${MODDIR:-${0%/*}}
[ -f "$MODDIR/module_info.sh" ] && . "$MODDIR/module_info.sh"

STATE_DIR=/data/adb/albatross
mkdir -p "$STATE_DIR" 2>/dev/null
if [ ! -w "$STATE_DIR" ]; then
  STATE_DIR=/data/local/tmp/albatross-module
  mkdir -p "$STATE_DIR" 2>/dev/null
fi
GUARD_DIR="$STATE_DIR/guard"
mkdir -p "$GUARD_DIR" 2>/dev/null

TAG=AlbModule
AM=/system/bin/am
LOGCAT=/system/bin/logcat

log() {
  _line="[$(date '+%m-%d %H:%M:%S')] guard: $*"
  echo "$_line"
  /system/bin/log -t "$TAG" "$_line" 2>/dev/null
  return 0
}
warn() { log "WARN: $*"; }
now_sec() { date '+%s'; }

# 配置：模块 config.env → 状态目录的覆盖 config.env（degrade.env 由本脚本维护，这里不读，
# 避免降级配置把自己的 GUARD_* 开关也一起锁死）
[ -f "$MODDIR/config.env" ] && . "$MODDIR/config.env"
[ -f "$STATE_DIR/config.env" ] && . "$STATE_DIR/config.env"
: "${GUARD_ENABLE:=1}"
: "${GUARD_MAX_BAD_BOOTS:=3}"
: "${GUARD_EARLY_REBOOT_SECONDS:=300}"
: "${GUARD_HEALTH_SECONDS:=60}"
: "${GUARD_DISABLE_ON_LIMIT:=1}"

is_on() {
  case "$1" in
    1|true|on|yes) return 0 ;;
    *) return 1 ;;
  esac
}

read_int() { # read_int <file> <default>
  _v=$(cat "$1" 2>/dev/null | tr -dc '0-9')
  if [ -z "$_v" ]; then
    echo "$2"
  else
    echo "$_v"
  fi
}

state_get() { read_int "$GUARD_DIR/$1" "$2"; }
state_set() { printf '%s' "$2" > "$GUARD_DIR/$1" 2>/dev/null; }

append_event() {
  printf '[%s] %s\n' "$(date '+%m-%d %H:%M:%S')" "$*" >> "$GUARD_DIR/events.log" 2>/dev/null
  # 事件流水留最近 200 条，避免无限增长
  if [ -f "$GUARD_DIR/events.log" ]; then
    _n=$(wc -l < "$GUARD_DIR/events.log" 2>/dev/null | tr -d ' ')
    if [ -n "$_n" ] && [ "$_n" -gt 200 ]; then
      tail -n 200 "$GUARD_DIR/events.log" > "$GUARD_DIR/events.log.tmp" 2>/dev/null &&
        mv -f "$GUARD_DIR/events.log.tmp" "$GUARD_DIR/events.log" 2>/dev/null
    fi
  fi
  return 0
}

is_disabled() { [ -f "$MODDIR/disable" ]; }
is_removing() { [ -f "$MODDIR/remove" ]; }

# 本次开机时刻 = 当前时间 - 已运行秒数（用来算"上次启动后多久就重启了"）
boot_epoch() {
  _up=$(cut -d. -f1 /proc/uptime 2>/dev/null)
  case "$_up" in
    ''|*[!0-9]*) _up=0 ;;
  esac
  echo $(( $(now_sec) - _up ))
}

# 上轮是否有内核崩溃证据（pstore 在重启后仍然可读）
pstore_evidence() {
  for _f in /sys/fs/pstore/console-ramoops*; do
    [ -f "$_f" ] || continue
    if grep -qiE "kernel panic|Unable to handle kernel|BUG: |watchdog" "$_f" 2>/dev/null; then
      printf '%s' "$(basename "$_f")"
      return 0
    fi
  done
  return 1
}

# 降级落盘：level → degrade.env（setup.sh 会最后加载它，优先级最高）
write_degrade() {
  _lvl="$1"
  case "$_lvl" in
    0)
      rm -f "$GUARD_DIR/degrade.env"
      ;;
    1)
      cat > "$GUARD_DIR/degrade.env" <<'EOF'
# guard 自动降级 level 1：关掉高风险开关（内核隐藏走内核模块，风险最高）
ENABLE_KPM_HIDE=0
ENABLE_ANTI_DETECTION=0
ENABLE_MEMFD_INJECT=0
EOF
      ;;
    2)
      cat > "$GUARD_DIR/degrade.env" <<'EOF'
# guard 自动降级 level 2：只装环境，不再自动拉起服务
ENABLE_KPM_HIDE=0
ENABLE_ANTI_DETECTION=0
ENABLE_MEMFD_INJECT=0
AUTO_START=0
EOF
      ;;
    3)
      cat > "$GUARD_DIR/degrade.env" <<'EOF'
# guard 自动降级 level 3：模块已自动禁用
ENABLE_KPM_HIDE=0
ENABLE_ANTI_DETECTION=0
ENABLE_MEMFD_INJECT=0
AUTO_START=0
EOF
      ;;
  esac
  state_set level "$_lvl"
  return 0
}

apply_ladder() {
  _bad="$1"
  _reason="$2"
  _lvl=0
  if [ "$_bad" -ge "$GUARD_MAX_BAD_BOOTS" ]; then
    _lvl=3
  elif [ "$_bad" -ge 2 ]; then
    _lvl=2
  elif [ "$_bad" -ge 1 ]; then
    _lvl=1
  fi
  if [ "$_lvl" = 3 ] && ! is_on "$GUARD_DISABLE_ON_LIMIT"; then
    _lvl=2
  fi
  write_degrade "$_lvl"
  case "$_lvl" in
    0) ;;
    1)
      warn "连续坏启动 $_bad 次（$_reason）：降级到 level 1，已关掉 内核隐藏/对抗/memfd"
      append_event "level=1 bad=$_bad 关闭高风险开关（$_reason）"
      ;;
    2)
      warn "连续坏启动 $_bad 次（$_reason）：降级到 level 2，只装环境不再自动启动服务"
      append_event "level=2 bad=$_bad AUTO_START=0（$_reason）"
      ;;
    3)
      warn "连续坏启动 $_bad 次（$_reason）：已写模块 disable，本模块自动停用"
      warn "恢复方法：action.sh guard-reset（或管理器里重新启用模块，WebUI 里也有按钮）"
      if ! is_disabled; then
        : > "$MODDIR/disable" 2>/dev/null
      fi
      append_event "level=3 bad=$_bad 已自动禁用（$_reason）"
      ;;
  esac
  return 0
}

count_bad_boot() {
  _reason="$1"
  _bad=$(( $(state_get bad_boots 0) + 1 ))
  state_set bad_boots "$_bad"
  apply_ladder "$_bad" "$_reason"
  return 0
}

# 停掉我们自己起的服务（收敛用；不碰别人的 albatross_server）
stop_our_server() {
  _root=$(cat "$STATE_DIR/root.path" 2>/dev/null)
  [ -n "$_root" ] || _root=/data/local/tmp/albatross/
  case "$_root" in
    */) ;;
    *) _root="$_root/" ;;
  esac
  for _p in $(pidof albatross_server 2>/dev/null | tr -d '\r'); do
    _exe=$(readlink "/proc/$_p/exe" 2>/dev/null)
    if [ "$_exe" = "${_root}albatross_server" ]; then
      log "停掉本模块启动的服务 (pid=$_p)"
      kill "$_p" 2>/dev/null
    fi
  done
  return 0
}

# ---------- 子命令 ----------

do_begin() {
  if ! is_on "$GUARD_ENABLE"; then
    log "guard 已关闭（GUARD_ENABLE=0），跳过判定"
    return 0
  fi
  if is_disabled; then
    log "检测到模块 disable 标记，本次不启动"
    return 1
  fi
  if is_removing; then
    log "检测到模块正在被卸载，本次不启动"
    return 1
  fi

  # 安全模式：所有模块都不该在这时候乱动系统
  if [ "$(/system/bin/getprop persist.sys.safemode 2>/dev/null)" = "1" ] ||
     [ -f /data/adb/magisk/safe-mode ] || [ -f /data/adb/ksu/safe-mode ] || [ -f /data/adb/ap/safe-mode ]; then
    log "系统处于安全模式，本次跳过自动启动"
    append_event "安全模式，跳过本次启动"
    return 1
  fi

  # 上一轮是否走完了健康窗口
  _pending=$(state_get boot_pending 0)
  if [ "$_pending" != 0 ]; then
    _reason=""
    _last=$(state_get last_start 0)
    if [ "$_last" != 0 ]; then
      _alive=$(( $(boot_epoch) - _last ))
      # 负数=开机后时钟被校准/上次记录来自另一个时间基线，不能当成"刚启动就重启"
      if [ "$_alive" -ge 0 ] && [ "$_alive" -lt "$GUARD_EARLY_REBOOT_SECONDS" ]; then
        _reason="上次启动后 ${_alive}s 就重启（< ${GUARD_EARLY_REBOOT_SECONDS}s）"
      fi
    else
      _reason="上次启动没有留下时间戳"
    fi
    _ev=$(pstore_evidence 2>/dev/null)
    if [ -n "$_ev" ]; then
      _reason="${_reason:+$_reason；}内核崩溃记录: $_ev"
    fi
    if [ -n "$_reason" ]; then
      count_bad_boot "$_reason"
      if is_disabled; then
        return 1
      fi
    else
      log "上次启动未完成健康窗口，但未发现异常重启证据，本次按正常启动继续"
      append_event "上次启动未完成健康窗口（无异常证据），继续尝试"
    fi
  fi

  state_set boot_pending 1
  state_set last_start "$(now_sec)"
  log "guard begin: bad_boots=$(state_get bad_boots 0) level=$(state_get level 0)"
  return 0
}

do_ok() {
  state_set boot_pending 0
  state_set last_ok "$(now_sec)"
  _bad=$(state_get bad_boots 0)
  if [ "$_bad" != 0 ]; then
    state_set bad_boots 0
    log "健康窗口通过，坏启动计数已清零"
    append_event "健康窗口通过，bad_boots 归零（level 保持 $(state_get level 0)，可用 guard-reset 恢复）"
  else
    log "健康窗口通过"
  fi
  return 0
}

do_health() {
  _pre_ss="$1"
  _count="$2"
  _wait="$GUARD_HEALTH_SECONDS"
  log "健康窗口观察 ${_wait}s（启动前 system_server pid=${_pre_ss:-未知}）"
  sleep "$_wait"

  _fail=""
  if [ "$(/system/bin/getprop sys.boot_completed 2>/dev/null)" != "1" ]; then
    _fail="boot_completed 不是 1"
  else
    _ss=$(pidof system_server 2>/dev/null | tr -d '\r')
    if [ -z "$_ss" ]; then
      _fail="system_server 不见了"
    elif [ -n "$_pre_ss" ] && [ "$_ss" != "$_pre_ss" ]; then
      _fail="system_server 换过 pid（$_pre_ss -> $_ss）"
    else
      _root=$(cat "$STATE_DIR/root.path" 2>/dev/null)
      [ -n "$_root" ] || _root=/data/local/tmp/albatross/
      case "$_root" in */) ;; *) _root="$_root/" ;; esac
      _ours=0
      for _p in $(pidof albatross_server 2>/dev/null | tr -d '\r'); do
        _exe=$(readlink "/proc/$_p/exe" 2>/dev/null)
        [ "$_exe" = "${_root}albatross_server" ] && _ours=1
      done
      if [ "$_ours" = 0 ]; then
        _fail="本模块启动的 albatross_server 已经不在了"
      else
        _crash=$("$LOGCAT" -b crash -d -t 200 2>/dev/null | grep -cE "system_server|zygote" || true)
        if [ "${_crash:-0}" != "0" ]; then
          _fail="crash buffer 里出现 system_server/zygote 崩溃记录"
        fi
      fi
    fi
  fi

  if [ -z "$_fail" ]; then
    do_ok
    return 0
  fi

  warn "健康检查失败：$_fail"
  append_event "健康检查失败：$_fail"
  if [ "$_count" = "--count" ]; then
    stop_our_server
    "$AM" force-stop "${MGR_PKG:-qing.albatross.manager}" >/dev/null 2>&1
    count_bad_boot "健康检查失败：$_fail"
  else
    # 手动触发（now 模式）：只停服务收敛，不动降级档位
    stop_our_server
    "$AM" force-stop "${MGR_PKG:-qing.albatross.manager}" >/dev/null 2>&1
  fi
  return 1
}

do_fail() {
  _reason="$1"
  _count="$2"
  [ -n "$_reason" ] || _reason="手动标记"
  append_event "fail: $_reason"
  stop_our_server
  if [ "$_count" = "--count" ]; then
    count_bad_boot "$_reason"
  else
    warn "记录异常（不计数）：$_reason"
  fi
  return 1
}

do_status() {
  printf '__ALB_GUARD__\n'
  printf 'enabled=%s\n' "$GUARD_ENABLE"
  printf 'level=%s\n' "$(state_get level 0)"
  printf 'bad_boots=%s\n' "$(state_get bad_boots 0)"
  printf 'boot_pending=%s\n' "$(state_get boot_pending 0)"
  printf 'last_start=%s\n' "$(state_get last_start 0)"
  printf 'last_ok=%s\n' "$(state_get last_ok 0)"
  printf 'disabled=%s\n' "$(is_disabled && echo 1 || echo 0)"
  printf 'max_bad_boots=%s\n' "$GUARD_MAX_BAD_BOOTS"
  printf 'early_reboot_seconds=%s\n' "$GUARD_EARLY_REBOOT_SECONDS"
  printf 'health_seconds=%s\n' "$GUARD_HEALTH_SECONDS"
  printf 'disable_on_limit=%s\n' "$GUARD_DISABLE_ON_LIMIT"
  printf 'events:\n'
  tail -n 6 "$GUARD_DIR/events.log" 2>/dev/null
  return 0
}

do_reset() {
  state_set boot_pending 0
  state_set bad_boots 0
  write_degrade 0
  rm -f "$MODDIR/disable" 2>/dev/null
  append_event "用户复位：计数清零、降级配置与 disable 已移除"
  log "已复位：bad_boots=0 level=0 disable 已移除"
  return 0
}

do_disable() {
  : > "$MODDIR/disable" 2>/dev/null
  append_event "用户手动禁用模块（写 disable）"
  log "已写 $MODDIR/disable（下次开机不再启动；管理器里也可启用）"
  return 0
}

do_enable() {
  rm -f "$MODDIR/disable" 2>/dev/null
  append_event "用户手动恢复模块（删 disable）"
  log "已移除 $MODDIR/disable"
  return 0
}

CMD="$1"
shift 2>/dev/null
case "$CMD" in
  begin) do_begin ;;
  ok) do_ok ;;
  health) do_health "$1" "$2" ;;
  fail) do_fail "$1" "$2" ;;
  status) do_status ;;
  reset) do_reset ;;
  disable) do_disable ;;
  enable) do_enable ;;
  *)
    echo "用法: guard.sh {begin|ok|health [pid] [--count]|fail <原因> [--count]|status|reset|disable|enable}"
    exit 2
    ;;
esac
