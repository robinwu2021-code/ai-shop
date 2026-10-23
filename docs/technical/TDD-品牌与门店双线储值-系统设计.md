# 品牌与门店双线储值 · 系统设计

状态：基于34屏V2原型的详细设计评审稿，尚未实施。日期：2026-10-21。

- [功能矩阵与原型规则](../requirements/B端品牌与跨店储值-功能矩阵与原型.md)
- [34屏原型](../../prototypes/brand-stored-value-b.html)
- [API清单及关键契约](TDD-品牌与门店双线储值-API清单.md)
- [数据库字段、约束和迁移](TDD-品牌与门店双线储值-数据库设计.md)
- [结构化API清单](design/schema/brand-stored-value-v2-api.json)
- [结构化数据库模型](design/schema/brand-stored-value-v2-database.json)
- [评审DDL草案](design/schema/brand-stored-value-v2-review.sql)

## 1. 已确认需求与本稿设计选择

### 1.1 必须保持的产品规则

1. 品牌是运营维度，经营主体是结算维度；门店分别关联两者，品牌不直接归属经营主体，也不新增brand_entity关系表。
2. 顶层经营管理与当前门店工作台分离。品牌管理、商户／主体管理、门店管理在顶层平级；顶层不依赖当前门店默认主体取得权限。
3. 品牌储值：在顶层选择品牌后创建，品牌唯一发行方，指定一个结算主体。同品牌有效门店接受该余额，实际办理／核销仍需岗位及准入校验。
4. 门店储值：店内创建，只当前店可用，即使门店已关联品牌也独立发行。门店最多一个单店发行方，同时可接受所属品牌发行方。
5. 会员平台身份唯一，品牌关系共享，无品牌时按门店。会员共享不等于资金合并、私有档案共享、积分卡券或营销授权共享。
6. 一顾客在同发行方同币种一个账户；品牌与本店分别显示，一次核销只选一个账户。金额和批次沿用既有Pay账本，避免新旧两本余额账。
7. 顶层可约束门店新发行：是否允许、金额及赠送上限、活动期限、未消费余额上限、发布审批；下层不能放宽。规则收紧不重写已发生批次和历史消费权利。
8. 活动在Core，权益／储值金融事实在Pay。停止活动只停止新发行，不能停止历史余额正常消费。不增加C端线上购买、退款或独立核销码。

### 1.2 设计选择（不是声称已有代码）

- 增加经营管理授权空间及资源授权，避免把“账号所有人”当成tenant超级管理员。现有mch_account是账号与主体成员关系，不能直接承担品牌跨主体财务权限。
- 复用Core pmt_activity及version生命周期，新增储值发布协调和审批记录；Pay sv_release/tier是V2多档位金额真源。旧Core充值档位与Pay单档方案保留历史，V2不继续双写同一金融规则。
- 品牌首期采用指定主体统一收款；单店采用本店主体收款。同品牌跨主体在同tenant内，不默认支持跨租户。收款模式未来扩展不属于本次无条件开放范围。
- 首期每发行方一种业务币种和明确时区，不做跨币种合计或换算。顾客账户键仍保留currency。
- 人工付款登记与收款确认可分批，但不接银行自动打款。原型只演示全额一次付款；API和表结构支持累计及部分确认，后续单独补对应UI。

## 2. 源码基线与差距

| 领域 | 已核对源码／迁移 | 结论 |
|---|---|---|
| 组织 | shop-merchant MchEntity/MchStore/MchAccount | 本工作区未发现经营品牌实体；mch_store.entityNo是真实经营主体，不是brand |
| 会员 | MbrMember、MbrMemberStore、StoredValueMemberSelectionService | 现有会员人档/主体关系，搜索以entity为范围；须增加品牌共享关系和本店私有资料边界 |
| 账户 | SvAccount、V532、V539 | 唯一键仍包含entity/store；不能删除store条件就跨店共享 |
| 方案 | V533、StoredValuePlanService | 单门店单档不可变方案；V2需要活动版本下多档位 |
| 收款 | BizStoredValuePlanRechargeController | confirm-offline校验当前店与当前主体，需新增发行方收款／结算事实及权限，不能直接放宽entity相等判断 |
| 核销 | BizStoredValueController、ord_ent_service_order | 订单预览+消费者授权内核可复用；需把选定issuer/account贯穿预览、授权、预占和提交 |
| 通知 | V543及通知worker | 已有成功核销通知，可沿用去重并补发行方定位；真实微信仍受模板和用户授权控制 |
| 结算 | StlBill | 原表按子订单分账，不适合作为品牌发行方账期主体清分表 |

