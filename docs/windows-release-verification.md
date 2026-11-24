# Windows 发布验证

目标：Windows 10/11 x64，使用系统 Windows PowerShell 5.1 启动，不依赖系统 Java。本次按用户要求不打包 LibreOffice。

构建执行 `scripts/package.ps1`：在隔离输出目录运行 Maven verify，固定上游 ZIP 与词库的 SHA256；每次从已验证归档重新提取 Java 与安装工具，保留原始许可证。只有源码指纹与 JAR 哈希都相同，才允许 `-UseVerifiedJar` 复用已验证构建。

发布测试执行 `python -m unittest discover -s scripts/tests -v`。覆盖空库初始化、已有库保留、初始化错误、升级拒绝中断活动任务、服务状态不明时拒绝停止、安装解压失败、干净提交与源码校验、草稿恢复、未验证资产拒绝发布及实际替换检查。

真实验收执行 `python scripts/tests/release_smoke.py <release.json>`，仅使用生成的 EXE、ZIP 和随包 Java：

- 从 PATH 移除全局 Java、清除 JAVA_HOME，在中文及空格目录安装与启动。
- 校验实际 Java 进程路径、Temurin 版本、V5 的 54,213 条平替词与 540 条敏感词。
- MD、DOCX、JSON、SQL 扫描、自然平替、还原；要求存在映射、文字确实改变、脱敏后无示例敏感词、还原文字一致且原文不变。MD 逐字节一致。
- 无转换引擎的 RTF 进入明确异常报告，不把复制原文算作成功。
- 加入测试词形成 V6，重启及运行中的重新安装均保留版本与任务。
- 卸载保留独立数据目录及安装目录中的额外用户文件。
- 解压实际便携 ZIP、逐文件核对哈希、重新初始化 V5 并验证便携数据目录。

每次结果保存到忽略提交的 `dist/qa-*/result.json`，绑定 EXE/ZIP 哈希与源码提交。发布脚本强制校验该证据、干净 HEAD、远端 main、对应 OpenJDK 源码及精确资产列表；上传草稿后逐资产回下载计算 SHA256，全部通过才公开发布。

这是当前 Windows 主机上的真实验证，不代表已在全新系统镜像、每个 Windows 版本或历史 Chrome/Edge 80 二进制上完成认证；复杂办公文档仍需业务样例验收。
