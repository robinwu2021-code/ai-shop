# TDD-支付服务-I1存量缺陷

状态：已实现（2026-10-25 · `23af41cf4` … `6a7e79f8d`，分支 `codex/pay-i1-defects`）
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

### F09 · 出款对账比金额
- `PayoutReconAxis`：放款单勾上银行流水时比金额，不等 → 不标 MATCHED、开 `PAYOUT_AMOUNT_MISMATCH`（平台侧 = 放款额，通道侧 = 银行金额）。
- 结算单粒度不比：一笔银行流水可对应一批结算单（批量出款），逐张比必然误报；放款单是一笔转账一条记录。
- 差异类型多一个取值（`stl_recon_diff.diff_type`，无文案字典，运营端原样显示）。

### F11 · 内部令牌常量时间比较；生产 pay-svc 停用
- `InternalPayEndpoint#authorized`：`token.equals(given)` → `MessageDigest.isEqual`（其余 188 处早已走 `InternalServiceResponses.invoke`）。
- 守卫 `InternalTokenComparisonTest`（pay-svc）：源码里出现 `xxxToken.equals(…)` / `.equals(xxxToken)` 即红。
- R11 是**线上运维动作，不在代码里做**：已写进 `deploy/tencent/README.md` §8 第 9 条（`systemctl disable --now ai-shop-pay`，需人工执行）。

### F14 · 改通道市场原子化
- `PayChannelMasterServiceImpl#updateSettings` 加 `@Transactional("payTxManager")`：先删后插中途失败整体回滚。
- **偏差：不拒空集。** 空的市场集合在本系统是有意的「不限市场」语义（`MarketChannelAndWithdrawTest#unrestrictedChannelVisibleEverywhere` 守着，测试渠道 TEST 即如此配置）。
  R22 的真实风险是「中途失败留下空集 = 意外全市场开放」，原子化即消除；显式传空仍表示「不限」。若要改成显式标记（如 `["*"]`），属运营端契约变更，另议。

### F15 · 差异单不按天去重、说明与结论分列
- **迁移** `db/pay/V3__stl_recon_diff_dedup_note.sql`：`stl_recon_diff` 加 `dedup_key`（可空，唯一键在 I6 迁移 13 回填后加）、`note`。
- `ReconServiceImpl#recordDiff`：去重条件去掉账期日，只对 PENDING 去重（处置后再出问题是新事）；`dedup_key = 通道|流水号|差异类型`；系统说明写 `note`，`resolution` 留给运营。
- **契约**：`ReconDiffVO` 末尾加 `note`（只加不改，运营端不读也不受影响）。
- 3 条旧断言原本断的是「说明在 resolution 里」（即缺陷本身），改为断 `note`；3 个直接引入 `PayDataSourceConfig` 的测试类补 `shop.pay.flyway.enabled=false`（I0 时 `db/pay` 为空没暴露）。

### F13 · 回调报文带流水号；退款回调地址不拼接
- R15：`PaymentLedgerService#paymentNoOf(outTradeNo)`（按全表唯一的 out_trade_no 查）；`PayMessagePortImpl#callbackSettled` 在调用方未给 `paymentNo` 时补上，查不到照样落报文。回调控制器十几处调用不改。
- R32：`WechatDirectPayGateway` 新增 `refundNotifyUrl`：显式配置 `shop.pay.wechat.refund-notify-url`（新配置项，默认空）优先，否则按 URI 推导（路径末段后加 `refund`，查询参数保留）。

### F12 · 支付宝开关能起；收付通查单 / 查退款
- R13：两个支付宝网关去掉 `@Component` / 开关（类保留），装配点收到新 `AlipayChannelConfig`：开关打开时只登记一个 `AlipayNotReady` 并打 ERROR，**不装网关** —— 支付宝按未接通处理（进件 / 支付报渠道不可用）。客户端实现（RSA2、凭据）属 I8，届时在这里装上。
- R14：通道层新接口 `PaymentAccountLookup`（按商户单号查子商户号），支付域实现 `PaymentAccountLookupImpl`（读 F08 落的列，存量回退快照；退款单取原交易的）。
  `WechatPayGateway`（收付通）查单改走 `/v3/pay/partner/transactions/out-trade-no/{no}?sp_mchid&sub_mchid`；新增 `queryRefund` 走 `/v3/ecommerce/refunds/out-refund-no/{no}?sub_mchid`；不知道子商户号按「查询失败」处理，不猜。
- **未做**：收付通 `prepay`（合单下单）仍为默认「尚未实现」—— 收付通模式今天没开（`WX_PAY_MODE` 默认 direct），下单接入随渠道上线做。