迁移基线含已发生的真实一分钱联调账，不能使用早期“有效账户0”的历史结论。开发前重新拉取目标分支并查生产schema版本，本轮只读本地源码，不读取线上客户或凭据。

## 3. 领域关系与模块边界

| 序号 | 来源 | 目标 | 关系 / 条件 / 消息 |
|---|---|---|---|
| 1 | 认证管理账号 | 经营管理授权空间与资源岗位 | 流转 |
| 2 | 经营管理授权空间与资源岗位 | 品牌 | 流转 |
| 3 | 经营管理授权空间与资源岗位 | 经营主体财务授权 | 流转 |
| 4 | 品牌 | 品牌门店有效期关系 | 流转 |
| 5 | 品牌门店有效期关系 | 门店 | 流转 |
| 6 | 经营主体财务授权 | 门店 | 流转 |
| 7 | 经营主体财务授权 | 支付商户 payMerchantNo | 一对多 |
| 8 | 支付商户 payMerchantNo | 支付渠道及支付能力 | 流转 |
| 9 | 门店 | 支付商户 payMerchantNo | 本店默认收款配置 |
| 10 | 平台统一顾客 | 品牌或单店会员关系 | 流转 |
| 11 | 品牌或单店会员关系 | 品牌 | 流转 |
| 12 | 品牌或单店会员关系 | 门店 | 流转 |
| 13 | 平台统一顾客 | 储值账户 issuer/customer/currency | 流转 |
| 14 | 品牌 | 品牌发行方 | 流转 |
| 15 | 门店 | 单店发行方 | 流转 |
| 16 | 品牌发行方 | 储值账户 issuer/customer/currency | 流转 |
| 17 | 单店发行方 | 储值账户 issuer/customer/currency | 流转 |
| 18 | 品牌发行方 | 指定结算主体与版本 | 流转 |
| 19 | 单店发行方 | 指定结算主体与版本 | 流转 |
| 20 | Core活动及版本 | Pay发行配置与金额档位 | 流转 |
| 21 | Pay发行配置与金额档位 | 品牌发行方 | 流转 |
| 22 | Pay发行配置与金额档位 | 单店发行方 | 流转 |
| 23 | Pay门店发行管控 | 单店发行方 | 流转 |
| 24 | 储值账户 issuer/customer/currency | 原批次/凭证/冻结分配 | 流转 |
| 25 | 原批次/凭证/冻结分配 | 清分事实 | 流转 |
| 26 | 清分事实 | 发行方账期主体账单 | 流转 |


这里E→S表示实际经营关联及访问授权，不表示把财务授权表本身当经营主体；品牌和主体之间没有直接归属边。结算主体通过发行配置关联，是资金业务关系。

| 模块 | 真源职责 | 不能承担的职责 |
|---|---|---|
| shop-merchant | 管理空间、品牌、门店关联、资源授权及变更 | 顾客余额和金额清分 |
| shop-core/member | canonical人档识别、品牌会员、本店资料、短时引用 | 仅凭手机号合并资金账户 |
| shop-core/promotion | 活动名称、生命周期、草稿修订、门店审批、发布协调 | 维护第二套权威充赠金额或直接写Pay表 |
| pay/pay-domain + pay-svc | 发行方及结算版本、金额档位、策略、额度、账户、批次、核销、清分和账单 | 直接查询Core会员表或把管理空间所有人当财务授权 |
| shop-app/BFF及payclient/SPI | URL范围和岗位检查、DTO聚合、内部可信事实传递 | 跨库JOIN、绕过调用方资源权限、持Pay金融锁时回调Core |
| 现有outbox/job/message | 持久命令恢复、事件投递、消费通知 | 用消息发送成功推断账务提交成功 |

