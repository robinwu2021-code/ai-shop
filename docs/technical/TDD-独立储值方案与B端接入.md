# 门店独立储值 · 开发计划与接入设计

状态：A–E编码完成，F发布/迁移及不入账联调已通过，实收到账验收按用户选择搁置。更新：2026-10-20。档位：2（既有资金账户唯一性及隔离维度变更，不新增服务）。

执行方式：使用 executing-plans 在当前任务中逐批实施；先关键行为测试，再编码，每批编译与基本定向单元测试。不派生连锁功能，不运行全量回归，不把本文计划当作已实现能力。

依据：[门店储值任务表](./任务表-门店储值管理.md)、[需求修订](../requirements/营销模块重整与对象关系方案.md) §0、[接口评审](./archive/reviews/储值独立入口-服务端接口评审-2026-10-20.md)、[原型](../../prototypes/marketing-module-v2.html#s11)。本文替代此前本文件中的商家共用、连锁账户组及模式设置计划。

## §0 范围与验收映射

**账户规则：租户＋商家＋门店＋顾客＋币种唯一。** 同店多个储值方案入同一账户；不同门店余额、批次与预占隔离。方案按当前门店创建、管理和办理。

| AC | 验收要求 | 对应任务/批次 |
|---|---|---|
| M01 | 同客同店账户复用、不同店隔离 | ST01–03 / A、B |
| M02 | 门店多个独立方案创建、查询、详情、启停 | ST04、08、09 / C、E |
| M03 | 不经活动办理，按方案实收到账，幂等且可恢复 | ST05、08、10 / C、E、F |
| M04 | 本店有成功储值顾客列表，含零余额，支持搜索及详情 | ST06、08、11 / D、E |
| M05 | 储值/核销统一时间倒序，记录余额快照，稳定分页 | ST07、08、11 / D、E |
| M06 | 全部/储值/核销Tab、时间弹框、统一切店 | ST11 / E、F |
| M07 | 不丢失历史资金事实，旧活动充值与核销受控兼容 | ST02、03、05 / A–D、F |
| M08 | 页面复用B端组件与三语，真实API接入 | ST09–12 / E、F |

本期完成方案、充值、账户和基本收支管理。不开发连锁共用、账户组设置、模式切换、跨店核销、成本分摊、清分结算、线上购买、线上退款或新核销页面。核销内核只做门店选账兼容，已有核销记录保留。

## §1 模块、数据与接口

### 1.1 当前可复用能力

已有会员码识别、活动充值、实收提交/确认、幂等与异步入账、账户/批次/凭证/分录、核销内核、单客余额及充值进度。尚无独立方案、门店隔离账户、商家顾客目录和统一收支接口；演示原型不是业务页面。B端工作区现有营销接入代码需先辨明归属与状态，不能假定全部已提交或部署。

Pay负责储值经济规则、账户与账务；Core提供会员、商家及门店可信资料，保留旧营销参与；shop-app BFF负责认证、权限和跨服务聚合。Pay数据库事务不得持锁等待Core远程响应，禁止跨库JOIN、BFF直接调用Pay Mapper。

### 1.2 数据增量

| 表 | 修改目标 |
|---|---|
| sv_account | 新增store_no；唯一键改为tenant_no/user_no/entity_no/store_no/currency；账户号生成、ensure/findOwner/lockOwner同步纳入门店 |
| sv_lot | 保留recharge_no及资金拆分，补充值门店可查询字段；扩展PLAN来源版本引用；批次只能归当前门店账户 |
| sv_recharge | 保留已有store_no/store_name、实收凭据；新增source_kind=ACTIVITY/PLAN与plan_no/plan_version_no；快照冻结门店及方案 |
| 新增sv_recharge_plan | tenant_no/plan_no/entity_no/store_no/name/currency/status/current_version_no及BaseEntity.version |
| 新增sv_recharge_plan_version | version_no/plan_no/version_index/paid_amount_minor/bonus_amount_minor/redemption_scope_snapshot/schema_version/snapshot_hash；不可变 |
| 新增sv_statement | record_no/voucher_no/account_no/account_seq/user_no/entity_no/store_no/store_name/type/business_no/title/currency/signed_amount_minor/balance_after_minor/occurred_at及来源引用 |
| sv_user_store_index | 复用成功储值资格、最近充值/核销时间；按实际查询补索引，不另建连锁目录 |
| sv_voucher/sv_entry等 | 保留资金真源；同一Pay事务生成统一收支投影，voucher唯一键防重复 |

方案索引含租户/商家/门店/状态；方案同店名称并发唯一。收支唯一tenant_no+voucher_no；索引覆盖商家/门店/发生时间/唯一键及账户过滤。游标绑定商家、门店、顾客、类型、时间条件；排序发生时间倒序加稳定唯一键。

现有充值和批次活动引用为NOT NULL，新增迁移按来源调整必填约束；PLAN不能伪造活动号、参与凭证或营销预算。历史行识别为ACTIVITY；旧快照兼容，旧参与确认/释放路径保留。实施时分配未占用迁移版本，不修改已应用迁移。

**存量是发布门槛。** 先盘点旧商家共用账户、充值来源门店、批次剩余金额、历史核销分配、预占与未完成单据。可恢复的按真实批次归属迁移并对账，不能按最近门店猜测，更不能复制余额。来源不明或有未清预占的形成阻断清单，不自动清零。存量处理采用独立可审计步骤，不在通用Flyway里无条件拆资金；没有完成盘点不得切换写路径。

### 1.3 API契约目标

以下为本轮实现契约；统一前缀`/biz/stored-value`。商家从登录态取得，storeNo来自统一切店并由服务端验证；详情及状态操作也要验证资源归属门店。写入携带Idempotency-Key，状态修改带expectedRevision。

| 方法/路径 | 请求要点 | 响应要点 |
|---|---|---|
| GET /plans | storeNo、status?、cursor?、size | 方案/版本/修订、名称、币种、实收/赠送/到账、状态及nextCursor |
| POST /plans | storeNo、name、currency、paidAmountMinor、bonusAmountMinor | 原子创建已启用方案及不可变版本 |
| GET /plans/{planNo} | storeNo | 方案详情及版本 |
| PUT /plans/{planNo}/status | storeNo、status、expectedRevision | 最新方案或修订冲突 |
| GET /account-directory | storeNo、keyword?、cursor?、size | accountRef、脱敏姓名/手机、币种、本店可用余额、最近收支时间 |
| GET /accounts/{accountRef} | storeNo | 本店账户资料与合计/冻结/可用余额 |
| GET /records | storeNo、type=ALL/RECHARGE/REDEMPTION、startDate?、endDate?、accountRef?、cursor?、size | 记录号、顾客、摘要、门店、正负金额、变动后余额、币种、时间与原单号 |
| GET /records/{recordNo} | storeNo | 记录及方案快照；不返回私密收款凭据 |
| POST /plan-recharges | storeNo、customerRef、planNo、versionNo | 原单号、修订、状态及金额 |
| POST /plan-recharges/{no}/submit | expectedRevision、actualAmountMinor、collectionMethod、receiptNo | 提交原单；实际金额必须等于方案应收；BFF生成凭据摘要 |
| POST /plan-recharges/{no}/receive | expectedRevision、receiptNo、confirmationNote | 原单状态；已收款不等于到账 |
| POST /plan-recharges/{no}/cancel | expectedRevision、reason | 仅未确认收款原单可取消 |
| GET /plan-recharges/{no} | 编号 | 原单状态与到账结果 |

原活动充值路径不改请求结构；原单客余额端点同步调整门店语义并检查全部调用方，不能留下返回商家合计的旁路。账户只读引用不授予充值/核销授权，办理沿用合法customerRef。Core受控获取商家/门店范围顾客候选，再由Pay过滤储值资格；禁止全量会员前端筛选或静默截断候选冒充完整目录。

复用现有储值查询、创建与实收确认权限，新增方案读取/管理权限并同步角色种子与三语。首期不新增连锁配置权限。

## §2 分批开发执行计划

所有任务初始为待开发。遵循“先写关键失败测试→确认失败位置→实现→编译和该批定向测试→记录证据”的顺序；本计划编写阶段不执行业务编码或资金迁移。

### 批次A：契约冻结与历史盘点（ST01、ST03）

**输入**：门店任务表、原型、当前数据库和源码；**输出**：门店契约、增量DDL草案、历史资金盘点及迁移规则。

文件：更新`docs/requirements/PRD-美业储值.md`、`docs/technical/TDD-活动卡券储值-API契约.md`、`docs/technical/TDD-活动卡券储值-数据库设计.md`；新增门店账户归属ADR和只读盘点SQL/报告，具体迁移版本实施时分配。

- [ ] 对照本TDD更新PRD验收项、API载荷、权限、迁移前后唯一键和调用方清单。
- [ ] 盘点sv_account、批次、充值门店、核销分配、预占、RECEIVED与其他未完成单据；输出可迁移/需处理数量及金额，不改数据。
- [ ] 定义历史兼容与切换步骤：旧活动充值按实际办理门店选新账户；未结束旧资金业务必须完成或受控迁移后切换。
- [ ] 用代表性多门店样本验证分配规则及总额/冻结/批次对账；来源不明样本必须报阻断，不猜测分配。
- [ ] 核实金额Minor、UTC、日期区间[from,to)、同名冲突及账户只读引用约定。

**退出标准**：资金归属、兼容方式、API和权限无歧义；存量阻断已明确。若真实数据未访问，仅完成设计，不标记盘点验收通过。

### 批次B：门店账户隔离及兼容（ST02、ST03）

**输入**：A的契约与迁移规则；**输出**：完整按门店选账的后端及可对账迁移。

修改：`backend/shop-base/src/main/java/ai/neargo/shop/spi/storedvalue/StoredValuePort.java`及相关查询/使用端口；`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/storedvalue/entity/SvAccount.java`、`pay/mapper/storedvalue/SvAccountMapper.java`；Pay充值/核销/查询服务、商家与消费者BFF、远程请求DTO、场景夹具；新增迁移及受控存量转换脚本。

- [ ] 先写`StoredValueStoreAccountIsolationTest`：同客同店复用；两店账户不同；A店充值不能增加B店余额；B店不能选用A店批次或预占。
- [ ] 确认旧实现无法满足跨店隔离用例，再扩展账户键、账户号生成、开户与锁查询。
- [ ] 逐一修改余额、到账、报价、冻结、核销、恢复及消费者门店查询路径；保留租户和商家校验，锁顺序沿用既有约定。
- [ ] 执行迁移演练，核对本金、赠金、冻结及凭证；有歧义数据不进入自动迁移。
- [ ] Java编译、隔离测试及本次改动影响的一条旧活动入账/核销兼容测试；不重跑其他已回归场景。

**退出标准**：所有资金选择路径使用同一门店维度，无商家合计余额旁路；迁移对账通过。

### 批次C：门店方案与独立充值（ST04、ST05、ST08）

**输入**：B门店账户及A契约；**输出**：BFF→Pay真实独立充值闭环。

新增实体`SvRechargePlan`、`SvRechargePlanVersion`及对应Mapper；新增`StoredValuePlanService`/Impl、`StoredValuePlanRechargeService`/Impl；扩展Pay内部路径/请求DTO及PayClient适配；新增`BizStoredValuePlanController`、`BizStoredValuePlanRechargeController`。BFF位于shop-app/portal/biz，Mapper位于pay/mapper/storedvalue。

修改充值来源策略、`StoredValueRechargeWriterImpl`、`StoredValuePostingServiceImpl`、`SvRecharge`、`SvLot`及`StoredValueRechargeEventConsumer`，不复制第二套资金记账算法。

- [ ] 先写`StoredValuePlanServiceTest`与`StoredValuePlanRechargeFlowTest`：多方案、赠送0、溢出、同名并发、幂等及跨店拒绝。
- [ ] 实现当前门店方案创建/列表/详情/启停，状态写用修订与幂等，金额从不可变版本读取。
- [ ] 创建充值时校验当前店方案，冻结顾客/门店/金额/使用范围；锁定方案与启停竞争，停用后拒绝新建，先前合法单据可继续。
- [ ] PLAN来源直接校验Pay冻结事实；ACTIVITY继续校验Core活动来源，不伪造ParticipationPermit或活动号。
- [ ] 接实收提交/确认与异步到账；事件和原单查询恢复按持久化来源分派；不在Pay持锁事务里等远程调用。
- [ ] 接通BFF、远程和本地适配、权限与三语；保持旧端点契约及原单权限检查。
- [ ] 编译并定向验证同键重放/异载荷冲突、重复receive/事件、收款未到账恢复、方案停用、其他门店/商家拒绝。

**退出标准**：无活动可完成充值，只有CREDITED显示到账；重复请求、事件和恢复不重复加钱。

### 批次D：顾客目录与统一收支（ST06、ST07、ST08）

**输入**：B账户隔离、C充值事实；**输出**：前端可直接分页调用的目录与混合流水。

新增`SvStatement`、`SvStatementMapper`、`StoredValueStatementProjector`、`StoredValueMerchantQueryService`/Impl；扩展账户/门店索引Mapper与Core受控顾客查询；shop-base增加商家查询DTO/端口，Pay/PayClient/BFF补同一契约。充值和核销过账服务在原事务内写投影。

- [ ] 先写`StoredValueMerchantQueryTest`、`StoredValueStatementProjectionTest`：零余额目录、成功储值资格、时间并列分页、储值核销混排、历史余额快照与越权。
- [ ] 实现顾客分页/搜索、账户详情；只读accountRef校验租户/商家/门店，不替代写入授权。
- [ ] 成功过账凭证生成一条业务收支，金额正负与balanceAfter来自原凭证；处理中/取消不写余额变动。
- [ ] 实现类型、日期、顾客与当前店过滤；稳定游标绑定条件，过滤变化重置分页。
- [ ] 历史流水批量幂等回填，先dry-run并报告缺失来源，不改余额，不编造历史字段。
- [ ] 编译及本批定向测试，检查实际SQL索引；接口返回不包含内部双池或私密凭据。

**退出标准**：原型列表与详情可由真实接口完成，不用前端拼接两种独立分页来假装混合记录。

### 批次E：B端页面（ST09、ST10、ST11）

**输入**：C、D可用契约；**输出**：统一UI组件构建的真实接口页面。

新增页面：`b-app/src/pages/stored-value/index.vue`、`stored-value-plan-edit/index.vue`、`stored-value-plan-detail/index.vue`、`stored-value-recharge/index.vue`、`stored-value-account/index.vue`、`stored-value-record/index.vue`。三栏子列表和时间弹框放`stored-value/components/`。新增API类型和共享金额/日期/请求状态逻辑，更新营销入口、pages.json、导航、contract/endpoints及三语。

- [ ] 先写定向API及页面逻辑测试，验证storeNo、Minor金额、revision、幂等键与分页重置。
- [ ] 营销→储值管理，三栏为储值方案/顾客账户/收支记录；独立新建不经过活动。
- [ ] 实现方案列表、新建、确认、详情、启停；金额固定，变更需新建方案。
- [ ] 实现顾客列表→账户详情→最近收支/全部记录，含搜索、零余额和空态。
- [ ] 实现全部/储值/核销Tab与时间弹框；取消保留原条件，确认后刷新；沿用统一切店，不加本页门店选择器。
- [ ] 实现办理：会员码识别、选方案、私有实收凭据、按权限提交/确认、处理中查询与原单恢复；缺少receive权限显示待确认。
- [ ] 写命令持久化载荷与原幂等键；结果未知先查原单，禁止自动新建第二单。切店后旧顾客引用及旧请求响应失效。
- [ ] 复用sh-scaffold、sh-tabs、sh-card/sh-block、sh-sheet、sh-actionbar、sh-empty及已有扫码/上传组件；B端12px页/卡片边距、10px块间距、16px卡片圆角，文案简洁。
- [ ] 更新界面清单及接口交付登记；vue-tsc、定向Vitest和H5构建通过。

**退出标准**：原型主路径已由业务页面与真实API承载，无mock伪造发布、余额或入账成功。

### 批次F：定向联调与交付（ST12）

**输入**：A–E通过；**输出**：可审查代码、迁移及联调证据。

- [ ] 先部署兼容迁移/后端并验证读接口，再发布前端；存量盘点和迁移未通过时不得开放新写入。
- [ ] 当前测试店建立多个方案，验证启停与跨店不可见；同客在两家测试店验证账户隔离。
- [ ] 使用已授权的测试顾客、凭据完成一条充值到账，验证同店账户累计、单据、收支金额和余额一致。
- [ ] 对已存在核销内核执行本次门店选账影响的最小兼容用例，不新建核销页面；缺少本人授权时记录联调限制，不伪造授权。
- [ ] 验证时间Tab组合、取消/确认、分页、切店旧响应隔离、网络超时同键恢复和已收款待到账显示。
- [ ] 更新任务表状态、TDD验收输出、API/UI登记，按文件归属审阅diff；提交部署遵循会话授权，只暂存本任务改动。

**退出标准**：M01–M08均有真实证据，已知未完成项与联调限制明确；没有迁移阻断或资金状态不明问题。

## §3 执行依赖与完成规则

顺序：A→B→C→D→E→F。ST08随C/D完成，不留到前端阶段补接口。先完整实现服务端核心路径，再集中开发前端；每批只做基本测试，相关阶段完成后集中联调。

每个任务状态仅取：待开发、开发中、编码完成待验收、验收通过、受阻。编码完成不能代替联调通过；原型完成不计为业务页面完成。每批在任务表补提交/变更文件、编译输出、Tests run计数、未通过项，不报“零测试BUILD SUCCESS”为测试通过。

重放规则：同键同载荷返回原结果，同键不同载荷拒绝；异步到账期间只查询/恢复原单，不生成新充值。方案启停不影响已到账余额；取消仅作用于未确认收款的原单，不是退款。

## §4 迁移与回退约束

- 开始编码前按现有规范使用隔离工作区；共享目录不得批量暂存或覆盖其他会话改动。
- 迁移只新增版本；盘点、备份、转换、核对及写入切换分步执行，不借测试环境身份清空数据。
- 账户门店化与PLAN来源都会使旧二进制不兼容。存在新数据后只能回退到支持门店账户与双来源的兼容版本，不能直接退回商家共用逻辑。
- 发现异常先停止新办理，保留原单查询、已确认收款的恢复和资金事实；不以改余额、删凭证或回滚已应用迁移处理。

## §5 测试范围与命令

本段为实施时计划，当前未运行业务编译/测试。Java按仓库要求使用离线Maven，新增类遵循现有包规范、接口化服务、事务及SQL方言约束。

- Java编译：`mvn -o -f backend/pom.xml -pl shop-app,pay/pay-svc -am -DskipTests compile`。
- Pay本批测试：`mvn -o -f backend/pom.xml -pl pay/pay-domain -am test -Dtest=<本批测试类，逗号分隔> -Dsurefire.failIfNoSpecifiedTests=false`；核对实际Tests run，不将未发现测试当成功。
- BFF/集成测试：同上选择`-pl shop-app`，仅列新测试和本次确实受影响的兼容测试。
- B端目录：`npm run type-check`、`npm test -- tests/stored-value-api.test.ts tests/stored-value-form.test.ts tests/stored-value-page.test.ts`、`npm run build:h5`（新测试实施时创建）。
- 相关闸门：界面/API契约/权限/三语与生成物检查，`python3 scripts/gen-ui-catalog.py --check`及`git diff --check`；修改原型追加原型清单校验。

定向测试映射：M01/M07→账户隔离及迁移演练；M02→方案服务；M03→独立充值及恢复；M04→顾客查询；M05→投影/游标；M06/M08→页面及时间转换。对关键隔离、幂等和筛选行为各选必要用例做消融，暂时撤掉校验后测试必须失败，恢复后通过。其他已通过且未受影响的回归不重复执行。

## §6 当前进度

已完成：原型、静态接口差异评审、门店任务收敛及本开发计划。计划文档已检查无本期连锁任务；不代表代码完成。

待执行：A–F全部开发/迁移/联调任务。首个执行批次为A：契约冻结与只读存量盘点。

## §6 本轮实现记录

执行进度、实际接口字段与发布限制以[门店储值实现与交付](./TDD-门店储值实现与交付.md)和[任务表执行回写](./任务表-门店储值管理.md#6-执行回写2026-10-20)为准。上文未勾选项保留原计划，不代表全部已验收；测试类合并为StoredValueStorePlanFlowTest等定向用例。当前实例无历史资金，未实现通用旧资金拆分/流水回填执行器，有历史数据环境必须另行审计。实际搜索先取Pay本店账户候选，再由Core按批匹配；日期由BFF按商家市场时区换算。
