# 美业储值系统详细设计

状态：草稿，待评审 · 档位：2 · 创建：2026-10-16

本设计按最新原型收敛首期契约：商家收款并依活动发放储值；C 端先看有储值记录的门店，再看核销记录；会员识别复用统一会员码；本金和赠金仅作内部账务拆分；不提供线上退款。本文为本期系统设计真源，早期《美业储值流转与通知设计》保留为讨论历史，冲突以本文为准。

关联：[当前原型](../../prototypes/beauty-stored-value.html) · [需求](../requirements/PRD-美业储值.md) · [数据库 DDL 草案](./design/美业储值-schema.sql) · [架构决策提案](./ADR/ADR-037-储值作为pay内独立模块.md)。所有表、接口、类名均为拟定契约；本次不执行建表或修改业务代码。

## §0 需求到设计与页面

| AC | 本期验收要求 | 设计位置 / 页面 |
|---|---|---|
| S01 | 活动先发布，充值按活动发放 | §2.2、campaign/recharge；商家活动页 |
| S02 | 商家实收核验后幂等入账 | §2.3；线下收款确认 |
| S03 | 本金赠金内部拆分，页面只展示总额 | §2.4、§2.7 DTO 白名单 |
| S04 | 默认仅列本人有成功储值记录的门店 | §2.5；s14 |
| S05 | 门店按最近一次储值或核销时间倒序 | §2.5；s14 |
| S06 | 核销记录与简明详情可查询 | §2.7 C API；s28、s13、s23 |
| S07 | 我的与门店主页双入口 | §2.5；s25、s26 |
| S08 | 统一会员码，不建储值专用码 | §2.6；s27 为接入示意 |
| S09 | 核销并发不超扣，有交易事实与授权 | §2.4、§2.6 |
| S10 | 不支持线上退款，线上购买低优先级 | §1 边界、§2.7 无退款端点 |
| S11 | 到账/核销通知在提交后触发，异常可恢复 | §2.8 |
| S12 | 账本、账户、批次及收款可对账 | §2.9 |

## §1 范围与现状

### 1.1 首期范围

商家端：固定金额充赠活动、活动版本与范围、客户识别、收款凭据确认、账户总余额查询、服务消费开单、邀请本人确认、核销结果和记录查询。运营端：按权限查账、异常待办与通知日志、受控恢复任务。C 端：储值门店目录、单门店核销记录和详情；会员码沿用会员体系入口。

首期不提供在线退余额、消费退款或冲正 HTTP 接口，不建立可由员工任意调余额的接口。发生已核销争议，登记客服/财务待办，保留原交易；线下退费/纠错如何记账另立方案，不能删除流水或直接 UPDATE 余额。支付未完成时释放预占属于取消支付，不是退款。

线上购买储值及储值加现金支付为后续扩展，本期不开放；服务单须全额由合格储值支付，不足则拒绝核销，提示门店另行收款处理。不得自动扣尽余额后将剩余部分假记现金。通道收退款、跨主体通兑、次卡/期限卡和历史批量导入不在本期范围。

### 1.2 可复用与缺口

现有 pay-domain 依赖 shop-base/shop-store-mybatis/pay-channel，已有 pay 数据源、payTxManager、支付流水、对账基础。HTTP Controller 位于 shop-app，身份分别从 SecurityUtils/BizContext 获取；复用 PageData(records,total,page,size)。新储值业务位于 pay 内独立包，不新增服务或 Maven 模块。

旧会员资产设计并未对应完整的账户扣款实现；不得把文档里的 mbr_asset_* 当作现有表。统一会员码在本次本地源码检索中未定位到可直接复用的端到端实现：作为会员域接入依赖验收，不承诺已有可用码服务，也不在储值域临时生成另一套码。

## §2 架构与关键流程

### 2.1 模块边界