### F10 · 独立形态的发票列表显式收窄
- **契约**（内部接口，两端都在本仓）：`PayInternalApi#settleInvoices` 加必填 `merchantScope`；编码 `*` = 不限、空串 = 一个都不能看、否则逗号分隔（`encode/decodeMerchantScope`）。
- 主应用 `RemoteOpsSettleInvoiceAppService#merchantScope(DataScopeSpec)`：`all()` / 无上下文 → 不限（与内嵌拦截器一致）；有范围无商户维度 → 空集（宁严勿宽）。
- pay-svc `InternalPayEndpoint#invoices`：缺 `merchantScope` 回 400，不默认成「不限」；`SettleInvoiceService#list(…, merchantNos)` 按 `entity_no IN` 过滤，空集直接空页。内嵌形态仍走数据域拦截器（传 null）。
- **范围**：只修 R10 点名的发票列表。其余 `Remote*AppService` 的列表接口是否有同类问题，切独立形态（I3 之后）前逐个过一遍。

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
| F09 | `PayoutBankReconFlowTest#payoutAmountMismatchIsNotMatched` | ✅ 9/9（另 2 个对账类全绿） | 先红：银行只划 290 元，300 元的放款被标 MATCHED ✅ |
| F11 | `InternalTokenComparisonTest#noStringEqualsOnTokens` · `#detectorFires` | ✅ pay-svc 71/71 | 先红：抓到 `InternalPayEndpoint.java` ✅。R11 未执行（线上操作，待人工） |
| F14 | `PayChannelMarketAtomicityTest#failedReplaceLeavesOldMarketsIntact`（spy 让第二个市场插入时抛错） | ✅ 1/1；`MarketChannelAndWithdrawTest` 8/8 | 先红：中途失败后市场集合变成 `[HK]`（CN 已删）✅ |
| F15 | `ReconFlowTest#sameIssueNextDayIsNotDuplicated` · `#autoNoteDoesNotOccupyResolution` | ✅ 23/23；对账 4 类全绿 | 先红：三天三条；说明占了 resolution ✅ |
| F13 | `PayMessagePortImplTest#fillsPaymentNoFromOutTradeNo` · `#explicitWinsAndUnknownStillRecords` · `WechatDirectPayGatewayTest#refundNotifyUrlIsDerivedNotConcatenated` | ✅ 2/2 · pay-channel 64/64；回调 3 类全绿 | 先红：payment_no 落库为 null；退款地址拼成 `…/WECHAT//refund` ✅ |
| F12 | `AlipaySwitchBootTest#bootsWithAlipaySwitchOn` · `WechatPayGatewayQueryTest`（3 条）· `PaymentOpenConcurrencyTest`（加查子商户号断言） | ✅ 1/1 · 3/3 · 20/20；pay-channel 67/67 | 先红：开关打开即 `NoSuchBeanDefinitionException: alipayChannelClient`；收付通查单路径不带服务商参数、查退款恒为查不通 ✅ |
| F10 | `PayInternalApiRoundTripTest#settleInvoicesWithoutScopeIsRejected` · `#settleInvoicesParams` · `#merchantScopeEncodingKeepsAllAndNoneApart` · `RemoteOpsSettleInvoiceScopeTest`（2 条） | ✅ 11/11 · 2/2 | 契约变更，旧代码编译不过，无行为先红；消融：撤 400 校验（缺参当不限）→ `settleInvoicesWithoutScopeIsRejected` 红 ✅ |
| F05 | `MerchantPaymentFlowTest#stubDoesNotImpersonateUnlistedChannel` · `#inFlightCannotResubmit` · `#resubmitClearsRejectReason` | ✅ 10/10 | 先红：PAYBY 进件 code=0、在途重提 code=0、旧原因仍在。消融恢复回落 / 去在途拦截 / 去显式清空 → 三条红 ✅。注：`resubmitClearsRejectReason` 初版在旧代码上是绿的（stub 同步出结果，走了已修好的回查路径），加 `keepApplyingFor` 后才量到 submit 本身 |

## §6 对账二 · 设计 → 实现

14 个提交（`23af41cf4` … `640e52076`，基线 `5a0450779`），`git diff --stat 5a0450779..640e52076`：67 个文件，+1753 / −160。
逐条与 §2 对上（主代码；测试见 §5）：

