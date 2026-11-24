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

## 基础粗分类与 CSV（2026-11-24）

授权：用户明确重新定义基础版，不考虑高低版本迁移；最终指定 CSV 最小化。实现按计划内联完成，正式词库替换前必须确认没有任务与敏感记录并完整备份。

CSV 编解码、粗类别匹配及格式检查先写失败用例，再实现；初次13项核心测试通过。数据基础库25,200条，十类、42属性／英文长度组合各600条，纯数字编号仅600条，CSV3,040,929字节；7项数据校验通过。

独立 basic_csv_review 审核发现邮箱／IP句末句号漏匹配、数量／时刻可匹配较大值的一部分，以及合法长元数据导出不能导回。前两项为Important，第三项按实际备份不可回读的影响提升为Important。全部加入回归，观察失败后修复；最终57项通过，无失败或跳过。没有发起第二次独立审核。

Final: fixed 地址句末标点与数值／时刻完整边界 — MappingEngineTest 的结构边界两项用例 RED→GREEN。
Final: fixed 合法长元数据 CSV 往返 — LibraryCsvTest.existingLargeMetadataStillRoundTrips RED→GREEN，保留文件总量限制而不增加写入端没有的字段限额。
Final: Ruling: 自然语言词性仍基于来源及明确排除规则，未宣称全量人工审校；代表性格式之外明确无候选，不随机改变单位或日期写法。
Final: Ruling: 当前数据只覆盖 README 列明的日期／数值／联系方式格式；基础版不是所有格式的笛卡尔积。扩大格式范围留待实际样例。

隔离浏览器实际导入CSV成功，新增测试敏感词“星辰医院”归为设施名并脱敏成功。agent-browser 的下载捕获在本机取消，原生 in-app browser 已实际保存 swapy-library-v2.csv，核对包含25,200平替词和1个测试敏感词，下载文件已移至 backend/target/qa-basic-export-native.csv；该原生文件再导入返回200。没有把自动化工具的取消误报为原生导出成功；正式库不包含测试敏感词。

导出词库改用只读附件GET，以浏览器原生下载交付CSV；保留POST接口供已有API调用。API测试验证两入口字节一致。临时服务和自动化浏览器已关闭。

正式落地：停止服务并取得 service.lock 后，再次确认任务0、敏感词0，完整备份原115200条库到 data/backups/before-basic-csv-20261124-120409.db；只重建词库表，不清理其他数据。正式CSV导入返回200，核对25200条、10类、600数字编号。正式服务4175已恢复。CSV原生下载回读与再导入成功，数据7项测试、后端57项测试通过。

## 词语聚焦、英文库与筛选（2026-11-24）

用户继续收敛范围，默认删除具体值，保留七类词语、名称与文字标识。19,200条基础库重定前确认任务与敏感词均为空、停止服务并备份至data/backups/before-word-focus-20261124-140044.db。后续英文扩展通过兼容V2导入，完整保留这19,200条ID及属性，增加11,400条，合计30,600条。

英文通用词取自固定校验的WordNet3.0词性源，四种词性各600；可读英文标识6–20个ASCII字母各600。来源许可随CSV单文件保留，Java与Python编解码均忽略许可行作为数据词条。词库页面新增关键词／ID与四项组合筛选，API绑定参数、转义LIKE通配符、限定页大小；总数和分页使用同一读事务，摘要轮询取代完整词库获取。

独立只读basic_csv_review审核：Important为英文最短3字母错误排除go/do/be，先添加失败数据测试，再放宽为2字母重建尚未发布的英文扩展。Minor为覆盖报告“十类”残留，已改七类。没有发起二次审核。

Final: fixed 常用短英文动词缺失 — 数据测试RED→GREEN，旧19,200记录逐项保持。
Final: fixed 报告类别过期 — 当前生成报告七类，与CSV一致。
Final: deferred 前端快速交替筛选／迟到响应的自动化回归 — 当前有AbortController、active守卫与分页收缩处理，浏览器验证组合筛选和清空搜索；未新增针对这些竞态的自动化测试。
Final: Ruling: 英文词义和词性未全量人工审核；四个通用词词性各600不等于每个字母长度600，候选不足明确报告。旧客户端不能处理许可行不在本轮基础版兼容范围。

完整Maven verify 60项通过，数据10项通过，浏览器英文＋动词600条、CREATE查create、清空恢复及控制台检查通过。正式升级前备份before-english-query-20261124-143647.db，兼容导入HTTP200，正式CSV导出与发布文件SHA256一致，隔离服务及浏览器已关闭。查询验证详见basic-csv-verification.md。

## 英文名称、词形与多词性（2026-11-24）

授权：用户批准补英文人名／地点、常用词形及同词多词性。沿用既定Java／SQLite／CSV和不逐项提问的执行方式。使用writing-plans、TDD及executing-plans流程，独立只读basic_csv_review做一次最终审查。

StoreTest.poolSupportsMultiplePosButSensitiveSurfacesStayUnambiguous先因AMBIGUOUS_WORD失败，再将pool启用唯一键扩为词面＋POS＋form＋标准role；敏感词仍按词面唯一。MappingEngine不改变分配规则，新增跨词性候选同词面只分配一次及过去式不混现在分词的往返回归。LibraryCsvTest.inflectionNoticeIsRetainedOnExportAndReimport先失败后增加LemmInflect来源许可往返。

