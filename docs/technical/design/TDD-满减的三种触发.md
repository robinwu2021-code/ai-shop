# TDD · 满减的三种触发（顺带堵住送券那个口子）

> 状态：**在做** · 2026-09-18
> 起因：店主问「活动类型是否还有其他？」—— 查下来模型上还有三类，都卡在定价那一侧
> 相关：[TDD-活动的逻辑重排](TDD-活动的逻辑重排.md)、[营销域-架构对齐](营销域-架构对齐.md)、`CampaignPort` 的类注释

## 1 为什么现在做

`PmtActivity` 的模型是 **5 触发 × 4 优惠**，`ActivityServiceImpl.assertSane`
除了团购那条几乎全放行；但 `ActivityPricingServiceImpl` 真正会算的只有四种形状：

| 形状 | 存得进去 | 下单时生效 |
|---|---|---|
| AMOUNT × CUT（满额减） | ✅ | ✅ |
| GOODS × PRICE（特价） | ✅ | ✅ |
| GROUP × PRICE（团购） | ✅ | ✅ |
| QTY × GIFT（买赠） | ✅ | ✅ |
| **NONE × CUT**（无门槛立减） | ✅ | ❌ CUT 分支硬判 `TRIGGER_AMOUNT` |
| **QTY × CUT**（满件减） | ✅ | ❌ 同上 |
| **× COUPON**（下单送券） | ✅ | ❌ 全仓库无人读 `BENEFIT_COUPON` |

后三行是**存得下、显示成「进行中」、下单一分不减**，而且不报错。今天没有路径
造得出来，是因为 B 端 `TYPES` 只给四个入口 —— **拦住它的是前端不给按钮，不是后端**。
这正是 `CampaignPort` 那段注释里记下的老毛病（`mkt_campaign` 没有任何消费方）
换了张皮又长出来一次。

## 2 要做的三件事

### ① CUT 认三种触发

`ActivityPricingServiceImpl.autoDiscount` 的 CUT 分支按 `triggerType` 分：

- `AMOUNT` —— `goodsAmount >= triggerAmountMinor`（现状不变）
- `QTY` —— `goodsQty >= triggerQty`（**新**，整单满 N 件减 M）
- `NONE` —— 恒命中（**新**，无门槛立减）
- 其余（`GOODS` / `GROUP`）—— 跳过，见 ③

`GOODS × CUT`「买这几件货减 X」算不出来：`CampaignPort` 这一侧拿到的是**按商家
汇总后的金额**，没有逐件明细，减在哪一件上无从摊分。要做得先给 Port 加按商品的
减免通道，不在本次范围。

### ② MerchantAmount 加件数

```java
record MerchantAmount(String merchantNo, long goodsAmount, int goodsQty, String storeNo)
```

**加在 goodsAmount 后面而不是末尾**：两个金额/数量挨着，读调用点时不会
把 `storeNo` 看成第三个数。生产侧只有 `OrderServiceImpl.discountsOf` 一处构造，
`Group.lines` 里就有 `qty`，补一个 `goodsQty()` 求和即可。

⚠️ 件数口径是**下单件数**，不含买赠送出的那些 —— 送的没收钱，拿它去凑满件数
等于让优惠自己喂自己。

### ③ assertSane 显式拒绝算不出来的组合

`BENEFIT_COUPON` 与 `GOODS × CUT` 一律 `BAD_REQUEST`。

> 理由：留着不是「以后再支持」，是**一个不报错的死活动**。校验放行而定价无分支，
> 这两件事之间没有任何一道闸门 —— 靠前端不给入口挡着的东西，换个调用方就没了。
> 哪天真要支持，红的是这一行，正好提醒把定价那侧一起补上。

发券本身不进活动（券有自己的一页，两处都能发会让人不知道去哪儿）—— 这是
`activity-edit` 里已经写下的决定，本次只是把它从「前端不给」变成「后端不收」。

### ④ B 端放出两个类型

`TYPES` 加两条，并**把反查从「按 benefit」改成「按 trigger + benefit」**：

```ts
{ key: "CUT",     trigger: "AMOUNT", benefit: "CUT" }  // 满 50 减 5
{ key: "CUT_QTY", trigger: "QTY",    benefit: "CUT" }  // 买满 3 件减 5   ←新
{ key: "CUT_ANY", trigger: "NONE",   benefit: "CUT" }  // 立减 3        ←新
```

`kindOf()` 现在是 `TYPES.find(x => x.benefit === a.benefitType)` —— 三个类型
共用 `CUT` 之后它**必然回到第一个**，列表上「立减 3 元」会显示成「满 0 减 3」。
这是加枚举值时的老坑：只按另一个字段分支的地方会默默当成老玩法。

## 3 怎么验（判据要能证伪）

后端场景测试 `ActivityCutTriggerFlowTest`：

1. `NONE × CUT` 立减 3 —— 下 1 件 5 元的单，`goodsAmount` 应为 500 而优惠 300
2. `QTY × CUT` 满 3 件减 5 —— 下 **2 件**应减 0，下 **3 件**应减 500（边界两侧都测）
3. `QTY × CUT` 的件数**不含赠品** —— 买 2 送 1 叠满 3 件减 5 时仍不命中
4. `assertSane` 对 `BENEFIT_COUPON` 与 `GOODS × CUT` 抛 `BAD_REQUEST`

**消融**：把 ① 里新加的 `case TRIGGER_QTY` 删掉，第 2 条必须变红；
只看「测试通过」不算，要确认红的是**那一条**而不是别的断言。

## 4 不做什么

- 不动 `mkt_campaign`（老模型正在退场，见 TDD-活动与营销活动的合并）
- 不加按商品的减免通道（`GOODS × CUT`）
- 不做阶梯满减（满 100 减 10、满 200 减 30）—— 那要给活动加「档」，是另一个模型
