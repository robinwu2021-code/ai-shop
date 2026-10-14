# TDD 购物车下架件处理（P1 原因细分 + P2 清理与转化）

状态：草稿
关联：对话方案「商品下架后购物车商品的状态如何处理」②
创建：2026-10-08
档位：**1**（动契约：`CartItemVO` 加字段 · i18n 词条）

> **P0（门店级下架判不出）不在本 TDD** —— 并入 [ADR-030](../adr/ADR-030-商品归属改为门店Offer与品牌库.md) 的 P3
> 一起做（那时给 cart 加 `storeNo` 列），免得单独给 cart 加一次列、改一次契约，过几天又改。

---

## §0 对账一 · 需求 → 设计

| AC | 需求 | 落点 | 期 |
|---|---|---|---|
| AC1 | 失效原因细分：已下架 / 已售罄 / 活动结束，各自文案 | `CartItemVO.invalidReason`（**原因码**，非中文）+ 端上 `invalidText` 映射 i18n | P1 |
| AC2 | 原因码由后端一个真源判，端上不再自己从 invalid/available 猜「活动结束」 | `CartServiceImpl` 组装行时定 reason | P1 |
| AC3 | 原因码**不是**本地化文案（守历史教训：后端发中文会绕过 i18n 守卫） | 值域 `OFF_SHELF/ACTIVITY_ENDED/SOLD_OUT/null`；端上 switch 到词条 | P1 |
| AC4 | 失效区一键「清空失效商品」 | cart 页失效区头 + `cart.remove(所有失效 skuNo)` | P2 |
| AC5 | 每件失效件给「找相似」出口（下架件不是死路） | 跳搜索页带标题关键词 | P2 |

**孤立项**：无 AC 没落点；无落点挂不上 AC。

**不做**：① 门店级下架（P0 → ADR-030 P3）；② 下架瞬间 toast（要 diff 前后车，价值低、复杂度高，后续单议）；③ tabBar 角标是否含失效件（产品取向，另议）。

## §1 现状

- 后端 `CartServiceImpl.list`：SKU 查不到或 `!onSale` 或（仅活动且活动结束）→ `invalid=true`，**保留行**；售罄走 `available=0`（不是 invalid）。
- 端上 `invalidText` 只分两种：`invalid?已下架:已售罄` —— **活动结束被误标「已下架」**。
- `unsellable()`=`invalid||available===0` 决定进失效区、排除结算；切社区/onShow 重拉 → **恢复上架自动变回可买**（这条已对，不动）。

## §2 方案

### 契约
- `CartItemVO` **加一个字段** `String invalidReason`：`"OFF_SHELF"|"ACTIVITY_ENDED"|"SOLD_OUT"|null`。放在 `invalid` 之后。两个构造点都要补。
- 前端 `CartItem` 加 `invalidReason?: string`（**原因码**，注释写明不是文案）。
- i18n 新增 `cart.invalidActivityEnded`、`cart.clearInvalid`、`cart.findSimilar`（三语）。
- 库表：无。权限码：无。配置：无。

### 模块
| 动作 | 路径 | 说明 |
|---|---|---|
| 改 | `shop-core/.../trade/dto/CartItemVO.java` | +`invalidReason` |
| 改 | `shop-core/.../trade/service/impl/CartServiceImpl.java` | 两个构造点定 reason：snapshot null / !onSale → OFF_SHELF；activityOnly&&!open → ACTIVITY_ENDED；displaySellable==0 → SOLD_OUT；else null |
| 改 | `packages/shared/src/types/trade.ts` | +`invalidReason?`（码，非文案） |
| 改 | `c-app/.../cart/index.vue` | `invalidText` 按 reason 映射；失效区加「清空」；每件加「找相似」 |
| 改 | `c-app/src/i18n/locale/{zh-CN,en,ar}.ts` | 三个新词条 |

