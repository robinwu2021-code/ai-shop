# TDD-商户迁移同步与渠道关联

状态：已确认（2026-11-16，用户「按建议确认并开发」）；N2、N3、N5、N6 已实现（near-pay 在分支 `feat/oscar-entity-merchant-repair`），未部署
关联需求：[运营端-账户商户门店渠道-功能矩阵](../requirements/运营端-账户商户门店渠道-功能矩阵.md) §3.1–3.4、§六 D4；[PRD-支付域](../requirements/PRD-支付域.md)；交接 [oscar-channel-sync-2026-11-16](../handoff/oscar-channel-sync-2026-11-16/README.md)
档位：2（跨 ai-shop 与 near-pay 两个仓库；新资源与新表；历史归属修复不可逆，另见 [ADR-060](ADR/ADR-060-迁移归属冲突走修复申请不按名称合并.md)）
创建：2026-11-16 · 最后更新：2026-11-16

## 目标

把迁移来源中的**账号、经营主体（含资质）、门店、渠道商户信息**迁入 nearshop，每类对象提供**只查询**与**只新建**两组运营 API；全部对象到位后，再按关联清单逐条建立关联。查重、分支、顺序与重跑由脚本负责，接口不做「查到就合并」。

落点约定：账号、经营主体、资质、门店在商城 ai-shop（`/ops-shop/ops/...`）；支付商户、渠道账户、凭据、门店绑定在 near-pay（`/oms-api/pay-ops/v2/...`，下文记作 `P`）。所有写入都经运营 API，脚本不连库。

首户：Oscar（`oscar@umeshop.ai`）／創勢科技股份有限公司／ume 早餐店。

### 验收标准（PRD 无「迁移」条目，此处临时补 AC）

- AC1：Given 来源对象在 nearshop 中**零条**，When 脚本执行，Then 调用新建 API 建出一条，回读字段与来源一致。
- AC2：Given 已有**唯一且归属一致**的记录，When 脚本执行，Then 不调用任何新建 API，报告该项为「复用」。
- AC3：Given 查到**多条或归属/字段不一致**，When 脚本执行，Then 该项报告「冲突」并停止该项，其余互不依赖的项继续。
- AC4：Given 查询返回 HTTP 错误、超时或无权限，Then 报告「阻断」，**不得**当作零条进入新建。
- AC5：Given 新建请求超时，When 重跑，Then 先用**同一 requestNo** 查回执，不换号重建；同 requestNo 不同载荷返回幂等冲突。
- AC6：Given 资质已有同类型有效记录，When 调用资质新建 API，Then 返回冲突码，不覆盖原记录。
- AC7：Given 经营主体已有支付商户，When 调用 `merchant-creations`，Then 返回冲突并带出已有 `merchantNo`，不建第二个。
- AC8：Given 支付商户的来源关联指向旧主体，When 运营提交归属修复申请并经审批执行，Then 只改本次申请范围内的关联并留历史；不迁其他门店、不转移 MID。
- AC10（2026-11-16 用户纠正）：创建顺序为「账号 → 门店 → 选门店建主体」，每个新主体（含同一账号的第二个主体）都必须先有一家门店、基于该门店建主体，不单独建主体（实现方式待 Q5）；Given 主体尚无门店或门店未同步到支付侧，When 运营代建支付商户，Then 返回冲突，不建商户。
- AC9：关联清单 A–G 每条执行后回读，脚本输出编号对照表（userNo / entityNo / storeNo / merchantNo / accountNo / bindingNo）。

### 不做（Out of Scope）

- 门店归属迁移（矩阵 S09，D4 已定先不做）；
- 通用数据迁移平台、批量导入界面；
- 正式环境支付测试会话 `payment-test-sessions` 与渠道受理验收——关联完成后另开 TDD；
- 已有记录的字段修订（差异只报告，走现有业务维护流程）。

## §0 对账一 · 需求 → 设计

### 0.1 迁移对象清单

✅ 已有（源码已核对，2026-11-16）　❌ 待补　⚠️ 已有但语义不符

