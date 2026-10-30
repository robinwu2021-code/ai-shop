# Task57 设计候选：原支付号、正式线下实收与当前身份

状态：DESIGN_READY，等待 root 技术审查与 SQL pin；仅设计，所有测试/构建/native/SQL 执行均未执行。2026-10-23。
DESIGN_BASE=`5ef6d5f7fa1f9be593e467759ebaa5a8a7caa034`；Task56 accepted=`dbb635a7cf9d36ddddfbd0138e8dfc40216d4e9b`。
唯一工作树 `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`，分支 `codex/beauty-foundation`。本阶段仅本文件和 task-57-report.md 可写；没有实施放行。

## 0. 范围、裁决与真实基线

档位 2，沿已有 beauty backend PRD/TDD/ADR，Task57 跨 trade/pay/media 权威边界和追加 schema；本文是本任务精确设计，不新开平行主 TDD。依据 task-57-brief、bm04-checkout-design §3/7/8/10–11、真实 Task56 六方法。root 维护主 TDD/ADR/进度，实施者不修改这些文件。

交付真实 `CheckoutPort.initiatePayment/findPayment`、`PaymentOperationPort`、`OfflineReceiptPort`，实际 `stl_payment` 收款和长期原键映射。只追加原 owner；不建美业支付账，不进入 58/59/60，不开放 BM05。普通定价、Task56 六方法和 stable source line 保持其已接受合同。运行资金测试仅隔离 TEST/STUB 第三方边界。

root 设计裁决（本轮消息，不代表实现放行）：

* CASH/BANK_TRANSFER 同属已有 OFFLINE 纯记账：有权商户声明已经实收，receipt 不声称银行核验/平台过账。依据 PRD-支付方式与预约预定 §7.1、PRD-支付域 AC-3.6/6.3；保持影子佣金、零佣金、不分账、排除通道对账。
* BANK_TRANSFER 必须绑定真实、同店、实际经办上传的私有附件 owner 记录。新增最小 owner 能力和专用用途，不把旧主体号 `uploaded_by` 或任意 URL 当证据。
* `MerchantOperatorAccessPort.allowed(Query)` 兼容保留；新增 resolve 方法默认 fail-closed，同 SQL 同 namespace 同无调用方事务禁令。
* canonical operation 仲裁一次发送，多个原请求可以映射同一 payment；不在 operation.payment_no/out_trade_no 上加一对一 unique。

### 0.1 只读源码发现（相对本工作树）

| 实际位置 | 当前事实与设计影响 |
|---|---|
| `shop-base/.../spi/trade/CheckoutPort.java` | 真实六方法，PaymentFact 已含 tradeNo；不能用旧草案覆盖。CheckoutScope MAIN、caller BEAUTY、COUNT 约束已交付。 |
| `shop-core/.../trade/policy/CheckoutAccessPolicy.java:54` | 当前 C TokenStore、CONSUMER/USR/MAIN、active user、无外层事务已实现；复用。B requireMerchant 返回 session actor namespace，但尚未返回实际 mch account。 |
| `shop-merchant/.../merchant/mapper/MerchantMappers.java:63` | OperatorAccessMapper.selectAccess 已返回 `account_no,allowed`；typed MCH→mch_account_no、USR→user_no，最多两行、active entity/store/account、角色 JSON 实时检查。 |
| `shop-channel/.../channel/media/api/BizUploadController.java:132` | 目前上传 `uploadedBy=BizContext.current().merchantNo()`，是主体号；旧 GOODS/QUAL/AFTERSALE 不能满足此次经办证明。 |
| `shop-store-mybatis/.../media/SysMediaAsset.java` | assetKey/entityNo/storeNo/bizType/status/bytes/uploadedBy/createdAt；无 tenant 列，属于现有 MAIN-only 基础设施；无银行到款证明。 |
| `pay/pay-domain/.../pay/service/impl/PaymentLedgerServiceImpl.java:40` | open 是 REQUIRED payTx；先 success/open 查询再 count/insert，没有 order guard。paymentRequest 验被冻结请求；不是长期 requestKey 幂等。 |
| 同上 `:449/:473` | close=REQUIRES_NEW/RC，settle=REQUIRED payTx。close 不可在持 pay guard 的事务中调用。settle 保留通道实际金额，订单另核应收；Task57 不抹掉异常实收。 |
| `shop-app/.../paybridge/port/SettlePortImpl.java:173` | 旧 init 直接 open→prepay，任何 false 都 close；必须共用新发送仲裁，否则旧路可破坏 UNKNOWN。 |
| `shop-app/.../paybridge/PaymentReconReconciler.java:251` | notFound→closeUnpaid；对 operation 已开始发送的原号必须 defer，不把暂未找到当终态。 |
| `shop-core/.../trade/service/impl/OrderServiceImpl.java:2691/2731` | markPaid 先开 source TX/锁 order，再 requireConfirmed 调 SettlePort；改为先锁外 receipt，锁内纯 owner 核验。 |
| `shop-core/.../trade/order/service/lifecycle/impl/PaymentLifecycleServiceImpl.java` | OFFLINE 旧信任分支对 managed 拒绝；保留旧无凭证拒绝，新增真实 receipt 分支。 |
| `shop-core/.../trade/service/impl/MerchantOrderServiceImpl.java:324` | managed 旧 confirmOfflinePay 拒绝；保留，不用 boolean 绕过。 |
| `shop-core/.../trade/service/impl/CheckoutServiceImpl.java:751` | source 锁外取资金证据，独立 RR TX 第一条 SQL 锁 operation 后读取 ord 事实；UNKNOWN last pair/null money/空可用行合同保留。 |
| `shop-app/.../config/PayDataSourceConfig.java` | payDataSource 是独立 pool/connection/transaction manager，同 spring.datasource URL/schema；pay mapper 包只绑 paySqlSessionFactory。 |
| `pay/pay-svc/src/main/resources/application.yml` | flyway.enabled=false；repository 的 backend/pay 下 0 个 V*__*.sql。独立进程不等于独立迁移库。 |

平台迁移文件只读统计：459 个，实际最大 V539。V539=`8444 bytes`、SHA256=`5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b`，与冻结值相符。候选新增平台 V540；root 必须再次独立核 actual maximum、文本/hash 后 pin，首次执行之前不得落 SQL 文件或运行。beauty V1–V7/27 表完全不动。未连接任何 DB，以上是源码/配置事实，不冒称已查询运行库 Flyway history。

## 1. 精确公开合同、身份与请求归属

下列 FQN 前缀均为 `ai.neargo.shop.`；B=`backend/shop-base/src/main/java/ai/neargo/shop/`，C=`backend/shop-core/src/main/java/ai/neargo/shop/`，A=`backend/shop-app/src/main/java/ai/neargo/shop/`，P=`backend/pay/pay-domain/src/main/java/ai/neargo/shop/`，G=`backend/pay/pay-channel/src/main/java/ai/neargo/shop/`，M=`backend/shop-merchant/src/main/java/ai/neargo/shop/`，S=`backend/shop-store-mybatis/src/main/java/ai/neargo/shop/`，H=`backend/shop-channel/src/main/java/ai/neargo/shop/`。表中模块前缀+相对路径即完整文件路径，包和 Java FQN 与路径一致。

### 1.1 CheckoutPort 仅追加两方法及 DTO

```java
PaymentAttemptView initiatePayment(InitiatePaymentReq request);
PaymentAttemptView findPayment(CheckoutScope scope, PaymentLookup request);
record InitiatePaymentReq(OperationRef operation, String requestKey, String channel) {}
record PaymentLookup(OperationRef operation, String paymentOperationNo, String requestKey) {}
record PaymentAttemptView(OperationRef operation, String paymentOperationNo, String phase,
        String paymentNo, String outTradeNo, String channel, String currency, long amountMinor,
        Map<String,String> payParams, String receiptNo, String reasonCode, Instant observedAt) {}
```

requestKey canonical 1–128，paymentOperationNo 1–64，channel nullable 或 canonical 1–32；PaymentLookup 两个定位字段恰好一个非空。全部新请求拒未知 JSON 字段；新 long 金额 JSON 不接受小数/字符串强转，Map.copyOf；诊断 toString 隐藏 key/params/evidence。`phase` 是操作观测，不是第二支付状态：PREPARED/OPENED/SENDING/PAYABLE/UNKNOWN/SUCCEEDED/REJECTED/MANUAL_REQUIRED。

`trade.service.CheckoutService` 保持原方法，增加上述两签名和 HOST-only `PaymentAttemptView findPaymentForCurrentBuyer(PaymentLookup request)`；`trade.port.CheckoutPortImpl` 仍是单一生产 adapter。独立 `trade.service.CheckoutPaymentService`/`impl.CheckoutPaymentServiceImpl` 承担这三支付方法，CheckoutServiceImpl 委托，不把支付锁塞入六方法。source 只扩充 receipt/operation 事实。

`trade.service.OrderService` 追加 owner-internal `SettlePort.PaymentOpen paymentInstructionForCheckout(String orderNo, String buyerNo, String requestedChannel)`，由真实 OrderServiceImpl 实现：从原 ord_order/sub/item、确认消费及持久 managed 标志构建，调用当前 `resolvePayChannel/payDescription` 共用规则。不得接受外部 PaymentOpen。普通 pay 的价格/选择算法不重写。另追加专用内部 `SettlePort.PaymentOpen offlineReceiptInstructionForCheckout(String orderNo, String buyerNo)`，读取上述已消费 C 确认及原应收，允许 WAIT_OFFLINE_PAY/CLOSED/CANCELLED 的实收留痕，固定 OFFLINE/CNY/无外部 route。它不使用“允许已关单”boolean，不修改旧 pay 或旧 offline shortcut。