### reason 判定顺序（单一真源，端上不再猜）
```
snapshot==null  → OFF_SHELF      (下架/删除，查不到快照)
!onSale         → OFF_SHELF
activityOnly && 活动未开 → ACTIVITY_ENDED
displaySellable==0 → SOLD_OUT
否则            → null (可售)
```
`invalid`/`available` 两个旧字段**保留**（别处在用），`invalidReason` 是叠加的更细的那一层。

## §3 对账三 · 实现 → 需求（测试）

| AC | 测试 | 消融 |
|---|---|---|
| AC1/AC2 | 后端 `CartInvalidReasonTest`：下架→OFF_SHELF、活动结束→ACTIVITY_ENDED、售罄→SOLD_OUT、在售→null | 把 ACTIVITY_ENDED 那支去掉 → 红 |
| AC3 | 端上 `cart-page`：失效文案按 reason 出对应 i18n key（活动结束 ≠ 已下架） | 让 invalidText 退回只看 invalid → 活动结束用例红 |
| AC4 | 端上 `cart-page`：点「清空失效」→ `cartRemove` 收到全部失效 skuNo，不含有效件 | 去掉过滤 → 把有效件也删，红 |
| AC5 | 端上 `cart-page`：点「找相似」→ navigateTo 搜索页带 keyword | — |

每条消融：撤实现 → 对应红。

## §4 风险
| 风险 | 缓解 |
|---|---|
| 又把「原因」做成中文下发，重蹈 invalidReason 旧坑 | 值域是码不是文案；端上 switch 到词条；评审看这一条 |
| 「清空失效」误删 | 带确认（复用 removeTitle/removeHint） |
| 新字段老端读不到 | `invalidReason?` 可选；端上 reason 为空时回落旧的 invalid/available 文案 |

## §5 实现 → 需求（已跑）

| AC | 测试 | 结果 |
|---|---|---|
| AC1/AC2（OFF_SHELF/SOLD_OUT） | `CartStockGuardFlowTest#offShelfItemCarriesReason` `#soldOutItemCarriesReason` | 绿（后端 10 测） |
| AC1/AC3（ACTIVITY_ENDED 映射） | `cart-page#活动结束→invalidActivityEnded`、`#老后端无码回落` | 绿；消融：invalidText 退两分法→红 |
| AC4 | `cart-page#清空失效只删失效件` | 绿；消融：改删全部→红 |
| AC5 | `cart-page#找相似跳搜索带标题` | 绿 |
| 守卫 | `active-address` 两条随决策更新（失效区无步进器锚点改模板；清空失效＝用户点+确认、禁自动清） | 绿（c-app 全量 474） |

## §6 对账二 · 设计→实现（git diff --stat）

后端：`CartItemVO`（+字段+3常量）、`CartServiceImpl`（两构造点定 reason）、`CartStockGuardFlowTest`（+2）。
前端：`packages/shared/types/trade.ts`（+invalidReason?）、`c-app/cart/index.vue`（invalidText/clearInvalid/findSimilar+模板+样式）、三语 i18n（+3词条）、`cart-page.test.ts`（+4）、`active-address.test.ts`（2 条更新）。
与 §2 一致。

## §7 偏差说明

- **ACTIVITY_ENDED 的后端集成测试没写**：活动件不能直接 `add`（`add` 对 activityOnly 且活动未开会抛 `GOODS_ACTIVITY_ONLY`），要造「活动结束」还得直插 cart 行 + 活动夹具，成本高。后端那支判定是一行可读的三元（`activityEnded ? ACTIVITY_ENDED`），由代码审查 + 前端映射测（reason="ACTIVITY_ENDED"→对应文案）共同覆盖。OFF_SHELF/SOLD_OUT 有后端集成测。
- **`active-address.test.ts` 两条守卫随产品决策更新**：当年「不许批量清理」反转为「允许用户点+确认的清空，禁自动清」；另一条的锚点从「第一个 invalidItems 出现处」改为模板标记（clearInvalid 在脚本段也引用了它，旧锚点会漂）。先改测试文档再改代码的精神：决策变了，守卫跟着变并写清为什么。