| 模块 | 负责 | 不负责 |
|---|---|---|
| 会员域 | 用户与商家会员关系、统一会员码签发/校验、受控客户识别 | 储值余额写入 |
| 订单域 | 服务项目、价格、优惠、服务人员、订单状态、授权确认事实 | 计算批次扣减 |
| pay.storedvalue | 活动经济规则、充值、账户/批次、预占核销、凭证、读模型、对账 | 查询订单库、维护商品及会员档案 |
| shop-app/paybridge | 从订单/会员/商家组装可信快照，调 SPI，可靠同步订单 | 直接修改 sv 表 |
| 消息域 | 场景、收件人、站内信、外发任务和状态 | 决定入账是否成功 |
| 三端 | 总额、项目、门店、时间、状态及交互 | 本金赠金比例计算 |

资金写入只经 PostingService，所有资金表使用 paySqlSessionFactory；Mapper 放 pay.mapper.storedvalue，显式事务 payTxManager。不同域经号引用，不建立跨数据源 SQL JOIN。对外读 DTO 不序列化内部实体。

### 2.2 活动及规则版本

活动状态 DRAFT → PUBLISHED → PAUSED / ENDED。草稿可改；发布后经济规则变更必须产生新版本，再原子切换 currentVersionNo。旧充值快照不可改变。版本含固定档位、适用门店/项目、人数、名额和赠金预算；同一活动可多个档位，金额由服务端据 tierNo 读取，客户端不能提交赠金。

创建充值单先锁活动预算行，检查活动、时间、每人次数，原子占用名额/预算并固化版本。对同一用户的活动限购使用 sv_participation 行锁，不能先 COUNT 再插入。草稿过期/取消先解除尚未收款的占用；人工确认收款与取消锁同一充值单行，终态互斥。已确认收款的占用只能进入发放恢复，不能过期释放。

### 2.3 商家收款与发放

WAIT_COLLECTION → SUBMITTED → RECEIVED → CREDITED；尚未确认收款可 REJECTED/CANCELLED/EXPIRED。经办人登记实收总额、收款方式及私有凭据引用，有 receive 权限人员核验金额、消费者与主体后确认。提交时取凭据内容摘要，禁止修改提交后的实收金额；更正先驳回重建。

这是线下收款确认事实，不调用通道接口或伪造 transactionId。转账/现金凭据须按门店权限读取，浏览器不得获得长期公共文件 URL。消费者对象需经会员域的本人关系/身份核验，不凭任意手机号给他人绑定资金。

收款确认事务同时写 RECEIVED 与 CreditRequested 出站事件。发放事务锁充值单→账户→批次，检查 RECEIVED、唯一来源键，插入批次、本金与赠金分录，增加账户双池，完成预算和限购占用，置 CREDITED，更新门店读模型，写 Credited 事件。同一事务失败整体回滚；重复事件返回原凭证。已确认收款但未入账由恢复任务重试同一命令，无重新收款行为。

### 2.4 账户、预占与过账

账户唯一：(tenantNo,userNo,entityNo,currency)。充值批次保留原收款门店、活动版本、剩余本金 P、赠金 B、冻结额及适用范围；账户汇总与批次同事务维护。无到期/转赠规则。

服务端订单先定价，筛选符合门店/项目条件的批次，按充值时间、lotNo 排序。每批支付 A 的本金=floor(A×可用P/(可用P+可用B))，赠金=A−本金；多项目按订单侧项目金额分配，再按固定尾差顺序固化；最后耗尽时两池都归零。金额为 BIGINT 最小货币单位，比例乘法使用 BigInteger 防溢出。批次所有正向/冻结增减均条件非负；失败回滚整笔，不接受部分核销。

prepare：锁账户，检查 ACTIVE 和合格可用总额，插入 hold/allocation，增加冻结额；balance 不变。commit：锁 hold、账户与稳定排序的批次，校验授权/订单版本，减少冻结及剩余额，写凭证/分录、消费成功状态、读模型及事件。成功时将 settled_order_no 置为 orderNo，唯一索引保证同一订单即使改价重建过多个版本，也只能成功核销一次。release：仅 HELD 可转 RELEASED 并减冻结；重复返回原终态，COMMITTED 不可释放。

