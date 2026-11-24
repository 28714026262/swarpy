---
name: Swapy
description: 简洁清晰、以文件索引为主体的文档处理工作台。
colors:
  ink: "#243237"
  muted: "#65747a"
  accent: "#186c64"
  accent-hover: "#238276"
  pale: "#edf5f3"
  line: "#e6ebeb"
  ground: "#f6f8f8"
  white: "#fff"
  secondary-text: "#5e7278"
  table-heading: "#5e6d74"
  placeholder: "#68787e"
  input-border: "#d9d9d9"
  input-hover: "#85b5a9"
  upload-border: "#bfceca"
  upload-ground: "#fbfdfc"
  selected-row: "#f0f7f5"
  success: "#2f6b4d"
  success-ground: "#edf5ef"
  warning: "#8b6013"
  warning-ground: "#fff5df"
  error: "#a43c3c"
  error-ground: "#fcf0f0"
  processing: "#256278"
  processing-ground: "#edf5f8"
typography:
  headline:
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif'
    fontSize: "25px"
    fontWeight: 650
    lineHeight: 1.4
    letterSpacing: "-.5px"
  title:
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif'
    fontSize: "16px"
    fontWeight: 600
  body:
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif'
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.65
  control:
    fontSize: "13px"
  label:
    fontSize: "12px"
rounded:
  tag: "4px"
  control: "6px"
  navigation: "7px"
  compact-panel: "8px"
  panel: "10px"
spacing:
  compact: "6px"
  inline: "10px"
  small: "12px"
  medium: "16px"
  panel: "23px"
  column: "24px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.white}"
    rounded: "{rounded.control}"
    padding: "4px 15px"
    height: "32px"
    typography: "{typography.control}"
  button-primary-hover:
    backgroundColor: "{colors.accent-hover}"
  button-default:
    backgroundColor: "{colors.white}"
    rounded: "{rounded.control}"
    padding: "4px 15px"
    height: "32px"
    typography: "{typography.control}"
  button-link:
    textColor: "{colors.accent}"
    padding: "0"
    typography: "{typography.control}"
  input:
    backgroundColor: "{colors.white}"
    rounded: "{rounded.control}"
    typography: "{typography.control}"
  navigation-active:
    backgroundColor: "{colors.pale}"
    textColor: "{colors.accent}"
    rounded: "{rounded.navigation}"
    padding: "11px 15px"
  panel:
    backgroundColor: "{colors.white}"
    rounded: "{rounded.panel}"
  tag-success:
    backgroundColor: "{colors.success-ground}"
    textColor: "{colors.success}"
    rounded: "{rounded.tag}"
    padding: "0 7px"
  tag-warning:
    backgroundColor: "{colors.warning-ground}"
    textColor: "{colors.warning}"
    rounded: "{rounded.tag}"
    padding: "0 7px"
  tag-error:
    backgroundColor: "{colors.error-ground}"
    textColor: "{colors.error}"
    rounded: "{rounded.tag}"
    padding: "0 7px"
---

# Design System: Swapy

## Overview

**Creative North Star: "批处理工作台"**

浅灰办公表面承托白色内容平面，文件表是阅读和操作的主体。蓝绿色标明主操作、当前位置及可查看的文件；文字、分隔线和有限圆角组织密集信息。界面使用系统中文字体与 SVG 线性图标，没有照片、插画或栅格素材。

本文从实际原型提取共享视觉规则；工作台、流程向导和任务中心是同一套组件的三种结构。布局 A/B/C 与业务平替模式 A/B 相互独立。页面是 React 17、Ant Design 4 和 Moment 的本地静态资源实现，未连接业务服务；文档不代表完成设计验收，也不承诺已在 Chrome/Edge 80 实机验证。

**Key Characteristics:**
- 文件优先，主操作明确，结果逐文件可查。
- 白色平面、细分隔线、克制圆角与蓝绿色交互。
- 共享组件和演示状态支持三种布局比较。

## Colors

主色是办公环境中的深蓝绿色，背景和文字使用低饱和灰色；成功、警告、错误和处理中保留 Ant Design 的语义分工，并以本地覆写色值提高文字可读性。

### Primary
- **蓝绿色主操作**：用于主按钮、链接、导航选中、标签页指示线、进度和选中控件。悬停使用较亮主色。
- **浅绿选中面**：用于导航选中、最近任务图标及密码包信息背景。

### Neutral
- **深灰正文**：标题与主要内容。
- **柔灰辅助文字**：说明、路径、元数据。表头、次级文字和占位符分别使用 frontmatter 中的独立角色。
- **浅灰工作背景、白色内容平面与细灰边线**：区分页面、工作面和行列关系。

