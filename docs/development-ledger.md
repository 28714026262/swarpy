# SDD ledger — plan: docs/superpowers/plans/2026-11-23-swapy-java.md

授权：用户要求完善审核设计后直接开始开发，采用最近讨论的本地Java/SQLite路线；用户不需再次逐项批准。

Pre-flight: Task1提供Store/Catalog/Paths；Task2消费CatalogSnapshot；Task3消费Task1并调用Task4适配器；Task4使用Task2变换；Task5组合全部服务。为打破Task3/4依赖，Task3先使用真实普通文本适配，Task4扩展注册表，不另写虚假处理器。

Ruling: 当前目录无Git仓库，直接在backend子目录增加代码并保留既有原型，不执行worktree/commit脚本；代价是没有Git提交隔离，使用文件化台账记录。

设计独立审核完成：已修订同卷暂存原子发布、原生选择器桌面会话限制、SQL组持久化。进入内联实施，先测试后实现。

Tasks 1–3 基线：真实SQLite、匹配、普通文本/JSON/XML和扫描调度共15项测试通过（2026-11-23，backend/target/surefire-reports）。初始测试先出现缺失实现失败；数据库初始化发现未关闭PRAGMA结果集导致SQLITE_BUSY，关闭后通过。保持WAL连接避免每次短连接关闭触发清理，并等待协调器停止后关闭数据库，解决重启测试超时及Windows文件占用。

Task1 fixture修正：冲突批次中的新词必须introducedVersion=2，原夹具错误地设为1而先触发历史错误；保留ID_CONFLICT与全批回滚断言，未放宽业务校验。

## 首版交付验证（2026-11-24）

Tasks 1–5 的首版实现已落地。最终 `mvn -o -f backend/pom.xml verify` 于 00:44:35 完成，48 项测试、0 失败、0 错误、0 跳过；结果位于 `backend/target/surefire-reports`，构建日志 `backend-final-verification.log`。测试 JVM 和 Maven 限制内存及 CPU 数，避免本机其他 Java 工作负载造成资源耗尽。

独立代码审核发现的关键问题及处置见 [实现审核记录](superpowers/specs/2026-11-24-swapy-implementation-review.md)。修复包含产物与映射原子提交、完整结果树原子发布、原文缓存隔离、SQL 约束引用、目录链接边界、PDF 预留宽度、CSV 引用、词库检查点与精简密码包身份校验。新增回归验证通过；没有将原始审核描述为无条件通过，也未宣称进行第二轮独立审核。

额外检查通过：`node --check prototype/live.js`、`node --check prototype/bootstrap.js`、`node .impeccable/review/verify-transfer.cjs`（1000 个唯一 ID 及交换协议回归）、`docker compose -f compose.java.yaml config --quiet`。Docker 镜像没有实际构建运行，历史 Chrome/Edge 80 二进制没有实测。

真实浏览器与 HTTP 联调使用隔离的 `backend/target/qa-data` 和 `qa-input`，端口 4176；通过页面新增词库、选择目录自动扫描、脱敏、输入任务 ID 还原。最终 JAR 的脱敏任务 `T_a2bfac1f-30de-43fe-b2c3-8c068dfd3e3e` 和还原任务 `T_b59e6d1b-32be-4ba1-b996-c75b3d413fc1` 均成功，Markdown 还原内容与原输入完全一致。HTTP 密码包导出／同 ID 幂等导入、完整 JSON 密码本及异常报告请求通过。浏览器截图 `backend/target/qa-scan.png`、`qa-restore.png` 已目视检查。

隔离测试服务及自动化浏览器已关闭。正式本地服务运行于 http://127.0.0.1:4175/，使用 `D:/workspace/swapy/data`；启动验证时词库版本为 0，任务列表为 `[]`，未混入测试数据。停止命令 `./scripts/stop.ps1`。

发行裁定：提供可执行 JAR 和启动／停止／发行脚本；本轮不附带未经确认可分发的系统 JDK。DOC/RTF/ODT 的 LibreOffice 转换及复杂文档版式尚待真实环境样例验收，保留明确失败语义。其他裁定和未验收范围统一记入实现审核记录。

## 后续交互与格式修订（2026-11-24）

按用户新决定，正式页与静态原型都删除顶栏、面包屑和重复的固定四路提示。扫描先选目标、再点开始；词库仅保留离线导入更新／导出入口，Git 历史包及 GitHub 同步暂缓。DOCX 继续文字节点改写，旧 DOC 在读取或写出前直接返回 LEGACY_DOC_UNSUPPORTED，不生成结果文件；新增 DocumentTest 回归先失败后修复。

首次全套回归中，160 文件断点测试在 20 秒截止时仍处于正常推进（已完成 58，活跃 2，无处理失败），受到本机负载影响。该用例验证恢复正确性，不承担性能 SLA；统一异步等待上限调整为 90 秒，保持文件数、去重和全部异常断言不变，随后重新执行全套验证。

最终 `mvn -o -f backend/pom.xml verify`（backend-ui-final-verification.log）通过：49 项、0 失败、0 错误、0 跳过。真实页面确认选择目录前后任务数不变，点击开始后创建扫描；旧 DOC 出现在异常表中，消息为“不支持旧版 DOC，请先另存为 DOCX 后重试”，词库页没有同步按钮。桌面截图 qa-minimal-scan.png 已目视检查；窄屏检查发现原有导航和 ID 竖排，补充 live.css 修复为图标导航及表格水平滚动，随后仅重新打包静态资源（backend-ui-resource-build.log），不重复运行已通过的后端套件。

## 大词库浏览器导入（2026-11-24）

真实页面上传完整 115,200 条词库、翻页及 SQLite 计数通过。发现并修复每秒重复下载约 26 MB 及重叠请求，增加 ETag/304 与客户端合并请求、导入加载状态。新增 HTTP 回归先红后绿，全套 50 项通过；验证细节见 [导入验证记录](library-import-verification.md)。正式数据未导入，隔离服务已关闭。
