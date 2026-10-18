#!/system/bin/sh
# 开机自启入口（Magisk/APatch/KernelSU 的 late_start service 阶段执行）
# 真正的活儿都在 setup.sh 里做，这里只负责把它放到后台并落日志。

MODDIR=${MODDIR:-${0%/*}}
STATE_DIR=/data/adb/albatross
mkdir -p "$STATE_DIR" 2>/dev/null
if [ ! -w "$STATE_DIR" ]; then
  STATE_DIR=/data/local/tmp/albatross-module
  mkdir -p "$STATE_DIR" 2>/dev/null
fi
LOG_FILE="$STATE_DIR/run.log"

log -t AlbModule "service.sh: 调度 setup.sh (模块目录: $MODDIR, 日志: $LOG_FILE)" 2>/dev/null

# 启动自保护：安全模式 / 已被（自动或手动）禁用 / 连续坏启动到限 → 本次不启动
if [ -f "$MODDIR/guard.sh" ]; then
  if ! sh "$MODDIR/guard.sh" begin >>"$LOG_FILE" 2>&1; then
    log -t AlbModule "guard: 本次跳过自动启动（详见 $LOG_FILE）" 2>/dev/null
    exit 0
  fi
fi

# 放到后台执行：等待 boot_completed / 用户解锁可能要好几分钟，不能阻塞开机。
# 三个标准流全部断开：否则 adb shell / su -c 会一直等到这个后台进程结束
# （表现为"调用方卡住不动"），Magisk 也会等 service.sh 的整个进程组。
if [ -x /system/bin/setsid ]; then
  setsid sh "$MODDIR/setup.sh" boot </dev/null >>"$LOG_FILE" 2>&1 &
else
  sh "$MODDIR/setup.sh" boot </dev/null >>"$LOG_FILE" 2>&1 &
fi
