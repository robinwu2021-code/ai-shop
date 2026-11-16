# 运营代办入驻：表格与弹框

状态：已实现；部署与验收证据见本文各次发布记录。
## 2026-11-13 入驻详情抽屉疏密优化（已部署）

### §0 范围

针对统一入驻列表点击“查看”的详情抽屉：桌面加宽、明确资料分组、长字段独占一行，审核操作固定底部。代客/自营详情使用同一组局部布局组件，其他业务抽屉及新增商户弹框不受影响。

### §1 复用

复用共享 Drawer 的滚动正文和独立 footer，仅在入驻组合层设置 720px 桌面宽度、手机单列、段落留白和长内容换行。沿用原审核命令、事务、权限及前置条件，无服务端/API/数据库变更。

### §2 实施

申请信息与经营主体/联系人分组；门店名称、地址、账号及建档编号完整占行。资质卡片统一间距，图片使用适中预览，保留原图链接。通过/驳回放入固定底部区，保留服务范围、驳回原因及请求中的禁用条件。已决定或无审核权限时不显示动作。

### §5 验证与发布

- merchant-apply-display-ui、unified-admission-ui 共 21 项通过，覆盖原审核请求、服务范围阻断、已审/只读权限和历史申请；受影响文件 lint、生产 Webpack/TypeScript 构建及 /ops-shop 产物检查通过。独立代码复核未发现重要问题。未新增重复验证展示类名的测试。
- 实现提交 30a794f8c 已推送 codex/ai-shop-codex；静态发布 ops-admission-detail-20261113-30a794f8c。2 个公开页面及 34 个 JS/CSS 摘要与产物一致。后端 JAR、4 个服务进程、支付运营静态入口与配置均未变动。
- 保留现场 sales-closure-20261113-01c5f665b 已部署收款调整：基于本次提交的独立构建快照，叠加原发布清单中 9 个文件并逐项核对摘要；未将这些无关文件混入本次代码提交。
- neara@neargo.ai 独立临时页复验：已通过详情实际宽 720px，申请信息、经营主体与联系人、开店资料、入驻结果、资质分组正常；编号/地址完整显示，资质缩略图正常。已驳回申请保留原因，已审单不显示审核按钮。
- 375px 窄屏：抽屉 left=0、right=375、width=375，正文 clientWidth=scrollWidth=375，字段单列 335px，无横向溢出。仅为临时标签页设置尺寸，验证后已清除并关闭临时页。
- 线上 6 条记录均为已通过/已驳回，本次未制造待审业务数据；待审命令及禁用条件通过上述自动化场景验证。未执行审核、创建门店或派发渠道。
- [发布及复验证据](../../evidence/ops-admission-detail-20261113/verification.json)，含调整前、桌面、资质、手机与驳回详情截图。

## 2026-11-13 新增商户表单整理

### §0 范围

用户反馈：新增动作改称“新增商户”，避免与新增门店混淆；入驻类型移入表单并对齐控件；弹框采用紧凑、适中圆角和中性边框，初始聚焦标题而非下拉框；补全服务业展示；支付运营页头不显示地区。地区仍由系统确定，原权限与建店/审核规则保持。

### §1 复用

复用现有创建、审核、自营服务与系统行业字典。服务业字典已存在，但线上没有 SERVICE 经营类型，业务端只验收 FOOD/RETAIL。用户已选择“先完整展示服务业，标明经营方案待配置”：不新增或发布套餐，不绕过部署验收门禁。行业响应增加 available，未配置子行业不可提交，原服务端创建规则保留。支付页头调整在 near-pay 独立工作区完成。

### §2 实施

1. 更新中英文新增名称，标题/正文/操作区分层，入驻类型在正文首行使用全宽标准控件；自营嵌入模式去除第二层卡片外框。
2. 针对弹框名称、控件位置、标题聚焦、模式切换与必要阻断运行受影响 UI 验证，再构建和部署。
3. near-pay PayShell 移除页头地区显示与地区导航，不改实例地区及请求上下文；复用当前部署源并检查前端构建。
4. 线上复验使用独立临时标签页，保留用户当前填写中的表单；不创建真实申请或派发真实渠道。

