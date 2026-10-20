# 门店储值：实现与交付记录

日期：2026-10-20。分支：`codex/store-stored-value`，基线：`f745656ce`。本轮完成编码、定向验证、合并推送、Core/Pay/B端部署及业务库迁移；用户指定的不入账联调通过。实收到账及消费验收按用户本轮选择搁置。

## 1. 功能与模块

| 功能 | 所有者 | 代码入口 | 状态 |
|---|---|---|---|
| 独立方案创建/分页/详情/启停 | Pay | StoredValuePlanServiceImpl | 编码完成，定向验证 |
| 按方案创建充值、冻结规则 | Pay | StoredValuePlanRechargeServiceImpl | 编码完成，定向验证 |
| 识别会员、校验门店及岗位 | Core / BFF | CustomerIdentityPort、EntitlementScopeResolver、BizStoredValuePlanRechargeController | 复用原会员引用；重放优先查原单 |
| 提交实收、核验收款、取消 | Pay | StoredValueRechargeService及既有Writer | 复用原状态机及账务内核 |
| 异步到账与恢复 | Pay / app桥接 | StoredValueRechargeEventConsumer、StoredValuePostingServiceImpl | 按持久化PLAN/ACTIVITY来源分派 |
| 门店账户、余额、批次及核销选账 | Pay | SvAccountMapper、StoredValueAccountScope、Posting/Redemption服务 | tenant/entity/store/user/currency隔离 |
| 顾客目录和账户详情 | Pay + Core资料 / BFF聚合 | StoredValueMerchantQueryServiceImpl、StoredValueCustomerProfilePortImpl | 本店成功储值资格，包含零余额 |
| 统一收支和余额快照 | Pay | StoredValueStatementProjector、SvStatementMapper | 与充值/核销过账同事务 |
| B端六个业务页面 | b-app | pages/stored-value* | 真实API接入；三语和统一组件 |

本期不新增营销活动，不要求先创建活动。方案固定实收/赠送金额，允许赠送0；同店可建多个方案。同顾客同店各方案充值进入同一账户。经济规则不可编辑，变更需新建方案。连锁共用、跨店清分、线上购买/退款及新核销页面不在本轮。

## 2. 数据库

执行位置为Pay数据源，沿用项目Flyway；不修改已应用迁移。

| 迁移 | 变化 | 关键约束 |
|---|---|---|
| V532 | sv_account增加store_no，重建owner唯一键及门店查询索引 | tenant/user/entity/store/currency唯一；旧NULL门店账户保留原余额并阻断所有人新选账 |
| V533 | sv_recharge_plan、sv_recharge_plan_version | 本店名称唯一；版本金额及使用范围冻结；状态和revision分离 |
| V534 | sv_recharge增加来源和方案版本；sv_lot增加充值店/方案版本 | ACTIVITY保留原参与凭证；PLAN无伪造活动号；来源CHECK |
| V535 | 新建sv_statement | tenant/voucher唯一；门店/类型/账户+时间/编号索引；余额非负，充值为正、核销为负 |

账务真源仍是sv_account、sv_lot、sv_voucher、sv_entry和已有预占/分配表。统一收支表只是随成功过账生成的投影，不通过两种客户端分页拼接。金额使用Minor整数，运算校验溢出；历史余额取凭证after字段，不取当前账户余额。

当前实例只读盘点有效账户0，本金/赠金/冻结均0，因此本实例没有需要拆分的存量资金或回填的历史收支。该证据有时间边界，部署前必须再次盘点。其他有旧账户/凭证的环境不得直接套用“无需回填”结论；本轮未提供通用历史资金拆分/流水回填执行器，需先审计、对账并补迁移。旧NULL门店账户会被服务显式拒绝，不猜测归属或复制余额。

MySQL演练使用独立临时库，只导入sv表结构并执行V532–V535；创建11张sv表成功。同顾客两店可建2账户，同店重复账户被唯一键阻止；临时库已删除，残留0。联调业务库ai_shop没有执行这些DDL/DML。

## 3. API与字段

完整路径及权限表见[API契约增量P60–P75](./TDD-活动卡券储值-API契约.md)。统一ApiResponse包装；CursorPage沿用records/nextCursor。商家、租户、操作人来自会话，storeNo需归属与岗位复核。写操作传Idempotency-Key，最长64字符；同键异参拒绝。

