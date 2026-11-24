# Swapy Java Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 将静态原型连接到可本地启动、SQLite 持久化的真实扫描／脱敏／还原服务。
**Architecture:** 单 Java 服务加全局四路调度，JDBC 短事务，扫描文件级持久断点，文档适配器与 API 分离。
**Tech Stack:** Java17、SpringBoot4.2.0、SQLite JDBC3.54.1.0、JUnit、POI、PDFBox。
**Spec:** [Java设计](../specs/2026-11-23-swapy-java-design.md)

## Global Constraints

- 全局固定4路，不允许调整；Chrome/Edge80+，不上传原文。
- 扫描恢复同任务号，用户暂停不自动继续；脱敏／还原中断重跑新任务。
- A默认B可选，一对一、原文避重、英文大小写可逆、无模型。
- 每任务新目录，异常报告一任务一份，不复制失败文件或范围外文件。
- 只导出脱敏任务包，双库历史追加，ID不复用，同ID冲突拒绝。

## Review Focus

- 路径越界、链接和本机接口跨域调用必须拒绝（任务1、5）。
- 重启发生在文件结果提交前后，不能重复计数或跳过未完成文件（任务3）。
- 中文目标拼接形成假命中必须重选，英文混合大小写必须往返（任务2）。
- 结构格式和Office跨run不能损坏保护位置／样式（任务4）。
- 批次含末尾坏包不得部分写库；跨任务类型ID也冲突（任务1、5）。

### Task 1: SQLite与词库基础

Files: `backend/pom.xml`, `backend/src/main/java/cn/swapy/{Store,Catalog,Paths,Json}.java`, `backend/src/test/java/cn/swapy/StoreTest.java`。
Interfaces: `Store(Path data)`、`read(SqlFunction<T>)`、`write(SqlFunction<T>)`、`Catalog.snapshot/importLibrary/exportLibrary`、`Paths.resolve(rootId,relative)`。
- [x] 先写真实临时库测试：重开记录不丢，同ID异词拒绝、降级拒绝、旧词弃用后仍可查、事务失败零提交、路径越界拒绝。
- [x] `mvn -f backend/pom.xml test -Dtest=StoreTest` 观察缺失功能失败。
- [x] 实现JDBC迁移、单写事务、根路径校验、双库追加导入与权威本地发布。
- [x] 同命令通过，登记证据。

### Task 2: 匹配与映射核心

Files: `matching/{Matcher,MappingEngine}.java`, `matching/MappingEngineTest.java`。
Interfaces: `Matcher.find(String,List<Word>)`、`MappingEngine.plan(List<String>,CatalogSnapshot,mode)`、`redact(text,mappings,mode)`、`restore(text,mappings)`。
- [x] 先写最长优先、英文边界、大小写、候选在其他输入出现重选、甲乙拼接反例、候选耗尽与A/B往返测试。
- [x] 运行 `mvn -f backend/pom.xml test -Dtest=MappingEngineTest` 观察失败。
- [x] 实现属性筛选、一对一随机候选、全任务虚拟输出可逆校验及完整映射记录。
- [x] 核心测试与完整已有套件通过。

### Task 3: 四路调度与扫描断点

Files: `tasks/{TaskService,Scheduler,TaskRepository}.java`, `tasks/TaskServiceTest.java`。
Interfaces: `create(Request)`、`status(id)`、`pause(id)`、`resume(id)`、`issues(id)`、`recover()`、`close()`。
- [x] 先写临时文件扫描、暂停继续、重开后同ID续扫不重复、源变更重扫、全局活跃≤4、普通文件不进异常测试。
- [x] 运行 `mvn -f backend/pom.xml test -Dtest=TaskServiceTest` 观察失败。
- [x] 实现持久工作项、公平领取、文件结果事务、报告、新目录与已脱敏输出排除。
- [x] 测试通过并登记恢复协议实际行为。

### Task 4: 文档适配与脱敏／还原

Files: `documents/{DocumentAdapter,TextAdapter,StructuredAdapter,OfficeAdapter,PdfAdapter,ArchiveScanner,SqlAdapter}.java`, `documents/DocumentTest.java`。
Interfaces: `extract(Path):DocumentContent`、`rewrite(Path,Path,TextTransform)`、`validate(Path)`；任务调用Task2映射服务。
- [x] 先写MD、JSON键保护、XML结构、SQL跨文件定义引用、DOCX跨run、PDF文本残留、归档嵌套／加密／限额真实样例测试。
- [x] 运行 `mvn -f backend/pom.xml test -Dtest=DocumentTest` 观察失败。
- [x] 按格式分组实现、每组RED→GREEN；未实现能力显式拒绝，不回退成功。
- [x] 自动化样例套件通过；业务文档版式尚待真实样例渲染验收，详见实现审核记录。

### Task 5: 迁移协议、API与本地交付

Files: `packages/PackageService.java`, `Application.java`, `Api.java`, `SecurityFilter.java`, `backend/src/test/java/cn/swapy/{PackageTest,ApiTest}.java`, `prototype/live.js`, `scripts/{start,package}.ps1`, `backend/Dockerfile`, `README.md`。
Interfaces: 包单／批量import-export、密码本HTML/JSON、REST `/api/v1`、根目录浏览、静态页与轮询状态。
- [x] 先写单包／批次原子失败、同ID跨类型冲突、版本不足、JSON独立还原、跨域写拒绝、选择扫描→异常报告端到端测试。
- [x] 运行对应测试观察失败。
- [x] 实现协议与API，现有页面接服务端，不再使用假进度；提供可选运行时打包脚本与离线依赖说明；本轮未附带第三方运行时。
- [x] `mvn -f backend/pom.xml verify`、原型交换脚本、真实HTTP烟测通过；执行独立代码审核和修复。

## 执行记录

本目录不是Git仓库，不初始化仓库、不伪造提交；在 `docs/development-ledger.md` 保存每阶段测试和偏差证据。用户已要求审核后直接开发，内联执行，不重复请求执行方式或批准。

最终状态（2026-11-24）：首版实施及审核修复完成，48 项回归通过，无跳过；复杂版式、LibreOffice、Docker 镜像运行和历史浏览器实测未列为已验收。证据见开发台账与实现审核记录。