| # | 对象 | 判重键 | 查询 API | 新建 API |
|---|---|---|---|---|
| O1 | 账号 | 邮箱或手机 + 地区 | `GET /ops/customers/users?keyword=` ✅ | `POST /ops/customers/users` ❌（N1） |
| O2 | 经营主体 | 统一编号 + 地区 | `GET /ops/merchants?keyword=`、`GET /ops/merchants/{entityNo}`、`GET /ops/customers/operating-entities/{entityNo}` ✅ | `POST /ops/merchants/apply-on-behalf` ✅（**必须带门店资料 `storeProfile`**，主体与首店同单建） → `POST /ops/merchant/apply/{applyNo}/accept`、`/audit` ✅ |
| O3 | 资质 | 主体 + 资质类型（有效） | `GET /ops/merchants/{entityNo}/qualifications` ✅ | 现 `POST` 为登记或更新 ⚠️ → 新增只新建 `POST .../qualification-creations` ❌（N2） |
| O4 | 门店 | 主体 + 门店名 + 地址 | `GET /ops/stores`、`GET /ops/stores/{storeNo}` ✅ | 新主体：随 O2 申请建；已有主体加店：`POST /ops/merchants/{entityNo}/stores` ✅ |
| O5 | 支付商户 | 经营主体编号 | `GET P/merchants`、`GET P/merchants/{merchantNo}`、`GET /ops/merchant/{entityNo}/pay-service` ✅ | `POST P/merchant-creations` ✅ 已在 166 运行版本；缺主体级唯一 ⚠️（N3） |
| O6 | 渠道商户账户 | 环境 + 渠道 + MID | `GET P/channel-accounts` ✅ | `POST P/channel-accounts` ✅ |
| O7 | 渠道凭据 | 渠道账户 + 用途 + 版本 | `GET P/credentials` ✅ | `POST P/credentials` ✅（密钥只读受控配置） |
| O8 | 渠道产品 | 渠道账户 + 产品编码 | `GET P/channel-accounts/{accountNo}` ✅ | `POST P/channel-accounts/{accountNo}/products/{productCode}/open` ✅（命令） |
| O9 | 迁移来源 | 原系统商户 ID | ❌（N4，待定来源系统） | 无（只读） |

### 0.2 关联清单

| # | 关联 | API | 状态 |
|---|---|---|---|
| A | 账号 ↔ 经营主体（店主） | 代建申请时指定店主；回读 `GET /ops/customers/users/{userNo}/stores` | ✅；已有主体补店主暂无接口，Oscar 已是店主，本期不做 |
| B | 经营主体 ↔ 门店 | 主体创建时必须先关联门店（随申请同建）；已有主体加店在主体下新建 | ✅ |
| C | 经营主体 ↔ 支付商户 | `merchant-creations` 带 `operatingEntityId`；前置：主体已有门店且已经 D 同步到支付侧（N6） | ✅ |
| D | 门店 ↔ 支付门店投影 | `POST /ops/stores/nearpay-sync`，回读 `GET P/stores/{storeNo}` | ✅ |
| E | 支付商户 ↔ 迁移来源 | `POST P/merchants/{merchantNo}/source-links/{linkNo}/verify` | ✅（需真实来源依据） |
| F | 支付商户 ↔ 渠道账户 | 渠道账户新建时指定商户 | ✅ |
| G | 门店 ↔ 渠道账户 | `GET/POST P/stores/{storeNo}/bindings` | ✅；已有不同绑定走换绑审批 |
| H | 历史关联修复 | `P/source-association-repair-requests` | ❌（N5） |

### 0.3 AC → 落点

| AC | 落点 |
|---|---|
| AC10 | 脚本：主体零条时门店随主体计划新建；O5 待建前要求 O4 复用且门店投影存在，否则 BLOCKED。near-pay N6 |
| AC1–AC4、AC9 | 同步脚本 `deploy/ume/oscar-sync/sync.py`（查询—选择—新建/复用—回读） |
| AC5 | 各新建 API 的 `requestNo` 回执（N1、N2、N5 照 `merchant-creations` 的 `request_hash` 做法） |
| AC6 | N2 |
| AC7 | N3 |
| AC8 | N5 + ADR-060 |

**孤立项**：O9 无落点——来源系统未定（见 §4 待决 Q1）；在定之前 E 的来源依据由运营人工提供。

