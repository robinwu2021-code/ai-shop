# 餐饮系统独立支付、库存合同核验

> 状态：NEXT-07 源码核验完成；独立交易验收阻塞 · 2026-10-20。
> 核验基线：`ec5aba9a5`，餐饮分支 `codex/food-next01`。本轮不新增接口、配置、迁移或运行时适配器。
> [三端与独立服务方案](./TDD-餐饮系统-三端API与独立服务对齐.md) · [M0任务](./TDD-餐饮系统-M0基础工程与接入方案.md) · [工程实施收据](../../backend-plugins/shop-industry-food/README.md)。

## §0 结论与需求对账

餐饮继续复用商品与订单能力，订单仍是资金和库存写入的唯一编排入口。现有独立 PAY 已有费率、发票及大量权益/储值/迁移内部合同，但普通订单的收款、查单、关单、退款尚未通过当前 SettlePort 桥接远程化。库存已有独立数据源与事务、本地预留/履约/恢复实现；未在本次仓库范围内找到承载完整 StockPort 协议的独立库存启动模块、内部 HTTP 合同与客户端装配。

因此，两种餐饮部署形态的**正式下单、收退款营业门槛均未通过**。这不阻塞无资金、无库存副作用的营业策略读取和桌区配置切片；不能据此开放先吃后付、制作扣料或收银。

| 需求/AC | 当前证据 | 判定与去向 |
|---|---|---|
| ALIGN-04 / PAY-01：独立收款与 UNKNOWN 恢复 | 有统一 HTTP 客户端和部分 PAY 端点；普通收款桥接仍直连本地 | 远程业务验收未完成；PAY-01A/B、RET-01A/B |
| ALIGN-05 / CORE-01：独立库存预留至释放/出库 | 本地 InventoryStockPort + 独立 invTransactionManager；无已核验的完整远程链 | CORE-01B/C、RET-01C |
| CORE-02：先吃后付 | 订单冻结 lifecycleVersion 已影响库存动作；不等于未付款的餐饮受理合同 | CORE-02A，不能把 unpaid 当作 allocatePaid |
| ALIGN-06：统一服务寻址 | ServiceName 常量为 PLATFORM/PAY/JOB；ConfigServiceLocator 支持按字符串配置查找 | FOOD/INVENTORY 常量及 typed 合同接入待 CORE-01D |
| 餐饮不复制资金/库存真源 | 本轮只读核验，没有创建 Remote 桩或直接扣账 | 保留现有订单唯一写入者边界 |

这里的“阻塞”指**交易验收条件不满足**，不是认定外部服务一定未部署。本轮未登录部署机器、未探测真实服务、未读取生产环境文件或调用支付/库存写操作。

## §1 当前调用链与源码证据

### 1.1 支付：本地实现与已有远程能力分开看

- [SettlePort](../../backend/shop-base/src/main/java/ai/neargo/shop/spi/settle/SettlePort.java) 是当前订单使用的 SPI；[SettlePortImpl](../../backend/shop-app/src/main/java/ai/neargo/shop/paybridge/port/SettlePortImpl.java) 直接持有 PaymentLedgerService、SettleService、SettleGenerationOrchestrator、支付网关及退款相关本地服务，并没有切成 PayInternalApi 代理。
- [PayClientConfig](../../backend/shop-app/src/main/java/ai/neargo/shop/payclient/PayClientConfig.java) 只在 `shop.pay.deployment=standalone` 时创建客户端；它不能自动改变上述 SettlePortImpl 的方法实现。
- [PayInternalApi](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/client/PayInternalApi.java) 和 [路径常量](../../backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/client/PayInternalPaths.java) 已含费率/发票、权益授予/核销/退回/查询、储值充值/查询、历史券迁移等。不能把“普通收退款缺口”写成“PAY 只有费率接口”。
- `entPaymentAttempts` 与 [RemotePaymentAttemptFactsPort](../../backend/shop-app/src/main/java/ai/neargo/shop/payclient/port/RemotePaymentAttemptFactsPort.java) 查询的是权益上下文下的订单支付尝试事实，不是普通收款发起/查单/关单/退款执行接口。
- [PendingRemotePortsConfig](../../backend/pay/pay-svc/src/main/java/ai/neargo/shop/pay/svc/PendingRemotePortsConfig.java) 仍为 MerchantQueryPort、SettleSourcePort、RefundSplitBackPort、StoreDirectoryPort 等注册“调用即抛”的待接 Port。不能用 pay-svc 可启动替代这些业务事实合同的完成证明；已有权益专用核心客户端也不能自动覆盖普通结算所需事实。