| §2 条目 | 实际改动的主代码 |
|---|---|
| F01 / F02 | `spi/user/MerchantQueryPort` · `spi/user/MerchantAdminPort` · `MerchantPortImpl` · `MerchantGovernServiceImpl`（R26）· `SettleGenerationOrchestrator` · `OrderServiceImpl`（传渠道） |
| F03 | `StoreAdminServiceImpl` |
| F04 | `spi/pay/PayApplymentGateway` · `WechatApplymentGateway` · `AbstractApplymentGateway`（F06 同改）· `StubApplymentGateway` · `MerchantPaymentServiceImpl` |
| F05 | `MerchantPaymentServiceImpl` · `StubApplymentGateway`（测试钩子）· `application.yml`（`shop.pay.stub-channels`） |
| F06 | `MerchantPortImpl#accruePayQuota` · `AbstractApplymentGateway` · `AlipayApplymentGateway` |
| F07 | `db/pay/V1` · `PaymentLedgerServiceImpl` · `gen-test-schema.py` · `schema-test.sql`（生成物） |
| F08 | `db/pay/V2` · `StlPayment` · `DirectAccounts` · `OrdinaryPaymentInitiationServiceImpl` · `PaymentLedgerServiceImpl` · `RefundExecutionServiceImpl` |
| F09 | `PayoutReconAxis` |
| F10 | `PayInternalApi` · `SettleInvoiceService(Impl)` · `InternalPayEndpoint` · `RemoteOpsSettleInvoiceAppService` |
| F11 | `InternalPayEndpoint` · `deploy/tencent/README.md`（R11 待人工） |
| F12 | `AlipayPayGateway` · `AlipayApplymentGateway` · `AlipayChannelConfig` · `PaymentAccountLookup(Impl)` · `WechatPayGateway` · `WechatApis` · `WechatPayChannelConfig` |
| F13 | `PaymentLedgerService(Impl)#paymentNoOf` · `PayMessagePortImpl` · `WechatDirectPayGateway` · `WechatPayChannelConfig` · `application.yml`（`refund-notify-url`） |
| F14 | `PayChannelMasterServiceImpl` |
| F15 | `db/pay/V3` · `StlReconDiff` · `ReconServiceImpl` · `ReconService`（VO 加 `note`） |

| 差异 | 说明 |
|---|---|
| TDD 里没有、实际改了的 | `SchemaDriftTest`（随 `gen-test-schema.py` 接上 `db/pay`）；3 个直接引入 `PayDataSourceConfig` 的测试类加 `shop.pay.flyway.enabled=false`（I0 遗漏，`db/pay` 有了真迁移才暴露）；生成物 `glossary.json` / `静态常量清单.md` |
| TDD 列了、实际没动的 | 无 |

### 偏差说明

1. **F01 / F05 测试环境渠道**：初稿设想「测试进件改用 STUB」作废 —— STUB 不是主数据渠道，测试订单实际走 `TEST`。改为：stub 只替 `shop.pay.stub-channels` 点名的渠道进件；结算取号时 `OFFLINE / FREE / STUB / TEST` 不按渠道过滤。
2. **F03 不校验渠道**：`mch_store.pay_merchant_no` 一店一列不分渠道，绑定时无目标渠道可比；按渠道取号由 F01 在解析侧保证。
3. **F09 只比放款单**：一笔银行流水对一批结算单（批量出款），结算单粒度比金额必然误报。
4. **F11 的 R11 未执行**：生产停 pay-svc 是线上运维动作，写进部署待办，需人工。
5. **F12 未做收付通 prepay**：收付通模式未启用，下单随渠道上线；支付宝客户端（RSA2 / 凭据）属 I8。
6. **F14 不拒空集**：空集是有意的「不限市场」，原子化已消除「意外变空」。
7. **F10 只修发票列表**：其余 `Remote*AppService`（共 23 个）的列表接口在切独立形态前逐个过。
8. **I0 遗漏的修补**：直接引入 `PayDataSourceConfig` 的测试类未关支付 Flyway，在 F15 时暴露并修正。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-25 | 用户「开始 I1：按开发计划逐条修复 F01–F15，每条先写会变红的测试」 |
| 2026-10-25 | 15 条全部实现，14 个提交；每条先红后绿，消融见 §5 |
| 2026-10-25 | 迁移：`db/pay` V1–V3 在本机 MySQL 9.7.4 上由应用启动执行成功（`pay_flyway_history` 记 4 行含基线；唯一键与 4 列核对无误；平台迁移 499 条未动）；H2 由 `gen-test-schema.py` 接上 `db/pay` |
| 2026-10-25 | 全量 `mvn test`：4022 条、0 次 OOM，失败 42 条**全部为 I0 基线既有**，无新增；另修好 1 条（`M7SettleFlowTest#rateCardIsVisibleToMerchant`）。第一轮回归抓到「支付迁移在无平台 Flyway 的切片测试里空跑」与反向依赖计数 +4，已修（`6a7e79f8d`） |
| 2026-10-25 | `pre-push`：除餐饮会话既有的 `PrintDeliveryTest.setup`（`3b48c8957`，H2 多列 ALTER）外全部通过；生成物与共享守卫因本机无 node_modules 跳过 |

**上线前置**（不在代码里）：执行方案 Q1 在生产库确认 `stl_payment.out_trade_no` 无重复；R11 人工停用生产 pay-svc（`deploy/tencent/README.md` §8 第 9 条）。

