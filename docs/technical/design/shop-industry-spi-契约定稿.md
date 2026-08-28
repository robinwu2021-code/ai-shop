# `shop-industry-spi` · 契约定稿

状态：**草稿 · 待确认** · 创建 2026-08-28
定位：行业包与基座之间**唯一共同语言**的完整接口清单 —— 此前签名散在四册，本册收拢为**可直接建模块**的一份。
模块性质：**零依赖**（不引 Spring/MyBatis，照 `shop-job-api` 先例）；行业包编译期只依赖它。

---

# 一 · 模块结构

```
shop-industry-spi/src/main/java/ai/neargo/shop/spi/industry/
├── IndustryPackage.java                 包声明（manifest）
├── view/      OrderView · OrderLineView · ModifierView · GoodsView · ServiceTraitView
│              PaymentProof · Money · BizRef · PriceCtx     ← 纯 record，无实体泄漏
├── listener/  OrderLifecycleListener
├── extension/ PrintPayloadProvider · ResourceTypeProvider · CheckoutContributor
│              GoodsSegment（兜底通道，用前须论证三判据哪条不成立）
├── core/      CoreOrderApi · CorePaymentApi · CoreBookingApi · CoreResourceApi
│              CoreStaffApi · CoreAvailabilityApi · CoreMemberApi · CoreListingApi
│              CorePrintApi · CoreCapabilityApi · CoreAfterSaleApi · CoreCatalogApi
└── workflow/  Engagement · Phase · EngagementInvariants · WorkflowNo
（定时任务不在此：行业直接实现既有 ai.neargo.job.api.JobHandler）
```

# 二 · 包声明

```java
public interface IndustryPackage {
    String code();                        // FOOD / BEAUTY（对齐 mch_store.industry_pkg）
    String version();                     // 包自身版本
    String spiVersion();                  // 编译期契约版本；major 不匹配 → 启动拒绝
    Set<String> capabilities();           // 本包提供的能力码
    Set<String> requiredCapabilities();   // 依赖的基座能力，缺一拒绝启动
    String migrationLocation();           // db/industry/<code>
    String migrationHistoryTable();       // <prefix>_flyway_history
}
```

# 三 · 基座 → 行业（事件与扩展点）

```java
public interface OrderLifecycleListener {         // outbox at-least-once，实现者自证幂等
    default void onCreated(OrderView o) {}
    default void onPaid(OrderView o) {}
    default void onCancelled(OrderView o) {}
    default void onRefunded(OrderView o) {}
    default void onFulfilled(OrderView o) {}
}
public interface PrintPayloadProvider {           // 通道/设备/路由/重试在基座，内容在行业
    String scene();                               // KITCHEN / CHECKOUT / SERVICE_START / CARD_BALANCE …
    List<PrintDoc> render(PrintContext ctx);
}
public interface ResourceTypeProvider {           // 桌台/技师/工位的行为语义
    String resourceType();                        // TABLE / STAFF / SEAT / ROOM / VEHICLE
    default void onOccupied(String resourceNo, BizRef ref) {}
    default void onReleased(String resourceNo, BizRef ref) {}
}
public interface CheckoutContributor {            // 下单前建议：只能建议，不能改价/改库存/改状态
    CheckoutAdvice advise(CheckoutContext ctx);   // PASS / REJECT(reason) / ANNOTATE(kv)
}
```
**故意不存在的扩展点**：改价、改库存、改订单状态 —— 基座采纳与否由基座定（既有裁决）。

# 四 · 行业 → 基座（Core*Api 十二个，逐方法）

```java
CoreOrderApi        place(PlaceCmd) → PlaceResult；find(orderNo) → OrderView；
                    confirmOfflinePaid(orderNo, channel, batchNo, operator)；cancel(orderNo, reason)
CorePaymentApi      record(RecordCmd) → PaymentView；reverse(paymentNo, reason, operator)；
                    successSum(refType, refNo) → Money；settle(SettleCmd) → SettleResult
CoreBookingApi      hold(HoldCmd) → HoldResult；confirm(holdNo, BizRef)；release(holdNo, reason)
CoreResourceApi     occupy(resourceNo, BizRef, operator) → occupationNo；release(occupationNo, operator)；
                    list(storeNo, type) → List<ResourceView>
CoreStaffApi        canServe(storeNo, goodsNo) → List<StaffView>；find(staffNo) → StaffView
CoreAvailabilityApi slots(SlotQuery) → List<SlotView>       // 技能∩排班∩余量∩时长缓冲
CoreMemberApi       deductAsset(DeductCmd) → DeductResult；reverseAsset(txnNo, reason)；
                    balance(memberNo, entityNo) → BalanceView；applicable(cardNo, goodsNo) → boolean
CoreListingApi      soldOut(listingNo)；setDailyQuota(listingNo, quota, reset)   // 沽清即此，不碰库存
CorePrintApi        submit(scene, PrintContext) → jobNo；reprint(jobNo)
CoreCapabilityApi   enabled(storeNo, capability) → boolean；enabledCaps(storeNo) → Set<String>
CoreAfterSaleApi    refundLine(orderItemId, reason, operator)   // 含 modifier 金额；GIFT 不退钱
CoreCatalogApi      goods(goodsNo) → GoodsView（含 traits/modifiers 视图）；resolvePrice(skuNo, PriceCtx) → Money
```

# 五 · 工作流基类（受限继承）

`Engagement`（相位机 OPEN↔SETTLING→CLOSED|VOIDED **final**；`due()` 现算 final；
钩子 onAttached/onSettling/onClosed/onVoided；`subStates()` 全射映射）——
全文见[工作流领域模型](./TDD-工作流领域模型V2-状态与继承.md) §二，签名以本模块代码为准。

# 六 · 版本与守卫

- **semver**；接口只增不改，新方法一律 `default`；major 升级 = 破坏性变更，启动比对 `spiVersion()`，不匹配拒绝启动且报文写清"哪个包/要什么/当前什么"；
- View 全部为 record 快照，**不出现任何 `ord_*/prd_*/mch_*` 实体**（ArchUnit：spi 包 import 白名单 = JDK only）；
- 新增扩展点走 ADR（每个扩展点都在削弱基座确定性）；`GoodsSegment` 使用前须在评审里写明三判据哪条不成立。

# 七 · 落地清单

建模块（backend/shop-industry-spi，进 shop-parent reactor）→ 按本册落接口与 view →
`JobContractTest` 式契约测试（零依赖验证 + spiVersion 常量存在）→ 基座侧实现 Core*Api（P0/P1 各阶段就位）。
