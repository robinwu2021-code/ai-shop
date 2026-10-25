# TDD-支付服务-I1存量缺陷

状态：实施中
档位：1 · 依据：[开发计划 · 支付服务](./开发计划-支付服务.md) §5 I1 · [代码 Review](./支付服务-代码Review-2026-10-24.md) R01–R32 · [任务表](./任务表-支付服务.md) F01–F15
创建：2026-10-25 · 最后更新：2026-10-25

修 CN 现有支付链路上「钱打错、记错」的 S1 级缺陷。**每条先写会变红的测试，修复后变绿，再撤修复做一次消融。**
不引入新模型（支付单、流水等在 I3）；迁移放 `db/pay`（I0 立的独立目录）。

## §0 对账一 · 需求 → 设计

| AC | 缺陷（Review） | 验收（Given / When / Then） | 落点 |
|---|---|---|---|
| F01 | R01 | Given 主体同时有微信、支付宝收款号 When 支付宝订单生成结算单 Then 快照支付宝号；兜底号有确定顺序、不取别店专属号 | `MerchantQueryPort#payMerchantNoOf(merchantNo, storeNo, channel)` |
| F02 | R02 · R26 | Given 同一门店 × 渠道 When 解析能力 / 额度 / 路由 Then 三处结果一致；门店换主体后不取旧主体的号 | `MerchantPortImpl` 内收口 |
| F03 | R03 | Given A 店专属号 When 绑给 B 店 Then 拒绝 | `StoreAdminServiceImpl#setPayment` |
| F04 | R04 | Given 同一主体的主体级与门店级进件 When 提交 Then 外部单号不同 | 进件网关 `out_request_no` |
| F05 | R05 · R06 · R23 | 未接通渠道进件失败（不回落 STUB）；APPLYING 单再提被拒；重提后驳回原因清空 | `MerchantPaymentServiceImpl` |
| F06 | R24 · R25 | 并发 10 次额度累加结果正确；支付宝进件回查带申请单号 | `accruePayQuota` · `AlipayApplymentGateway` |
| F07 | R07 | 并发两次「去支付」只有一行 `out_trade_no` | `db/pay` V1 唯一键 · `PaymentLedgerServiceImpl#open` |
| F08 | R08 | 子商户模式退款带上原交易的子商户号 | `db/pay` V2 · 收款写入、退款读取 |
| F09 | R09 | 出款金额不符不勾平 | `PayoutReconAxis` |
| F10 | R10 | 受限账号经 Local / Remote 看到的发票一致 | `RemoteOpsSettleInvoiceAppService` · pay-svc |
| F11 | R11 · R12 | 内部令牌常量时间比较；生产 pay-svc 停用或只读 | `InternalPayEndpoint` · 部署 |
| F12 | R13 · R14 | 开支付宝开关应用能起；收付通退款回调回查成功 | 支付宝客户端 bean · `WechatPayGateway` |
| F13 | R15 · R32 | 回调报文落 `payment_no`；退款回调地址不拼接 | 回调控制器 · `WechatDirectPayGateway` |
| F14 | R22 | 改通道市场中途失败不留空集；空集被拒 | `PayChannelMasterServiceImpl` |
| F15 | R19 | 同一异常第二天不新增差异单；`resolution` 只放结论 | `ReconServiceImpl` · `db/pay` V3 |

## §1 现状与影响面

- 收款号解析在 `shop-merchant/.../MerchantPortImpl`（`payMerchantNoOf` / `paymentRouteOf` / `resolvePayment` / `activeChannelsOf`），唯一调用方 `SettleGenerationOrchestrator`。
- **测试环境渠道**（实测）：测试订单的支付渠道是 `TEST`（`sys_pay_channel` 测试渠道，生产种子停用），stub 网关是 `STUB`；
  测试进件写 `WECHAT`，靠 R05 的 STUB 回落「进件成功」。STUB 不是主数据渠道，测试进件**不能**改用它（初稿的设想作废）。
- 不受影响：端上接口、权限码、i18n。

## §2 方案