### 1.2 库存：独立库不等于跨进程合同

- [InventoryStockPort](../../backend/shop-app/src/main/java/ai/neargo/shop/invbridge/port/InventoryStockPort.java) 在 `stock-authority=INVENTORY` 时提供主实现，直接调用 ReservationService、StockCountService、InventoryAclService、LocationService 及恢复/收货适配。
- [InventoryDataSourceConfig](../../backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/config/InventoryDataSourceConfig.java) 已提供独立数据源、事务与迁移历史；本地数据库隔离不是 HTTP 跨进程的证据。
- [ReservationService](../../backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/service/ReservationService.java) 有本地预留、提交、分配、出库、释放、取消墓碑和到期恢复能力，但没有对外的“按原预留引用查询完整结果”方法；实现的 `find` 是私有方法。
- [OpenInventoryController](../../backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/api/open/OpenInventoryController.java) 只有 `GET /open/v1/items`、`GET /open/v1/stock-ledger`、`POST /open/v1/stock:sync`。它使用 X-App-Key/X-App-Secret 和 read/stock:sync 权限，不能拿盘点同步冒充订单预留、释放或原路恢复，也不能直接换成内部服务密钥调用。

### 1.3 环境资料的证据等级

| 资料 | 已能证明 | 不能证明 |
|---|---|---|
| [PAY application.yml](../../backend/pay/pay-svc/src/main/resources/application.yml) | 独立启动、默认端口8083、沿PAY_DB_*接库、默认关闭主库Flyway、PLATFORM内部目标键 | 当前实际实例、版本、密钥、支付通道及核心事实Port可用 |
| [systemd模板](../../deploy/tencent/systemd/ai-shop-pay.service)、[部署说明](../../deploy/tencent/README.md) | 仓库存在PAY服务安装/部署入口 | 机器已部署该基线或普通交易已进入PAY；“只承接费率”注释属于历史范围，不能覆盖新权益/储值源码 |
| [smoke-boot.sh](../../scripts/smoke-boot.sh) | 用隔离/不可连接数据库验装配的脚本 | 真数据库可用、真实资金/库存闭环；脚本自身明确不验数据库连通 |
| 本次库存文件/模块检索 | 未找到完整独立库存内部客户端、服务启动模块及部署模板 | 外部环境绝对不存在库存进程或其他仓库协议 |

补验资料必须包含实例/构件版本、提供方合同版本、隔离库、通道沙箱、配置形态与追踪证据；仅提供URL或健康检查200不足以解除交易门禁。

## §2 逐项合同、输入输出与缺口

以下签名是**当前源码**，不是已存在的 HTTP API。远程扩展只列待评审语义，不在本轮编造路径或写占位 Remote 类。

### 2.1 支付能力矩阵