当前 C：每次 initiate/replay/取 payParams 前 `CheckoutAccessPolicy.requireCurrentBuyer()`；真实 token get 验证，主体必须 CONSUMER/USR/MAIN，buyer==原订单==confirmation buyer。撤销/失效 session 不能写或取参数；不得用 DelegatedIdentity/内部服务钥匙/已存 buyer 绕过。已经接受的发送动作遇撤权由其原线性化点授权；后续恢复 query/callback 可以确认既存资金，不能开始未发送操作或获取新参数。

### 1.2 HTTP（HOST api）

| 方法/路径 | 实际实现/参数与授权 |
|---|---|
| POST `/mp/checkout/{operationNo}/payments` | MpCheckoutController→initiatePayment；Idempotency-Key 必填；`PaymentBody(String channel)`；caller=BEAUTY 服务端固定。 |
| GET `/mp/checkout/{operationNo}/payments/{paymentOperationNo}` | MpCheckoutController→findPaymentForCurrentBuyer；当前 C 本人；按业务 operation 号读，不发送。 |
| POST `/mp/checkout/{operationNo}/payments/lookup` | 原响应丢失：Idempotency-Key header 必填，空 body；当前 C 本人，以 CONSUMER/USR/当前 buyer/INITIATE_PAYMENT 命名空间查原键，不发送。 |
| POST `/internal/trade/checkout/findPayment` | 既有 CheckoutInternalApi/Controller 追加 `Result<PaymentAttemptView> findPayment(Scoped<PaymentLookup>)`；协议1、真实可信 MERCHANT delegated identity+当前 store/order view；payParams 永远空。 |
| POST `/biz/checkout/{operationNo}/offline-receipts` | 新 C `trade.api.biz.BizOfflineReceiptController`；Header key；Body `(method,amountMinor,currency,evidenceRef,receivedAt,note)`；scope 从当前 BizContext/真实会话构造。 |
| GET `/biz/checkout/{operationNo}/offline-receipts/{paymentOperationNo}` | 同 controller；ORDER_VIEW+同店整单范围；可读该店原操作（不同经办亦可），无 payParams。 |
| POST `/biz/checkout/{operationNo}/offline-receipts/lookup` | 原响应丢失：Idempotency-Key header 必填，空 body；ORDER_VIEW+当前真实 actor namespace/OFFLINE_RECEIPT 查本人原键，不能借同键泛查其它经办。 |
| POST `/biz/upload/image?bizType=OFFLINE_RECEIPT` | 扩充真实 H BizUploadController；相同 MultipartFile/5MiB/图片头检测；专用分支真实当前 MERCHANT token + cash permission + 当前店 + actual account。返回 `{url:privatePath,evidenceRef:canonicalAssetKey}`；正文/URL不进诊断日志。 |

raw requestKey 不出现在 URL/query string/响应/日志；HTTP 日志不得记录 Idempotency-Key header，接口不回显该 header。typed internal PaymentLookup 若用 requestKey，始终限定当前可信 MERCHANT actor namespace + OFFLINE_RECEIPT；若用 paymentOperationNo，按真实当前店/整单范围核验，仍不返回 params。外部 B 无法按 C 原键查支付，只能按已获授权的业务 operation 号读取事实。

不新增任何 internal C confirm/place/initiate/取参数路由；PaymentOperationPort 无 HTTP 公共入口。OfflineReceiptPort.confirm 本期也只允许真实当前 HOST B TokenStore 会话，不把普通内部 delegation 升级为现金操作授权；find 可沿现有 B read 身份政策。58/59 才 wire 美业/C/B 消费端，此处只交 owner endpoint，不虚构未来 remote。

### 1.3 OfflineReceiptPort

B `spi/trade/OfflineReceiptPort.java`：

```java
PaymentAttemptView confirm(CheckoutScope scope, OfflineReceiptReq request);
PaymentAttemptView find(CheckoutScope scope, PaymentLookup request);
record OfflineReceiptReq(OperationRef operation, String requestKey, String method,
        long amountMinor, String currency, String evidenceRef, Instant receivedAt, String note) {}
```

method 精确 CASH/BANK_TRANSFER；currency=CNY；amountMinor>=0，必须==owner 全单待收（无拆收/混合/找零）；receivedAt 必填、毫秒精度、>=原订单 createdAt、<=owner 当前时刻+120秒。CASH note 1–500 必填；BANK_TRANSFER evidenceRef canonical 1–255 必填，note 可空≤500；CASH 如提交附件也须同样真验。首次必须原 C 已确认并消费 confirmation 完成 place OFFLINE、单主体单店。WAIT_OFFLINE_PAY 正常投影；CLOSED/CANCELLED 也允许首次足额“已实收申报”，因为该操作没有物理扣款：以原 OrdCheckoutConfirmation.amountMinor/currency、所指 quote.pricingJson 与原 ord_order.payAmount/currency 三者一致的原应收为准，关单不能改为零或重算现价。确认必须原 buyer/operation/order 同链、已 CONSUMED；原应收事实缺失或不一致拒绝。即使 P0 之前已关单，也先真实 owner receipt、随后原 LATE_PAYMENT resolution，保持原终态不投影 PAID。当前 cash 权限、实际店归属、附件与足额要求不豁免；原 quote/place 已通过正式 offline 四层准入，后来的商品下架/删除、类目策略或门店 offline 开关关闭不能抹掉对该原单已实收的申报；有既有 SUCCESS 则仅可兼容复用原 receipt，不能再收。

C `trade.service.OfflineReceiptService` 同两签名、`impl.OfflineReceiptServiceImpl`、`trade.port.OfflineReceiptPortImpl`。共享 `trade.policy.CheckoutAccessPolicy.requireCurrentCashier(CheckoutScope)` 返回 `CashierActor(tenantNo,entityNo,storeNo,actorNo,realm,subjectKind,accountNo)`；原 requireMerchant/六方法合同不变。无调用方事务，真实 MERCHANT TokenStore 校验同 tenant/typed actor 后再当前权限、实际门店、实体和 courier-only 拒绝。

B `spi/user/MerchantOperatorAccessPort` 追加：

```java
default Optional<AuthorizedOperator> resolveAuthorizedOperator(Query query) { return Optional.empty(); }
record AuthorizedOperator(String accountNo) {}
```

生产 MerchantOperatorAccessPortImpl 实现复用 OperatorAccessMapper.selectAccess：恰好一行 allowed=true 才返回真实 accountNo；allowed 方法兼容保留，仍实时查同源。MCH current.userNo 是 mch_account_no，USR current.userNo 通过该实体 active account 映射；不是 entityNo/personnelNo。资产 uploadedBy、offline actor_account_no 都存 accountNo，同时 operation 保存 actor_no/realm/kind 作为可审计登录 namespace。

### 1.4 永久 requestKey 与授权

`ord_checkout_payment_request` 主库先持久，随后才碰 pay：唯一 `(tenant,actor_realm,actor_kind,actor_no,action,request_key_hash)`。action=INITIATE_PAYMENT/OFFLINE_RECEIPT，canonical actor 是实际 session namespace；OFFLINE 同时冻 accountNo；scope/buyer/caller/checkout operation/order/sub 存行且进 requestHash。

* request_key_hash=SHA256(raw UTF8 key)，不截断、不保存原 key；requestHash=SHA256(owner canonical JSON version1)。原键查时先核当前身份/权限，再取原映射；同键异 operation/channel/method/amount/evidence/time/note→CHECKOUT_PAYMENT_CONFLICT，永不生成新号。
* 新 random `PO`+32hex（34字符）业务号在 P0 第一次 insert 内生成；并发唯一冲突读取胜出行比完整摘要；不信异常就重试另号。表不 TTL/删除，不依赖 sys_idempotent/24h cache。
* ONLINE payload 记录请求 channel（包括 null）、原订单 owner economics 摘要。首次有效通道路由生成后冻 `payment_request_json`；同键后不拿当前路由覆盖原请求，不因店进件变更换资金号。
* OFFLINE payload 包含 actual account+method+预期全额+CNY+receivedAt+note+canonical evidenceRef。首次 receipt 原证据不能换；同键重复不重收。不具收款权限时 confirm replay 也拒；ORDER_VIEW find 可读原结果。
* 原 key 同 payload →原 paymentOperationNo；新 key 同未终态兼容指令→新 request mapping、pay alias 原 canonical/payment/outTradeNo；有 SUCCESS 时只找原 receipt，不能 open 第二笔。OFFLINE 不同 key 同 payload 可映射原 receipt，但 method/evidence/time/note/实际account 与原首次不符即 conflict；其它经办可用 find，不通过二次 confirm 改经办。
* 确定拒绝后，原 key 永远原 REJECTED；只有真实 C 明确新 key 且原 attempt 有确定“从未发送”终态证明，才允许新 outTradeNo。超时、false、notFound、关订单、TTL、进程消失都不是该证明。

## 2. 资金 Port 与 canonical operation

B `spi/settle/PaymentOperationPort.java`，仅可信 trade owner/桥接 in-process；不是 caller 自报金额能力：

```java
PaymentOperationView prepare(PaymentOperationReq request);
PaymentOperationView initiate(String operationNo);
PaymentOperationView query(String operationNo);
PaymentOperationView confirmOffline(OfflinePaymentReceiptReq request);
record PaymentOperationReq(String tenantNo, String operationNo, String requestHash,
        String orderNo, String buyerNo, String entityNo, String storeNo,
        SettlePort.PaymentOpen payment) {}
record PaymentOperationView(String operationNo, String canonicalOperationNo, String phase,
        String paymentNo, String outTradeNo, String requestHash, String receiptNo,
        String reasonCode, SettlePort.PaymentReceipt receipt, Map<String,String> payParams,
        Instant payParamsExpiresAt, Instant observedAt) {}
record OfflinePaymentReceiptReq(PaymentOperationReq operation, String method,
        String evidenceRef, String actorNo, String actorRealm, String actorKind,
        String actorAccountNo, Instant receivedAt, String note,
        ReceiptEvidencePort.ReceiptEvidence evidence) {}
```