## §1 现状与影响面

- 账号：`OpsCustomerController` 只读（`users`、`users/{userNo}/stores`）。
- 资质：`OpsQualificationController#save` 带 `qualNo` 为更新、不带为新增，同类型不判重。
- 支付商户代建：near-pay `OpsMerchantAssistanceController` + `OpsMerchantCreationService`，`requestNo` + `request_hash` 幂等；只校验主体可用与地区范围，**不查该主体是否已有商户**。已在 near-pay 主线 `codex/near-pay-migration` 与 166 运行版本 `release/166-collection-sse-20261116` 上。
- 门店投影、来源核验、渠道账户、凭据、绑定、产品开通：near-pay `pay-ops/v2` 已有。
- 会被改到的：资质登记（只加新端点，不改现 `POST` 语义，运营前端不受影响）；代建支付商户（加唯一校验，影响运营代建与 B 端 `biz/pay/v2/merchant-creations`，两者共用服务）。
- 明确不受影响：入驻审核流程、门店新建、渠道进件流程、支付下单。

## §2 方案

### 契约变更

| 编号 | 仓库 | 端点 | 库表 / 迁移 | 权限码 | 错误码（三语） | 优先级 |
|---|---|---|---|---|---|---|
| N1 | ai-shop | `POST /ops/customers/users`；`GET /ops/customers/users/creations/{requestNo}` | `usr_account_creation`（请求回执） | `customer:user:create` | 账号已存在、幂等冲突 | P2（Oscar 不需要） |
| N2 | ai-shop | `POST /ops/merchants/{entityNo}/qualification-creations` | 无新表（`qualNo` 由服务生成） | 复用 `merchant:category:grant` | 复用通用 `CONFLICT`（同类型 `VALID` 资质已存在），无新词条 | P1 |
| N3 | near-pay | `POST P/merchant-creations` 与 `biz/pay/v2/merchant-creations` 行为变更 | 无；建前查代建回执 `operating_entity_no` 与来源关联 `external_entity_id`（均排除 `CLOSED` 商户） | 不变 | 复用 `STATE_CONFLICT`，说明「经营主体已有支付商户 PM…」 | P0 |
| N4 | ai-shop | `GET /ops/migration-sources/merchants?registrationNo=`、`/{sourceId}`、`/{sourceId}/channel-accounts` | 视来源系统而定 | `merchant:read` | — | 待 Q1 |
| N5 | near-pay（迁移 V105） | `GET P/source-association-repair-requests`、`GET .../{repairNo}`、`POST ...`、`POST .../{repairNo}/approve`、`/reject`、`/execute` | `pay_source_association_repair`（申请、范围、前后快照、审批、执行结果） | `pay:merchant:repair`（申请）、`pay:merchant:repair:approve`（审批，申请人不可自批） | 范围外对象、状态不允许、版本冲突 | P0 |
| N6 | near-pay | 运营代建 `POST P/merchant-creations` 加前置（App 侧 `biz/pay/v2/merchant-creations` 不变） | 无；查 `pay_store_projection`（同主体、未删除、非 `DELETED`/`DISABLED`） | 不变 | 复用 `STATE_CONFLICT`「经营主体尚未关联门店」 | P0 |

配置项：无。脚本连接参数走环境变量 `NEARSHOP_BASE`、`NEARSHOP_TOKEN`，不入库不入 Git。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `deploy/ume/oscar-sync/sync.py` | 只读同步：按 O1–O8、A–G 查询并输出复用 / 待建 / 冲突 / 阻断报告（WP1，本期先交付） |
| 新增 | `deploy/ume/oscar-sync/test_sync.py` | 判定规则单测（零条、唯一、多条、归属不一致、HTTP 失败） |
| 新增 | `deploy/ume/oscar-sync/oscar.example.json` | 首户目标描述（只有编号与判重键，无证件、银行账号、密钥） |
| 新增 | ai-shop `shop-merchant/.../OpsQualificationController#create` | N2 |
| 修改 | ai-shop `MerchantGovernService` / Impl | N2：新增 `createQualification`，同类型有效记录存在即抛冲突 |
| 修改 | near-pay `pay-domain/.../OpsMerchantCreationService` | N3：建前按主体查已有商户 |
| 新增 | near-pay `pay-domain/.../platmch/repair/*`、`pay-portal/.../OpsSourceAssociationRepairController` | N5 |
| 新增 | near-pay 迁移 `V1xx__source_association_repair.sql` | N5 |
| 修改 | `deploy/ume/oscar-sync/sync.py --apply [--execute-repair]` | WP6（已实现）：只自动做 O3 资质只新建、H 修复申请（审批由他人）与已批准执行、D 门店同步支付侧、O5 运营代建、G 门店绑定；账号、主体、门店、渠道账户、凭据、产品、来源核验只报告。requestNo 前缀取配置 `requestPrefix`，重跑沿用原单；修复申请缺真实资料依据不提交 |