| 编号/动作 | 当前输入 → 输出 | 当前实现/幂等与恢复 | 缺口与责任 |
|---|---|---|---|
| P01 发起收款 | `openPayment(PaymentOpen) → String`；`initPayment(PaymentOpen) → PayInitResult` | 本地PaymentLedger.open后网关prepay；复用未终态实际outTradeNo，失败/关闭后可换后缀新号 | 普通收款typed远程受理及按原指令查回缺失；PAY-01A |
| P02 查询收款证据 | `successPayment(orderNo[,channel,tradeNo])`、`paymentByOutTradeNo(outTradeNo) → PaymentReceipt` | 本地已记录的成功/支付证据；不是“向通道发起查单”的通用远程入口 | 原指令、金额币种/归属校验，通道查询与本地证据分层返回；PAY-01A/B |
| P03 关闭未付收款 | SettlePort没有close；`PaymentLedgerService.close(outTradeNo,reason) → void` | 本地账本关闭；PayGateway当前也无通用close方法 | 不得把数据库CLOSED当成通道已关单；必须先设计查询/关单/回调竞争协议；PAY-01A |
| P04 支付成功投影 | `settlePayment(PaymentSettled) → orderNo` | 本地支付先记权威成功，订单随后投影；不能由客户端宣布成功 | PAY证据/回调、核心幂等消费及差异查询跨进程闭环；PAY-01B |
| P05 结算/分账回退 | `generateForOrder(orderNo) → int`；`reverseSplit(...)`等返回boolean | 本地编排需核心子单/商家快照；prepared/confirm/cancel回退依赖原售后准备 | 核心事实及PAY准备/确认/取消合同、回退成功后才退款；RET-01A |
| P06 发起/查回退款 | `requestRefund(RefundRequest)`、`queryRefundExecution(executionNo / RefundRequest) → RefundDispatchResult` | 本地RefundExecutionService已区分ACCEPTED/SUCCEEDED/FAILED/UNKNOWN；强查回重核原请求 | PayInternalApi未暴露对应普通退款合同；未知不能换键重发；RET-01B |
| P07 退款准备/派发 | `prepareRefund(subOrderNo,afterSaleNo,amountMinor,reason) → paymentNo`；`dispatchRefund(paymentNo)`、`refreshRefund(paymentNo) → void` | 原本地准备后提交，再在事务外派发/查回；旧无afterSaleNo的refund重载只能一子单一次 | 不复用旧重载承担多次部分退餐；跨域准备/派发/恢复状态须审定；RET-01A/B |
| P08 退款确认证据 | `settleRefund(outRefundNo,channel,amount,currency,provider) → RefundDispatchResult`；旧3参重载返回orderNo；`refundEvidence(paymentNo) → RefundEvidence` | 核对原支付/退款指令；原服务可投影恢复，返回编号不代表资金成功 | 冻结金额/币种/原渠道、已退上限、原售后证据联通；RET-01B |
| P09 原款/币种/存在性 | `refundOrigin(orderNo,tradeNo,payChannel) → PaymentReceipt`；`paymentCurrency(orderNo) → String`；`hasRefundForAfterSale(afterSaleNo) → boolean` | 本地查询，已有现金意图可阻止错误撤销准备 | 独立PAY权威查询与原指令身份绑定；RET-01B |

**主要对象字段，保持现有语义：**

- `PaymentOpen(orderNo,userNo,entityNo,payChannel,amountMinor,subject,currency,stores)`；stores为`PaymentStore(subOrderNo,route)`。`PaymentRoute`字段为merchantNo/storeNo/payMerchantNo/channel/subMerchantId/accountVersion/fundsPath。多商家付款时entityNo可空，必须按冻结stores路由逐项核对，不能从当前商家配置重建原收款归属。
- `PayInitResult(success,outTradeNo,payChannel,payParams,message)`；用实际返回的outTradeNo唤起支付，不用调用前猜测的编号。
- `PaymentSettled(outTradeNo,payChannel,tradeNo,amountMinor,succeededAt,currency)`；`PaymentReceipt(paymentNo,orderNo,userNo,payChannel,tradeNo,amountMinor,currency,status)`。
- [RefundRequest](../../backend/shop-base/src/main/java/ai/neargo/shop/spi/settle/RefundRequest.java)：executionNo/afterSaleNo/subOrderNo/originPaymentNo/amountMinor/currency/reason；[RefundDispatchResult](../../backend/shop-base/src/main/java/ai/neargo/shop/spi/settle/RefundDispatchResult.java)：executionNo/paymentNo/status/errorCode/retryable/succeededAt。
- `SplitReversal(subOrderNo,afterSaleNo,goodsMinor,freightMinor,goodsPaidMinor)`；RefundEvidence还包含direction、原售后/子单/用户、currency/amountMinor/status、verifiedMinor/verifiedAt/providerNo及RefundSafety。

没有通用独立收款合同前，不由餐饮Controller直接拼PayGateway调用；收款受理成功、通道唤起参数、支付资金成功是不同证据。AA/多次结账必须为每个获准结账份额绑定核心支付指令与金额上限，不能用整台总额或客户端金额代替。

### 2.2 库存能力矩阵