相较绑定草案显式补 canonicalOperationNo、receiptNo、参数期限、真实 account 和证据事实，说明真实 alias/验证责任；其余字段原意不改。`operation`/`payment` 均由 order owner 读自身事实创建；CHECKOUT pay 核 MAIN/身份/金额/币种/订单对应、ONLINE实际 PaymentStore 的entity/store/channel、OFFLINE无外部路由。公开 body 绝不反序列化这些内部指令。

P `pay.service.PaymentOperationService` / `impl.PaymentOperationServiceImpl` 的精确本域能力：

```java
PaymentOperationView prepare(PaymentOperationReq request);
PaymentOperationView open(String operationNo);
PaymentOperationView find(String operationNo);
PaymentOperationView prepareLegacy(SettlePort.PaymentOpen payment);
Optional<DispatchTicket> claimSend(String operationNo);
PaymentOperationView recordPayable(DispatchTicket ticket, Map<String,String> params, Instant expiresAt);
PaymentOperationView recordUnknown(DispatchTicket ticket, String reasonCode);
PaymentOperationView rejectNotSent(DispatchTicket ticket, NotSentReason reason);
PaymentOperationView confirmOffline(OfflinePaymentReceiptReq request);
PaymentOperationView observeReceipt(String operationNo);
boolean protectsUncertainAttempt(String outTradeNo);
record DispatchTicket(String canonicalOperationNo, String paymentNo, String outTradeNo,
        long revision, SettlePort.PaymentOpen payment) {}
enum NotSentReason { NO_GATEWAY, LOCAL_PRECONDITION }
```

只传 SQL DTO/不可变事实；没有 gateway/core import。每个 mutation 显式 payTxManager；open 与 ledger.open 必须同 payDataSource 连接/同 TX，REQUIRED 外部 proxy调用，禁止 self-invocation 期待新事务。prepare 调用返回提交后才 open；service 使用短 TransactionTemplate 明确阶段或 public proxied transaction 方法，不在一个 @Transactional initiate 中串 P1–P4。

A `paybridge.service.PaymentDispatchService` / `impl.PaymentDispatchServiceImpl`：`prepare/initiate/query/confirmOffline` 与 Port 同签名，另 `SettlePort.PayInitResult initiateLegacy(SettlePort.PaymentOpen)` 供既有 SettlePortImpl。`paybridge.port.PaymentOperationPortImpl` 只委托。这个 orchestrator 无事务；入口断言平台/pay DS均未绑定事务；resolve identity/provider、prepay/query、media bytes I/O均锁外。没有可选空实现/default success。

### 2.1 精确阶段

| 步骤 | 原子事实/锁 | 崩溃或重复 |
|---|---|---|
| P0 trade | 短平台 TX：checkout operation→order读/核→payment request insert，owner frozen PaymentOpen和hash一起存 | 未提交无资金调用；提交后原key恢复原PO，不重新命令/路由 |
| P1 pay | 独立短payTX insert PREPARED；ONLINE 的 prepare 冻 PaymentOpen；OFFLINE 的 confirmOffline 先冻 PaymentOpen + offline_request_json 完整规范化离线命令，indexed evidence_ref/evidence_json 仍 NULL | 同订单两个合法不同 key 可各有 PREPARED；不提前占证据 unique；重复需完整摘要/scope相同，无 ledger 时 query只返回PREPARED |
| P2 pay | order guard→请求 operation→当前 PAY rows；同TX ledger.open+paymentNo/outTradeNo+canonical binding | rollback 全无；commit 全有；无 ledger已建mapping空窗口 |
| P3 claim | guard→canonical operation→payment；只有canonical OPENED且send_started_at=null CAS到SENDING/revision+1/send_started_at；提交后DispatchTicket | 只有CAS胜者拿ticket；alias不独立SENDING；其它只query/原参数 |
| I/O | 无DB TX，gateway.prepay(原outTradeNo,冻结指令) | 异常/非确定false→UNKNOWN；不能close或换号 |
| P3结果 | 短payTX guard→canonical→payment，核ticket的原号/revision，SUCCEEDED优先 | 参数成功PAYABLE；明确NOT_SENT有限关闭REJECTED；结果晚到不能覆写SUCCESS |
| P4 | 锁外 query 原号/或读取已成功ledger，短payTX ledger.settle/observe；之后锁外 owner markPaid | callback与query归同paymentNo/receiptNo，最多一次paid副作用 |

canonical：每个实际 payment 的 `stl_payment.initiating_operation_no` 指向第一仲裁 row；root 的 canonical_operation_no=自身。其它 request row phase=ALIASED，绑定同 paymentNo/outTradeNo、canonical=root；只允许一跳，不形成alias链。ALIASED是持久内部状态，对外 phase 取root/真实ledger。operation.payment_no/out_trade_no 不unique。P2兼容比较完整冻结 PaymentOpen（含subject/route/account版本），不止金额。

OFFLINE 精确并发：A、B 的 P1 可先后或并发提交独立 PREPARED（完整 offline_request_json 均已冻结，证据索引列均 NULL）。P2 先取同全局 order guard，再 current-read 原 PAY/root。第一胜者按本请求建 ledger、root自指、填经办/method/evidence_ref/evidence_json并同TX settle；证据 unique 在此一次领取。另一请求持 guard 后若见原 root，比较 normalized offline_request_json 的原 method/account/receivedAt/note/evidence facts（排除 operationNo/requestKey/requestHash 这些请求身份），匹配则 PREPARED→ALIASED、映射原 payment/outTrade/root，indexed evidence 列始终 NULL，不再 ledger.open/settle；不匹配 conflict，原 PREPARED 保留但不收款。若A在P2前崩溃，B可成为root；A恢复后同样alias B。若A的P2回滚，证据unique与ledger绑定一起回滚，B可继续。对不同订单复用同证据，由 unique(evidence_ref)拒绝，整P2回滚；不先独立提交半笔 SUCCESS。

旧 `SettlePortImpl.initPayment` 改委托 initiateLegacy，同一次派发仲裁；prepareLegacy 为没有长期key的旧接口生成内部operation，随后复用 P1/P2。已有未终态有canonical就alias并查询原root；没有canonical的历史已开流水按“可能曾发送”接管为UNKNOWN，不能凭未记录send_started_at猜可重发。只有本次P2实际新建的ledger才可设OPENED。历史 openPayment→trusted callback仍可settle。旧普通支付仍使用原价格/路由；managed `/mp/order/{no}/pay` 直接拒绝并要求使用原checkout operation的新入口，不能绕 currentC/key。

参数：首次 provider返回的params原样冻结，保守有效期=记录时刻+5min；不承诺通道实际有效期恰为5min。本人currentC可在该窗口读原params；B/internal/worker空map。到期只query，PAYABLE若未成功返回reason PAYMENT_PARAMS_EXPIRED，无重签/盲重发。现有网关没有已核实的same-outTradeNo再次prepay保证，首期不提供任何自动重发能力，不新增假capability。PREPARED/OPENED只在当次真实C invoke initiate可发送，后台query不发送。

### 2.2 确定失败、真实资金与旧通道合同

G `PayGateway.PrepayResult` 保持旧四参数构造兼容，追加 `PrepayDisposition disposition`：PAYABLE/NOT_SENT/UNKNOWN。四参数构造：success=true→PAYABLE；false→UNKNOWN；`fail()`仍UNKNOWN。新增 `notSent(String message)` 只用于原代码在调用client前的本地缺配置/缺入参/默认未实现分支；发出调用之后任何异常或缺 prepay_id 都UNKNOWN。调gateway前仍提交SENDING，只有同ticket且disposition=NOT_SENT可有限拒绝。

`PaymentLedgerService` 追加 `void closeUnsent(String outTradeNo, String canonicalOperationNo)`，MANDATORY payTx，仅供 PaymentOperationService.rejectNotSent 在已锁相同canonical且有匹配ticket/revision/NotSentReason时调用；不开放Controller/Port，不接受caller boolean。它只能关PAY INIT/PENDING，SUCCESS绝不覆盖。旧public close仍REQUIRES_NEW，按guard→canonical(如有)→payment锁，碰SENDING/PAYABLE/UNKNOWN/已成功root一律保留；不能被operation事务调用。历史无canonical旧close语义保留。

现有 QueryResult(ok,paid,found,amount,tradeNo,currency) 不证明“通道最终未支付”，所以当前 paid=false/found=false 等均非新号依据。合法新号目前只来自明确 NOT_SENT 的 REJECTED；不虚构“查不到即可重新支付”或手工改 phase。若今后接确证 terminal unpaid，需要独立真实通道合同再实现；本任务不偷偷把unpaid解释closed。

query实际原号校验：修真实 WechatDirectPayGateway/WechatPayGateway/AlipayPayGateway.query，paid结果必须响应out_trade_no逐字匹配请求、真实金额解析成功、币种非空、tradeNo非空，不满足返回failed。支付宝仍按当前国内CNY合同，不声称任意币种。FREE=0走真实既有免支付逻辑，TEST/STUB仅隔离测试；测试网关按请求原号返回/存记录。新PaymentDispatchService还比较原root outTradeNo、指令order/buyer/channel及receipt原号查询结果，不把另笔successPayment当原attempt。

receipt_no 新增在权威 stl_payment，首次 PAY settle（包括OFFLINE）同事务 mint `RC`+32hex，唯一且replay不变；不另造金额账。历史SUCCESS无receipt_no仍是历史可读事实，不在迁移中伪造已收新receipt；新operation成功必须真实receiptNo。operation视图join ledger获得receiptNo，避免alias各造receipt。