### 关键接口

N3 冲突：沿用 `CatalogException.stateConflict`，`reasonCode=STATE_CONFLICT`，说明文字带已有 `merchantNo`；脚本收到冲突后按主体回查 `GET P/merchants` 取已有商户复用，不解析文字。同 `requestNo` 重放仍返回原回执（校验在重放判断之后）。

N5 申请体（范围必须显式列举，执行时逐项比对前快照，任一不符整单失败）：

```json
{
  "requestNo": "oscar-repair-001",
  "merchantNo": "PM13A13B737D7C4A57",
  "fromOperatingEntityId": "M261106BF7GK8052W",
  "toOperatingEntityId": "M261113Y3QW5XKEFW",
  "storeNos": ["ST2611132NS49C2EPQ"],
  "evidence": "资料依据说明（不含证件原文）",
  "expectedVersion": 3
}
```

脚本单项算法：

```text
query exact identity
  error/timeout/denied -> BLOCKED
  zero    -> PLAN_CREATE（--apply 时：create(requestNo) -> query receipt -> query resource）
  one & same owner/data -> REUSE
  many / different owner / different fields -> CONFLICT（停本项）
```

### 实施顺序

1. **WP1 只读脚本**（不动契约，本期先交付）→ 在 166 跑出 Oscar 编号对照与冲突清单。
2. **N3、N5**（near-pay，P0）与 **N2**（ai-shop，P1）并行；各自单测 + 本仓闸门。
3. 部署 166 后，**WP6** 给脚本加 `--apply`：依次 O1 → O2+O4（主体随门店同建）→ A → O3 → D（门店同步支付侧）→ O5/C → E → O6–O8 → F、G，冲突处插入 H。
4. N1、N4 等 Q1 定论后再排。

## §3 选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A 查询 / 新建分离，脚本判重 | 接口语义单一；冲突可见；可重跑 | 脚本逻辑多 | ✅ 用户 2026-11-16 指定 |
| B 一个 `sync`/upsert 接口 | 调用少 | 隐式合并，按名称匹配易错并 | ❌ |
| C 脚本直接写库 / SQL copy | 快 | 绕过权限、审计与业务校验 | ❌ |
| 历史归属：修复申请 + 审批执行 | 有范围、有快照、可审计 | 多一个资源 | ✅ 见 ADR-060 |
| 历史归属：普通保存覆盖 | 无新开发 | 无审批、丢历史 | ❌ |

## §4 风险与待决

| 风险 | 影响 | 缓解 |
|---|---|---|
| 统一编号与 `tax_no` 位数不一致 | 主体误判重复或漏判 | 判重只用营业登记编号原字段；不一致报冲突，不按名称兜底 |
| N3 改变 B 端代建行为 | App 侧重复代建由成功变冲突 | 冲突带已有 `merchantNo`；B 端与运营端同测 |
| 修复执行半途失败 | 关联处于中间态 | 单事务执行；前快照比对不符整单拒绝 |
| 蓝新 MID `PMD3A0C5A8C8FB4F92` 被误转移 | 资金归属错误 | N5 范围不含渠道账户；执行器对渠道账户只读 |

已决（2026-11-16，用户按建议确认）：

- Q1 迁移来源系统未定：N4 暂缓，来源核验（关联 E）由运营人工提供依据。
- Q2 归属修复审批：持 `pay:merchant:repair:approve` 的运营，申请人不得自批。
- Q3 一个经营主体只允许一个支付商户：N3 照此实现。
- Q4 N1（运营新建账号）本期不做。

