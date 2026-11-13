# 运营代办入驻：表格与弹框

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
