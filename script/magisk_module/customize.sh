#!/system/bin/sh
# Magisk/APatch/KernelSU 安装模块时执行（$MODPATH 是模块安装目录）
# 刷入时如果系统已启动（BOOTMODE=true），顺带立即配置一遍，免得非要重启。

SKIPMOUNT=true
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=false

. "$MODPATH/module_info.sh"

ui_print "****************************************"
ui_print " Albatross 运行环境"
ui_print " 模块版本: $MODULE_VERSION"
ui_print " 内置核心: v$CORE_VERSION ($CORE_ZIP)"
ui_print " 内置 APK: $(basename "$MGR_APK")"
ui_print " 目标应用: $MGR_PKG"
ui_print " 配置说明: 模块目录下 config.env"
ui_print "****************************************"

set_perm "$MODPATH/setup.sh" 0 0 0755
set_perm "$MODPATH/guard.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/customize.sh" 0 0 0755

ui_print "- 文件已就位"

if [ "$BOOTMODE" = "true" ]; then
  ui_print "- 检测到系统已启动，立即配置运行环境（也可以忽略，重启后会自动完成）"
  sh "$MODPATH/setup.sh" now >/dev/null 2>&1
  ui_print "- 完成；详情见 /data/adb/albatross/run.log，也可点模块的「操作」按钮重跑"
else
  ui_print "- 重启后模块会自动安装核心/插件并拉起服务"
fi