### §5 验证

- 新增商户命名、标题聚焦、正文控件全宽对齐、自营嵌入去框与服务业未配置阻断，4 个受影响前端文件共 96 项通过；lint、OpenAPI、生产构建/TypeScript、产物检查通过，OPS 契约既有 8 项欠账不变。
- OpsStoreSetupQueryTest、OpsStoreIndustryControllerTest 与 CommerceStoreCreationFlowTest：8 项通过、1 项 MySQL 原生场景因缺少 COMMERCE_MYSQL_URL 凭据跳过，未宣称该原生场景通过。源映射包含未发布行业并给出 available=false，已发布类型及禁用父项行为均有验证。
- near-pay 页头、样式与导航 5 项通过，live/http/ai-shop 身份配置的生产构建通过。两个线上基线类与原源码反汇编一致；服务端只替换/增加 3 个应用类，不改 SQL 或其他归档条目。
- 代码已推送：ai-shop 主开发分支 df0f6bf3e；near-pay develop/s1 的 b037e95d、e23e2472。UME166 服务端发布 ops-merchant-form-20261113-df0f6bf3e-r2，JAR SHA256 为 7095f47e08f5ef6abdab161252fc79b0548548c23d06038ce2a5225c865f4500，API/OPS 均 UP；前端发布 ops-merchant-form-ui-20261113-df0f6bf3e，2 个页面及 34 个资源与构建产物一致。
- 首次发布因既有 Outbox 历史门店同步事件接管检查失败，原 JAR 已恢复。随后只读检查发现现场其他维护操作已完成 ADOPT，审计原因为测试门店投影恢复，事件已 SENT。本任务未执行接管、标记发送或修改投递配置；恢复健康后重试发布成功。首次失败记录保留，不与成功记录混写。
- 支付运营线上存在另一工作区未提交的页面调整，因此只在现有静态树上增量发布页头 CSS，并更新 HTML/RSC 的新资源引用，保留 32 个 JavaScript 文件及原 CSS 原字节；没有将本地完整构建覆盖线上。发布 ops-header-region-20261113-e23e2472，3 个公开页面与 CSS 校验通过，支付产品入口保留。
- 已使用 neara@neargo.ai 的独立临时页完成线上只读复验：新增商户名称、标题初始焦点、入驻类型与地区正文首行对齐、中性细边框、两种模式切换及取消正常；自营不再嵌套边框卡片。台湾主体仅显示公司/独资行号；服务业及 10 个子行业完整显示待配置，子行业不可选择，餐饮 12 项子行业仍可选。渠道页头地区隐藏，支付产品导航正常。用户正在填写的标签页未修改，未提交申请、创建门店或派发渠道。
- 证据：[ops-merchant-form-20261113](../../evidence/ops-merchant-form-20261113/verification.json)。MySQL 原生创建场景本次未运行，继续复用此前未变更的写入流程验收。

## 2026-11-13 统一入驻管理（已部署并完成管理员只读复验）

### §0 用户确认与验收

统一“入驻管理”列表，区分代客入驻和平台自营。新增商家弹框按类型展示原表单；代客仍提交审核，自营仍直接建档，不混淆创建人和经营模式。地区取系统配置，台湾不显示大陆字段。保留支付开通入口。

### §1 身份与权限

不新增写入路径。申请继续以 applyNo 审核，自营以 merchantNo 新增门店，事务及幂等沿原服务。统一查询按现有审核/代办/自营权限裁剪：审核员可看申请，代办员仅看代办申请，仅自营权限只看自营。原菜单授权点保留，归并到同一地址，不改角色授权。跨域查询通过 SelfOperatedAdmissionPort 读取自营投影，禁止直连对方表。

### §2 实施计划