### Named Rules
**The 操作归色 Rule.** 主色服务于操作、当前位置和结果入口；状态标签继续保留各自的语义色及文字，不把警告改为主色。

## Typography

**Body Font:** 系统界面字体栈，中文回退到 Microsoft YaHei；不依赖在线字体。

同一字体栈用于界面各层级，没有独立展示字体。主标题是操作性页面标题，不是海报式展示字。Ant Design 自带控件仍沿用其基础行高和字重，局部覆盖字号。

### Hierarchy
- **Headline**：页面标题，桌面使用 frontmatter 中的 headline，窄屏缩为 22px。
- **Title**：面板和对话框标题；任务结果名为 19px，窄屏 18px。
- **Body**：页面说明和导航；正文默认行高为 1.65。
- **Control**：按钮、输入、文件名称、表格正文及设置行。
- **Label**：辅助说明、路径、表头与状态标签。长文件名可换行，长路径允许断行。

## Layout

默认工作台在桌面保留 204px 固定侧栏、70px 顶栏，内容最大宽度 1600px；主内容通常以 31px/36px 的上/水平间距展开，底部保留 80px。输入和文件表占弹性左列，设置占 294px 右列，列间距 24px。大屏 1450px 起增加水平留白至 44px、设置列至 316px。

1150px 及以下侧栏为 180px，设置列为 260px，内容水平间距为 23px；850px 及以下侧栏收为 76px 图标栏，设置移至文件表下方；600px 及以下导航成为 62px 的顶部栏，内容水平间距 15px，设置项重新纵向排列，结果工具条换行，详情两列变一列，表格保留横向滚动能力。

流程向导隐藏侧栏，改用顶部导航与三步结构，主体宽度至多 1000px。任务中心以 78px 图标栏配合 276px 任务列表与工作区；1150px 以下列表缩为 220px，850px 以下隐藏任务列表。所有布局共享页面、表格、设置、结果和详情组件。布局比较器属于原型演示工具，不是正式产品导航；桌面固定在视口底部，手机改为正常文档流，方向键只在比较器获得焦点时切换布局。手机输入方式选项与文件工具条分行，避免挤压。

**The 索引优先 Rule.** 保持文件名称、路径、状态和查看入口的关联，窄屏将设置下移，不把文件索引压成不可阅读的装饰。

## Elevation & Depth

工作面以背景色、细边线和分隔线建立层级；主按钮移除了库默认阴影。原型布局比较器使用柔和阴影（`0 5px 20px rgba(24,44,42,.16)`）。对话框、抽屉和消息保留 Ant Design 的浮层及遮罩处理，而不是把全部表面强制压平。

### Shadow Vocabulary
- **演示工具浮层**：仅布局比较器使用上述单层阴影。
- **库浮层**：Ant Design 对话框和消息使用 `0 3px 6px -4px rgba(0,0,0,.12), 0 6px 16px 0 rgba(0,0,0,.08), 0 9px 28px 8px rgba(0,0,0,.05)`；抽屉沿用其方向性库阴影。

## Shapes

内容平面采用 10px 圆角，最近任务和上传区使用 8px，导航及文本预览使用 7px，常规控件使用 6px，标签使用 4px。图标容器采用小型圆角方块，身份头像及状态点可为圆形。白色内容面使用细实线边框，上传区使用虚线边框；SVG 图标采用 currentColor、1.65 描边和圆形端点。

## Components

### Buttons

主按钮采用蓝绿色实底、白字、有限圆角；通常高 32px，设置底部执行按钮高 42px 且撑满容器。默认按钮是白底细灰边，悬停改为主色文字和描边；链接按钮不增加内边距，文字型按钮用于帮助等次要动作。主色按钮悬停和聚焦为较亮蓝绿色。自定义按钮和链接的键盘焦点为 2px 主色外轮廓，偏移 3px；禁用行为由 Ant Design 提供。

### Chips

状态标签去掉边框，使用小字号、4px 圆角和紧凑水平间距。已完成/启用为成功，部分完成/部分脱敏为警告，处理失败为错误，处理中为处理态；未命中、弃用、已跳过等为中性。文字与颜色共同传达状态。警告计数沿用相同标签。

### Cards / Containers

白色工作面以细灰边框区分背景，不增加卡片装饰。常规内容区内边距约 22–26px，手机收紧至 16–19px；分组依靠边线、间距和小标题。最近任务是可点击紧凑横行，悬停改变边框。

### Inputs / Fields

