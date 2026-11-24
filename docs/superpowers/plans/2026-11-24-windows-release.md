# Windows one-click release plan

用户明确要求推送远端并制作包含软件环境的一键安装启动包，发布GitHub Release。当前Windows主机，默认目标Windows10/11 x64，v0.1.0首发；沿用无需反复审批的执行方式。用writing-plans、TDD及最终独立审核完成。

提供安装EXE和便携ZIP；内置可分发Temurin17精简运行时、SQLite JDBC、本地前端和当前V5词库。用户最新明确“先不做LibreOffice”，因此不打包转换引擎或额外微软运行库，RTF/ODT缺少引擎明确报错。无需JDK/Maven/Docker/公网词库。仅监听localhost。安装版任务数据位于用户LocalAppData独立目录，便携版放自身data，可由SWAPY_DATA覆盖；卸载保留用户数据。

首次启动仅在空词库时导入随包CSV；已有词库和任务不覆盖。启动与停止入口使用Windows自带PowerShell5.1，打包脚本保留UTF8 BOM，中文和空格路径可用。默认打开浏览器；无浏览器模式供验证。

- [ ] 失败测试覆盖发布必备内容、内置运行时、初始词库及保留已有版本。
- [ ] 打包脚本、启动入口、NSIS安装器、第三方许可及下载校验与来源清单。
- [ ] Java全套回归，实际离线安装、便携启动、词库初始化、重启/升级/卸载保留数据，MD/DOCX/JSON/SQL脱敏还原及缺少转换引擎报错。
- [ ] 一次独立审核修复阻断问题，完善README和Release说明，生成SHA256校验文件。
- [ ] 推送、打tag、上传草稿资产，回下载校验后发布Release并核对资产与远端。