生成器保留原30,600条ID及属性，V3新增中性合成人名、地点、固定词形词典候选及多词性补齐。数据三项新增需求用例先失败后通过。初稿名称只列偶数长度，审核将其评为Important；新增连续长度测试观察失败，再补7–22每个长度各600条。最终52,993条、88个组合，数据14项通过。

Final: fixed 名称奇数长度无候选 — test_english_names_cover_contiguous_common_lengths RED→GREEN，数据14/14。
Final: Ruling: 词形与合成名称未全量人工审校 — 使用固定带标签词形词典和overrides，保留来源许可与校验；不声称任意语境自然，若源标注有误需追加弃用修订。
Final: Ruling: 来源许可适用范围未作法律判断 — 保留上游原许可及来源信息，不增写法律保证；如业务分发有额外合规要求需另核对。
Final: Ruling: 审核未等候父任务最终测试 — 不将审核意见当测试结果，实际读取64项Java测试、14项数据测试及隔离文件往返结果作为证据。

Java测试64/64通过；正式服务持有JAR导致repackage失败，停止空闲服务后package成功，未改动Java代码无需重复测试。隔离浏览器V3导入、词形选择通过；四项敏感词真实自然平替及65字节还原完全一致。正常升级前备份before-english-completion-20261124-150039.db，正式导入HTTP200，导出与发布文件SHA一致。正式敏感词0，测试任务仅在隔离目录，隔离服务关闭。

边界：人名／地点本轮明确覆盖7–22字母；普通英文词仍按相同字母数筛选，不保证每长度600。敏感词需显式选择词性与形态，不自动语境消歧或基础词变形扩展。逐字母大小写保持以支持无损还原，词界不同的英文合成名称可能出现非标准内部大写。

## 军事示例敏感库首批（2026-11-24）

用户要求继续填充敏感库，以军事词汇为首批例子；沿用先前持续执行、正式兼容导入及两个仓库推送授权。人工整理360条（中文193／英文167），仅公开通用术语及部分英文词形、缩写、文字标识，不添加实际部队番号、坐标、人物或具体数值。词性和粗属性显式配置，不把词清单等同于涉密判定。

增加种子seeds/military.csv与scripts/military.py。稳定S_UUID追加到主CSV，旧52,993个pool ID及属性逐项不变。为reconnaissance的14字母名词缺口补20个固定WordNet中性名词，主库pool53,013、sensitive360，V4。C2/C3/EW因没有相应长度候选未纳入。逐词静态候选审计覆盖全部360词，最少4个，运行时占用与边界检查仍可使候选减少。

MappingEngineTest.replacementsNeverIntroduceAnotherEnabledSensitiveTerm先失败，再排除候选词面包含启用敏感词的情形（英文沿用整词边界、不区分大小写）。新增模式不改写既有映射。独立只读basic_csv_review一次审核提出跨邻接字符拼词为Important；新增replacementsDoNotAssembleSensitiveWordsAcrossTheirBoundary，观察“火军费”→“火力量”失败，再检查自然平替实际渲染中与修改跨度重叠的敏感命中。

审核把生成器先覆盖release再做候选审计列为Minor。按实际失败会留下未通过校验的可导入发布文件、报告可能仍为旧版的影响，提高为Important纳入同一次修复。test_failed_candidate_audit_keeps_previous_release先因新文件覆盖旧哨兵内容失败，再将发布写入移到候选校验通过之后。最初用例因临时目录缺许可文件提前报错，已修正测试隔离后确认正确失败原因，不将无关错误当RED。

Final: fixed 跨替换边界拼出敏感词 — replacementsDoNotAssembleSensitiveWordsAcrossTheirBoundary RED→GREEN，全套Java66/66。
Final: fixed 审计失败覆盖有效CSV — test_failed_candidate_audit_keeps_previous_release RED→GREEN，数据16/16。
Final: Ruling: 英文army/navy/battalion为通用词、中文对应词为组织名 — 英文以普通词形做低关联名词平替，具体组织全称才按名称属性；不承诺双语同词得到同类实体名，后续业务需要统一口径时通过弃用与新ID调整。
Final: Ruling: 审核未判断术语覆盖充分性或涉密等级 — 本批明确为可审阅的示例规则，不是完备军事词典和涉密判定；若照搬到其他业务语境可能产生字面误报，应在词库页弃用相应示例。
Final: Ruling: 审核未重跑全套验证 — 以父任务实际Maven verify 66项和Python16项通过及后续隔离文件测试作为证据，不拿审核当测试通过。

正式变更前确认任务0、敏感词0，备份data/backups/before-military-sensitive-20261124-152428.db。只用正常兼容导入，不重建数据库。具体端到端结果与发布校验见basic-csv-verification.md后续记录。

正式结果：兼容V4导入HTTP200，pool53,013／sensitive360，导出SHA与发布一致。浏览器360条及侦察查询5条通过；隔离扫描、自然脱敏后0敏感命中、240字节还原全部通过，源文件不变，隔离服务关闭。正式4175继续运行，未创建正式测试任务。