公共概念：OperatingScope(type=BRAND/STORE,no)、IssuerScope、ManagementScope、StoreExecutionContext相互区分。所有者品牌/单店scope决定创建账户，当前门店只决定本次办理执行地。不要在通用EntitlementContext里放任意客户端entityNo模拟品牌归属；新resolver在会话及可信事实基础上构建服务端scope。

## 4. 主要流程与一致性

### 4.1 品牌及门店配置

1. 账号取得明确管理空间和资源授权；品牌创建不选择entity。
2. 门店配置保持entity与brand两个维度；加入品牌需当前店和目标品牌双方有权确认。
3. 影响检查记录scopeRevision、原品牌会员关系、原单店发行方、旧品牌接受资格、预占、已收款未知及结算单。
4. 变更单先PREPARING，Core/Pay以稳定command及单调revision协调issuer访问。退出先停相应新执行；加入Pay准备确认后才生效。
5. 不迁移本店资金或历史主体，失败可恢复原变更命令，不能靠反复改brandNo完成。

### 4.2 创建、审批和发布

1. 顶层URL固定brand scope；店内URL固定store scope，禁止body覆盖。门店有品牌不改变STORE创建的意义。
2. Core建立草稿修订号并协调Pay准备金融release；Pay持金额、币种、规则，Core草稿只保存releaseRef/hash。修改金额生成新release，不能改旧行。
3. 门店调用checks，Pay计算当前全局/品牌门店/单店限制：allowIssue取AND，requireApproval取OR，数值上限取MIN。缺失必要根规则拒绝新发行，而不是默认无限额度。
4. 超硬限制返回FIX；符合规则但要求审批则PENDING_APPROVAL。审批绑定draftHash、revision和来源scope，不允许沿用已修改草稿的决定。
5. 审批或直接发布时复核当前授权及Pay最新策略。Core生成固定activityVersion，Pay通过同一幂等命令绑定并激活对应release，返回ack后Core标记可办理。
6. 单边失败保持PREPARING或失败状态，worker查询原命令继续；不能发布一个Pay未激活的活动。停止新发行以Pay先阻断新办理为准，Core接ack更新状态。

支付域持锁事务只读本地策略、准入及金融事实；组织资格在前置可信契约和本地revision镜像中检查。不得在锁账户/额度期间同步RPC会员、品牌或活动服务，避免重现此前跨域事务占用连接池的问题。

### 4.3 双账户办理

1. 当前店搜索会员，Core解析canonical身份；品牌会员可跨主体被识别，但只返回本次所需摘要。
2. 顾客详情分别返回STORE和BRAND账户；未开账户为null，首次成功过账开户，会员加入不批量开空钱包。
3. 选择scope及已激活release/tier，形成短时rechargeOptionRef。提交中不信任自算金额、客户号或替换的issuer。
4. 单店办理锁生效策略及额度counter，事务内占用实收+赠送额度；品牌办理按发行配置及品牌发行限制，不误套门店20%政策。
5. 收款事实与实际收款主体一致；品牌CENTRALIZED时门店必须具有指定收款配置的确认权，不能把任意本店现金记成总部已收款。
6. CREDITED事务写account、lot、voucher、entry、statement、issuer目录，并提交quota占位；核心账与投影同事务或使用可对账outbox，结果未知先原单resolve。
7. 已合法确认收款的原单使用冻结版本恢复，不因为之后政策收紧而丢弃原款或重复收款。失败账务不展示到账，消费者只收到真实成功事实通知。

### 4.4 核销

1. 订单金额来自服务端商品/服务报价；当前店返回可接受的本店及品牌账户。
2. 明确选择一个账户，冻结account/issuer及版本进入预览。C端授权包含订单、门店、金额、账户范围和过期时间。
3. Pay对同一账户和原批次分配预占；提交复核授权、资产版本和原单幂等，禁止跨issuer拼余额。
4. 成功产生凭证及清分源事件，单店quota.outstanding减实际消费总额；停止新充值不阻断既有合法余额核销。
5. 通知仍按usage/recipient/scene去重，链接改为issuer范围并focusDocumentNo，记录实际核销门店。微信模板未配置时不得假称发送成功。

### 4.5 汇总及结算