1. 增加入驻统一查询服务及端点，地区、类型和状态在源查询中筛选，按创建时间和唯一键稳定归并分页，记录总数不受截断影响。申请保留单据，不额外拼接审核后普通商户，避免重复。
2. ApplyTab 复用审核抽屉，统一表格展示商家、类型、店主、状态、时间、操作；平台自营行查看及新增门店。OnBehalfTab 统一新增弹框，按权限显示创建类型，自营成功返回列表并刷新。
3. 菜单归并、旧代办/自营深链归一，三语文案、API 契约、生成物与菜单迁移同步。
4. 验证混合分页/类型/地区/权限、审核原命令、新增类型及成功刷新、深链和文案；相关编译、测试、构建后推送主开发分支并部署 UME166，只读线上复验，不派发真实渠道。

### §5 验证记录

- 已实现：统一查询通过 SelfOperatedAdmissionPort 隔离表归属，两个源最多各取当前页窗口，数据库分别筛选与计数后稳定归并，不把源分页结果直接拼接。代客筛选只取代办来源；全部视图保留审核员原有的商户自主申请，并如实标为“商家申请”，不误标成代客。
- AC 对应验证：AdmissionManagementServiceTest 的 mergesBeforePaginationWithAccurateCountsAndStableOrder、assistedOperatorCannotReadSelfOperatedOrUnassistedApplications、selfOperatedPermissionAloneNeverReadsApplications、requestedAssistedTypeRestrictsEvenAuditorToAssistedSources 覆盖归并分页、来源、权限；AssistedApplySearchTest 与 SelfOperatedAdmissionPortTest 验证地区及归档条件在分页前应用。
- 前端 unified-admission-ui.test.ts 覆盖单列表、服务端类型筛选、两种表单切换、台湾固定地区、自营成功关闭刷新、创建权限及按实际商户键新增门店；原代办提交与审核命令回归保留。相关前端 159 项通过，服务端 22 项与 SchemaDriftTest 6 项通过，角色端点验证 40 项、复合权限解析 3 项通过。
- lint、生产构建/TypeScript、按 UME166 的 /ops-shop 前缀适配执行原产物检查通过；OPS 契约既有 8 项欠账不变，OpenAPI、权限矩阵、UI 目录、功能清单及 H2 Schema 已重新生成。推送复用上述增量证据，不重复无关全量门禁。
- 发布前已将当前线上包与原提交编译出的 11 个基线类进行反汇编比较，语义一致。本次候选仅更新或增加目标类与 V667 菜单迁移，不覆盖其他包条目、配置或角色授权。
- 并行发布占用部署锁，本次在切换前退出，未修改线上。随后线上收款发布使用 V666__platform_sales_collection.sql；本次未执行的菜单迁移顺延 V667，导航迁移 6 项和 SchemaDriftTest 6 项重新验证通过。针对新运行包 51860fb0 的 11 个目标基线类再次比对，仍无语义差异，候选基于该包重建以保留收款发布。
- 同步合入已部署的 d3e6ada5a 收款发布源码，避免静态站点覆盖其商城页面。合并无冲突；重新生成 API/权限/Schema，入驻 UI 与商城收款 API 共 17 项、SchemaDriftTest 6 项及生产构建通过。包含前述验证，共覆盖 169 个不同前端用例；未扩大到无关全量回归。
- 合并后的角色端点检查复现 collectors 查询漏登记；按其原 context.operator 的 commerce:mall:offline:confirm 补入 perm-endpoint-map，不修改运行权限。
- 统一新增自营后，原“可达服务区域为零”提示会随弹框关闭消失；unified-admission-ui.test.ts 新增用例有效复现，改为保留全局错误提示，同时清除上一笔代客申请的结果提示。受影响前端 89 项、lint、生产构建及产物检查通过；累计不同前端用例 170 项。统一列表的细化登记明确三种原权限的 OR 关系，登记守卫 37 项通过。
- UME166 服务端发布 ops-unified-admission-20261113-b2a510207：API/OPS 均 UP，V667 成功，原角色授权、权限码及全部历史迁移 checksum 不变；NearPay、库存与配置未改。最终前端发布 ops-unified-admission-ui-20261113-e02631cd0，公开 2 个 HTML 与 34 个 JS/CSS 摘要匹配，保留已部署收款页面。
- Chrome 现有 robin@neargo.ai（STORE_APPLY_REVIEW）只读复验通过：一个入驻管理入口、一张六列表格、6 条实际申请，无新增商家按钮，未放宽原权限。已恢复 Chrome 原标签页；未提交真实入驻、门店或支付请求。
- 用户重新登录后，已在独立临时页验证管理员列表、代客/自营弹框及旧自营深链；当前列表为 6 条代客记录，平台自营筛选为 0 条，服务区域未配置时保留阻断提示。375px 下弹框无横向溢出；本次表单整理后再次验证类型切换及取消。服务区域原配置未改变，未创建真实业务数据。
- [发布及复验证据](../../evidence/ops-unified-admission-20261113/verification.json)，同目录含审核账号列表截图。实现主提交 5671e1e31、迁移顺延 f0802662a、保留协同发布 d15186fad、必要提示修正 e02631cd0。