| DTO | 业务字段 |
|---|---|
| 方案Create | storeNo、name、currency、paidAmountMinor>0、bonusAmountMinor>=0 |
| 方案View | planNo、versionNo、storeNo、name、currency、paidAmountMinor、bonusAmountMinor、totalAmountMinor、status、revision |
| 方案Status | storeNo、status=ACTIVE/INACTIVE、expectedRevision |
| 充值CreateRequest | customerRef、storeNo、planNo、versionNo |
| SubmitRequest | expectedRevision、actualAmountMinor、collectionMethod=CASH/TRANSFER、receiptNo |
| ReceiveRequest | expectedRevision、receiptNo、confirmationNote |
| CancelRequest | expectedRevision、reason |
| ResolveView | found、recharge（原单进度，找不到时null） |
| AccountView | accountRef、maskedName、maskedPhone、currency、balanceMinor、heldMinor、availableMinor、lastOccurredAt |
| RecordView | recordNo、maskedName、maskedPhone、type、title、storeNo/storeName、currency、signedAmountMinor、balanceAfterMinor、occurredAt、businessNo、planNo/versionNo、paidAmountMinor/bonusAmountMinor |

实收凭据契约优化：商家输入真实线下收款/转账凭据编号receiptNo，BFF按租户/商家/充值号/凭据生成摘要，客户端不计算或展示receiptDigest。此流程记录商家的线下收款确认，不代表平台核验银行或支付渠道流水。请求toString隐藏凭据和会员引用，普通查询不返回它们。

日期过滤：页面提交startDate/endDate（YYYY-MM-DD，含结束日）；BFF按商家MarketBrief.timeZone换算UTC的[from,to)。兼容Instant from/to但禁止与日历字段混用。类型仅ALL、RECHARGE、REDEMPTION。筛选、租户、操作人和门店绑定签名游标；变更筛选重新分页。

顾客搜索在BFF分批遍历Pay已授权的本店账户，再将每批最多100个userNo交Core匹配姓名/手机尾号。每请求最多10批；未扫描完返回nextCursor，可继续加载，不能把无匹配的当前批当作完整结果。Core不再在全租户会员中截断200人，也不跨库JOIN；姓名返回首字符加**、手机脱敏。

accountRef只读、签名且绑定当前主体/操作人/门店，不能代替customerRef或核销授权。只持充值权限时仅可查询启用方案；创建、查询、提交、确认及取消都复核原单门店。

## 4. 充值状态和恢复

1. 商家在统一门店上下文进入营销→储值→办理储值，识别统一会员码，选择本店方案并输入线下凭据。
2. BFF校验会员引用和门店，Pay锁定可用方案版本，创建WAIT_COLLECTION原单并冻结金额/范围；无活动参与。
3. submit校验应收与实收一致，进入SUBMITTED；具有收款核验权限的岗位receive后进入RECEIVED。
4. 既有事件及恢复路径按PLAN来源校验冻结事实，Pay事务写账户/批次/凭证/分录/收支及门店索引；最终CREDITED才展示到账。
5. WAIT_COLLECTION/SUBMITTED可取消；RECEIVED不提供线上退款。超时不新建充值。
6. 页面保存原始创建载荷和各步骤幂等键，刷新可继续原单。resolve用原创建键与请求指纹查原结果，不要求过期会员引用再次识别。
7. 未知创建可以cancel-create建立同键持久取消栅栏；晚到创建也不能生成资金单。已有原单则沿既有取消规则处理，已确认收款不可取消。
8. 切店/卸载后每个写入阶段检查原上下文，停止后续自动submit/receive；原门店命令保留供恢复。已发到服务端的单步请求不能撤回，须以原单状态为准。

## 5. B端与接口对应

| 页面 | API及行为 |
|---|---|
| stored-value/index | 方案/顾客账户/收支记录三栏；营销独立入口 |
| stored-value-plan-edit | context、POST plans；Minor精度；二次确认；原键重试 |
| stored-value-plan-detail | GET plan、PUT status；状态修订冲突刷新 |
| stored-value-recharge | member-code/resolve、plan-recharges及子步骤、resolve/cancel-create |
| stored-value-account | GET account；最近收支及全部记录 |
| stored-value-record | GET record；发生时金额/余额/门店快照 |

列表复用sh-card并使用统一间距；收支只有全部/储值/核销Tab及时间Sheet，无本页门店选择器。沿用sh-scaffold、sh-tabs、sh-kv、sh-actionbar、sh-empty和既有扫码端口，zh-CN/en/ar文案。mock拒绝办理，不伪造充值成功。

## 6. 验证与剩余交付

本轮只执行新增和受影响的定向用例，不运行全量回归。测试清单与结果回写[门店储值任务表](./任务表-门店储值管理.md)。代码审查发现的顾客搜索范围、姓名脱敏、切店继续写入问题已修复；架构检查的Controller序列化依赖移入桥接组件。

发布仍需：复核部署时存量→在受控窗口执行Flyway并部署后端→验证真实权限及读接口→发布B端→授权测试顾客一条实收到账及跨店隔离核验。当前本地H2/Spring集成和MySQL空结构演练不代替上述服务器联调，不宣称线上验收完成。

## 7. 发布更新

2026-10-20，运行版本466c6b83c已发布，V532–V535均success=1。不入账联调及资金未变证据见[发布记录](./design/reviews/门店储值-部署与不入账联调-2026-10-20.md)；前文发布前时点描述保留，不作为当前未部署结论。