同一账户统一锁顺序，事务先由消费/充值业务头定幂等，再锁账户，最后稳定排序批次；活动预算锁仅在充值事务使用，消费不倒查活动行，避免跨链路锁环。写失败重试保持同一 requestId 与 payloadHash，同键异参返回冲突。

不变量：账户本金/赠金=批次之和=入账分录之和；冻结=有效 hold allocation 之和；0≤冻结≤余额；每个消费只能有一份成功核销凭证。当前余额允许物化，任何修改都和不可变分录同事务提交。

### 2.5 门店目录与排序

sv_user_store_index 是只读投影，不保存余额。成功充值在实际收款门店置 hasRecharge=1，lastRechargeAt 用 GREATEST 更新；成功核销在实际消费门店更新 lastConsumeAt。读目录必须 hasRecharge=1；只有消费无充值的门店仍可由门店主页查本人核销，但不出现在默认储值目录。余额为零仍列出。

lastActivityAt=max(lastRechargeAt,lastConsumeAt)，同时间按 storeNo ASC 排序；按业务 occurredAt，不按事件接收时间。不同币种同一门店仍只一行目录，列表不合计金额；记录逐笔返回币种。门店更名可取商家读模型当前名称，核销详情使用发生时门店名称快照。门店停业/不可用仍允许查历史，列表标识历史门店状态，不隐藏消费事实。

门店名等展示快照由编排层传入；pay 不直接 JOIN 商家表。目录在入账/核销事务内维护，避免排序延迟；外部事件只负责通知和订单同步。重建根据成功充值和消费事实按 MAX 聚合，可重复运行。

### 2.6 会员码及核销授权

统一会员码必须短时有效、可验证、防重放。商家扫码由会员域 MemberCodeResolvePort 校验，返回短期 customerRef 与用户绑定，不返回储值凭证。customerRef 绑定 tenant/entity/store/operator/有效期，只能用于对应操作，不得当作永久 userNo 参数。

扫码只是识别。商家创建服务单，调用既有订单确认能力获得消费者本人对 orderNo、revision、amount、currency、entity/store 的授权。优先复用通用支付确认页/面板；目前简化原型未画该通用交互，实施前补齐统一能力，不能退化为员工扫码直接扣款。授权短期且一次性，核销消费时在源域和目标业务保持唯一关联。没有授权则拒绝 prepare/commit。

订单源事务保存待核销意图与 Outbox；pay commit 事务保存核销和出站事件；订单 Inbox 幂等推进 PAID，后续服务完成独立变化。订单更新失败不重复扣款；消费者核销记录可显示支付已核销、订单确认中。关单与授权核销使用订单价格版本及源支付意图状态串行守卫；超时只释放权威已取消的预占，不由本地时钟单方面释放。取消与commit冲突时由已持久化支付意图定终态，异常进入待办。

## §3 数据库设计

完整可阅读建表草案见 [schema.sql](./design/美业储值-schema.sql)，位于文档目录，不是 Flyway 迁移；未申请迁移版本号。所有表均含 id、tenant_no、created_at、updated_at；资金凭证不可软删除。时间存 UTC DATETIME(3)，API 转 ISO8601，门店展示按市场时区。