- Q5（待定，2026-11-16）：「先门店后主体」时门店在主体建成前挂在哪里——现有 `mch_store.entity_no` 为必填，且没有「选门店建主体」接口。候选：A 门店先归属账号、`entity_no` 可空，主体新建必填 `storeNo`；B 先挂占位主体、建正式主体后用现有门店换挂。适用范围（运营端+脚本 / 连 App V46）一并待定。Oscar 首户账号、主体、门店均已存在，不受影响，迁移先行。

残余风险：N3 为事务内先查后插，不加库级唯一键（166 存量可能已有同主体多商户，加唯一键会使迁移失败）；同主体两个不同 `requestNo` 并发代建仍可能各建一个，由脚本串行执行规避。

## §8 第二期：运营端迁移接口（2026-11-16 用户要求「全部通过运营端 API 完成」，草稿）

背景：首户改为以原系统商户「李昱達」（`88600253990002308`）为准，在 Oscar 账号（`U261113VG2HFDJMK0`）下新建主体「李昱達」，门店用 Oscar 名下 `ST2611132NS49C2EPQ`「ume 早餐店」，新建支付商户，渠道暂不配。后续有大量账号按同一路径迁移，商户本人不参与操作。

### 8.1 现状核对（源码与 166 数据）

| 能力 | 现状 | 缺口 |
|---|---|---|
| 来源数据 | 导入表 `ai_shop_ume.stg_ume_active_stores_20261113`、`stg_ume_first_batch_store_accounts_20261113`，`nearpay_prod.stg_ume_active_payment_merchants_20261112` | 无运营读取接口（N4） |
| 账号下建主体 | 仅 App `POST /biz/entities`（本人）；运营 `apply-on-behalf` 会连带新建门店 | 运营端不能「选已有门店建主体」 |
| 门店换挂主体 | 仅 App `POST /biz/store/{storeNo}/entity`（本人），与 near-pay 两阶段协同（`StoreEntityClaimServiceImpl`） | 运营端无入口；策略 `StoreRelinkLocalPolicy` 要求目标主体 `ACTIVE`、来源主体 `PENDING_LICENSE`；来源只剩该店时来源主体被注销 |
| 主体变 ACTIVE | 仅 near-pay 实体资料审批回写（`EntityRegistrationProjectionImpl`，要求 8 位统编） | 个人工商户无统编 |
| 支付商户 | `POST /ops/merchant/{entityNo}/pay-service/apply` 由商城主体同步建商户并建来源关联，门店随之关联；`registrationNo` 必填 | 个人无统编；运营代建 `merchant-creations` 依赖 `mch_operating_entity`（仅本人 App 可建），与门店主体体系不通，不采用 |

### 8.2 拟新增接口

| 编号 | 仓库 | 端点 | 说明 |
|---|---|---|---|
| M1 | ai-shop | `GET /ops/migration-sources/merchants/{sourceMerchantId}`；`GET /ops/migration-sources/merchants?email=&storeName=` | 只读导入表：门店、账号（手机末 3 位）；权限 `merchant:migration:read` |
| M2 | near-pay | `GET /pay-ops/v2/migration-sources/merchants/{sourceMerchantId}` | 只读导入表：主体要素（证号末 4 位）、各渠道及渠道商户号；权限 `pay:merchant:view` |
| M3 | ai-shop | `GET /ops/migration/entity-claims/precheck?ownerUserNo=&storeNo=`；`POST /ops/migration/entity-claims`；`GET /ops/migration/entity-claims/by-request/{requestNo}` | 一个运营命令：为店主新建主体（记来源商户号）→ 按迁移口径置主体状态（待 D1）→ 复用现有门店换挂协同把门店挂到新主体；`requestNo` 幂等、可续跑；权限 `merchant:migration:execute`；审计 |
| M4 | 现有 | `POST /ops/merchant/{entityNo}/pay-service/apply` | 建支付商户；个人无统编时的取值待 D2 |

### 8.3 决定（2026-11-16 用户确认）