| 编号/动作 | 当前 StockPort 输入 → 输出 | 本地桥接事实 | 远程验收缺口/责任 |
|---|---|---|---|
| I01 预留 | `lock(lockNo,List<SkuQty>) → List<String>`不足SKU | InventoryStockPort在inv事务按owner分组reserve；通常成功空列表，短缺抛业务异常，不能吞异常转空列表 | typed全成/全败/未知结果、原引用查询、库存权威侧跨店SKU校验；CORE-01B |
| I02 普通释放/取消 | `release(lockNo) / release(lockNo,originalNeeds) → void` | 无行版releaseByRef；有行版按owner调用cancelByRef，能先落墓碑拒绝迟到reserve | 不能只用无行释放模拟取消；保留冻结needs与原引用、释放结果/查回；CORE-01B/C |
| I03 V1付款确认 | `confirm(lockNo)`；`confirm(lockNo,lifecycleVersion)` | V1走commitByRef，reserved转实扣并生成SALE出库单 | 可重复调用的结果证据/出库单回执，不能仅凭void返回推断跨进程完成；CORE-01B |
| I04 V2已付分配 | `confirm(lockNo,2)`；`allocatePaid(lockNo) → void` | confirm(2)经平台Outbox发ALLOCATE；allocatePaid实际调用reserve服务，保持实物占用到交接 | 事件/消费方跨进程与权威确认、重复/乱序恢复；CORE-01C；后付另见CORE-02A |
| I05 交接出库 | `issue(fulfillmentRef[,orderNo]) → void` | 本地按履约引用出库；两参默认转一参，当前InventoryStockPort覆写一参 | 原履约行、订单归属、原出库回执；厨房制作不能任意当已交付；CORE-01C |
| I06 已付未出库释放 | `releaseAllocated(fulfillmentRef[,orderNo]) → void` | 本地释放原已付分配，不产生实物入库 | 退款成功/撤销授权、状态竞争、原引用查回；RET-01C |
| I07 原路恢复 | `restoreOriginal(purposeNo,originalLockNo,items) → RestoreResult` | OriginalInventoryRestoreAdapter翻译SKU；原账恢复服务定位原过账，已有completed查询但未暴露为完整StockPort查询 | 冻结purpose/原引用/行，COMPLETED/PENDING及原路回执查询远程化；RET-01C |
| I08 实物退货收货 | `receiveReturn(receiptNo,afterSaleNo,originalOrderNo,fulfillmentRef,List<ReturnLine>) → void`；`returnReceiptStatus(receiptNo) → String` | ReturnReceiptService按原收货号记质量SALEABLE/UNSALEABLE，完成后可查状态 | 收货与退款分轨、拒绝异参重放、退餐报损不自动变可售；RET-01C |
| I09 兼容恢复 | `restore(restoreNo,items) → void` | 走本地reserve恢复；不是退款回调的一概加库存 | 新退餐走可核验原路/收货语义，不回退盲目加库存；RET-01C |
| I10 可售查询 | `sellable(skuNo[,storeNo]) → int` | 当前InventoryStockPort未覆写，继承StockPort的MAX_VALUE兼容行为 | 不能宣称已查到库存；门店有效可售量/停售限量分开，CORE-01B |
| I11 盘点调数 | `setOnHand(skuNo,storeNo,onHand,reason) → void` | 本地StockCountService.adjustOne，落盘点单 | 不作为下单占用/取消接口；餐饮不直接改库存余额 |

`SkuQty(skuNo,int qty,storeNo,fulfillmentRef,int lifecycleVersion)`；三个/两个参数兼容构造默认生命周期1。`ReservationService.Line(itemId,locationId,int qty,fulfillmentRef)`；ReturnLine为skuNo/int qty/quality，底层收货Line为itemId/int qty/quality。主体与库位从原SKU/门店事实核对，不能由客户端把qty/store直接当可信库存坐标。

### 2.3 容易被默认值掩盖的合同差异

