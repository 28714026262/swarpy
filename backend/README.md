# Swapy 本地 Java 服务

Java17+，单服务、SQLite本机持久化、全局固定四路。启动后打开 http://127.0.0.1:4175/ 。静态原型仍可独立运行，真实服务入口不会导入原型样例词库或任务。

## 开发启动

```powershell
mvn -f backend/pom.xml verify
./scripts/start.ps1
```

停止：`./scripts/stop.ps1`。数据库、报告、任务及断点在 `data/`。扫描可在暂停或服务重启后沿原任务号继续；脱敏、还原中断后保留产物，重跑新建任务。

发行目录：`scripts/package.ps1`；可通过 `-RuntimeHome <可分发JDK目录>` 生成随包运行时，未带runtime时使用系统Java17。仅构建本机发行文件，不上传或发布到外部。

## 部署参数

| 参数 | 用途 |
| --- | --- |
| SWAPY_DATA | 本机数据库与任务默认目录；不可指向共享盘 |
| SWAPY_ROOTS | 用分号分隔的允许输入／输出根目录；未配置为工作目录和用户主目录 |
| --swapy.output=… | 默认输出根目录；网页仍可选择已登记根内其他目录 |
| SWAPY_CATALOG_REPO | 本机词库Git工作副本；发布生成swapy-vN标签，同步校验版本链，不自动push |
| SWAPY_PUBLISHER | 词库权威发布端设true；配置Git的普通实例默认只读同步，避免多设备各自生成同版分叉；未配置Git的单机模式可本地维护 |
| SWAPY_SOFFICE | LibreOffice soffice.com/soffice.exe/soffice位置，用于RTF/ODT |
| --server.port=… | 本机访问端口，默认4175 |

选择目录默认使用网页文件浏览器，浏览Java服务可访问的已登记目录；不上传原文。默认仅允许本机访问，无图形选择器或独立客户端组件。

未配置Git时可使用本机词库和手工导入导出；统一多设备发布应指定同一权威来源，不能将不同lineage的版本号直接比较。缺少Git仓库时同步按钮明确报错。

## 格式与验证边界

文本、MD、CSV、JSON、XML、YAML、TOML、INI、HTML、SQL、DOCX由Java适配；ZIP/7Z/RAR只扫描。PDF只处理文本层，使用真实内容流改写；字体不能编码目标、文字无法放入原空间或跨操作符无法安全定位时明确失败，绝不以白框遮盖代替删除，也不OCR。

旧版 DOC 直接报 LEGACY_DOC_UNSUPPORTED，扫描、脱敏、还原均不尝试转换；请另存为 DOCX 后重试。RTF/ODT使用LibreOffice独立临时profile转换，缺少引擎报告OFFICE_ENGINE_UNAVAILABLE，不输出原样副本。本机未安装该引擎时，完整转换和复杂排版不能视为已经验收。Docker可选镜像包含该引擎；版式仍需使用业务样例验收。

每任务一份仅异常项的HTML报告；密码本提供HTML与完整JSON。只允许稳定脱敏记录导出任务包，支持多选／全部，批量导入完整验证后原子提交。包不含原文，精简包解算依赖词库，完整JSON可以独立还原。

## 可选Docker部署

```powershell
docker compose -f compose.java.yaml up -d --build
```

只读挂载SWAPY_INPUT_ROOT（默认当前目录），数据库与输出使用命名卷。与原型compose.yaml分开，不替换已有原型服务。

## 验证

`mvn -f backend/pom.xml verify` 使用真实临时SQLite、文件、Git仓库及HTTP服务；日志与结果在backend/target/surefire-reports。浏览器依赖本地分发，无公网CDN。第三方依赖版本在pom.xml锁定，依赖许可与运行时许可应随发行归档；文档产物不得把示例回归测试等同于全部业务文档版式保证。

## 当前界面交互

扫描先选择输入，再点击开始；选择不会创建任务。词库页仅保留导入更新和导出，导入会校验谱系、版本、不可变 ID 及追加历史并原子更新。独立 Git 同步入口、完整 Git 历史包和外部 GitHub 词库暂缓；无需配置 Git 即可使用当前离线流程。
