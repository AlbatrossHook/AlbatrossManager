#!/system/bin/sh
# 模块卸载时执行：停掉模块拉起的服务、清掉模块产生的临时文件。
# 注意：不会卸载 AlbatrossManager / 插件 APK，也不动它们在 /data/data 下的数据，
#       需要彻底清理请自行卸载应用。

MODDIR=${MODDIR:-${0%/*}}
[ -f "$MODDIR/module_info.sh" ] && . "$MODDIR/module_info.sh"

# 只停本模块启动的服务：设备上可能还有别人/别的模块起的 albatross_server
STATE_DIR=/data/adb/albatross
_root=$(cat "$STATE_DIR/root.path" 2>/dev/null)
[ -n "$_root" ] || _root=/data/local/tmp/albatross/
case "$_root" in */) ;; *) _root="$_root/" ;; esac
for _p in $(pidof albatross_server 2>/dev/null | tr -d '\r'); do
  if [ "$(readlink "/proc/$_p/exe" 2>/dev/null)" = "${_root}albatross_server" ]; then
    kill "$_p" 2>/dev/null
  fi
done

# /data/adb/albatross 是 Albatross 模块共享的状态目录，设备上还有别的 albatross 模块时保留
if [ ! -d /data/adb/modules/albatross_manager ] &&
   [ ! -d /data/adb/modules_update/albatross_manager ]; then
  rm -rf "$STATE_DIR"
fi

if [ -n "$MGR_PKG" ]; then
  rm -rf "/data/data/$MGR_PKG/files/magisk" "/storage/emulated/0/Android/data/$MGR_PKG/files/magisk"
fi

log -t AlbModule "uninstall.sh: 已停止 albatross_server 并清理临时文件" 2>/dev/null