### F01 · 按渠道解析收款号
- **契约**：`MerchantQueryPort#payMerchantNoOf(merchantNo, storeNo)` → `payMerchantNoOf(merchantNo, storeNo, channel)`（唯一实现 `MerchantPortImpl`，唯一调用方 `SettleGenerationOrchestrator` 传 `src.payChannel()`）。
- 规则：门店显式配了且同渠道 ACTIVE → 用它；否则只在**同渠道的主体级号与本店专属号**里取（默认门店的号优先，再按 id 升序），别店专属号永不参与兜底。
- `CHANNEL_FREE_PAY_CHANNELS = {OFFLINE, FREE, STUB, TEST}` 与空渠道不按渠道过滤（钱不经真实通道），其余规则相同。

### F05 · 进件不回落、在途不重提、原因真清空
- 删 `gateway()` 的 STUB 回落；改为构造时由 STUB 显式替身 `shop.pay.stub-channels`（新配置项，默认 `WECHAT,ALIPAY`）点名的渠道，没点名的照样 `PAY_CHANNEL_UNAVAILABLE`。
- `submit`：`APPLYING` 且 `channelApplyNo` 非空（已发通道）→ 拒；入驻占位（也叫 APPLYING、但没发过）不受影响。
- `submit`：驳回原因改为 `clearRejectReason`（显式 `set(null)`），回查路径早已如此。
- 测试支撑：`StubApplymentGateway#keepApplyingFor`（与既有 `rejectWithoutReasonFor` 同类的测试钩子）。

### F02 · 门店 × 渠道 → 收款记录只有一处解析
- `MerchantPortImpl#accountOf(merchantNo, storeNo, channel, activeOnly)`：门店显式绑定的号 → 本店专属号 → 默认门店绑定的号 → 主体级号（同档 id 升序）；别店专属号不参与；渠道规则同 F01。
- 结算取号（`payMerchantNoOf`，`activeOnly=true`）、能力（`payCapabilityOf`，渠道未知传 null）、额度（`accruePayQuota`）三处都走它；能力 / 额度沿用不限状态的原口径。
- **契约**：`MerchantAdminPort#accruePayQuota` 加 `channel` 参数，唯一调用方 `OrderServiceImpl` 传 `order.getPayChannel()`。
- R26：`MerchantGovernServiceImpl#activePayMerchantNo` 查本店专属号时加主体条件。
- `activeChannelsOf` 本就按「主体级 + 本店」过滤，不改。

### F03 · 门店只能绑「主体级号或本店专属号」
- `StoreAdminServiceImpl#setPayment`：在原「本主体 ACTIVE」校验之外，号若是某家门店的专属号（`store_no` 非空），只能绑给那家店。
- 渠道维度不校验：`mch_store.pay_merchant_no` 一店一列、不分渠道，绑定时没有目标渠道可比（按渠道取号由 F01 在解析侧保证）。

### F04 · 进件外部单号每单唯一
- **契约**：`PayApplymentGateway.SubmitCommand` 加 `storeNo`、`requestNo`。`requestNo = 主体 + 门店（主体级记 E）+ 提交时刻`，由进件服务生成，重提也换新号。
- 微信 `out_request_no`、支付宝 `out_biz_no` 改用 `requestNo`；缺了直接抛 `ChannelException`，不退回主体编号。stub 的申请单号同样按 `requestNo`（原先也按主体编号，同一类撞单）。

### F06 · 额度并发累加、支付宝回查带单号
- `MerchantPortImpl#accruePayQuota`：改为一条原子 `UPDATE … SET quota_used_minor = CASE WHEN quota_period = ? THEN quota_used_minor + ? ELSE ? END, quota_period = ?, version = version + 1 WHERE id = ?`，影响行数 ≠ 1 即抛（让回调重推，不静默少记）。不用「读改写 + 重试」：常在回调事务里调，REPEATABLE READ 下重读仍是旧快照。
- `AbstractApplymentGateway` 加 `buildQuery(channelApplyNo)`（默认空），`AlipayApplymentGateway` 覆写为 `{order_id: …}`。

### F07 · 同一订单并发「去支付」只落一行
- **迁移** `db/pay/V1__stl_payment_out_trade_unique.sql`：`stl_payment` 加 `UNIQUE (out_trade_no)`。上线前置：执行方案 Q1 查存量重复为 0。
- `PaymentLedgerServiceImpl#open`：插入撞唯一键 → `lockPayment`（加锁读，REPEATABLE READ 下普通读看不到对方刚提交的行）读回 → 同一套快照比对 → 返回同一单号。
- 尝试序号只数终态（不含 INIT / PENDING）：否则并发一方把对方的在途行数进去，算出「订单号-2」绕开唯一键（并发测试第 5 轮实测抓到）。
- **测试建表**：`gen-test-schema.py` 与 `SchemaDriftTest` 接上 `db/pay`（在平台迁移之后重放，与生产执行顺序一致）。