## §0 范围与结论

2026-11-13，档位 1。地区沿用登录环境 `shop.merchant.default-market`，前端无地区切换，服务端拒绝跨地区覆盖。主页展示运营代办申请，新增在居中弹框中按店主、门店、经营主体分组填写。已提交申请只查看，审核仍在入驻审核入口。

## §1 身份、事务与复用

复用 MarketPhonePolicyService 地区手机号策略、OpsService 的申请事务及现有按 owner 的活动申请约束；不新增写入、幂等或审核规范。TW 手机只记录，以已注册邮箱定位店主，不派发支付渠道。代办查询复用申请分页服务，数据库分页前限定 submitted_by 非空；独立入口沿用代办权限，审核接口权限不变。

## §2 实施与验收

1. 增加统一地区上下文，POST 使用系统地区，旧客户端仅可传相同地区。
2. 增加仅代办申请检索，保留既有状态、关键词、数据库分页规则。
3. 页面使用申请表格；新增弹框分组、双栏布局、独立滚动和固定操作区，提交成功关闭并刷新列表，失败保留填写。
4. 验证系统地区/跨地区拒绝/TW 原样记录/代办筛选、表格与弹框交互、无权限、加载失败及提交数据；相关契约、文案、lint 与生产构建。

## §5 验证与交付

- 服务端 14 项、前端 83 项、权限矩阵 37 项全部通过；lint、Next 生产构建（含 TypeScript）、产物检查、OPS API 契约与界面目录校验通过。未增加欠账基线。
- 地区上下文失败禁止提交；TW 原样电话、不传 market、跨地区覆盖拒绝、SQL 在分页前限定代办来源、提交成功关闭刷新及失败保留填写均有相关验证。
- 运行包仅替换 shop-core 本次 5 组类（含内部类），其他 ZIP 条目及迁移资源逐字节相同。当前分支基线编译的 18 个类与线上反汇编语义无差异。
- UME166 服务端发布 ops-assisted-admission-20261113-a2aa6e7b5，API/OPS 健康检查 UP；OPS 原默认 CN 已与 API 统一为 TW，其他容器运行参数不变，环境文件、JAR、旧容器均保留备份。
- 前端最终发布 ops-assisted-admission-ui-20261113-4a4c8174b-r2，公开 2 个 HTML 与 34 个 JS/CSS 摘要逐项匹配。首次最终样式发布在切换前发现其他任务重启 NearPay，未切换页面；重新核对环境后成功发布，无需重复已通过的验证。
- 线上只读复验通过：6 条运营代办申请、已通过状态筛选 3 条、详情只读；新增弹框固定 TW 且无地区选择；标签与输入控件对齐，选填折叠/展开正常。1280px 桌面和 375px 窄屏弹框均未横向溢出，操作区可见，窄屏正文可独立滚动。电话仅记录、取消关闭均正常；未提交真实申请、未派发真实渠道。
- 遵循 Java §8.4 复用增量结果，推送使用 SKIP_COMPILE_GATE=1，不宣称无关全量门禁通过。
- [验收证据](../../evidence/ops-assisted-admission-dialog-20261113/verification.json)。


## 2026-11-13 入驻菜单与台湾页面整合

### §0 范围与确认