- D1 来源已审核（`audit_status=2`）的商户迁移时即为 `ACTIVE`：由运营审核代填单落实（审核通过即激活），审核依据为来源审核结论。
- D2 个人工商户无统编：支付商户登记号用负责人证件号，由运营录入，不进 Git、不进脚本配置。
- D3 原「創勢科技」可改掉或删除：不再走门店换挂。

### 8.4 166 现状追加核对与路线调整

- 门店换挂在 166 不可用：`shop.merchant.store-claim.enabled` 未开，且 `INVENTORY_ENABLED=true` 使 `StoreRelinkLocalPolicy` 恒报 `INVENTORY_HISTORY_UNKNOWN`。
- 批量迁移不需要换挂：原系统 600 余户的主体（`MUME…`）、门店（`STUME…`）、店主（`UUMEU…`）已导入为待补证照主体。
- 新路线：运营代填 `POST /ops/merchants/apply-on-behalf` 新增可选 `entityNo`（店主名下待补证照主体）→ `accept` → `audit` 通过即激活该主体为 `ACTIVE` 并改为申请名称，**保留其原默认门店**，不另建主体与门店 → `POST /ops/stores/nearpay-sync` → `POST /ops/merchant/{entityNo}/pay-service/apply` 建支付商户 → 渠道账户与绑定。
- 首户 Oscar：`entityNo=M261113Y3QW5XKEFW`（原「創勢科技」，门店 ume 早餐店）改为「李昱達」（个人）。

### 8.5 已实现（M3' 代填指定主体）

| 动作 | 路径 |
|---|---|
| 修改 | `shop-core/.../api/ops/OpsMerchantApplyController.java`：`OnBehalfReq.entityNo`（可选，`[A-Za-z0-9_-]{1,64}`） |
| 修改 | `shop-core/.../platform/OpsService.java`、`impl/OpsServiceImpl.java`：`createApplyOnBehalf(cmd, submittedBy, entityNo)`；锁主体、拒绝已托管登记、与 `storeProfile` 互斥，主体须为店主本人名下待补证照且有营业中默认店（复用 `merchantApplicationEntity`） |
| 测试 | `OnBehalfMerchantFlowTest#onBehalfWithEntityNoActivatesThatPendingEntityAndKeepsItsStore`、`#onBehalfWithEntityNoRejectsForeignEntityAndStoreDraft`（全类 16/16）；`OpsMerchantRegionalContactTest` 8/8 |

M1、M2（来源读取）随批量迁移排期；本户不依赖。

## §9 渠道账户转移（N7，2026-11-17，草稿 → 用户已定 D4、D5）

背景：李昱達原有生产渠道账户 `PM-UME-NEWEBPAY-TRL00001898`、`PM-UME-LINEPAY-1626862643` 挂在手工测试支付商户 `UME-M-88600253990002308` 下，需转到新支付商户 `PM2026111707510001`。同渠道商户号全局唯一（`uk_pay_merchant_2` 不含删除标记），不能重建；渠道账户更新接口不能改归属；归属修复（N5）会因目标主体已有商户冲突。

现状（2026-11-17 只读）：两账户共 55 笔 PRODUCTION 订单（成功 5、待支付 50，11-05～11-16），10 条账务；订单与结算按单快照 `platform_merchant_no`；凭据、产品、渠道资格按账户 `pay_merchant_id` 关联；两账户各绑定测试门店投影 `900000041`（UME-S）。

决定：
- D4 有未完结订单仍允许转移；历史与残单按快照仍归原商户，转移只影响此后新单。
- D5 执行时自动结束该账户在「不属于目标商户的门店」上的有效绑定，留痕。

契约（near-pay，照 N5 模式）：`GET/POST P/channel-account-transfers`、`GET .../{transferNo}`、`POST .../{transferNo}/approve|reject|execute`；表 `pay_channel_account_transfer`（V106）；审批受控类型、申请人不得自批；执行单事务比对前快照（账户版本、原商户、目标商户状态与市场），改账户的 `platform_merchant_id/no`，结束不属目标商户门店的绑定，写前后快照与审计；不动订单、结算、凭据、产品。之后用现有 `POST P/stores/{storeNo}/bindings` 绑定 ume 早餐店。