若trusted channel确实收了不同金额/币种，ledger仍保存真实SUCCESS/receipt、冻结请求JSON不改；operation对外MANUAL_REQUIRED，owner经原receipt mismatch resolution阻止PAID。不能为了金额校验把真实已收的钱抹掉或再开新attempt。ledger.paymentRequest对异常金额可能拒绝，恢复时读取operation冻结JSON，不能重价改应收。

## 3. 正式附件、门店策略与线下事务

B `spi/media/ReceiptEvidencePort.java`：

```java
ReceiptEvidence verify(ReceiptEvidenceQuery request);
record ReceiptEvidenceQuery(String tenantNo, String entityNo, String storeNo,
        String actorAccountNo, String evidenceRef) {}
record ReceiptEvidence(String tenantNo, String assetKey, String entityNo, String storeNo,
        String uploadedByAccountNo, String bizType, long bytes, String contentType,
        Instant uploadedAt, String factHash) {}
```

唯一生产实现 S `media.port.ReceiptEvidencePortImpl` 委托 `media.service.ReceiptEvidenceService`/`media.service.impl.ReceiptEvidenceServiceImpl`，本owner直接SysMediaAssetMapper。无调用方TX；先owner读真实行、核固定MAIN/专用bizType/OFFLINE_RECEIPT/ACTIVE/无purgeBatch/同entity/store/account/canonical key，再锁外MediaStore.exists。`factHash` owner规范化上述事实（无URL/签名/原件正文）；再次读取同assetKey核不可变字段及ACTIVE后返回。PUBLIC不暴露该verify，信任来自正式owner调用路径不是客户端自报factHash；pay bridge向该owner重新verify req.evidenceRef，并与trade冻结证据相等，之后才payTX。

上传绕过审计：H BizUploadController 的 OFFLINE_RECEIPT 必须在原 BIZ_TYPES 白名单分支之前独立处理，不能简单加入旧 GOODS/QUAL/AFTERSALE 白名单。该分支内私有 requireReceiptUploader() 通过 B/auth 的 SecurityUtils、当前认证token、TokenStores.get、BizContext 当前实际店、MerchantOperatorAccessPort.resolveAuthorizedOperator(Query(permission=pay:cash:receive)) 核 MERCHANT/MCH或USR/MAIN/active owner事实，导出内部 ReceiptUploader(entityNo,storeNo,accountNo)；不 import core CheckoutAccessPolicy、不接收客户端 entity/store/uploadedBy。普通 generic 分支永远不能写 OFFLINE_RECEIPT。其余真实上传caller：BizZipImportController.storeImage固定GOODS，MpUploadController固定_USER/EVIDENCE，MpAvatarController固定_USER/AVATAR，都不接收新bizType；测试用伪scene/bizType/scope字段证明不能写专用owner记录。MediaUploadService仍唯一PENDING→bytes→ACTIVE写者，不复制媒体账。

本地私有边界真实证据：A UploadResourceConfig.GoodsOnlyInterceptor只允许两段legacy或第三段goods；新key固定五段且第三段offline_receipt，不能经公开/uploads取得。H MediaReadController:55要求签名且private cache，CosMediaStore.isPublic:92也只有goods。新支付/receipt查询与日志不返回URL/签名/正文；不新增公共签名签发端点。MediaUsageService.reclaimable复用purgeService查询排除新用途；对旧队列/异常行的batch→toVO额外对OFFLINE_RECEIPT返回thumbUrl=null，不给普通媒体治理页生成凭证签名，该文件列入owned。

不添加media第二账表。专用新上传只采用真实account，不为历史旧资产补虚构actor。MAIN-only是显式范围（sys_media_asset无tenant列）；其它tenant fail-closed，key由既有实体/店/用途/随机文件格式生成，不能传任意hostURL、签名URL、../、_ENTITY/_USER、旧QUAL/AFTERSALE/普通EVIDENCE。evidenceRef唯一约束置于pay operation的canonical线下行；aliases不另存evidence_ref，只指canonical，避免同receipt replay与unique冲突。不同订单/operation重用同附件拒绝；本期一张转账凭证只能归一次全额线下收款，不支持合并转账拆分分摊。

私有保留：增加SysMediaAsset.OFFLINE_RECEIPT常量；MediaScanner不把该用途转RECLAIMABLE；MediaPurgeService.reclaimableQuery无条件排除该用途（includeQual也不能绕），run执行每个实际目标前再次拒绝该用途，旧队列也不能删。MediaPurgeJob调用该service；实际 PENDING 处理者是 MediaReconcileJob（不是 MediaBackfillService）：对专用类型 exists=false 只保留 PENDING 记录、不删行；exists=true 只用 WHERE id/key/status=PENDING 的条件更新为 ACTIVE，不能用陈旧扫描覆盖/删除已 ACTIVE 行。MediaBackfillService 只回填 GOODS，无专用类型删除路径；原MediaStore.delete调用点审计只允许此owner路径，测试覆盖显式key、filter、已排队、定时执行。专用类型全部保留（包括未收款上传），本期不引入自动清理/保留年限。以固定bizType防删除，关闭“锁外exists后被删除”的正常应用窗口，不持SQL锁做磁盘/COS RPC。恶意DB/运维绕应用删字节不是本用例可保证范围；之后读取丢失即明确证据不可用，不声称银行凭证被核验。

保留现有私有签名读取；操作返回仅reference，日志无原件/URL/token。B现有上传返回privatePath为兼容UI，不把path当授权或支付证明。媒体owner `verify` 支持本地disk/COS的同MediaStore事实，调用本身始终锁外；不新造空RemoteReceiptEvidencePort。未来远程owner必须实现同合同，本期可部署HOST本地。

门店经营策略继续由现有 `PayModeServiceImpl.availablePayModes(goods,store,fulfillment)` 四层交集：商品显式OFFLINE、类目显式禁止（无行按当前真实实现放行）、有效主体营业资质/自营规则、门店offline开关，以及STORE_VERIFY允许的履约。prepare/place 已调用真实 `ai.neargo.shop.spi.product.PayModePort` 完成准入。receipt 是原单已经发生的外部实收记账，不能把当前商品下架/删除或开关后来关闭作为拒记已收依据；它不再调用当前商品可售/支付方式重新准入或重新定价。原策略授权事实来自已持久的 operation.commandJson/currentQuoteNo→OrdCheckoutQuote.pricingJson，以及 consumed OrdCheckoutConfirmation.quoteNo/revision/hash/amount/currency→placed order/sub/item 映射与持久 OFFLINE 标志；不是要求还原所有当前商品记录。order锁内核上述原OFFLINE/单店/冻结应收/消费确认事实。旧平台券/补贴拒绝保持，不发明新的CASH/BANK_TRANSFER“通道”。两method在正式OFFLINE policy下都是允许的人工实收方式，差别是凭证要求；不新增新店配置默认全开。

线下处理：

1. 当前cashier授权、order/source原准入事实、附件owner真实验证，均锁外。核足额；P0保存原request。重复原请求先查原资金结果，可在附件后来状态改变时返回已经合法提交的原receipt，不重新执行收款；首次未完成的操作仍要求证据可验证。
2. pay confirmOffline 的P1将scope/actor/method/receivedAt/note/evidence factHash+canonical metadata冻结进offline_request_json；短payTX guard→operation→payment，核原应收快照一致（含首次申报前已关单），ledger.open(OFFLINE/CNY/owner金额，stores=空，无伪造银行路由)。同TX ledger.settle(outTradeNo,OFFLINE,`OFFLINE:`+canonicalOperationNo,owner金额,receivedAt.toEpochMilli(),CNY)→receiptNo。method是审计字段而不是payChannel。
3. 同TX仅canonical root写operation方法/证据/实际account/time；alias只保留完整原请求json和原root映射。插入绑定/evidence unique失败全rollback ledger/open/settle；不可留下已收无mapping。
4. 锁外读真实receipt后order markPaid；锁内再次核current order/buyer/entity/store/payMode/全额/receipt proof。关单赢则ord原终态不变，真实receipt仍SUCCESS，原PaymentResolutionService记录LATE_PAYMENT；返回MANUAL_REQUIRED+原receipt，重复不得再收。先收赢则原标准PAYMENT/OFFLINE_CONFIRMED投影PAID，managed只FULFILLING且verifyCode为空，无COMPLETED、无服务发货假上报。
5. stl_bill沿原SettleGenerationOrchestrator/SettleServiceImpl：OFFLINE_SETTLED、commission_minor=0、waived_commission_minor真实shadow、不进入split。新OFFLINE stl_payment也必须在ReconServiceImpl.checkStalePayments/相关通道账单扫描排除，尽管正常receipt已SUCCESS；不产生永久通道差异。

## 4. 事务、Bean/DS图与所有资金入口审计

| Bean/能力 | datasource / transaction / I/O |
|---|---|
| CheckoutServiceImpl/CheckoutPaymentServiceImpl/OfflineReceiptServiceImpl→CheckoutMappers/TradeMappers | 平台 transactionManager；auth/route/media/pay调用前后短TX分段，不在平台锁内调pay或media |
| MerchantOperatorAccessPortImpl、MerchantPayPortImpl/实际paymentRouteOf | 平台owner SQL，auth无外层TX；route已有自己的只读RR；锁外取真实route |
| ReceiptEvidencePortImpl/ServiceImpl、MediaUploadService、MediaStore | media元数据在平台，disk/COS I/O无SQL TX；只证据事实，不调资金 |
| PaymentOperationPortImpl→PaymentDispatchServiceImpl | HOST bridge，无TX；引用pay-domain/service与真实PayGatewayRouter/UserIdentityPort，禁止直接core mapper |
| PaymentOperationServiceImpl→PaymentOperationMapper/SettleMappers.PaymentMapper/PaymentLedgerService proxy | paySqlSessionFactory/payDataSource/payTxManager，所有写与本域账在同连接 |
| PaymentLedgerServiceImpl | 原owner；guard覆盖open/settle/close/finite拒绝。refund原资金容量锁沿原逻辑，不借来当order guard |
| OrderServiceImpl/PaymentLifecycleServiceImpl | 锁外SettlePort读取；锁内纯receipt验证+普通owner投影 |