1. [StockPort](../../backend/shop-base/src/main/java/ai/neargo/shop/spi/product/StockPort.java) 的allocatePaid、issue、releaseAllocated默认可空执行；原路恢复/收货默认抛UnsupportedOperationException；returnReceiptStatus默认NOT_TRACKED；sellable默认MAX_VALUE。新远程实现必须逐项覆写并验收，不能把默认值当“成功”或“库存无限”。
2. [ReservationServiceImpl.reserve](../../backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/service/impl/ReservationServiceImpl.java) 在同owner/externalRef命中既有有效预留时直接回原reservationId，没有先比较全部lines/qty/lifecycleVersion；本地幂等不等于远程同键异参已经拒绝。CORE-01B须冻结/校验请求摘要，同时验证同键并发，不能只靠前端不改参。
3. 跨owner的lock目前依赖同一inv事务保证整批全成或全败；远程客户端逐owner调用会改变这个保证。须由权威侧批量原子受理，或另行评审订单持久化意图的分组恢复合同，不能用普通for循环代替。
4. 当前StockPort的数量是int。餐饮称重菜、配料组合及BOM扣料的BigDecimal/基础单位转换、精度与损耗没有在该交易接口中表达；不得静默取整。CORE-02A要指定单位和精度合同，既有整数份数能力可先按受支持范围交付。
5. lifecycleVersion=2表示已付分配与交接出库分离，不自动证明“先吃后付”已支持。未收款受理、信用/押金/额度与释放时点必须有对应核心授权和库存承诺；不能直接调用allocatePaid假装支付已完成。

## §3 可评审的权威域子任务

以下编号是在原CORE/PAY/RET任务下拆分的实施候选，不是已发布API或新增运行配置。每个任务先确定typed请求/结果、幂等及查回再写适配器；餐饮只消费结果。

| 子任务/权威方 | 修改范围与产出 | 完成标准 |
|---|---|---|
| CORE-01A 核心订单 | 既有建单/支付/库存调用编排与food内部建单合同，冻结主体/门店/原行/生命周期；原意图恢复 | 只有核心触发资金/库存一次；同批点单失败、重试、重启能查原意图，不重建第二单 |
| CORE-01B 库存提供方+核心invbridge | inventory内部typed预留/取消/结果查询/可售，原StockPort远程装配；同键异参、批量原子性、幂等墓碑 | 独立进销存原库余额和流水证明预留一次、取消后迟到预留拒绝；不会继承默认no-op/无限量 |
| CORE-01C 库存提供方+核心履约 | V1确认、V2分配、原履约出库/释放、事件回执/查回 | 重复/乱序事件与重启不重复扣货；明确制作/交接时点与paid条件 |
| CORE-01D 平台服务基础+提供方 | ServiceName/现有ServiceLocator接入、INVENTORY独立启动/协议、food受控内部服务；沿InternalHttp密钥与错误分类 | 两个餐饮部署形态都寻址真实PAY/库存；无地址/密钥明确失败，无本地业务实现回退 |
| PAY-01A 支付提供方+payclient/paybridge | 普通收款受理、原指令查询、关单typed内部合同；SettlePort对应方法显式路由 | 超时/重启按原键恢复；返回实际outTradeNo，通道关单与账本关闭分清，查款/回调竞争有证据 |
| PAY-01B PAY+核心投影 | 可信支付成功/金额币种/原收款归属证据、重复回调/查询投影恢复 | 不信客户端付款结果；PAY成功而核心未投影可恢复，核心不可先认付款成功 |
| RET-01A 核心售后+PAY结算 | 分账/本地债务回退、退款prepare/confirm/cancel与原售后源事实远程合同 | 回退未确认不退资金；准备不可错误撤销；保留既有顺序和安全上限 |
| RET-01B PAY退款+核心退款执行 | 普通退款request/query/confirm、原款/evidence/safety查询、UNKNOWN和部分退餐 | 原execution/售后号不变；重复请求不多退、异参拒绝，核验原币种/累计已退与原渠道 |
| RET-01C 库存退货+核心售后 | 原路恢复/收货/结果查询、质量与报损、原履约账定位 | 仅退款不加库存；实物收货独立于现金成功；相同receipt/purpose只记一次 |
| CORE-02A 核心+库存+餐饮 | 后付受理承诺/押金/信用授权、制作扣料/BOM、称重精度与库存生命周期合同 | 场景未验前能力关闭；后付不能冒充已付分配，厨房报损/退餐有独立库存语义 |

本轮不调整现有stock-authority含义，不发明已存在的inventory.deployment属性，不扩展Source枚举或新造餐饮支付账。库存主账仍由进销存维护，正式资金账仍由PAY维护，平台订单负责权威编排和投影。

## §4 独立合同验收与恢复步骤

### 4.1 必需的实际组合