实现（near-pay `release/166-channel-transfer-20261117`）：`merchant/account/transfer/*`、`OpsChannelAccountTransferController`、`V106__channel_account_transfer.sql`（隔离表，含执行分区列）；权限 `pay:channel-account:view`（查看）、`pay:channel-account:transfer`（申请）、`pay:channel-account:transfer:approve`（审批与执行）。执行同时改账户 `entity_name_snapshot`（仅展示用）；目标商户门店上的绑定保留并改记目标商户（否则收款入口与终端校验判不一致）；未完结订单按 CREATED/AWAITING_PAYMENT/AUTHORIZED/AWAITING_CONFIRM 计数入审计快照、不参与漂移比对。POS 终端与设备绑定不处理（本次两账户均为 0）。

2026-11-17 变更：N5、N7 的自批校验改为 `ApprovalSeparation.conflict`（near-pay `b42b7efa`），单人审批开关打开时允许同一人提交并审批；用户决定，已发布 166（jar `7349b3fa…`）。

执行（2026-11-17，运营 neara）：`CT14CDF69BE48C4605`（蓝新 TRL00001898）、`CTBB0D57C478D14E4C`（LINE Pay 1626862643）审批并执行；两账户归属 `PM2026111707510001`，测试门店 `900000041` 两条绑定置 `INACTIVE`；ume 早餐店绑定 `SB3AE65267D7994580`（NEWEBPAY）、`SBA7AC537FFDA74B32`（LINEPAY）；55 笔订单快照仍为 `UME-M-88600253990002308`。测试：`ChannelAccountTransferServiceTest` 7/7、`ChannelAccountTransferMySqlTest` 7/7、`OpsChannelAccountTransferControllerTest` 4/4，N5 三类与 `PayApplicationBootTest` 8/8 不受影响。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 |
|---|---|---|
| AC1–AC4 | `deploy/ume/oscar-sync/test_sync.py` | WP1 |
| AC5 | N5 幂等测试（`SourceAssociationRepair*Test`）；脚本 `test_sync.py::ApplyTest` 8 条（只读模式零写入、代建回读、未同步先排队不代建、500 不写、冲突走修复不建第二个商户、已批准仅带开关执行、补绑定、资质缺资料不提交）——全文件 20/20 | ✅ |
| AC6 | ai-shop `QualificationCreateOnlyTest`（3 条：同类型有效即冲突且不写；无则新建；带 `qualNo` 拒绝） | ✅ 3/3 |
| AC7 | near-pay `MerchantCreationMySqlTest#secondMerchantForSameOperatingEntityConflictsAndNamesTheExistingOne`、`#sourceLinkedEntityAlsoCountsAsHavingAMerchant`（MySQL 9.7@3308） | ✅ 全类 17/17 |
| AC8 | near-pay `SourceAssociationRepairServiceTest`（H2）7/7、`SourceAssociationRepairMySqlTest`（MySQL 9.7）7/7、`OpsSourceAssociationRepairControllerTest` 4/4；自批拦截做过消融（去掉即红） | ✅ |
| AC10 | ai-shop `test_sync.py::StoreFirstTest` 4/4；near-pay `MerchantCreationMySqlTest#operatorCreationRequiresTheEntityToHaveASyncedStore`（全类 18/18）、`OpsMerchantReviewMySqlTest` 1/1 | ✅ |
| AC9 | 166 只读运行报告 | 待运行 |

## §6 对账二 · 设计 → 实现（实现完再填）

两仓库各一个提交（ai-shop 当前分支；near-pay `feat/oscar-entity-merchant-repair`），文件清单：

