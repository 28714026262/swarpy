# Swapy

面向内网文件的敏感词扫描、可逆脱敏与还原工具，采用 **Java 17 + SQLite + 浏览器界面**。文件留在服务所在主机或其可访问的共享盘，不上传到外部服务。

仓库名为 `swarpy`，产品名为 Swapy。当前为可运行首版，复杂格式和版式仍需实际业务文档验收。

## 功能

- **扫描**：先选择文件或目录，再点击开始；显示步骤与进度，仅列命中和异常项，支持暂停、继续、服务重启后的文件级断点及完整重扫。
- **脱敏与还原**：任务内一对一随机映射，默认标记平替 A，可选自然词平替 B；还原兼容两者，通过本地任务 ID、密码包或完整 JSON 密码本提供凭证。
- **四路并行**：扫描、脱敏、还原可同时提交，整个服务固定共享四个文件处理槽位。
- **词库**：离线导入更新、导出和词条维护；历史追加、ID 不复用、弃用保留，拒绝不兼容谱系、降级及冲突导入。Git/GitHub 同步和 Git 历史离线包暂缓。
- **任务迁移**：每任务独立目录和异常报告；稳定脱敏任务支持多选／全部导出密码包及批量原子导入，支持 HTML／JSON 密码本。

前端使用本地 React 17、Ant Design 4，无公网 CDN；目标 Chrome/Edge 80+，尚未在历史浏览器二进制上实测。

## 本地启动

需要 **JDK 17+、Maven**。首次构建需下载 Maven 依赖；以下命令在 PowerShell 中执行：

```powershell
git clone https://github.com/28714026262/swarpy.git
cd swarpy
mvn -f backend/pom.xml verify
./scripts/start.ps1
```

打开 **http://127.0.0.1:4175/**。首次启动词库为空，请先新增词条或导入兼容词库。服务不导入原型演示数据。

停止服务：

```powershell
./scripts/stop.ps1
```

脚本后台启动服务，使用随包运行时或系统 Java，将日志及进程记录保存到 `data/`。非 Windows 环境可直接运行：

```sh
java -jar backend/target/swapy-0.1.0.jar
```

### 指定文件访问范围

网页文件浏览器选择的是 **Java 主机可访问的文件**，当前没有浏览器用户电脑的原生文件夹对话框或独立桌面助手。

```powershell
$env:SWAPY_ROOTS='D:/documents;E:/shared'
$env:SWAPY_DATA='D:/swapy-local-data'
./scripts/start.ps1 -Port 4175
```

根目录以分号分隔；未配置时为工作目录和用户主目录。服务账户需具备相应权限。SQLite 数据目录必须放本机磁盘，不能放共享盘；输入可以使用挂载共享盘。默认仅监听 `127.0.0.1`。其他参数见 [后端说明](backend/README.md)。

## 使用与数据

1. 在词库页新增词条，或点击“导入更新”导入词库 JSON，系统完成版本校验和更新。
2. 扫描页选择目标，再点击“开始扫描”；按需勾选“完整重扫”。
3. 脱敏页设置输入、输出和平替方式，执行后在任务页查看报告、导出密码本或密码包。
4. 还原页选择脱敏结果目录，提供凭证，输出到新的任务目录。

默认产物为 `data/tasks/<任务号>/files/` 和同级 `report.html`；自定义输出时放到所选位置。原文件保留，报告仅记录异常并提供文件索引。扫描可沿原任务号恢复；脱敏／还原中断后保留已提交有效产物，重跑创建新任务。

精简密码包不含源文件，解算需要同谱系且版本足够的词库；完整 JSON 密码本可独立提供还原映射。**词库、密码本、密码包可能包含敏感内容或映射，应妥善保护并单独备份。** 仓库忽略本地数据、密码包、日志和构建产物。

完整 115,200 条平替库已通过浏览器文件导入、翻页和数据库计数验证。词库未变时使用 ETag／304 校验，避免每秒重复下载完整词库。[导入测试记录](docs/library-import-verification.md)

## 格式范围

| 格式 | 当前支持及边界 |
| --- | --- |
| TXT、LOG、MD、CSV | 文字处理；CSV 按字段引用、转义 |
| JSON、XML、YAML、TOML、INI、HTML | 按结构适配；JSON 键不参与替换 |
| SQL | 包含字段定义及引用，保护表名与关键字；无法安全绑定时整组失败并报告 |
| DOCX | 直接修改文字节点，可跨普通文字 run 匹配，保留未修改样式；复杂版式待验收 |
| PDF | 只改写可安全处理的文本层内容流；字体、空间或定位不满足时失败，不用遮盖代替删除 |
| RTF、ODT | 依赖可选 LibreOffice；缺少引擎明确报错，转换及复杂版式尚待实际环境验收 |
| 旧 DOC | 直接报错，不自动转换；请另存为 DOCX 后重试 |
| ZIP、7Z、RAR | 仅扫描，限制深度、大小和资源；加密归档跳过并报告 |
| 图片、OCR、独立源码文件 | 不在处理范围 |

不能安全处理的文件明确报告，不以原样复制冒充脱敏成功。详见 [实现审核及验收边界](docs/superpowers/specs/2026-11-24-swapy-implementation-review.md)。

## 可选 Docker 部署

```powershell
$env:SWAPY_INPUT_ROOT='D:/documents'
docker compose -f compose.java.yaml up -d --build
```

正式入口 http://127.0.0.1:4175/。使用只读输入挂载和数据卷，镜像配置包含 LibreOffice。当前已校验 Compose 配置，尚未实际验收镜像构建运行。容器可访问的文件取决于挂载路径。

静态原型使用 `docker compose up -d --build`，入口 http://127.0.0.1:4174/；该入口只演示交互，不承担真实文件处理。

## 开发、验证与发行

```powershell
mvn -f backend/pom.xml verify
node --check prototype/live.js
node --check prototype/app.js
docker compose -f compose.java.yaml config --quiet
```

最近完整回归 **50 项通过，无失败、无跳过**，覆盖真实临时 SQLite、格式处理、映射往返、扫描恢复、包导入原子性、Git 适配和 HTTP 接口。浏览器已验证选择不执行、点击开始才创建扫描、旧 DOC 报告及基本脱敏还原流程；不等同于全部业务文档版式保证。

Windows 发行目录：`./scripts/package.ps1`；可用 `-RuntimeHome <可分发JDK目录>` 生成随包运行时，未指定则依赖系统 Java 17。分发第三方组件须遵守相应许可证。

```text
backend/      Java 后端、SQLite、格式适配与测试
prototype/    正式页面、静态原型与本地前端依赖
scripts/      启动、停止和发行脚本
docs/         需求、设计、审核及开发记录
```

## 文档

- [需求规格](docs/requirements-v1.1.md) · [最新决策](docs/requirements-decisions.md)
- [Java / SQLite 设计](docs/superpowers/specs/2026-11-23-swapy-java-design.md) · [设计审核](docs/superpowers/specs/2026-11-23-swapy-java-review.md)
- [实现审核与边界](docs/superpowers/specs/2026-11-24-swapy-implementation-review.md) · [开发台账](docs/development-ledger.md)
- [部署说明](backend/README.md) · [产品事实](PRODUCT.md) · [界面设计](DESIGN.md)

历史文档可能记录被替换的方案，请以最新决策和 Java 方案为准。