`PaymentLifecycleService` 精确扩充：`PaymentReceipt readConfirmed(String orderNo,String channel,String tradeNo)`（无外层TX，唯一SettlePort读取处）与 `ConfirmedPayment requireConfirmed(OrdOrder order,String channel,String tradeNo,PaymentReceipt receipt)`（不做任何外域调用）；原三参数方法仅旧unmanaged OFFLINE shortcut保留本地适用分支，其它调用改经锁外reader，不把receipt作为可公开反序列化proof。

`OrderServiceImpl.markPaid` 锁外取得immutable receipt，再用sourceTransactions（平台TX）调用markPaidSource传入；对普通调用已有外层TX的旧OFFLINE必须保留unmanaged本地shortcut，不在那条路径远查，managed永拒该shortcut。其它线上markPaid若外层事务已开显式拒绝错误调用而非持锁RPC。现有points proof仍锁外；不复制状态写者。locked validation核receipt order/user/channel/trade/amount/CNY及sub持久entity/store/source operation与OFFLINE/ONLINE语义；不相信调用者boolean。已PAID replay核同receipt再无副作用返回。

锁序：平台 checkout操作→order（P0只本地事实）；source RR仍第一条op锁、后全一致性读不混current读。pay canonical全序：guard(全局orderNo)→operation（多个时严格按operationNo字典序；P2短事务显式READ_COMMITTED，在guard后用最新普通读确定root候选，再按字典锁集合，guard持有时root不被替换）→PAY row按id；所有ledger open first guard再current读所有order PAY rows，避免外层RR旧snapshot。settle guard→PAY（不反向锁operation）；query observe guard→operation→PAY。refund原PAY→refund/capacity，无路径从refund再取order guard/operation；不新增反向边。media不与pay持锁交叠。

外部 IO 审计已按真实当前 provider 闭合（不是待实现后再调查）：gatewayRouter/identity lookup 在 P3 claim 前，claim 提交后 prepay/query；SettlePort read 在 order 锁前；generateForOrder 保持原 AfterCommit。managed projector 的实际可达链如下：

| 锁内调用 | 当前真实 provider/下游与边界 |
|---|---|
| stockPort.confirm(orderNo,lifecycleVersion) | C product.port.StockPortImpl:161/190 为平台 Mapper；A invbridge.port.DualWriteStockPort:167/173 在 DUAL 条件下为平台 confirm + mirror→本地 OutboxEventBus；A invbridge.port.InventoryStockPort:114/119 在 INVENTORY 条件下，v2→本地 outbox，v1→ReservationServiceImpl.commitByRef:330 的 invTransactionManager→本地 reservation/outbound/inventory Mapper。没有 HTTP/RPC；v1 独立库存 TX 是已接受既有边界，不能称与 ord 原子，本任务不重构该库存策略。 |
| personPort.findByUser | C user.port.PersonPortImpl→user.service.impl.PersonServiceImpl.findByUser:95→personMapper，本地 SQL；全仓实际该 Port 仅此实现。 |
| memberEventPort.onOrderPaid | C member.port.MemberEventPortImpl→MemberServiceImpl.onOrderPaid:116→会员 Mapper；fresh member 的 scannedInto:735→marketing.port.AttributionPortImpl→marketing.attribution.impl.AttributionServiceImpl.current:109→attributionMapper，均本地 SQL。 |
| merchantAdminPort.accruePayQuota | M merchant.port.MerchantPortImpl:1741→resolvePayment/PaymentMapper/本地累加，没有外部资金调用。 |
| entitlementOrderUses.decideForTerminalOrder | C trade.service.impl.EntitlementOrderUseServiceImpl:202→原 order/use/decision 本地 Mapper + OutboxEventBus；普通 managed 无 entitlement use 时直接返回。 |
| cardSaleSnapshotService.recordPaidFromOrder | C trade.entitlement.impl.CardSaleSnapshotServiceImpl:64→orders查询/snapshots.markPaidOnce；无 card-sale 行则0，不调用 pay。 |
| eventBus.publish(OrderPaid/SubOrderPaid) | S event.OutboxEventBus:67/94→OutboxMapper insert/insertIfAbsent；异步消费不在此锁内调用。 |
| notifyShippingOnPaid / groupJoinPort / points proof | managed 明确跳过 notifyShipping；Task56普通选择不含 group/points/card消费，既有校验保留；points proof 原本锁外。 |

因此本次需要搬出的实际跨资金 owner 调用就是原 requireConfirmed→SettlePort；已冻结为 readConfirmed 锁外 + requireConfirmed(receipt) 锁内。新增测试用真实 HOST provider graph 及外部第三方代理记录 TX 活性，不能仅用mock证明。库存 INVENTORY v1 独立提交的既有边界在报告单列，不冒称原生库存/跨库原子已覆盖，也不新增 remote provider。source 外部 query 仍在 RR/op 锁之前；该 RR 内保持第一 SQL 锁 operation，其后普通一致性读取。

### 4.1 调用点闭合清单

| 当前调用点 | Task57处理 |
|---|---|
| SettlePortImpl.openPayment→ledger.open | 原返回签名，统一order guard；无gateway |
| SettlePortImpl.initPayment→ledger.open/prepay/close/settle(FREE) | 改共用PaymentDispatchService.initiateLegacy；同canonical发送，FREE真实零元settle |
| OrderServiceImpl.pay→SettlePort.initPayment | 普通保持原价格/路由；managed要求Checkout新方法，不能旧route绕session/key |
| 新CheckoutPaymentService→PaymentOperationPort | P0→P1→P2→P3；C current，金额owner |
| 新OfflineReceiptService→confirmOffline | 实际B/证据/策略，pay原子open+settle |
| PayCallbackController (`/pay/callback/stub`) | 保留真实配置启用/原号query核验；ledger.settle与markPaid复用；不直接新open |
| ChannelPayCallbackController (`/pay/callback/...`) | 原验签/owner通道结果→SettlePort.settlePayment；原号和channel核验；receiptNo首次settle生成 |
| PaymentReconReconciler.scan→ledger.settle→OrderRepairPort.markPaid | 同资金receipt；notFound对canonicalSENDING/PAYABLE/UNKNOWN defer；不隐式close或新open |
| PaymentLedgerService.close | REQUIRES_NEW原外部入口，guard/current状态保护，不能有限关闭新发送不确定attempt |
| OrderService.closeUnpaid/closeExpiredOrders/cancel | 只订单终态，不能充当支付终态/重开号授权；晚款保留LATE_PAYMENT |
| PaymentLedgerService.refund/claimRefundDispatch/markRefund... | 不open PAY、不改canonical；保留现有容量/原号协议；回归refund相关原入口 |
| MerchantOrderService.confirmOfflinePay | managed持续拒绝；无receipt的PaymentLifecycle旧offline信任亦拒绝 |

### 4.2 旧 unmanaged OFFLINE 与既有现金竞态的真实含义

`PaymentInitiationCashRaceFlowTest#cashConfirmedAfterCashierReadCannotCreateAnotherChargeableAttempt` 真实执行是普通 ONLINE 订单，TEST gateway.markPaid→`/pay/callback/stub`→ledger SUCCESS，然后释放旧 C successPayment 的 stale read；它没有调用旧商家线下确认。因此该 AC 的成功现金在 stl_payment，guard 的 current success检查可见。

另经源码审计，`MerchantOrderServiceImpl.confirmOfflinePay:324/361` 对 unmanaged 确实只更新 ord 离线经办/时间，经 `PaymentLifecycleServiceImpl:35` 本地 shortcut→PAID、AfterCommit 离线 bill；它没有 stl_payment SUCCESS，不能把它宣传为本次正式 receipt。旧 `BizOrderController:249` 仍 RECEIVE 权限，不能据此给其调用者新 pay:cash:receive 或新 OfflineReceiptPort 能力，managed 仍拒绝。

当前真正生产 `.initPayment(...)` caller 全仓只有 `OrderServiceImpl:2654`，其 `:2585` 必须 WAIT_PAY；旧 offline 的锁前/锁内都必须 WAIT_OFFLINE_PAY。`PaymentMachine:29/34/37/40` 只创建/POINTS_READY选择两者，没有 WAIT_PAY↔WAIT_OFFLINE_PAY 转换，PAID 亦无返回待付款边。故同一真实 owner 订单不存在“合法线上 pay 已读 WAIT_PAY、随后旧 offline 确认成功”的可达交错；不为伪造内部 PaymentOpen 设计新公共授权。追加真实 unmanaged OFFLINE HTTP 下单的负例 `PaymentInitiationCashRaceFlowTest#legacyOfflineOrderCannotInitiateChannelBeforeDuringOrAfterMerchantConfirmation`：用既有合法 RECEIVE 经办确认，同订单 C pay 在确认前/并发/后均拒，gateway预支付0、chargeable PAY0。该反例不伪seed状态、不伪造receipt；online旧竞态继续真实SUCCESS+guard原生验证。若未来新增在线/线下切换，必须另设计跨owner资金仲裁，不能引用本次两状态互斥结论。

## 5. Source事实与错误/权限