同时验 `food embedded + PAY standalone + inventory independent` 与 `food standalone + PAY standalone + inventory independent`。记录构件版本、目标实例、合同版本、数据库/事务身份与事件/流水号，不记录密钥。库隔离验证不能只看配置名；至少从一次操作的原业务引用核对权威库记录。

[InternalHttp](../../backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalHttp.java) 复用3秒连接、调用方读超时、X-Internal-Token及不记body的基础设施；[CallOutcome](../../backend/svc-client/src/main/java/ai/neargo/svc/client/CallOutcome.java) 仅为OK/NOT_CONFIGURED/UNREACHABLE/TIMEOUT/REMOTE_ERROR。**传输分类不是业务执行结果**：超时、丢响应甚至不可达重试都先按原操作键查权威意图，查不到不自动推断未执行；没有业务幂等/查询合同的写操作禁止自动重试。

### 4.2 挂起的真实合同测试

| 待实施测试 | 前置门槛/本轮判定 | 必须观察的实际结果 |
|---|---|---|
| `IndependentPaymentContractTest#recoverUnknownPaymentByOriginalKey` | PAY-01A/B的普通收款合同、核心事实Port、实际PAY沙箱服务及隔离账库。当前 **BLOCKED_CONTRACT_AND_ENVIRONMENT**，未创建空类或mock通过 | 发送一次冻结指令，提供方受理后丢响应；恢复进程按同key/原实际outTradeNo查回，账本和通道只有一笔，核心最终投影一次；改金额/路由用同key拒绝 |
| `IndependentInventoryContractTest#reserveOnceAndReleaseAfterCancellation` | CORE-01B/C独立服务、取消墓碑和查询协议及隔离库存库。当前 **BLOCKED_CONTRACT_AND_ENVIRONMENT**，未创建空类或mock通过 | 初始现存/可用量已知；同key并发与超时查回只占一次；取消成功后恢复余额；延迟reserve不能重新占用；确认/出库/退货原引用可核对 |
| 后续退款UNKNOWN与原路恢复测试 | RET-01A/B/C及核心源准备协议；尚未验收 | 退款未知不换键再发；原收款/金额/币种一致；同receipt/purpose只产生一份对应恢复或收货，不用退款成功自动加库存 |
| 后付/称重/制作扣料测试 | CORE-02A待审定typed合同；尚未验收 | 先吃后付/先付后吃的承诺、分配、制作及交接时点分别正确，单位精度不丢失 |

没有真实合同/可核验提供方时，保留上述阻塞即可推进无交易配置；不能通过HTTP代理的费率/发票测试、StockPort mock调用次数或健康检查替代这些断言。

## §5 本轮验证记录

- 仓库范围：`backend/shop-base`、`shop-app/paybridge/payclient/invbridge`、`pay/pay-domain/client`、`pay/pay-svc`、`shop-inventory`、`svc-client`、`deploy`与smoke脚本。未在该范围找到独立库存预留/查回HTTP合同；结论不延伸为外部环境不存在。
- 执行既有传输基线：`JAVA_HOME=<JDK21> mvn -o -f backend/pom.xml -pl pay/pay-svc -am test -Dtest=PayInternalApiRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false`。
- 结果：5项通过，0失败/错误/跳过。该类是真HTTP随机端口与令牌/响应合同，但FeeRuleService/SettleInvoiceService使用MockitoBean，**仅证明费率/发票传输，不证明普通收退款独立联通**。
- 未编写或用mock关闭两个Independent*业务验收；未访问共享数据库、未调用真实通道或库存写接口、未执行部署/迁移。本轮仅文档，无需重新运行所有餐饮或全backend测试；NEXT-03已知独立schema回归问题不在本轮修复。

## §6 实施回填与后续顺序

本轮产出本核验册、三端对齐摘要、M0任务状态和工程收据；不修改核心业务代码。NEXT-07的“核验/拆任务”完成，不等于ALIGN-04/05远程业务验收通过。

下一步按NEXT-08选桌区首写所需的营业治理、审计与权限小切片，再进入NEXT-09桌区API092～095；这些配置切片不等待资金合同。餐饮正式下单/收银/退餐则等待本册对应CORE/PAY/RET子任务及真实独立合同通过。NEXT-05已发现的主库request_hash与新版Outbox前置缺项仍须由平台原发布流程处理，本轮未升级。