- 品牌汇总：仅品牌issuer。单店汇总：各店issuer分别统计；顶层可以做同币种经营指标合计，不能变成合并支付余额。
- 流量按[from,to)发生事实；余额用期初+变动得到asOf存量。按活动看来源lot剩余，不能把账户当前余额复制给每个活动。
- 混合活动一次核销按实际allocation归属；人数和笔数需按原业务号distinct，不直接相加日表的去重计数。
- 清分使用原批次本金/赠金分量和issuerVersion；同主体消费只内部对账；跨主体按冻结规则形成应付事实。
- 结算按issuer/period/payer/payee/currency去重，bill_item独占清分事实。已登记凭据不等于到账，分别记录registered和confirmed。
- 默认付款登记人与收款确认人应不同，且后者必须有payee的财务岗位；顶层账号或品牌管理员不能凭运营权限确认资金。
- 首期手工登记，不接银行打款。争议不覆盖原金额；已结算纠错进入后续调整事实。

## 5. API及数据库交付规模

本稿API清单90个操作，包括86个新增建议、3个现有接口扩展、1个既有授权流程复用；其中包含内部协调和C端兼容影响，不等于要开发90个Controller。数据库建议26个新表、10个现有表字段增量，已给出字段、索引、CHECK、状态及受控迁移说明。

API建议路径和权限码未加入运行时路由、权限种子或OpenAPI生成物；DDL不加入Flyway，不执行生产库。进入开发时需要按模块逐项转换并复用目标分支已存在能力，不能将本文当作已经通过运行时验证。

## 6. 分阶段实施依赖（设计，不自动开始编码）

| 阶段 | 主要范围 | 前置验收 |
|---|---|---|
| D1组织与身份 | 管理授权、品牌门店、共享会员、双线context | 同品牌识别／跨品牌隔离，运营权限不扩大财务 |
| D2发行与管控 | 发行方、Pay档位版本、策略、Core发布与审批 | 单店与品牌URL不可混用，超限不能审批绕过，发布故障可恢复 |
| D3账务与迁移 | 旧单店issuer映射、新唯一键、双账户、额度、核销授权 | 不复制资金、并发不超发不超扣、已收款原单恢复 |
| D4报表与结算 | 来源批次汇总、清分、账单、付款登记及收款确认 | 金额勾稽、账单不重复、内部对账不生成假付款 |
| D5客户端与交付 | B端两入口、C端issuer目录及消息定位、定向联调 | 旧接口无旁路、切店隔离、旧客户端有明确兼容或升级提示 |

用户要求继续适用：每轮代码实施后编译和基本定向单测；不跑全量，不重复已通过回归。当前只设计，不启动后端编译或业务测试。

## 7. 评审重点与设计边界

- 经营管理空间、Core活动ownerScope和Pay金融权限是三种范围，不因共享tenant或同一登录人而合并。
- 组织／收款配置事实激活必须有revision与ack栅栏，不能用几秒TTL替代撤销一致性。
- 单店规则只约束STORE新发行；收紧不改历史批次，也不能以暂停新发行为由阻止消费者消费。
- 跨主体品牌采用统一收款是本稿选择；如改门店分别收款，需另补收款归集应收及对账，不直接套用本稿净应付规则。
- 旧资金迁品牌、门店退出承接、主体更换、自动划款、跨租户及部分付款UI留独立评审；不以“待确认”占位阻断当前API和数据库文档完成。


## 8. 原型逐屏到API对账

| 原型 | API编号 |
|---|---|
| s01 | MG01, MG03, MG06, MG07, MG08 |
| s02 | BR01 |
| s03 | BR02 |
| s04 | BR03, BR04, BR05, BR06, BR07 |
| s05 | MG07, ST01 |
| s06 | ST01, ST02 |
| s07 | BR01 |
| s08 | ST02, ST03, ST04, ST05 |
| s09 | ST01 |
| s10 | SA01, PL04 |
| s11 | BR01, IS01, BA01 |
| s12 | BA02, BA04 |
| s13 | IS02, IS03, IS04, PL05, MG08, IS06 |
| s14 | BA05, BA06 |
| s15 | BA03, BA07, BA08, RP05 |
| s16 | MB01 |
| s17 | MB02, MB04, RC01, RC02, RC03, RC04 |
| s18 | RD01, RD02 |
| s19 | RP01 |
| s20 | RP02 |
| s21 | CL01, CL02 |
| s22 | CL03, CL04, CL05, CL06, CL07, CL08, CL09, CL10, CL11, CL12 |
| s23 | SA02, SA04, PL04 |
| s24 | MG03, MG04, MG05 |
| s25 | PL01 |
| s26 | PL02, PL03 |
| s27 | SA05, PL04 |
| s28 | AP01 |
| s29 | AP02, AP03 |
| s30 | SA03, SA07, SA08, RP05 |
| s31 | RP04 |
| s32 | US01, US02, US03, MB04 |
| s33 | SA05, SA06 |
| s34 | RP03 |