source只在锁外query已经映射的pay operations（query不发送）并按paymentNo去重；合法receipt核原order/buyer/channel/trade/amount/currency。RR tx内沿56既有owner事实比较和source revision/hash规则；新PaymentFact填canonical operationNo、paymentNo、原outTradeNo、真实receiptNo与tradeNo。存在alias也只汇总一笔；异常/重复/晚款只作财务异常，不自动抵应收。first未知与owner不可达仍null received/outstanding+BLOCKED/空可消费行+last verified pair。不能因PREPARED/UNKNOWN就写received=0；历史seeded receipt无新operation时保留null新字段，不能伪造操作号；此路径不是57真实收款证据。

新增 BizPerms.CASH_RECEIVE=`pay:cash:receive`；LABELS中文“确认线下实收”。OWNER沿已有*；MANAGER/CLERK/PICKER/COURIER/CS不加默认权限，RECEIVE/ORDER_VIEW都不能confirm。b-app zh-CN/en/ar及shared mock同步；en“Confirm offline receipt”，ar“تأكيد استلام الدفع دون اتصال”。原biz-perm-label只扫biz:不能声称pay:已覆盖：新增精确`packages/shared/tests/checkout-cash-permission.test.ts`读真实常量/LABELS/三语/mock，并运行真实grant/revokenative。

候选新ErrorCode（actual代码区间核对未占用后root审）：CHECKOUT_PAYMENT_CONFLICT=20021 `err.trade.checkout_payment_conflict`；CHECKOUT_RECEIPT_EVIDENCE_INVALID=20022 `err.trade.checkout_receipt_evidence_invalid`；CHECKOUT_PAYMENT_PARAMS_UNAVAILABLE=20023 `err.trade.checkout_payment_params_unavailable`。三语固定含义分别“原支付请求不能变更”“线下实收凭证无效或不属于当前收款范围”“原支付参数暂不可用，请查询原支付结果”；en/ar对应，不把原gateway文字透出。UNKNOWN/MANUAL_REQUIRED结果 reasonCode按§2，不以异常假定未发送。NoSuch/外域仍NOT_FOUND，身份仍FORBIDDEN/UNAUTHORIZED，四层策略使用现有80011/80012。

## 6. 候选 V540 DDL（逐字候选，尚未创建/执行/pin）

候选路径 `backend/shop-app/src/main/resources/db/migration/V540__checkout_payment_operation.sql`。仅平台统一Flyway追加；不在pay-svc打开另一个Flyway，不更改V539/历史SQL。JSON用LONGTEXT+JSON_VALID与既有生成器一致；CHECKOUT MAIN/不可删除/namespace/NULL组合显式CHECK。新表按BaseEntity字段，case-sensitive业务键；无跨库FK。root审查后hash/bytes另记录，首次执行后不可改。

```sql
CREATE TABLE ord_checkout_payment_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    caller VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    operation_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    payment_operation_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    sub_order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    buyer_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    entity_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    store_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    actor_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    actor_realm VARCHAR(16) NOT NULL,
    actor_kind VARCHAR(8) NOT NULL,
    actor_account_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    action VARCHAR(24) NOT NULL,
    request_key_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    payment_request_json LONGTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkout_payment_operation (tenant_no,payment_operation_no),
    UNIQUE KEY uk_checkout_payment_key (tenant_no,actor_realm,actor_kind,actor_no,action,request_key_hash),
    KEY idx_checkout_payment_order (tenant_no,caller,operation_no,id),
    CONSTRAINT ck_checkout_payment_scope CHECK (tenant_no='MAIN' AND caller='BEAUTY'),
    CONSTRAINT ck_checkout_payment_actor CHECK ((action='INITIATE_PAYMENT' AND actor_realm='CONSUMER' AND actor_kind='USR' AND actor_account_no IS NULL) OR (action='OFFLINE_RECEIPT' AND actor_realm='MERCHANT' AND actor_kind IN ('MCH','USR') AND actor_account_no IS NOT NULL)),
    CONSTRAINT ck_checkout_payment_json CHECK (JSON_VALID(request_json) AND JSON_VALID(payment_request_json)),
    CONSTRAINT ck_checkout_payment_deleted CHECK (deleted=0)
) ENGINE=InnoDB COMMENT='Permanent checkout payment request identity, no cash balance';

CREATE TABLE stl_payment_order_guard (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_order_guard (order_no),
    CONSTRAINT ck_payment_order_guard_live CHECK (deleted=0)
) ENGINE=InnoDB COMMENT='Serializes all payment opens for one order, no amount or balance';

CREATE TABLE stl_payment_operation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    operation_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    canonical_operation_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    owner_kind VARCHAR(24) NOT NULL,
    order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    buyer_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    entity_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    store_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    request_hash CHAR(64) NOT NULL,
    payment_request_json LONGTEXT NOT NULL,
    offline_request_json LONGTEXT NULL,
    purpose VARCHAR(8) NOT NULL,
    phase VARCHAR(24) NOT NULL,
    payment_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    out_trade_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    send_started_at DATETIME(3) NULL,
    pay_params_json LONGTEXT NULL,
    pay_params_expires_at DATETIME(3) NULL,
    last_error_code VARCHAR(64) NULL,
    method VARCHAR(16) NULL,
    evidence_ref VARCHAR(255) COLLATE utf8mb4_bin NULL,
    evidence_json LONGTEXT NULL,
    actor_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    actor_realm VARCHAR(16) NULL,
    actor_kind VARCHAR(8) NULL,
    actor_account_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    received_at DATETIME(3) NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_operation (operation_no),
    UNIQUE KEY uk_payment_receipt_evidence (tenant_no,evidence_ref),
    KEY idx_payment_operation_order (tenant_no,order_no,id),
    KEY idx_payment_operation_payment (tenant_no,payment_no),
    KEY idx_payment_operation_canonical (tenant_no,canonical_operation_no),
    CONSTRAINT ck_payment_operation_scope CHECK (owner_kind IN ('CHECKOUT','LEGACY_TRADE') AND (owner_kind='LEGACY_TRADE' OR (tenant_no='MAIN' AND entity_no IS NOT NULL AND store_no IS NOT NULL))),
    CONSTRAINT ck_payment_operation_phase CHECK (phase IN ('PREPARED','OPENED','SENDING','PAYABLE','UNKNOWN','SUCCEEDED','REJECTED','ALIASED','MANUAL_REQUIRED')),
    CONSTRAINT ck_payment_operation_mapping CHECK ((phase='PREPARED' AND payment_no IS NULL AND out_trade_no IS NULL AND canonical_operation_no IS NULL) OR (phase<>'PREPARED' AND payment_no IS NOT NULL AND out_trade_no IS NOT NULL AND canonical_operation_no IS NOT NULL)),
    CONSTRAINT ck_payment_operation_alias CHECK ((phase='ALIASED' AND canonical_operation_no<>operation_no AND send_started_at IS NULL AND pay_params_json IS NULL AND evidence_ref IS NULL AND evidence_json IS NULL) OR (phase<>'ALIASED' AND (canonical_operation_no IS NULL OR canonical_operation_no=operation_no))),
    CONSTRAINT ck_payment_operation_purpose CHECK (purpose IN ('ONLINE','OFFLINE')),
    CONSTRAINT ck_payment_operation_offline_request CHECK ((purpose='ONLINE' AND offline_request_json IS NULL) OR (purpose='OFFLINE' AND offline_request_json IS NOT NULL AND JSON_VALID(offline_request_json))),
    CONSTRAINT ck_payment_operation_prepared_evidence CHECK (phase<>'PREPARED' OR (evidence_ref IS NULL AND evidence_json IS NULL)),
    CONSTRAINT ck_payment_operation_offline CHECK (purpose<>'OFFLINE' OR phase IN ('PREPARED','ALIASED') OR (method IS NOT NULL AND method IN ('CASH','BANK_TRANSFER') AND actor_no IS NOT NULL AND actor_realm IS NOT NULL AND actor_realm='MERCHANT' AND actor_kind IS NOT NULL AND actor_kind IN ('MCH','USR') AND actor_account_no IS NOT NULL AND received_at IS NOT NULL AND ((method='CASH' AND note IS NOT NULL AND CHAR_LENGTH(note)>0) OR (method='BANK_TRANSFER' AND evidence_ref IS NOT NULL AND evidence_json IS NOT NULL)))),
    CONSTRAINT ck_payment_operation_evidence CHECK ((evidence_ref IS NULL AND evidence_json IS NULL) OR (evidence_ref IS NOT NULL AND evidence_json IS NOT NULL AND JSON_VALID(evidence_json))),
    CONSTRAINT ck_payment_operation_json CHECK (JSON_VALID(payment_request_json) AND (pay_params_json IS NULL OR JSON_VALID(pay_params_json))),
    CONSTRAINT ck_payment_operation_params CHECK ((pay_params_json IS NULL AND pay_params_expires_at IS NULL) OR (pay_params_json IS NOT NULL AND pay_params_expires_at IS NOT NULL)),
    CONSTRAINT ck_payment_operation_deleted CHECK (deleted=0)
) ENGINE=InnoDB COMMENT='Frozen payment instructions and canonical dispatch, cash remains stl_payment';

ALTER TABLE stl_payment
    ADD COLUMN initiating_operation_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN receipt_no VARCHAR(40) COLLATE utf8mb4_bin NULL,
    ADD UNIQUE KEY uk_payment_receipt_no (tenant_no,receipt_no),
    ADD KEY idx_payment_initiating_operation (tenant_no,initiating_operation_no);
```

