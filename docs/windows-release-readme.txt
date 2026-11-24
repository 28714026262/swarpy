Swapy 0.1.0 — Windows 10/11 x64

安装版
双击 Swapy-0.1.0-windows-x64-setup.exe。安装完成后自动启动并打开浏览器。
以后可使用桌面或开始菜单中的 Swapy 快捷方式。
首次启动会初始化随包词库，请等待完成。

便携版
完整解压 portable.zip 到可写目录，双击 Start Swapy.cmd。
不要在压缩包里直接运行。停止服务使用 Stop Swapy.cmd。
关闭浏览器不会停止后台服务。

内置环境
Eclipse Temurin Java 17、SQLite、本地前端资源和 V5 词库。
不需要另装 JDK、Maven、Node.js、Python 或 Docker。
打开 http://127.0.0.1:4175/，使用 Chrome/Edge 80+。
仅目标兼容历史浏览器，未在历史 80 版本二进制上实测。

数据与升级
安装版数据位于 %LOCALAPPDATA%\Swapy\data；升级或卸载保留该目录。
便携版数据位于解压目录的 data，请随程序目录一起保留并备份。
已有词库不会被随包词库覆盖，新版本词库可在“词库管理”离线导入。
已有任务执行时，安装器会提示先完成任务或暂停扫描。
原文、输出目录和扫描断点均保存在本地，不上传外部服务。

词库
随包 V5：54,213 条平替词、540 条军事示例敏感词（含电子战）。
示例不是涉密判定清单。ES/EA/EP 等短缩写可能跨语境误报，可按需弃用。
英文短语按字面空格和连字符匹配，不自动判断上下文词性。

格式边界
DOCX 直接处理文字；本次不包含 LibreOffice，RTF/ODT 缺少转换引擎时明确报错。
旧 DOC 明确报错；图片、OCR、独立源码不处理；压缩包仅扫描。
PDF 仅处理安全的文本层，不能安全替换的文件进入异常报告。
复杂版式需用业务样例验收，不保证与办公软件显示完全一致。

常见情况
端口被其他实例占用时，可在命令行运行： "Start Swapy.cmd" -Port 4177
更多输入目录可通过 SWAPY_ROOTS 环境变量配置（以分号分隔）。
可通过 SWAPY_DATA 指定数据目录，SQLite 数据目录须位于本机磁盘。
启动失败查看数据目录中的 service.log 和 service-error.log。

第三方许可和来源见 THIRD-PARTY-SOURCES.md、runtime/legal 和 licenses 目录。
发行文件的 SHA256 见 Release 中的 SHA256SUMS.txt。