## 9. 术语修订：主体与支付商户

用户确认：经营主体与商户是1:N，商户抽象支付渠道收款身份。现有代码MchPaymentMerchant/mch_payment_merchant已经表达这一关系，不能把“主体就是商户”的旧文档口径继续带入本设计。

- entityNo：经营、责任及结算主体。
- payMerchantNo：该主体在支付渠道下的商户记录，含payChannel、进件状态、授权支付方式及渠道结算配置。不能仅凭主体ACTIVE推断该商户可收款。
- issuerNo：品牌或门店储值发行方，与支付商户不同。一个发行方可以按渠道／场景引用同一指定主体旗下不同支付商户。
- 平台登录账号、商家操作账号、顾客储值账户、支付渠道商户四者分别建模。

品牌CENTRALIZED模式选择指定主体旗下有效商户；STORE_LOCAL选择本店经营主体旗下有效商户。不能把门店默认payMerchantNo用于不同主体的品牌统一收款。线下CASH/TRANSFER不伪造支付商户或渠道交易记录。

微信与支付宝等渠道充值进入同一issuer顾客账户，不能因payMerchantNo不同拆成多个钱包。每笔充值及批次冻结实际收款主体、支付商户、渠道、支付流水引用和发行配置版本；收款确认按原流水恢复，不随当前路由变更。

支付渠道给商户结算与品牌发行方向服务主体清分是两种业务：前者跟payMerchantNo及渠道规则，后者跟issuerNo、原清分规则及主体账单，不能复用同一个“已结算”字段。


## P3 batch contract (2026-10-23, coded; acceptance pending)

This section supersedes earlier P3 statements that maintenance is Java-only or has no HTTP entry. Stage delivery is one code/compile batch followed by one focused test chain. P3 remains IN_PROGRESS until evidence is recorded.

### Ownership and API boundary

- Core owns live store/entity/management authorization, canonical member identity resolution and readiness proof collection. Pay owns issuer versions, immutable releases, wallet dimensions, quota, journal and maintenance receipts.
- GET/POST `/biz/management/stores/{storeNo}/stored-value/issuer` manages store settings; brand equivalent stays DRAFT with no financial activation. GET `/biz/management/stored-value/issuers/{issuerNo}` resolves the natural resource before authorization.
- POST `.../issuers/{issuerNo}/configuration-checks` previews blockers; POST `.../configuration-versions` writes a revision with Idempotency-Key; GET `.../collection-routes` returns currently authorized routes. Existing liabilities lock settlement/clearing/debit-rule changes.
- POST `/biz/management/stores/{storeNo}/stored-value/issuer/prepare` takes managementNo. The explicit owner action requires management grant administration, store read and actual entity ownership; it grants only issuer read/configure to that actor. Reading the page grants nothing and preparing never opens finance.
- GET `/biz/management/stored-value/settlement-entity-candidates` returns entityNo/name/accepted/canAccept. Selecting an owned but unaccepted entity explicitly grants `sv.issuer.settlement.authorize` through the existing management-grant API. No automatic settlement authorization.
- POST `.../issuer/access-refresh` takes managementNo and returns issuerNo/storeNo/sourceRevision/status. Refresh is monotonic and does not activate access.
- IS05 GET `/biz/stores/{storeNo}/stored-value/issuers`; RC01 GET `.../recharge-options`; RC02 POST `.../issuer-recharges/confirm-offline`; RC03 GET `.../issuer-recharges/{rechargeNo}`; RC04 POST `.../issuer-recharges/resolve`; RC05 POST `.../issuer-recharges/cancel-create`; RC06 POST `.../issuer-recharges/{rechargeNo}/cancel`; RD01/RD02 `.../issuer-records` list/detail. All are store-scoped, reuse original permission/idempotency rules, and reject brand financial scope in P3. RC06 uses existing recharge-create permission, unpaid cancellation only, no online refund.
- Owner maintenance: POST `/biz/management/stored-value/migration-manifests` body storeNos; POST `.../{manifestNo}/pause` body storeNos; POST `.../{manifestNo}/map` body storeNos/pauseReceiptNo. All require Idempotency-Key. Authenticated actor is derived server-side; owner/source/member facts are fetched live, never supplied in requests. Execution defaults closed.
- Global maintenance: token-protected POST `/internal/stored-value/maintenance/{action}` for key-step/guard-dimensions/resume; body operatorNo/approvalNo. GET `/internal/stored-value/commands/{commandNo}` returns the original receipt. Separate server-file approval binds the complete ledger, inventory, operator, validity and mapping receipt chain. Store ownership is insufficient for global DDL.