guard以BaseEntity id主键+全局orderNo唯一实现同一guard（而非草案复合PK），无金额；采用 `INSERT ... ON DUPLICATE KEY UPDATE order_no=order_no` 后 `SELECT ... FOR UPDATE`，不能仅 JVM synchronized。实际旧租户边界：BaseEntity:21/30明确一期MAIN且不启用行级租户隔离；AuditMetaObjectHandler:30默认填MAIN；旧ledger.open不设tenant，PaymentOpen不带tenant，查询order/outTrade不加tenant谓词，真实财务身份是全局订单号。新guard不能按客户端tenant分裂同一订单锁，故unique(order_no)；tenant只保留审计，guard无MAIN硬CHECK。LEGACY_TRADE允许原row tenant，CHECKOUT仍MAIN；不承诺旧owner不存在的多租户隔离，不改旧价格/号域。原有历史非MAIN行仍走同一全局guard，不能被新CHECK截断。PaymentOperationPort.initiate/query(String operationNo)是真正全局业务号查找，故stl_payment_operation.operation_no全局唯一（非tenant组合唯一），mapper也只按该全局号精确查找；alias/root跨行核原order与冻结owner scope一致。未来真正多租户资金隔离需统一迁移所有财务identity/查询，本次不伪实现。严禁IF NOT EXISTS、clean、repair、任意baseline。MySQL首次测试覆盖fresh结构和V539 structural fixture追加同一DDL；不声称完整历史Flyway从V1 replay全部兼容。

## 7. 精确实施 owned 与顺序（尚未授权实施）

本阶段owned仅 `.superpowers/sdd/beauty-backend/task-57-design.md`、`task-57-report.md`。
产品实施候选文件集合如下，root放行时以此产生精确manifest；若审计需增减先更新本设计/报告，不扩大到主TDD/brief/ADR/progress/reviews。每组创建/修改为明示；不自动stage/commit。

| 模块 | Create/Modify 精确相对文件 |
|---|---|
| B (modify) | spi/trade/CheckoutPort.java；spi/trade/CheckoutInternalApi.java；spi/user/MerchantOperatorAccessPort.java；common/ErrorCode.java |
| B (create) | spi/trade/OfflineReceiptPort.java；spi/settle/PaymentOperationPort.java；spi/media/ReceiptEvidencePort.java |
| C (modify) | trade/service/CheckoutService.java；trade/service/impl/CheckoutServiceImpl.java；trade/port/CheckoutPortImpl.java；trade/policy/CheckoutAccessPolicy.java；trade/api/mp/MpCheckoutController.java；trade/mapper/CheckoutMappers.java；trade/service/OrderService.java；trade/service/impl/OrderServiceImpl.java；trade/order/service/lifecycle/PaymentLifecycleService.java；trade/order/service/lifecycle/impl/PaymentLifecycleServiceImpl.java |
| C (create) | trade/entity/OrdCheckoutPaymentRequest.java；trade/service/CheckoutPaymentService.java；trade/service/impl/CheckoutPaymentServiceImpl.java；trade/service/OfflineReceiptService.java；trade/service/impl/OfflineReceiptServiceImpl.java；trade/port/OfflineReceiptPortImpl.java；trade/api/biz/BizOfflineReceiptController.java |
| P (modify) | pay/service/PaymentLedgerService.java；pay/service/impl/PaymentLedgerServiceImpl.java；pay/entity/StlPayment.java；pay/service/impl/ReconServiceImpl.java；media/SettleMediaRefs.java |
| P (create) | pay/entity/StlPaymentOperation.java；pay/entity/StlPaymentOrderGuard.java；pay/mapper/PaymentOperationMapper.java；pay/mapper/PaymentOrderGuardMapper.java；pay/service/PaymentOperationService.java；pay/service/impl/PaymentOperationServiceImpl.java |
| A (modify) | paybridge/port/SettlePortImpl.java；paybridge/PaymentReconReconciler.java；portal/internal/InternalCheckoutController.java |
| A (create) | paybridge/service/PaymentDispatchService.java；paybridge/service/impl/PaymentDispatchServiceImpl.java；paybridge/port/PaymentOperationPortImpl.java |
| G (modify) | pay/channel/PayGateway.java；pay/channel/WechatDirectPayGateway.java；pay/channel/WechatPayGateway.java；pay/channel/AlipayPayGateway.java（pre-send certainty/paid-original-no checks） |
| M (modify) | merchant/port/MerchantOperatorAccessPortImpl.java（复用现有OperatorAccessRow/Mapper，不改已接受SQL含义） |
| S (modify) | media/SysMediaAsset.java；media/MediaScanner.java；media/MediaPurgeService.java；media/MediaReconcileJob.java；media/MediaUsageService.java（专用用途保留/不签预览） |
| S (create) | media/service/ReceiptEvidenceService.java；media/service/impl/ReceiptEvidenceServiceImpl.java；media/port/ReceiptEvidencePortImpl.java |
| H (modify) | channel/media/api/BizUploadController.java |
| auth (modify) | backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java |
| resources (create/modify) | backend/shop-app/src/main/resources/db/migration/V540__checkout_payment_operation.sql（候选）；backend/shop-app/src/test/resources/schema-test.sql（由真源生成）；backend/shop-app/src/main/resources/i18n/messages.properties/messages_en.properties/messages_ar.properties |
| client permission sources | b-app/src/i18n/locale/zh-CN.ts；en.ts；ar.ts；packages/shared/src/mock/db.ts；packages/shared/tests/checkout-cash-permission.test.ts（新） |
| runner (create) | scripts/verify-checkout-payment.mjs（复用56隔离模式但独立54x pin/报告精确类集合，不改变旧runner参数语义） |

tests候选准确类：B test `ai.neargo.shop.spi.trade.CheckoutContractTest`修改八方法与DTO；B test新 `ai.neargo.shop.spi.settle.PaymentOperationContractTest`；A test scenario 新 `PaymentOperationNativeMysqlTest`, `PaymentOperationProcessRestartNativeTest`, `OfflineReceiptFlowTest`, `CheckoutPaymentAuthenticationFlowTest`, `CheckoutCashPermissionFlowTest`, `ReceiptEvidenceFlowTest`；A test scenario 修改 `PaymentInitiationCashRaceFlowTest`, `PaymentLedgerCloseRaceFlowTest`, `CheckoutSourceFactsFlowTest`, `CheckoutManagedLifecycleFlowTest`。新testsupport `ai.neargo.shop.support.PaymentOperationProcessMain` 与 `PaymentGatewayFaultServer`（只TEST类路径；独立子JVM和loopback第三方故障边界，无生产fault hooks）。G 修改现有 `backend/pay/pay-channel/src/test/java/ai/neargo/shop/pay/channel/WechatDirectPayGatewayTest.java`；G 新建同包 `PaymentQueryIdentityTest.java` 覆盖微信两adapter/支付宝原号与金额/currency解析。A 修改现有 `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java` 登记新增B endpoint。H媒体端点经A真实HTTP Flow验证，不假设不存在的H测试类。

生成物候选需运行真源后精确检查：backend/scripts/gen-test-schema.py、scripts/gen-backend-layers.mjs、gen-table-inventory.mjs、gen-biz-role-matrix.mjs、gen-biz-feature-perm-matrix.mjs、gen-perm-domain-matrix.py、gen-glossary.mjs。仅保留本任务生成差异；root主TDD/README/旧OpsPoints漂移不混入。准确候选产物均在 `docs/technical/reference/`：后端分层清单.md、数据库表清单.md、B端功能矩阵-按角色.md、B端功能点-权限码-页面.md、三端权限矩阵-按业务域.md、中英文对照-词条.md、中英文对照-实体与字典.md、静态常量清单.md、依赖清单.md、glossary.json（无本任务差异者不owned/stage）；另 schema-test.sql 已在表列出。端点无consumer wire暂不编辑59端契约。新增HOST Biz端点登记 `BizEndpointPermTest` 实际权限表，生成器可见；不宣称API SDK已接。

顺序（root release后）：

1. 核IMPLEMENTATION_BASE/clean→创建AC测试先真实RED；新增签名未存在导致compile是setup/contract缺件，不称业务RED。可用真实旧init并发/UNKNOWN/锁内Settle的断言先立RED。
2. root独立pin候选SQL后创建迁移、实体/Mapper/guard；真实native atomic/unique/old-new races RED→GREEN。
3. operation+canonical+dispatcher；模拟第三方响应丢失，SENDING commit→独立进程kill/restart query同号。
4. currentC/P0 mapping+owner instruction，actualHTTP身份/撤权/同键冲突。
5. 真实cash account/私有媒体owner/permission+offline receipt，原order paid projector无锁内pay调用。
6. source真实operation/receipt、旧managed拒绝、recon与offline零佣金/无分账/退款回归。
7. firstGREEN后最小消融；逐字节还原、同selectorGREEN；最终diff/SQL pin/owned hashes提交root独立review，不自动commit。

## 8. AC→实际计划测试与命令

以下每一项均PLANNED/UNEXECUTED。新类会实际创建，不能用缺类/0运行/skip作通过；native-only条件必须显式放每个具体类。默认Maven正确隔离，显式native缺MySQL配置硬失败。