输入、带图标搜索和选择框使用 6px 圆角及 13px 字号。输入和选择框悬停描边为淡绿；插入光标为主色。焦点阴影及部分选择控件边框仍含上游蓝色，属于下文记录的主题覆盖缺口，不作为新表面的品牌规则。表单必填校验和禁用反馈沿用成熟库组件。

上传区是浅色虚线框，配 SVG 上传图标及明确的文字动作；选择文件和目录仅登记演示文件信息。密码包导入会读取 JSON 演示记录，不读取文档正文。

### Navigation

默认导航为图标加文字；活动项以浅绿底、主色字和较高字重标记。桌面悬停使用浅灰绿底。图标模式保留按钮 title 与当前页语义。业务操作标签页通过底部主色细线指示当前操作；流程向导使用 Ant Design Steps，任务中心另有任务列表。

### File Index and Results

文件行把名称、路径、大小或状态并列，名称及“查看”打开右侧详情抽屉；抽屉宽 505px，窄屏限制为视口宽。表头浅灰，行底细线，悬停为浅灰绿。默认输入表可选择文件，选中行采用浅绿底（selected-row）；结果分为文件索引、失败问题和可视化密码本，扫描结果不显示密码本。报告导出与成功映射分开呈现。

运行视图用进度条与文本日志演示处理，支持中断。新增词条及弃用确认由对话框承载；必填项使用库校验。动效保留 Ant Design 的默认过渡，启用减少动态效果偏好时全局关闭过渡与动画。

## Do's and Don'ts

### Do:
- **Do** 用现有 Ant Design 4 组件和共享样式延伸界面，保留主操作与结果入口的视觉层级。
- **Do** 以文本补充语义色，让状态、词库版本和演示性质可见。
- **Do** 让长路径换行，并按现有断点调整表格、工具条和设置区。
- **Do** 将新状态和任务数据标为演示，保持业务模式与布局选项的名称可区分。

### Don't:
- **Don't** 用装饰性图片替代文件索引及操作信息；当前世界以组件、文字和 SVG 图标构成。
- **Don't** 把演示工具比较器或固定样例数据当成正式产品的永久界面规则。
- **Don't** 将原型演示运行或导出记录描述为实际脱敏、加密密码包、服务端持久化或已完成正式后端部署。

未固化为规范的差异：部分 Ant Design 输入焦点和按钮 active 状态尚未完全主题化，仍含上游蓝色。它们是现状记录，不是品牌扩展规则。本文未修复这些样式，也未替代独立 finish review。

实现边界：任务、词条、版本和文件元数据保存在页面内存，刷新恢复；服务端目录、词库同步、运行日志与进度是演示。上传文档不会实际脱敏或还原，正式密码包协议和后端处理尚未实现。静态原型已通过 Docker Compose 构建、健康检查及 HTTP 访问验证，入口为本机 4174 端口；该验证不代表正式 B/S 后端完成。Chrome/Edge 80+ 是兼容目标，尚未在 80 实机验证。

## 扫描页面收敛（2026-11-23）

用户最新决定优先于旧页面描述：默认扫描以文件浏览器选取文件或文件夹，无上传式扫描。执行页不显示文件清单或最近任务，不放布局比较栏和说明段落。仅报告命中敏感词、读取失败、未扫描等异常项，正常文件不单列。进度条支持暂停、恢复；原型以 localStorage 检查点演示刷新后继续，正式断电恢复由服务端持久化实现。错误、状态、字段标签及简短演示标识保留。

## 全窗口并行配置页（2026-11-23）

最新结构以扫描、脱敏、还原、任务记录和词库管理为导航。扫描、脱敏和还原均在同页保留输入、输出（非扫描）、步骤、进度与异常报告，切换不打断任务。主区域不设最大宽度，填满视口并在主区域内部滚动；详情抽屉和词条表单为全窗口。取消服务设置。原生系统文件夹选择后自动开始扫描；仅通过FileList元数据模拟，实际断电续扫和文件内容处理仍需正式实现。原型各操作的检查点独立保存。

## 凭证和迁移控件（2026-11-23）

并行固定4路，不展示调整控件。还原凭证采用上传密码包和输入任务ID二选一，本地缺失、非脱敏任务或词库版本不足以就地告警并禁用开始。词库页提供导入、导出，新词条生成UUID并防重，导入不改历史ID。任务页提供脱敏任务包导入、选中导出和全部导出，扫描和还原行禁用密码包选择且无包导出入口。保持全窗口与原有配色，窄屏操作栏可换行。

## 主视区精简（2026-11-24）

所有页面不设顶栏或面包屑；主内容从窗口顶部的页面内边距开始。词库版本仅在词库管理页呈现，执行页不显示本地服务标识或重复的固定并行配置。保留任务输入、输出、凭证、进度、异常和必要操作。