| 表 | 关键字段 / 唯一性 | 用途 |
|---|---|---|
| sv_campaign | campaign_no；entity_no、current_version_no、status | 活动头 |
| sv_campaign_version | version_no；campaign_no、revision；rules_json/hash | 不可变规则、档位与范围快照 |
| sv_campaign_budget | version_no；quota/bonus_limit、held/used | 名额/赠金占用与使用 |
| sv_participation | version_no+user_no；held/used_count | 并发限购 |
| sv_recharge | recharge_no；request_id；user/entity/store、金额快照、凭据、状态 | 充值与收款事实 |
| sv_account | user+entity+currency；本金/赠金、冻结、sequence | 资金汇总与有序账后余额 |
| sv_lot | recharge_no 唯一；account、版本/范围快照、双池及冻结 | 每次充值批次 |
| sv_consumption | consume_no；order_no+revision，成功settled_order_no唯一；授权、消费总额、项目/门店快照 | 对外核销事实 |
| sv_hold | hold_no；consume_no 唯一；状态、expiry、快照摘要 | 核销预占 |
| sv_hold_allocation | hold_no+lot_no；本金/赠金、项目分摊快照 | 固化拆池及来源 |
| sv_voucher | voucher_no；biz_type+biz_no；account_seq 唯一 | 不可变入账凭证、账后双池 |
| sv_entry | voucher_no+lot_no；双池delta | 余额变动唯一审计事实 |
| sv_user_store_index | user_no+store_no；has_recharge、last_activity_at | 门店目录排序 |
| sv_outbox / sv_inbox | event_no；consumer+event_no | 可靠事件与防重 |
| sv_exception | kind+biz_no；状态、处理人、证据及动作 | 收款待发放、挂单、对账差异 |

外键使用同域业务号关联和服务守卫，符合现有部署拆分；不建立跨域 FK。唯一索引均含 tenant_no。JSON 仅保存不可变快照/项目分摊，金额、状态、排序及查询条件必须独立列。普通更新带 version 乐观锁；资金写通过锁与条件更新，不把乐观锁冲突当作成功。

目录索引：(tenant,user,hasRecharge,lastActivity DESC,store ASC)。核销索引：(tenant,user,store,status,occurredAt DESC,consumeNo DESC)。B 端按 entity/store 查询必须有对应前缀索引。凭据、内部金额拆分和授权引用不进入 C DTO 或普通通知日志。

## §4 API 契约

### 4.1 通用规则

沿项目现有 ApiResponse 包装、PageData 分页；C 门店目录/记录使用 cursor 分页，返回 {records,nextCursor,hasMore}，避免追加时 offset 重复。cursor 含查询筛选摘要、分页首请求 snapshotAt 和末项时间/号，签名防篡改，30 分钟有效；目录查询 last_activity_at≤snapshotAt，期间更新的门店允许暂时不在后续页，客户端刷新重取。它不是历史时点快照，不承诺活跃更新时无遗漏；如未来要求完整快照另建查询快照表。普通 B/O 查询 page≥1，size≤100；C size 默认20、最大50。

所有写操作携带 Idempotency-Key，服务端作用域为 tenant+操作+key，匹配 payloadHash；重复返回原资源，不重复扣款。同键异参409。C userNo 强制从登录态取；B entity从BizContext取，store校验岗位；O需要明确权限、审计及主体范围。GET请求同样检查本人/主体归属，不能凭业务号越权。

### 4.2 C 端（仅查询）

| Method / 路径 | 请求 | 响应 |
|---|---|---|
| GET /mp/stored-value/stores | cursor?,size? | 储值门店目录；默认有成功储值，不需传store |
| GET /mp/stored-value/consumptions | storeNo 必填,cursor?,size? | 本人该门店核销记录；门店主页直接进入可无充值历史 |
| GET /mp/stored-value/consumptions/{consumeNo} | 无 | 本人核销详情 |

StoreItem={storeNo,entityNo,storeName,storeStatus,lastActivityType:RECHARGE/CONSUME,lastActivityAt}；相同时间类型优先CONSUME，不影响排序。列表不返回双池、总余额、充值批次或用户号。

ConsumptionItem={consumeNo,title,amountMinor,currency,storeNo,storeName,occurredAt,status:CONSUMED,orderSyncStatus}；详情加items[{title,quantity}],serviceStaffName,orderNo。不返回本金、赠金、活动比例、授权token或操作人员私有资料。服务人员缺失可为null，不能伪造。空数组与查询失败严格分开。

不创建 /mp/.../refund、/recharge 或 /code 接口；会员码签发继续属于统一会员能力，具体URL不在储值域另定。

### 4.3 商家端