| AC | 计划准确 selector/断言 |
|---|---|
| 首key永久、同key同payload、跨buyer/actionkey隔离 | PaymentOperationNativeMysqlTest#sameKeyKeepsOriginalPaymentAcrossCacheExpiry；#changedKeyPayloadConflictsWithoutAnotherLedgerRow；真实trade请求表/ledger rowcount/outNo |
| 同key/differentkey/旧新open并发 | #sameAndDifferentKeysShareOneCanonicalAttempt；#legacyAndCheckoutOpenRaceShareOrderGuard；至少双连接barrier，1未终态、1canonical、aliases共原号 |
| SQL真实NULL/别名约束 | PaymentOperationNativeMysqlTest#offlineNullMethodRealmKindCannotPassCheck；#twoPreparedOfflineKeysCanAliasOneReceipt；#legacyHistoricalTenantUsesGlobalOrderGuard，实际MySQL负例/原资金锁域 |
| ledger+mapping原子 | #ledgerAndOperationBindingRollbackTogether；真实tx异常后两边均无/原mapping保持，无fake mapper |
| PREPARED/Open恢复 | #preparedQueryDoesNotDispatch；#openedRequestStillNeedsCurrentConsumerToSend |
| SENDING发前崩溃/响应丢失 | PaymentOperationProcessRestartNativeTest#crashAfterSendingCommitQueriesOriginalNumber；#lostGatewayResponseSurvivesIndependentProcessRestart；不同PID+SQL send_started_at+fault server request log证据 |
| UNKNOWN不close/新号 | PaymentOperationNativeMysqlTest#unknownAndNotFoundNeverOpenAnotherAttempt；#reconNotFoundCannotCloseSendingOperation；false/throw/notFound均原号，gateway send count不增长 |
| 合法新key/NOT_SENT | #newConsumerKeyAfterProvenNotSentMayCreateNextAttempt；原key仍REJECTED，新key新号；late旧ticket不能close成功 |
| callback/query同receipt、金额异常 | #callbackAndQueryReturnOneImmutableReceipt；#mismatchedRealCashCreatesResolutionWithoutAnotherAttempt |
| 真C session/撤权 | CheckoutPaymentAuthenticationFlowTest#realConsumerSessionRequiredForPaymentAndParams；#merchantOrDelegatedIdentityCannotPay；#revokedTokenCannotReplayOrReadParams；#foreignBuyerCannotUseKnownRequestKey，真实HTTP+TokenStore |
| B cash权限/真实account | CheckoutCashPermissionFlowTest#ownerUsesExistingWildcard；#defaultRolesCannotReceiveCash；#explicitGrantAndRevocationApplyOnReplay；#merchantAndConsumerNamespaceResolveActualAccount，实际owner数据 |
| 媒体真实owner/byte/scope | ReceiptEvidenceFlowTest#uploadRecordsActualAccountAndPrivateReceiptType；#foreignStoreActorUrlMissingBytesAndWrongTypeAreRejected；#genericAndConsumerUploadsCannotCreateReceiptAssets；#receiptAssetIsNotPublicOrSignedByMediaGovernance；#sameEvidenceCannotFundAnotherOperation；#receiptTypeCannotBePurgedByExplicitFilterQueuedOrScheduledPath |
| 线下全额/C确认/四层 | OfflineReceiptFlowTest#confirmedOfflineOrderProducesRealCashReceipt；#bankTransferBindsActualPrivateAsset； #noConfirmationSplitAmountOrMixedMethodIsRejected；#originalOfflineReceiptSurvivesLaterDisabledStoreAndDeletedGoods；四层逐一拒绝在quote/place入口，旧原单receipt不重新准入 |
| 原key、换payload、并发 | OfflineReceiptFlowTest#duplicateReturnsOriginalReceiptAndChangedPayloadConflicts；#twoCashKeysCannotCollectTwice，真实ledger而非SQL伪seed成功 |
| 关单晚款 | OfflineReceiptFlowTest#closeWinningAfterReceiptAcceptanceRetainsCashAndLateResolution；#closedOrderRetainsCashAndCreatesResolution；#cancelledOrderFirstReceiptUsesOriginalConfirmedDue；覆盖P0前已关与接受后竞态，真实资金1条、原order终态/1resolution，不改原应收 |
| managed旧shortcut及服务gate | CheckoutManagedLifecycleFlowTest已有oldMerchantOfflineShortcutCannotInventManagedReceipt/sharedOfflineTrustBranchCannotBypassManagedGuard；新增realOnlineAndOfflineReceiptNeverIssueLegacyCodeOrCompleteService |
| source原facts | CheckoutSourceFactsFlowTest#realOperationAndReceiptPreserveOriginalOutTradeNo；#aliasesDoNotDoubleCountCash；unknownReceiptNeverBecomesZeroMoney等原RR/退款/lastpair全部回归 |
| 旧竞态 | PaymentInitiationCashRaceFlowTest#cashConfirmedAfterCashierReadCannotCreateAnotherChargeableAttempt；#legacyOfflineOrderCannotInitiateChannelBeforeDuringOrAfterMerchantConfirmation；PaymentLedgerCloseRaceFlowTest#staleFailureCannotCloseSuccessfulCash，扩增真实nativeguard竞态而保留原H2入口可运行条件 |
| 纯记账 | OfflineReceiptFlowTest#formalOfflineReceiptKeepsZeroCommissionShadowAndNoChannelRecon；既有OfflineSettleFlowTest、OfflinePayFlowTest、OfflinePaySwitchFlowTest |
| noTX IO/真实DS | PaymentOperationNativeMysqlTest#ledgerAndOperationUseOnePayConnection；#gatewayMediaAndReceiptReadsRunWithoutDatabaseTransaction；有支付DS与平台DS不同连接的断言 |

原生runner计划 `CHECKOUT_PAYMENT_TESTS=PaymentOperationNativeMysqlTest,PaymentOperationProcessRestartNativeTest,OfflineReceiptFlowTest,CheckoutPaymentAuthenticationFlowTest,CheckoutCashPermissionFlowTest,ReceiptEvidenceFlowTest node scripts/verify-checkout-payment.mjs`。每批 `MemAvailable>=524288KiB`；`docker --context colima-hxkey-tests`/profile hxkey-tests、cached mysql:9.7.2、`--pull never`、普通DISK64M、loopback随机端口、UUID容器/随机schema/secretenv、finally只清自有UUID。严禁test-on-mysql.sh、停其它容器/改VM/动共享autotest/远程。

runner先验证root pin的V540和冻结V539，建V539结构fixture再执行候选准确V540；从正式源生成最终fresh结构另验证新约束。只当前自有schema，记录基础结构fixture边界，不称全历史迁移replay。原生类全部 concrete `@EnabledIfSystemProperty(named="checkout.test.native",matches="true")`，runner传true与真实MYSQL配置，删本次选择类旧Surefire报告后核fresh/nonzero/tests>0/skips=failures=errors=0；不改全局POM排除/known/checker。

普通/受影响回归计划：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dtest=CheckoutContractTest,PaymentOperationContractTest,PaymentInitiationCashRaceFlowTest,PaymentLedgerCloseRaceFlowTest,PaymentRequestSnapshotTest,PaymentLedgerRefundCallbackTest,OrderPaymentLifecycleWiringFlowTest,OrderPaymentRecoveryWiringFlowTest,OfflinePayFlowTest,OfflinePaySwitchFlowTest,OfflineSettleFlowTest,ZeroAmountPayFlowTest -Dsurefire.failIfNoSpecifiedTests=false
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dtest=ArchitectureTest,PayTxManagerConventionTest,ConditionalBeanWiringTest,BackendI18nParityTest,ErrorCodeUniqueTest,BizRoleSeedTest,BizEndpointPermTest,PaymentLedgerCoverageTest,MediaRefCoverageTest,SchemaParityTest -Dsurefire.failIfNoSpecifiedTests=false
node scripts/check-sql-portability.mjs
node scripts/check-generated-docs.mjs
```

实际selectors实施前 `rg --files/rg -n '@Test|void '`再核一遍，不能凭计划记通过。pay-channel单元/退款/零售餐饮相关selector按真实影响列精确日志（default retail与`-Pfood-embedded`分别说明；standalone JAR归60）。三语/shared静态用已有cached Vitest运行实体/权限/placeholder与新增pay码test，报告Task56§20原8项、架构2项和生成2漂移边界，不把已有债当新改动掩护。

独立进程证据：JUnit parent持有MySQL和独立loopback fault server（仅外部TEST gateway）；ProcessBuilder启动新JVM PaymentOperationProcessMain（实际Spring context、真实trade/pay mapper+TokenStore）；child1发当前C HTTP并将精确PID/operation/outNo checkpoint发到parent。parent在检测SQL SENDING已提交且faultserver收到/或放行前按案例kill child1；同database/env启动child2，必须PID不同、没有复用bean/cache。child2从真实原key查询，faultserver保留跨process第三方状态/查询日志，断言仅原outNo，不能增加send/open；收到真实SUCCESS时原receiptNo+order投影/源行稳定。发前崩溃案例只能UNKNOWN，不能把notFound改成功。进程kill只能自有PID；secrets不入命令行/log。

最小消融选择：只移除 `claimSend` 的 `send_started_at IS NULL/phase OPENED` CAS防线（精确最小行），运行 #unknownAndNotFoundNeverOpenAnotherAttempt 或独立进程lost-response case，必须出现同原号第二prepay次数的行为RED；恢复保存的完整文件字节SHA后同selectorGREEN。若最小改动尚被另一正确防线拦住，记录消融未命中而选择真实guard原子性最小防线；不得把setup/编译失败当消融。firstRED、firstGREEN、消融RED、逐字节恢复GREEN分别记录。

## 9. root技术review关注与未验证边界

1. root确认 canonical/alias + receipt_no在权威ledger的相较绑定草案最小字段扩充；独立核V540文本/actualmax并pin之后才实施。
2. root确认专用OFFLINE_RECEIPT永久保留、单证据单笔全额、实际account上传与操作者一致的范围；不是银行真实性证明。已获得方向批准，最终字段/DDL仍待审。
3. 当前QueryResult没有确定terminal unpaid语义：只支持确证NOT_SENT后新Ckey重试；其余UNKNOWN保守原号查询。本任务不宣称可恢复所有外部渠道无回执失败为可重付。
4. 真实当前 projector provider 已在§4审计，支付receipt读取需搬锁外；INVENTORY v1仍既有独立库存事务，不在本次声称跨库原子。旧ledger是全局order身份而非已隔离多tenant，guard须对应真实身份，root核对该兼容选择。
5. 新endpoint/媒体用途/跨模块架构测试和生成物实际文件清单需代码落地时逐项对账；设计阶段没有执行任何测试、native、SQL、gateway或部署。Task60既有债、整BM04真实JAR、美业绑定/消费者wire仍未交付。
