# TDD-C端优惠依据

状态：已实现（2026-09-20）
档位：2（既有 VO 加字段 · 无新端点 · 无库表 · i18n）
依据：用户 2026-09-20「优惠要展示优惠依据，点击优惠，给出优惠的内容，或者直接备注」
创建日期：2026-09-20

## 1. 实测：现在只给一个数

线上今天的单：商品 ¥39.90、优惠 −¥10.00、实付 ¥29.90。端上从头到尾只看得到
「优惠 −¥10.00」五个字，**减的是什么一个字都没有**。

减这 10 块的是一个**商家活动**：`pmt_activity` 里名为「abc」的直减券
（`PT202609201328190007701`，`trigger_type = NONE` 无门槛、`benefit_type = CUT` 直减 1000 分、
`end_at` 为空即不结束）。后端**知道**是它 —— `pmt_apply` 每一笔都记着
`promo_type = ACTIVITY / promo_no = PT2026…7701 / amount_minor = 1000 / funder = MERCHANT`，
结算就是按这张表算的。

**知道却不说**：`OrderVO.Amount` 只有 `discountMinor` 一个合计数。
于是买家看到的是一个来历不明的减免 —— 而它可能来自活动、券、或者两者叠加。

## 2. 契约

`OrderVO` 末尾加一行明细（预览与订单详情都填）：

```java
record DiscountLine(String kind,      // ACTIVITY / COUPON
                    String name,      // 「abc」「新人首单券」
                    long amountMinor) // 这一条减了多少
```

- `OrderVO.discountLines`：为空表示没有优惠（不是「没查到」）。
- 合计 `amount.discountMinor` **不变**，明细只是把它拆开 —— 两者对不上时以合计为准，
  明细是解释不是账。

## 3. 数据来源

| 视角 | 来源 |
|---|---|
| 预览（确认页） | `Discounts.auto().applied()`（活动号 → `pmt_activity.name`）+ 选中的券 |
| 订单详情 | `pmt_apply`（按 `order_no` 查）→ `promo_no` → 活动名 / 券名 |

券名在订单上已有快照（`ord_sub_order.coupon_*`），活动名要按号查一次。

## 4. 界面

**直接备注，不做弹层**（用户给的两条里取后者 —— 少一次点击，且弹层在小程序上还要多一套交互）：

```
优惠        −¥10.00
            活动「abc」 −¥10.00
```

一条时就一行；多条时逐条列。这一段在**确认页**与**订单详情**各出现一次，走同一个组件。

## 5. 测试

- 后端 `CampaignDiscountFlowTest`（已有满减场景）：预览的 `discountLines` 里有那条活动，
  名字与金额对得上；消融：不填 `discountLines` → 红。
- 端上 `c-app/tests/discount-lines.test.ts`：有明细时逐条渲染；为空时整段不渲染
  （不是显示「无」）。

## 6. 风险

- 老订单没有 `pmt_apply` 行（老模型走 `mkt_campaign`）→ 明细为空，页面只显示合计，
  与今天一样。**不回填历史**：那要按当时的规则重算，而规则可能已经改了。

## 实现 → 需求（测试真实输出）

- 后端 `CampaignDiscountFlowTest`：18 跑 / 0 红。`fullCutApplies` 新增三条断言
  （kind=ACTIVITY、name=「满50减8」、amount=800）。消融：预览不挂 `withDiscountLines` → 红。
- 端上 `c-app/tests/discount-lines.test.ts`：5 跑 / 0 红。c-app 全量 276 跑 / 0 红；`vue-tsc` 干净。

## 一路上修掉的两处旧账

- **老模型的活动此前连「用了哪个」都没记**（`CampaignPortImpl` 只给金额）。
  现在与新模型口径一致，都带活动号与名字 —— 否则老活动减了钱说不出名字。
- **测试助手 `fullCut(name, …)` 把 name 丢了**（活动名恒为「测试活动」），
  调用处写着「满50减8」而库里不是。接上之后清理钩子（按名删）就删不掉了 ——
  残留活动泄漏进别的用例，报错指向毫不相干的地方。清理改成按活动号前缀删。

## 偏差说明

- **不做弹层，直接在金额区逐条列出**（用户给的两条里取「直接备注」）：少一次点击，
  小程序上也少一套交互。
- 订单详情读的是 `pmt_apply` 当时落下的那几行，**不按现在的规则重算**。
  老订单（走 `mkt_campaign` 的那些）没有这张账，明细为空、只显示合计。
- 积分抵扣不在明细里：它本来就单独占一行，且买家自己勾的。