| Method / 路径 | 入参 / 结果 | 拟权限 |
|---|---|---|
| GET /biz/stored-value/campaigns | status,page,size → 活动摘要 | biz:sv:campaign:read |
| POST /biz/stored-value/campaigns | name,tier配置,范围 → 草稿 | biz:sv:campaign:write |
| PUT /biz/stored-value/campaigns/{no}/draft | expectedRevision,规则 → 新草稿/冲突 | biz:sv:campaign:write |
| POST /biz/stored-value/campaigns/{no}/publish | expectedRevision → 发布版本 | biz:sv:campaign:publish |
| POST /biz/stored-value/campaigns/{no}/pause | reason → 状态 | biz:sv:campaign:publish |
| POST /biz/stored-value/customers/resolve | memberCode → customerRef,maskedName | biz:sv:customer:resolve |
| GET /biz/stored-value/accounts | customerRef,currency → 可用总额、冻结总额 | biz:sv:account:read |
| POST /biz/stored-value/recharges | customerRef,storeNo,versionNo,tierNo → 待收款单 | biz:sv:recharge:create |
| POST /biz/stored-value/recharges/{no}/submit | actualAmountMinor,collectionMethod,receiptRef → 待核验 | biz:sv:recharge:create |
| POST /biz/stored-value/recharges/{no}/receive | expectedRevision,receiptDigest → 已确认实收/待入账 | biz:sv:recharge:receive |
| POST /biz/stored-value/recharges/{no}/reject | reason → 驳回释放占用 | biz:sv:recharge:receive |
| POST /biz/stored-value/recharges/{no}/cancel | reason → 未收款取消 | biz:sv:recharge:create |
| GET /biz/stored-value/recharges/{no} | 无 → 总额、状态、收款/入账时间 | biz:sv:recharge:read |
| POST /biz/stored-value/consumptions | customerRef,orderNo,orderRevision → 待本人确认消费单 | biz:sv:consume:create |
| GET /biz/stored-value/consumptions | storeNo,dateFrom,dateTo,page,size → 本店核销记录 | biz:sv:consume:read |
| GET /biz/stored-value/consumptions/{no} | 无 → 核销结果详情 | biz:sv:consume:read |

商家不传可信价格，编排层从源订单读取价格/项目/服务人员快照并校验客户、主体、门店。消费创建后订单通用确认链路取得本人授权，经内部SPI完成prepare/commit，不暴露可绕过授权的B端commit按钮。

活动配置以“充值金额/赠送金额”描述充赠规则；账户与消费页面只展示总额，不展示内部本金池/赠金池或拆分。不存在通用调整余额、退款、删除核销或重放扣款端点。发布权限与收款确认权限独立分配，不能沿用一个泛化客户查询权限开放资金操作。

### 4.4 运营端

| Method / 路径 | 功能 | 拟权限 |
|---|---|---|
| GET /ops/stored-value/exceptions | entityNo?,kind,status,page,size | ops:sv:exception:read |
| GET /ops/stored-value/exceptions/{no} | 事实、差异、已执行动作 | ops:sv:exception:read |
| POST /ops/stored-value/exceptions/{no}/retry-credit | 仅RECEIVED未CREDITED恢复；原业务键 | ops:sv:credit:recover |
| POST /ops/stored-value/exceptions/{no}/query-source | 重新核对订单/收款事实，不改余额 | ops:sv:exception:verify |
| GET /ops/stored-value/reconciliations | 日切结果/差异，无账户随意调整 | ops:sv:recon:read |

通知查询和重试复用通知中心能力，接口接线在实施时按已有工具扩展；不发明第二套通知日志。运营页面同样只展示总额，内部双池证据在受控审计存储留存，不做默认UI字段。

错误语义：NOT_FOUND对越权资源不泄露存在性；SV_ACTIVITY_UNAVAILABLE、SV_QUOTA_EXCEEDED、SV_ACCOUNT_FROZEN、SV_BALANCE_INSUFFICIENT、SV_SCOPE_MISMATCH、SV_AUTH_REQUIRED、SV_ORDER_CHANGED、SV_ALREADY_COMMITTED、SV_IDEMPOTENCY_CONFLICT。实施时在现有ErrorCode及三语消息中正式登记，不将本文字符串当已存在码。