```
ai-shop
  M backend/shop-merchant/.../api/ops/OpsQualificationController.java       N2 端点
  M backend/shop-merchant/.../service/MerchantGovernService.java            N2 接口
  M backend/shop-merchant/.../service/impl/MerchantGovernServiceImpl.java   N2 实现
  A backend/shop-merchant/src/test/.../QualificationCreateOnlyTest.java     AC6
  M ops-web/known-orphan-endpoints.txt                                       N2 脚本专用端点登记
  M 生成物：docs/technical/README.md、reference/三端权限矩阵-按业务域.md、reference/运营端-角色×端点矩阵.md、packages/shared/tests/fixtures/ops-role-endpoint-matrix.json
  A deploy/ume/oscar-sync/{sync.py,test_sync.py,oscar.example.json}         WP1
near-pay（feat/oscar-entity-merchant-repair）
  M pay-domain/.../platmch/PlatmchStore.java                                N3 主体→商户查询
  M pay-domain/.../platmch/creation/{MerchantCreationService,OpsMerchantCreationService}.java  N3
  M pay-domain/src/test/.../MerchantCreationMySqlTest.java                  AC7
  A pay-domain/.../db/pay/V105__source_association_repair.sql               N5 表（原 V102，发布前改号）
  A pay-domain/.../platmch/repair/{SourceAssociationRepairStore,Service,Subject}.java  N5
  A pay-portal/.../api/ops/v2/OpsSourceAssociationRepairController.java     N5 端点
  M pay-domain/.../access/PayResourceCatalog.java                           N5 权限码
  M pay-domain/.../audit/AuditResourceType.java、environment/EnvironmentTablePolicy.java  N5 审计类型 / 共享表
  A pay-portal|pay-svc 测试 3 个                                            AC8
```

| 差异 | 说明 |
|---|---|
| §2 列了 `GET .../repair-requests/by-request` 未单列 | 列表接口带 `requestNo` 过滤即可回查回执 |
| N5 新增 `PayResourceCatalog`、`AuditResourceType`、`EnvironmentTablePolicy` 三处 | 权限码由资源目录生成；审计类型与环境表策略为新表必登记项 |
| N2 未带 `requestNo` | 超时重试由「先查」或冲突兜底，不新增回执表 |

### 偏差说明

- **N5 修复语义**：执行时原行原号改 `pay_merchant_source_link.external_entity_id`（原主体→目标主体），`link_no`、`platform_merchant_id` 不变，`source_revision` 归 0 以便目标主体下一版来源受理能被应用；不删行、不插新行（表无状态列，插新行会让旧主体继续解析到该商户）。V23 注释称该列「来源主体不可变 ID」，此处是经审批的唯一例外，历史由修复单前后快照与两条审计保留，已写入 ADR-060。
- **N5 范围门店**：`storeNos` 中未关联的门店补关联（`origin=APPROVAL`，带审批号）；旧主体与目标主体下未列出的门店不动。
- **审批**：接入现有审批框架（类型 `SOURCE_ASSOCIATION_REPAIR`，单节点）；审批人须同时有 `pay:approval:review` 与 `pay:merchant:repair:approve`；申请人自批无条件拒绝，不受 `near-pay.approval.single-operator` 影响。

### 遗留

- 修复后旧主体不再对应商户；ai-shop 若再按旧主体发来源受理，near-pay 会为其新建平台商户——执行修复前须确认旧主体已停用或不再同步。
- near-pay V22 建表 `sensitive` 列未加反引号，MySQL 9.7 下建不了（测试夹具已绕过），生产库须另行核实。
- near-pay ops-web 未展示新审批类型名称；数据库/接口设计文档未更新（该仓库文档校验原本就不通过）。
- ai-shop `ArchitectureTest#domainsMustNotTouchWebRuntime` 失败于 `OpsPlatformController.logout(HttpServletRequest)`，为合入的统一登录分支带入，与本 TDD 无关。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-16 | 草稿；WP1 只读脚本先行 |
| 2026-11-16 | 方案确认（Q1–Q4 按建议）；N2、N3、N5 实现，相关测试全绿，已提交、未部署 |
| 2026-11-16 | 用户纠正：经营主体创建必须先关联门店（范围：迁移流程 + 运营代建支付商户，不改 App 的 V46「主体可先于门店」）；新增 AC10、N6 |
| 2026-11-16 | 用户明确顺序「账号 → 门店 → 选门店建主体」，实现方式记为 Q5 待定；先完成 WP6 `--apply` 并执行 Oscar 迁移 |
| 2026-11-16 | near-pay N3/N5/N6 发布 166（迁移改号 V105，因 V102–V104 已被实体资料占用）；见 [发布记录](../handoff/oscar-channel-sync-2026-11-16/release-166-nearpay.json) |