档位 1。用户确认自营为常用业务，保留独立入口。入驻审核和代办合为入驻申请，支付开通保留独立状态列表。台湾仅显示台湾相关账号、证照文案，地区使用统一系统配置，页面无切换。提示只保留当前错误或阻塞信息。

### §1 复用与权限

申请创建、审核和自营建档仍使用各自原权限和事务。两个入驻菜单授权点保留，通过相同目的地址合并显示，不更改角色授权。复用平台地区电话策略，经 SPI 提供给自营服务；台湾使用已注册邮箱定位 owner，联系电话只记录，显式传系统市场到原 activateSelfOperated，保留按 owner 的既有幂等与自营业务规则。

### §2 实施计划

1. 菜单保留入驻申请、自营建档、支付开通，旧代办深链归一；保留自营独立入口。
2. 入驻申请合并列表/新增/审核，按权限选择原有查询，精简为五列，完整信息放详情；只显示必要错误和结果。
3. 自营使用系统地区与电话策略、台湾邮箱账号，表单分组和选填折叠，去除大陆专属及长篇提示；保留服务区域与真实结果。
4. 支付开通列表只显示主体、资料状态、渠道状态和操作，渠道标识/交接/绑定/返回码等放详情。
5. 验证地区策略、自营 owner 与 market、导航权限/旧深链、列表与弹框、错误与详情、文案和生产构建；按增量部署 UME166 并只读复验，不派发真实渠道。

### §5 验证与交付

- Backend: 14 regional policy/application tests, 6 self-operation contact/controller tests, 12 existing self-operation scenario tests and 6 schema projection tests passed (38 total).
- Frontend: 158 related navigation/application/payment/copy tests passed; application display has 11 tests (3 added), and payment board has 13 tests (1 added): 162 distinct tests passed. Permission matrix: 37 passed.
- Lint, production build/TypeScript, production artifact guard, API contract (existing 8 debts unchanged), generated UI catalog and schema projection passed.
- Compiled 13 baseline classes and compared their disassembled behavior with the fresh live package c477fa54: no differences. Candidate replaces 13 class entries across shop-base/core/merchant and adds V665 only; all other archive entries are byte-identical.
- 最终增量：服务端 38 项、前端 162 项、权限矩阵 37 项通过；补充自营幂等结果不伪造门店名称及支付详情随权限范围刷新的回归验证。实现提交 4a9baa006、集成修正 12308bf7a、页面修正 6c2590277 已进入主开发分支。按 Java §8.4 复用增量结果，推送使用 SKIP_COMPILE_GATE=1，不宣称无关全量门禁通过。
- UME166 已通过协同发布部署本次服务端，13 个目标类与已验证候选逐字节匹配；复用运行包，未再次覆盖或重启。API/OPS 健康检查 UP，V665 成功应用，原权限码、角色授权及历史迁移校验和不变，系统地区 TW。
- 前端最终发布 ops-admission-consolidated-ui-20261113-6c2590277-r2，2 个公开 HTML 与 34 个 JS/CSS 摘要匹配。复验期间另一发布 ops-login-error-20261113-r1 遗漏商城 API 前缀导致根路径 /ops 返回 404；保留该产物并恢复本次已验证构建，恢复后 /ops-shop/ops 下身份、菜单、自营地区、行业、服务区域接口均返回 200。
- 线上只读复验通过：入驻三入口、旧代办链接归一、申请五列表格与台湾新增弹框、自营使用已注册邮箱及系统 TW、375px 窄屏无横向溢出、支付五列表格及测试记录详情。未创建真实申请、未提交或派发真实支付渠道。
- 当前环境没有可选服务区域，自营默认指定区域模式保留“暂无可选服务区域，请先配置。”这一必要阻断，未更改原业务规则或填造生产配置。
- [验收证据与发布记录](../../evidence/ops-admission-consolidation-20261113/verification.json)，同目录包含六张页面截图。


2026-11-13统一分支集成补充：166先应用了支付SSE的V664（256749001）；运营菜单迁移未应用，合并公共OTP时顺延为V665__ops_admission_entry_consolidation.sql，SQL内容不改。历史候选记录中的“adds V664”是当时制品描述，当前以V665为准。