## §5 代码模块与目录

```text
backend/shop-base/src/main/java/ai/neargo/shop/spi/
  storedvalue/StoredValuePort.java             # prepare/commit/release/creditFact/queryFact
  member/MemberCodeResolvePort.java             # 统一会员能力，若已有等价接口则扩展复用
backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/
  storedvalue/
    model/                                     # Money、Account、Lot、Hold、Voucher、状态
    policy/                                    # LotAllocator、PrincipalBonusSplitter、ScopePolicy
    app/                                       # CampaignService、RechargeService、RedemptionService接口
    app/impl/                                  # 各编排实现；短事务，不调外部网络
    posting/StoredValuePostingService.java      # 账务唯一入口，锁、幂等、余额/凭证/事件
    store/                                     # Repository接口
    store/impl/                                # MyBatis适配，唯一接触Mapper的包
    query/                                     # CustomerStoreQuery、ConsumptionQuery
    port/StoredValuePortImpl.java               # 跨域SPI实现
    event/                                     # Outbox发布、Inbox去重、具名事件
    recon/                                     # 恒等式与源事实差异
    job/                                       # CreditRecovery、HoldRecovery、Reconciliation
    dto/                                       # 业务命令/结果，C端白名单另映射
  mapper/storedvalue/                           # 扫描归现有pay工厂
backend/shop-app/src/main/java/ai/neargo/shop/
  portal/mp/pay/MpStoredValueController.java
  portal/biz/pay/BizStoredValueController.java
  portal/ops/pay/OpsStoredValueController.java
  payclient/StoredValueAppService.java          # 三端门面接口
  payclient/impl/StoredValueAppServiceImpl.java
  paybridge/StoredValueOrderCoordinator.java    # 订单快照/授权/可靠事件协调
  paybridge/StoredValueFactReconciler.java       # 跨域对账，pay不查订单表
backend/shop-core/.../message/                  # 新通知场景及事件到消息适配
packages/shared/src/types/stored-value.ts       # 三端共用总额/记录类型，无内部双池
c-app/src/api/{contract,endpoints,http}.ts       # 按现有生成契约规范接入
c-app/src/pages/stored-value-{stores,ledger,detail}/index.vue
b-app/src/pages/stored-value*/                  # 活动、客户、充值、服务单
ops-web/app/finance/                            # 储值异常入口，复用通知中心
```

纯model/policy不依赖Spring/MyBatis，公开接口与实现分离；Controller不读Mapper。Mapper仅由store.impl使用，posting独占资金表写入。query可读取只读投影；活动元数据更新可独立，但禁止绕过posting修改余额。ArchUnit覆盖以上依赖方向，新增包要验证Local/Remote pay宿主装配。

SPI命令建议：Prepare(consumeNo,orderNo,revision,userNo,entityNo,storeNo,amount,currency,itemsSnapshot,authorizationRef,requestId)→HoldResult；Commit(consumeNo,sourceDecisionNo,requestId)→Receipt；Release(consumeNo,cancelDecisionNo,requestId)→Result。参数只能由可信编排层生成；sourceDecisionNo必须引用持久源事实，不能接受任意HTTP字符串代替授权。余额与主体事实由pay校验，商品/订单真实性由上层校验。

## §6 通知与恢复

| 场景 | 触发 | 受众 / 类型 | 内容 |
|---|---|---|---|
| SV_RECHARGE_CREDITED | 发放事务提交 | 本人C、经办B；交易结果 | 门店、到账总额、时间 |
| SV_CONSUMED | 扣款凭证提交 | 本人C；交易结果 | 项目、总金额、门店、时间 → 核销详情 |
| SV_CONSUME_CONFIRM | 本人待确认意图建立 | C；待办 | 金额/门店 → 通用确认入口 |
| SV_CREDIT_DELAYED | RECEIVED超过60秒未入账 | B/O；异常待办 | 业务号、总额、待入账 |
| SV_RECON_DIFF | 对账创建差异 | O及有权财务；待办 | 差异种类、单号、处理入口 |