### Database and cutover

- V548/V549 remain unchanged. Additive V550 adds sv_release.legacy_activity_version_no, sv_release_tier.legacy_tier_key, source uniqueness and LEGACY_ACTIVITY provenance constraint. It does not alter balances or account identifiers.
- Readiness protocol 2 requires V548/V549/V550 and new source columns. Old protocol 1 nodes cannot pass the sealed inventory check.
- Under a dedicated database advisory lock, commit each journal intent before fixed-whitelist DDL. First establish issuer/customer/currency uniqueness, then remove old conflicting keys; install enforced `ck_sv_issuer_dimensions` for nonblank issuer/version/customer. Physical nullable definitions remain compatible with old schema until controlled cutover; the enforced CHECK establishes required semantics.
- Resume requires complete live approval and metadata proof, exact keys, enforced dimensions and no pending DDL intent. One Pay transaction locks all approved STORE issuers/access rows, sets ISSUER write mode and records the receipt. DRAFT becomes ACTIVE; ISSUE_STOPPED stays stopped, canIssue=false. BRAND DRAFT remains closed. Replay returns the original receipt.
- No copy of funds, no reset of account/lot/voucher/statement sequence and no fresh platform identity. Legacy activity creation in issuer mode freezes source version and tier once under issuer lock, records canonical existing customer, reserves quota and reuses the original synchronous posting/recovery path. Cross-store legacy activities remain blocked until P5.

### B-app behavior and modules

- Top-level brand and store settings use one new issuer page with explicit scope. The in-store entry is read-only for financial settings and does not select another store or brand.
- Entity/payment-route selection uses shared sh-sheet, page uses sh-scaffold/sh-card/sh-kv/sh-actionbar; all visible copy goes through zh-CN/en/ar catalogs. Configuration impact is checked before write. Unknown save results retain the exact configuration and original key in local storage, lock editing and permit original-command retry.
- Pay: StoredValueAccountDimensionGuard, StoredValueMigrationResumeService, StoredValueMigrationOperator, StoredValueIssuerService, StoredValueStoreIssuerService, StoredValueIssuerLedgerBridge.
- Core gateway: StoredValueMigrationOperatorService, BizStoredValueMigrationController, BizStoredValueIssuerController, BizStoredValueIssuerOperationsController; Merchant: IssuerAuthorityService. Remote/embedded typed adapters share the same contracts.
- Frontend: api/stored-value-issuers.ts, api/stored-value-issuer-http.ts, pages/stored-value-issuer/index.vue. Existing recharge sheet consumes store issuer options and restores original pending commands before checking new-issuance availability.

### Acceptance and rollout

Compile Core (food-embedded), independent Pay and B-app together after all coding. Then run focused issuer/configuration/identity/operator/HTTP/UI tests and isolated native MySQL8.4 constraint/resume/migration tests. No full repository test suite and no duplicate unrelated regressions. Stage signoff requires actual evidence, then one whole-stage review and controlled deployment. New issuer/execution/key-cutover switches remain false during ordinary publication; no online DDL or financial transaction is implied by source deployment.