### F08 · 退款带原交易的子商户号
- **迁移** `db/pay/V2__stl_payment_sub_merchant.sql`：`stl_payment` 加 `sub_merchant_id`、`pay_merchant_no`（两条 ALTER 分写）。
- `DirectAccounts#of(PaymentOpen)`：DIRECT 取第一家门店的路由、AGGREGATED 为空 —— 下单（`OrdinaryPaymentInitiationServiceImpl`）、开单落库（`PaymentLedgerServiceImpl#open`）、退款（`RefundExecutionServiceImpl`）同一条规则。
- 退款 `TxContext.subMchId`：先取原交易的列，存量行为空时回退到原交易请求快照（`PaymentLedgerService#paymentRequest`）。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| F01 | `StoreSettleFlowTest#resolvesByChannel` · `#fallbackNeverBorrowsAnotherStoresAccount` · `#offlineKeepsEntityDefaultDeterministically` | ✅ 11/11 | 先红：旧实现问微信号返回了支付宝号（无排序）、A 店取到 B 店专属号。消融去渠道过滤 / 去门店过滤 → 前两条红 ✅ |
| F02 | `StoreSettleFlowTest#capabilityFollowsTheSameAccountAsSettlement` · `#storeMovedToAnotherEntityDropsOldEntitysAccount` | ✅ 14/14（另 9 个相关类全绿） | 先红：门店绑微信号，能力读到支付宝行（`["APP"]`）；换主体后读到旧主体的号。注：能力那条初版在旧代码上是绿的（H2 按唯一索引让 LIMIT 1 碰巧命中绑定的号），把绑定换成微信后才量到 ✅ |
| F03 | `StoreSettleFlowTest#cannotBindAnotherStoresAccount` | ✅ 12/12 | 先红：B 店专属号绑给 A 店 code=0；撤掉新校验即回到该状态 ✅ |
| F04 | `ApplymentGatewayTest#outRequestNoIsPerApplyment` | ✅ 9/9 | 先红：字段已加、网关仍发主体编号时 `expected "E-1-E-100" but was "E-1"` ✅ |
| F06 | `MicroPayCapabilityFlowTest#concurrentAccrualsAllLand` · `ApplymentGatewayTest#alipayQueryUsesDifferentApi`（加断言） | ✅ 9/9 · 9/9 | 先红：10 个并发累加只落 1 笔（`expected 10000 but was 1000`）；支付宝查询参数为空 ✅ |
| F07 | `PaymentOpenConcurrencyTest#concurrentOpenYieldsOneRow`（@RepeatedTest 20，4 线程） | ✅ 20/20；`PaymentRequestSnapshotTest` 等 5 类全绿 | 先红：只有唯一键时 5 轮全是 `DuplicateKeyException` 抛给调用方；加复用后第 5 轮仍出两行（计数含在途行）→ 只数终态后 20/20 ✅ |
| F08 | `RefundSubMerchantTest#refundCarriesTheOriginalSubMerchant` · `#legacyRowFallsBackToRequestSnapshot` · `PaymentOpenConcurrencyTest`（加列断言） | ✅ 2/2 · 20/20；退款相关 6 类全绿 | 先红：两条均 `expected "SUB-…" but was null` ✅ |
| F05 | `MerchantPaymentFlowTest#stubDoesNotImpersonateUnlistedChannel` · `#inFlightCannotResubmit` · `#resubmitClearsRejectReason` | ✅ 10/10 | 先红：PAYBY 进件 code=0、在途重提 code=0、旧原因仍在。消融恢复回落 / 去在途拦截 / 去显式清空 → 三条红 ✅。注：`resubmitClearsRejectReason` 初版在旧代码上是绿的（stub 同步出结果，走了已修好的回查路径），加 `keepApplyingFor` 后才量到 submit 本身 |

## §6 对账二 · 设计 → 实现

（实现后填）