INAPP作为持久结果记录，WXSUB/PUSH只在模板/授权/设备可用时尽力触达，短信默认关闭。新活动不自动营销群发。没有本金赠金拆分通知，没有线上退款消息。普通充值/支付通用结果消息与储值结果按业务关联去重，服务履约完成消息另行保留。

账务与sv_outbox同pay事务；消息消费事务落站内信/发送任务并按eventNo+recipient+scene去重。外发只保证尽力，有未知回执不盲目重发。通知失败不回滚资金。事件含schemaVersion、eventNo、occurredAt、tenant及业务号，传总额与可鉴权详情路径，不传会员码token和凭据。

恢复任务：CreditRecovery只恢复已实收未发放；HoldRecovery核对源订单持久事实后commit/release；OutboxRelay有租约、退避和死信告警；各任务使用已有job接入，不让job-worker依赖业务包。任务幂等，不提供万能“重放业务事件”按钮。

## §7 对账、安全与实施验收

每日按市场时区核对账户/批次/分录、冻结/hold、实收/发放、订单/消费凭证以及读模型MAX时间。pay内恒等式在pay检查，跨域比较由paybridge获取双方快照。充值实收、核销消费、未消费额分别统计；核销不二次现金结算。

关键验收：重复收款确认仅发一次；最后余额并发只成一笔；失败/处理中不更新目录；余额耗尽仍保留目录；乱序事实不倒退时间；仅消费无储值门店不入目录但可直达查询；跨用户/主体/门店读取拒绝；C DTO及通知不含内部双池；旧码/过期码/无授权不能扣款；关单与核销竞争不丢账；通知失败不重复消费；无线上退款端点。

数据库草案在目标数据库做DDL、JSON/CHECK与索引执行计划验证后才转Flyway；本次仅文档校验，不声称数据库/Java测试已通过。后续实施须配MySQL真实事务并发测试和源订单掉进程恢复测试；对非负条件、唯一键、身份校验执行消融验证。

## §8 依赖与评审项

已明确范围：C端记录优先，目录先门店，按最近储值/核销倒序，统一会员码，页面隐藏双池，不在线退款。待评审细节：商家是否允许经办人兼收款确认人、统一会员码服务实际落点、通用本人授权交互、现金凭据保留周期及异常SLA。首期默认发布/收款确认有独立权限，缺少统一身份授权能力时不开放扫码扣款。

书面方案评审通过后再细化逐项实施计划及迁移号、编写代码。无需为本期新增独立支付服务，也不实施线上充值/混合支付/退款等扩展分支。


### 契约示例与结构校验记录

C 端目录单项示例（由服务端按最近活动倒序返回）：

```json
{"storeNo":"STORE001","entityNo":"ENTITY001","storeName":"澄颜美肤 · 人民路店","storeStatus":"ACTIVE","lastActivityType":"CONSUME","lastActivityAt":"2026-10-16T06:30:00Z"}
```

核销详情示例（ApiResponse 内的业务数据）：

```json
{"consumeNo":"SV20261016002","orderNo":"ORDER001","title":"面部护理","items":[{"title":"面部护理","quantity":1}],"amountMinor":30000,"currency":"CNY","storeNo":"STORE001","storeName":"澄颜美肤 · 人民路店","serviceStaffName":"小林","occurredAt":"2026-10-16T06:30:00Z","status":"CONSUMED","orderSyncStatus":"CONFIRMED"}
```

本次仅检查文档链接、16张表的定义与唯一性声明、三端API范围及文本格式；未运行建表、性能测试或业务测试。实际DDL上线需与项目审计基类、数据权限注册、Flyway及目标MySQL版本一起验证。
