# B 端权限对接 · 整改清单

状态：**已盘点 · 整改进行中（2026-08-12）** · 方案见 [TDD-B端权限对接整改](./TDD-B端权限对接整改.md)

> 来源：对着 [B端权限方案](../reference/B端权限方案.md)、
> [B端功能矩阵-按角色](../reference/B端功能矩阵-按角色.md) 与
> [三端角色权限功能对齐清单](../../requirements/三端角色权限功能对齐清单.md)
> 逐条核**需求 → 后端 → 前端 → 产物**四层。
> 核对方法写在文末 §七 —— **它本身应该变成一条守卫**，否则这份清单三个月后就烂了。
>
> 后端机制层无需改动（守卫已重跑：`BizEndpointPermTest` 4 条、`BizPermsTest` 9 条全绿）。
> 唯一的实质性需求缺口是 **D2 配送员订单视图未裁剪**，那条要拍板；
> 其余是**前端对接**与**文档产物**的问题。

## 〇、总览

| 编号 | 问题 | 影响 | 优先级 |
|---|---|---|---|
| A1 | 配送页对 CLERK / COURIER 整页空 | 配送员进不去为他而设的页 | **P0** |
| A2 | 分拣页对 PICKER 整页空 | 理货员进不去为他而设的页 | **P0** |
| A3 | 商品 tab 对 CS / COURIER 报错而非 denied 态 | tabBar 页，天天可见 | **P0** |
| A4 | 订单页「售后」tab 对 CLERK / COURIER 报错 | 点一下报错 | P1 |
| A5 | 订单详情的发货/送达按钮不判权 | CS 看得见点不动 | P1 |
| A6 | goods-edit / stats / order 三页无门禁 | 刷新与深链无 denied 态 | P2 |
| A7 | 工作台对无权角色必打 3 个注定 403 的请求 | 已 catch，只是噪音 | P3 |
| B1 | 9 个受控端点没有任何前端 | 其中 2 条是业务断路 | P1 / P3 |
| **B2** | **4 个功能点「契约接了、页面没画」** | 看起来像做完了 | P1 |
| C1 | 方案 §五 拒绝码写成一个，实际是两个 | 照它写的断言会全错 | P1 |
| C2 | 方案 §六 守卫表缺 2 条（含最关键的★★★） | 低估了守卫覆盖面 | P2 |
| C3 | 方案 §六 端点数 67 已过期 | 数字失真 | P3 |
| C4 | 矩阵产物里 `RECEIVE` 的含义取错 | 生成器 regex bug | P2 |
| **D1** | **「唯一权威」需求文档整章过期** | 读它的人会以为 B 端仍在裸奔 | **P0（文档）** |
| **D2** | **配送员订单视图未裁剪 —— 需求里唯一没落地的一条** | 配送员看得到全店金额 | **要拍板** |
| D3 | 需求与实现的三处口径差 | 小 | P2 |
| D4 | `B端功能清单.md` 角色 3 个、页面 20 页、契约 46 个全过期 | 三个数都错 | P1 |
| D5 | `需求矩阵-三端.md` 里店员/配送员仍标 P1、B-11.10 仍标 ⬜ | 已交付的当成没做 | P1 |
| D6 | 这轮判权工作没进 `待完成功能清单.md` 的账 | 交付记录缺一笔 | P2 |
| **E1** | **员工管理只有老板能用 —— 店长不能加人改角色** | ✅ 有意设计，非缺陷 | 确认题 |
| E2 | 员工管理缺删除、改手机号、操作日志（B-11.10.3） | 需求有、实现无 | P2 |
| E3 | `StaffVO` 两处注释过期（说店长能看手机号、角色只列 2 个） | 误导 | P3 |

---

## 一、前端对接（A 组）

共同的病根一句话：**页面门禁只写了一个权限码，`load()` 里却打了要另一个码的端点**。
后端按方案正确返回 70006，前端把它渲染成「这家店什么都没有」。

首页已经踩过并写下了结论（[home/index.vue:106-123](../../../b-app/src/pages/home/index.vue#L106)：
「**把权限不足渲染成业务待办是最坏的一种失败**」），但这个结论没有推广到其它页。

### A1　配送页对 CLERK / COURIER 整页空　**P0**

- **现场**：[delivery/index.vue:35](../../../b-app/src/pages/delivery/index.vue#L35)
  `Promise.all([mDeliveryRule(), mOrderList()])`，无 catch。
- **门禁**：`biz:ship`；**实际还需**：`biz:store`（`/biz/delivery/rule` GET+POST）、`biz:order:view`。
- **谁撞上**：CLERK、COURIER（都有 `ship`，都没有 `store`）。
- **为什么必然发生**：工作台「待配送」格子的 perm 正是 `biz:ship`
  （[home/index.vue:78](../../../b-app/src/pages/home/index.vue#L78)），**主动把配送员送进这一页**。
- **建议**：`mDeliveryRule` 用 `merchant.can('biz:store')` 包住并 `.catch(() => null)`，
  规则卡片按取到与否显示；配送员只需要「待送列表 + 标记送达」，那两个码他都有。
- **验收**：以 COURIER 角色进配送页，看得到待送单，看不到规则卡片，无报错。

### A2　分拣页对 PICKER 整页空　**P0**

- **现场**：[picking/index.vue:40](../../../b-app/src/pages/picking/index.vue#L40)
  `Promise.all([mPickingList(), mPickupOrders()])`，无 catch。
- **门禁**：`biz:receive`；**实际还需**：`biz:verify`（`/biz/pickup/orders`）。
- **谁撞上**：PICKER —— 而 `PICKER = {RECEIVE, STOCK}` 这个角色就是为这一页设的。
- **注意**：`mPickupOrders` 的结果喂给「备货中的单 / 标记到货」，
  所以不是少一块，是**到货登记这个动作整个没了**。
- **建议**：两个调用各自 `.catch()`；到货区按 `can('biz:verify')` 或按取到的数据判空。
  若「标记到货」对理货员是必需的，则该考虑把 `/biz/pickup/orders` 的权限从
  `VERIFY` 改成 `canAnyBiz(VERIFY, RECEIVE)` —— **这是后端决定，要单独确认**。
- **验收**：以 PICKER 角色进分拣页，分拣单可见、可上报短少。

### A3　商品 tab 对 CS / COURIER 报错而非 denied 态　**P0**

- **现场**：[goods-list/index.vue:47](../../../b-app/src/pages/goods-list/index.vue#L47)
  `mGoodsList()` 无条件调；页面**没有** `denied` 门禁，且它是 tabBar 四页之一
  （[pages.json:174](../../../b-app/src/pages.json#L174)）。
- **需要**：`biz:stock`（`/biz/goods`）。**谁撞上**：CS、COURIER。
- **对照**：同页的新建/编辑/上下架/改库存按钮都已按 `can()` 裁
  （[goods-list/index.vue:138-176](../../../b-app/src/pages/goods-list/index.vue#L138)）——
  只有列表本身漏了。
- **建议**：加 `:denied="!merchant.can('biz:stock')"`。
  另需确认 tabBar 是否该对这两个角色隐藏「商品」——custom tabBar 已开
  （`"custom": true`），有条件做。
- **验收**：以 CS 角色点「商品」tab，看到 denied 空态，无 toast 报错。

### A4　订单页「售后」tab 对 CLERK / COURIER 报错　P1

- **现场**：[orders/index.vue:57](../../../b-app/src/pages/orders/index.vue#L57)
  `mAfterSaleList()` 要 `biz:aftersale`，而页面门禁是 `biz:order:view`。
- **谁撞上**：CLERK、COURIER（有 order:view，无 aftersale）。
- **建议**：`TABS` 里给「售后」加 perm 字段，按 `can('biz:aftersale')` 过滤 —— 与工作台
  待办格子同一手法（`base.filter(c => merchant.can(c.perm))`）。

### A5　订单详情的发货 / 送达按钮不判权　P1

- **现场**：[order/index.vue:22-28](../../../b-app/src/pages/order/index.vue#L22)
  `canShip` / `canDeliver` **只按 `fulfillment` 与 `status` 判，没有权限项**。
- **谁撞上**：CS（有 `order:view` 能进详情，无 `ship`）——按钮画给他，点了 70006。
- **建议**：`canShip = … && merchant.can('biz:ship')`，`canDeliver` 同。

### A6　goods-edit / stats / order 三页无门禁　P2

正常入口都已按 `can()` 裁，但刷新、深链、tabBar 直切时没有 denied 态：

| 页面 | 需要 | 正常入口（已裁） |
|---|---|---|
| [goods-edit](../../../b-app/src/pages/goods-edit/index.vue) | `biz:goods`（保存/识图）、`biz:stock`（详情） | goods-list 的编辑按钮 |
| [stats](../../../b-app/src/pages/stats/index.vue) | `biz:customer` | 我的页 cell |
| [order](../../../b-app/src/pages/order/index.vue) | `biz:order:view` | 订单列表 |

- **建议**：一律补 `:denied`，与其它 16 页一致。

### A7　工作台对无权角色必打 3 个注定 403 的请求　P3

[home/index.vue:119-123](../../../b-app/src/pages/home/index.vue#L119) 的
`mStats` / `mPayments` / `mStore` 已各自 `catch`，**功能上没有问题**，
但对店员等角色是每次进首页都发 3 个必失败的请求（日志噪音 + 首屏多 3 个 RTT）。

- **建议**：调用前先 `can()`，与同文件 `blockers` 的写法一致。

---

## 二、功能矩阵缺口（B 组）

### B1　9 个受控端点没有任何 b-app 调用

后端已受控、前端零调用（比对 `endpoints.ts` × `BizEndpointPermTest.REQUIRED`）：

| 端点 | 权限 | 性质 |
|---|---|---|
| `/biz/pickup/verify/search` | VERIFY | ✅ **已接**（2026-08-12）：输码失败后按片段列候选，让店主确认是哪一单 |
| `/biz/quote/{quoteNo}/revise` | CAMPAIGN | ⚠️ **原判断是错的** —— 报价页早就能改价，见下 |
| `/biz/settle/bills/{settleNo}` | FINANCE | 结算单详情，列表点不进去 |
| `/biz/settle/invoice-title` | FINANCE | 未排期 |
| `/biz/settle/invoices` | FINANCE | 未排期 |
| `/biz/settle/statement` | FINANCE | 未排期 |
| `/biz/merchant/payment/store/{storeNo}` | FINANCE | 门店级收款号，stores 页走的是 `/biz/store/{storeNo}/payment` |
| `/biz/deposit` | FINANCE | 保证金，未排期 |
| `/biz/deposit/txns` | FINANCE | 保证金流水，未排期 |

- **其余确认是「后端先行」还是「已废弃」**—— 若是后者应从 `REQUIRED` 删掉，
  `noStaleEntries` 只拦已删端点，拦不住「端点还在但没人用」。

#### ⚠️ 更正：`/biz/quote/{}/revise` 不是业务断路（2026-08-12）

上一版从「端点没人调」推出「报价页只能新建不能改价」，**这一步推错了**。
读代码后的事实：

1. `quotes` 页的 `start()` 会预填我已有的报价，提交仍走 `mQuote(requestNo)`；
2. 后端 `quote()` 发现已有报价时**转调 `doRevise`** —— 改价留痕、涨价标记一样不少。

所以改价一直能用，只是走的是另一个入口。**「端点没人调用」不等于「功能缺失」。**

**但顺手发现两个入口的口径不一致**，且这条要你拍板：

| | 锁价（`chosenQuoteNo != null`）之后再改价 |
|---|---|
| `quote(requestNo)` | **直接拒** —— 「接受了也没意义，只会让商家以为还有机会」 |
| `revise(quoteNo)` | **允许** —— 锁价保护的是快照，那张报价对后续邻居仍有效 |

`M6cGroupFlowTest.chosenQuoteLocksPrice` **锁住了后者**（我加了一致性判断之后它立刻红：
期望 code 0，实到 20004）。所以 revise 的行为是有意的、被测试保护的，我已经改回去了。

**结论：③ 保留差异，但把它变成「有意的」**（2026-08-12 执行）。

理由是这两个入口回答的**不是同一个问题**：

| 入口 | 它在问什么 | 锁价后 |
|---|---|---|
| `quote(requestNo)` | 这张**需求单**还收不收报价？ | 拒 —— 已经选定了别人，收了「只会让商家以为还有机会」 |
| `revise(quoteNo)` | 我这张**报价**的挂牌价改成多少？ | 放 —— 对后续邻居仍然有效；成交按 `chosenQuote` 快照算 |

统一成任何一边都要牺牲其中一句话，而两句话都成立。所以不改行为，改的是**它有没有被写下来**：

- 两处源码互相点名对方，说明差异从何而来；
- 新增 `M6cGroupFlowTest.quoteAndReviseDifferAfterLock` **同时钉住两边**
  （`/quote` 必须 20004、`/revise` 必须 0）。

> 这条测试存在的理由就是**防止「顺手统一」** —— 我自己差点这么干过：
> 加了一致性判断之后 `chosenQuoteLocksPrice` 立刻红。
> 现在谁要统一，得先让这条红一次，并回答「上面那两句话哪一句不再成立」。

> 反向核对干净：**前端没有任何一个 `/biz` 调用是后端登记表里没有的**。

### B2　4 个功能点「契约接了、页面没画」　P1

比 B1 更隐蔽的一层：`endpoints.ts` 有定义、`contract.ts` 有类型、mock 有实现，
**唯独没有任何一页调用它** —— 从契约看像做完了。

| 功能点 | 端点 | 契约方法 | 权限码 |
|---|---|---|---|
| 本期发分服务费与开关状态 | `/biz/points/account` | `mPointsAccount` | `biz:finance` |
| 发分服务费明细（按单） | `/biz/points/records` | `mPointsRecords` | `biz:finance` |
| 开/关本店积分 | `/biz/points/toggle` | `mPointsToggle` | `biz:finance` |
| **改门店名与地址** | `/biz/store/{storeNo}/rename` | `mRenameStore` | `biz:store:admin` |

- **积分三条**是一整个功能面（发分服务费 + 本店积分开关）没有入口 ——
  老板既看不到这笔费用，也关不掉它。
- **改门店名**：`stores` 页有建店 / 停用 / 设默认 / 挂收款号四个动作，**唯独没有改名**，
  而契约与后端都在。开错一个字的店名现在只能停用重建。

> B1 + B2 合计 **13 个受控功能点在 b-app 上没有入口**。
> 完整表见 [B端功能点-权限码-页面](../reference/B端功能点-权限码-页面.md) §四 与 §四之二。

---

## 三、文档与产物（C 组）

### C1　方案 §五 的拒绝码写成一个，实际是两个　P1

- **方案原文**：「RBAC 拒绝 = HTTP 200 + 业务码 `10403`，全站约定，测试里按这个断言。」
- **实际**（[PermChecker.java:52-78](../../../backend/shop-base/src/main/java/ai/neargo/shop/auth/PermChecker.java#L52)）：
  - 不是商家 → 返回 false → Spring `AccessDeniedException` → `10403`
  - 是商家但角色不够 → **抛 `BIZ_ROLE_FORBIDDEN` = `70006`**
- 源码用 12 行注释论证了「为什么必须分成两个码」（作用域 403 与权限 403 撞码则无法排查），
  正文却把它合成了一条。**照 §五 写的断言会全错。**
- **建议**：§五 改成两码表，并注明「70006 = 找店主也没用，那个开关设计上不存在」。

### C2　方案 §六 守卫表缺 2 条，且缺的是最关键的一条　P2

`BizEndpointPermTest` 实际 4 条测试，方案只列了 2 条。漏掉的：

- `allCodesExist` —— 码写错一个字母 = 那个端点对所有人永久拒绝，表现只是「按钮点了没反应」
- `decisionsAreActuallyEnforced`（★★★）—— **表里定了权限但代码里没 `@PreAuthorize`**

第二条正是 §四 那句「代价是判权逻辑离开了标准位置，所以下面那几条守卫必须存在」
所指的东西：登记表是许愿，注解才是执行。**它不在表里，等于方案没说清自己靠什么兜底。**

### C3　方案 §六 的「67 个端点」已过期　P3

现状：62 受控 + 12 PUBLIC + 1 ANY_OF = 75。测试类的 javadoc 同样写着 67。
- **建议**：两处都不写死数字，或改为引用矩阵产物的统计行。

### C4　矩阵产物里 `RECEIVE` 的含义取错　P2

[B端功能矩阵-按角色.md:26](../reference/B端功能矩阵-按角色.md#L26) 显示
`RECEIVE` = 「B 端权限码与角色定义」——那是 `BizPerms` 的**类** javadoc。

- **根因**：[gen-biz-role-matrix.mjs:26](../../../scripts/gen-biz-role-matrix.mjs#L26) 的
  `\/\*\*([\s\S]*?)\*\/\s*` 惰性匹配会从类注释一路跨到第一个字段的 `*/`。
  只有**第一个**常量会中招，所以看起来像手滑而不是 bug。
- **建议**：把注释捕获限制在「不含 `*/` 的一段」，或直接要求注释与字段间只隔空白与缩进。
  改完重跑 `node scripts/gen-biz-role-matrix.mjs`（产物不手改）。

---

## 四、需求层（D 组）

前三组是「实现之间对不上」，这一组是**实现已经把需求越过去了，而需求文档还停在昨天**。
方向与常见的文档债相反：不是代码欠着需求，是需求欠着代码。

### D1　「唯一权威」需求文档整章过期　**P0（文档）**

[三端角色权限功能对齐清单](../../requirements/三端角色权限功能对齐清单.md)（2026-08-11）
开篇自称「**本文是角色与权限的单一事实源**」「代码是事实，矩阵是规格，本文是两者的对账」。
一天之后，它对账的那一侧变了，而它没变：

| 章节 | 文档写的 | 现状 |
|---|---|---|
| §0.1 | B 端「端点最健康，但**授权是空的**」 | 62 端点带 `@PreAuthorize`，4 条守卫盯着 |
| §0.3 缺陷 #1 | 🔴 `StaffRole` **全库无一处判权**，店长与店员权限完全一样 | 已修复 |
| §2.1 现状 | B 端 3 个角色 | 6 个（`MchStoreRole` 5 个常量 + OWNER） |
| §四 标题 | 「B 端权限码（13 个，**待实施**）」 | 13 个已实施 |
| §十 实施顺序 ① | 「B 端角色判权 ← 小、独立、**现在就能做**」 | 已完成，且**正是按 §10.1 说的「先写守卫让它先红」做的** |
| §0.1 数字 | 前端 63 / 后端 67 | 62 受控 + 12 PUBLIC + 1 ANY_OF = 75 |

- **为什么是 P0**：这份文档的地位决定了它的杀伤力 —— 下一个接手的人读到「🔴 越权，
  全库无一处判权」，合理的反应是**再实现一遍**。§10.1 甚至写好了实施手法，
  照着做一遍会撞上已经存在的守卫。
- **建议**：§0.3 缺陷 #1 划掉并注明落地日期与 commit；§二 标题改为「六个角色（已落地）」；
  §四 去掉「待实施」；§十 的 ① 标 ✅ 并把 §10.1 的手法改写成「已验证有效」的结论 ——
  那段经验值得留着，它是运营端 ② 的模板。

### D2　配送员的订单视图未裁剪 —— 需求里唯一没落地的一条　**要拍板**

这是 D 组里**唯一不是文档问题的**。

- **需求原文**（§4.4）：`biz:order:view` 那一行，配送员标的是 **🟡 不是 ✅**，并附注：
  「**全方案唯一需要新增后端能力的地方**……他要『待自送的单 + 地址』，
  不该看到金额与全店订单。手法照抄 `PickupOrderVO` 那套裁剪，**不用条件序列化藏字段**。」
- **现状**：`COURIER` 拿到的是与 MANAGER / CLERK / CS **完全相同**的 `biz:order:view`；
  [BizOrderController.java:33](../../../backend/shop-core/src/main/java/ai/neargo/shop/trade/api/biz/BizOrderController.java#L33)
  的 `/biz/order` 对所有持码者返回同一个完整 `OrderVO` —— 含 `Amount`（货款/运费/优惠/实付）、
  `verifyCode`、`pickupName`，且是**全店**订单不是「待自送」。
- **产物把这条需求吃掉了**：矩阵只有 `✅ / —` 两种符号，表达不了 🟡，
  于是 [B端功能矩阵-按角色.md:30](../reference/B端功能矩阵-按角色.md#L30) 里
  配送员的 `ORDER_VIEW` 是一个和店长一样的 ✅。**需求上的「受限」在下游三层里都不存在了。**
- **顺带**：`verifyCode` 出现在列表里，意味着 COURIER 与 CS 读得到全店取货码。
  **不构成绕过**（核销端点仍要 `biz:verify`），但取货码是凭证，值得一并决策。
- **三个选项，我不替你选**：
  1. **按需求做**：新增 `CourierOrderVO`（单号 + 地址 + 状态，无金额无码），
     `/biz/order` 按当前角色选 VO —— 需求原文指定的手法。
  2. **加一个码**：`biz:order:view:limited`，配送员换成它，`/biz/order` 按码分岔 ——
     好处是判权仍只看码，坏处是 13 变 14，且「同一端点两种返回」要写进契约。
  3. **明确降级**：接受配送员看得到金额，把 §4.4 的 🟡 改成 ✅ 并写下理由
     （比如「小店场景配送员多半就是店员兼的」）——**这也是一个正当的答案**，
     但它必须被写下来，否则下次盘点又会把它当成缺陷重新报一遍。

### D3　需求与实现的三处口径差　P2

| # | 需求 | 实现 | 判断 |
|---|---|---|---|
| a | §4.3 `biz:goods` 覆盖「规格模板」 | `/biz/spec-templates` 在 `PUBLIC` 里 | **实现是对的** —— 还没建店的申请人要选模板；改需求那一行 |
| b | §4.3 `biz:verify` 覆盖「按码搜索」 | 端点有，前端无（B1） | 需求对，缺的是前端 |
| c | §七「后端 5 个前端没调」 | 实际 9 个；`/biz/context` 前端已接（`mBizScope`） | 名单过期，随 D1 一起重生成 |

### D4　`B端功能清单.md` 的三个数都过期　P1

[B端功能清单.md](../../requirements/B端功能清单.md)（2026-08-06，自述「依据代码现状重写」）：

| 位置 | 写的 | 实际 |
|---|---|---|
| §一 角色 | 3 行：店主 ✅ / 自提点承接方 ✅ / **店员 ⬜「开放入驻后必做」** | 6 个角色已落地 |
| §二 页面地图 | 「20 页，与 `pages.json` 一致」 | **23 页** |
| §三 | 「13 个业务域，46 个契约方法」 | 14 个域，**75 个契约方法** |

- **页面地图缺的正好是 `payment` / `staff` / `stores` 三页** —— 也就是
  `biz:finance` 与 `biz:store:admin` 那两个「只有老板能碰」的入口。
  换句话说，**功能清单漏掉的恰恰是权限分级最需要说清楚的那三页**。
- **建议**：§一 的角色表直接换成矩阵产物的 13 × 6 表（或链过去，不要抄第二份）；
  页面地图补三页并标出各自的权限码 —— 页面 × 权限码这层映射现在**哪份文档都没有**，
  而 A 组六条问题全部出在这一层。

### D5　`需求矩阵-三端.md` 把已交付的当成没做　P1

- 第 70 行「商家店员（**P1**）……无财务、无结算账户可见性」—— 已落地，且约束已由
  `BizPermsTest.managerTouchesNeitherMoneyNorStaff` 锁住。
- 第 72 行「配送员（**P1**）……**只见配送单**」—— 与 D2 是同一条约束的另一处表述，
  两处必须一起改，否则改完一处另一处仍在报同一个缺陷。
- 第 209 行 B-11.10「角色 × 门店范围双维授权」仍标 **⬜** —— 这是本轮工作的正题，已完成。

### D6　这轮判权工作没进交付记录　P2

[待完成功能清单.md](../../requirements/待完成功能清单.md) 的「工程侧待办」有 E1–E29，
既没有一条对应 B 端判权（不在待办），也没有一条记它已完成。
按该文档的既有格式补一条（如 `E30 B 端六角色判权 ✅ 2026-08-12`），
写清楚「守卫先红再变绿」这个手法 —— 运营端 ② 要复用它。

---

## 五、员工与角色管理现状（E 组）

> 起因是一个具体问题：**B 端有没有「店长增加门店员工、修改角色和权限」的功能？**
> 结论：**加人与改角色有，但只有老板能用；「修改权限」这个功能整个不存在** ——
> 两条都是有意设计，不是遗漏。

### E1　店长不能加人、不能改角色 —— 有意设计　**确认题**

三个员工管理端点**全部**要 `biz:store:admin`，而这个码**只有 `OWNER` 有**：

| 功能点 | 端点 | 权限码 | 谁能用 |
|---|---|---|---|
| 员工列表（含停用，手机号脱敏） | `GET /biz/staff` | `biz:store:admin` | **仅老板** |
| 加员工 | `POST /biz/staff` | `biz:store:admin` | **仅老板** |
| 停用 / 启用 | `POST /biz/staff/{mchAccountNo}/status` | `biz:store:admin` | **仅老板** |
| 授予 / 撤销某店的某个角色 | `POST /biz/staff/{mchAccountNo}/store` | `biz:store:admin` | **仅老板** |

依据链完整，三层都写着同一句话：

- **需求**：[三端角色权限功能对齐清单](../../requirements/三端角色权限功能对齐清单.md) §4.5 ②
  「**店长不能管员工。** 授权别人 = 扩散权限，他能给自己加店、或把店员提成店长。」
- **代码**：`BizPerms.ROLE_PERMS` 里 MANAGER 的 11 个码不含 `STORE_ADMIN`
- **测试**：`BizPermsTest.managerTouchesNeitherMoneyNorStaff`（★★，已锁住）
- **前端**：[staff/index.vue](../../../b-app/src/pages/staff/index.vue) 门禁
  `:denied="!merchant.can('biz:store:admin')"`，店长看到的是 denied 空态

**老板实际能做的**（[BizMerchantController.java:387-424](../../../backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizMerchantController.java#L387)）：

1. **加员工**：只填 11 位手机号。**不发密码、不建 C 端账号** —— 他用自己的手机号 +
   验证码走 `/biz/auth/staff-login` 登录。已停用的同号会被**重新启用**而不是报「已存在」。
2. **停用 / 启用**：**老板不能被停用**（那是个能把自己锁在门外的按钮）；
   停用**不删门店授权**，他回来时授权还在。
3. **逐店逐角色授权**：`grantStore(账号, 门店, 角色, granted)`，**增量式不是覆盖式** ——
   老板想「再加一个配送员」不会把「店员」冲掉。撤到一个不剩 = 从这家店移除他。
   前端把六个角色分成两层：默认只显示店长 / 店员，理货员 / 配送员 / 客服收在「更多角色」里。

### E2　「修改权限」不存在，且**不打算做**

角色 → 权限码的对应关系硬编码在 `BizPerms.ROLE_PERMS`，**没有任何端点能改它**，
商家侧也没有任何界面。这是需求里明确写下的决定
（[对齐清单 §十一](../../requirements/三端角色权限功能对齐清单.md)「不做什么」）：

> 让商家自定义**权限码**（13 列勾选矩阵）—— 六个角色 + 可叠加已覆盖真实分工。
> 开放到权限码这一层，店主要面对一张他看不懂的表，
> 而他想问的是「这个人能不能帮我发货」。**角色是答案，权限码矩阵是把问题还给他。**

所以老板能调的是**「谁是什么角色」**，不是**「什么角色能干什么」**。
后者要改得发版 —— 这是已知代价，理由是「硬编码的问题是『改要发版』，不是『对不上』」。

### E3　员工管理确实缺的三件　P2

| 缺什么 | 现状 | 影响 |
|---|---|---|
| **删除员工** | 只有停用 | 停用够用（保留授权便于回归），但列表会越来越长 |
| **改登录手机号** | 无端点 | 店员换号只能停用旧号 + 新建，历史授权全部重配 |
| **操作日志** | **需求 B-11.10.3 明确列了，实现里没有** | 「谁把谁提成了店长」查不到；`MerchantStaffServiceImpl` 里没有任何审计写入 |

前两条建议按实际反馈再排；**第三条建议补** —— 授权变更是权限扩散的唯一入口，
它恰恰是最该留痕的动作，而现在它是全链路里唯一没有痕迹的一环。

### E4　代码里两处注释已经过期　P3

`StaffVO` 的 javadoc 停留在「店长能管员工」的旧模型：

- 「`loginPhone` 脱敏……**店长能看到所有店员的手机号**，那就等于一份可导出的通讯录」
  —— 脱敏这件事仍然对，但理由过期了：**店长现在根本进不了这个端点**。
  同一句话在 [BizMerchantController.java:387](../../../backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizMerchantController.java#L387) 又写了一遍。
- `StoreRoleVO.role` 的注释：「`MANAGER`（店长）/ `CLERK`（店员）」——
  实际是 5 个（`MchStoreRole.VALID` 里 MANAGER / CLERK / PICKER / COURIER / CS）。

改成「老板」即可；第二条直接指向 `MchStoreRole.VALID`，别再列第二份。

---

## 六、已核对、无需改动

- 三条判定规则（`*` 不走表 / 并集 / 空集=零权限）在
  [BizPerms.java:125](../../../backend/shop-base/src/main/java/ai/neargo/shop/auth/BizPerms.java#L125) 有实现且有测试
- 角色跟门店走：`BizContext.staffRoles()` 按 `currentStoreNo` 取，切店重拉 `perms`
  （[merchant.ts:106](../../../b-app/src/stores/merchant.ts#L106)）
- `ensureScope` 挂在 App 外壳上，解决「刷新后 perms 为空 → 界面自己锁死」
- 汇总端点 `/biz/dashboard/todo` 用 `canAnyBiz`，粒度交给端上按 perms 裁
- 前端 `can()` 在 perms 未加载时返回 false（fail-closed）

---

## 七、这次的核对方法 —— 建议固化成守卫

上面 A 组每一条都是同一个机器可判的命题：

> **页面的 `denied` 门禁，必须 ⊇ 该页所有 `api.*` 调用在
> `BizEndpointPermTest.REQUIRED` 里要求的权限码。**

三份输入全部已经是结构化的：`pages/*/index.vue` 的 `denied=` 与 `api.xxx`、
`endpoints.ts` 的 `path`、`REQUIRED` 的端点→码。
写成 b-app 下的一条测试之后，**新加一个页面或在页面里多打一个端点时它会红**，
而今天这 6 条只能靠人逐页读 —— 与后端 `everyBizEndpointHasADecision` 是同一个道理：
不是防止今天写错，是防止明天忘记。

漏判在前端不是安全问题（后端拦住了），但它的表现是「这家店今天什么都没有」，
**没有任何报错** —— 与方案 §四 里 DataScope 那两次栽跟头是同一种失败方式。

同理，D1/D4/D5 三条文档过期也有一条机器可判的命题：
**需求文档里标 ⬜/🔴/「待实施」的条目，不能对应到已经带 `@PreAuthorize` 的端点。**
这条不必现在就写，但它解释了为什么 D 组会同时出现在三份文档里 ——
**没有任何机制在实现落地时提醒需求文档跟上**。

---

## 八、建议顺序

```
① D2 拍板（配送员订单视图三选一）      ← 只有它会影响后端与契约，先定
② A1 A2 A3（P0 三页）                 ← 用户可感知，改动都在 b-app，各 10 行以内
③ D1 D4 D5（三份需求文档同步）         ← 一次做完，否则改一份另两份继续报同一缺陷
④ A4 A5 A6 + C1 C2 C3 C4              ← 收尾
⑤ §六 的前端守卫 + B1 前两条排期        ← 防明天忘记
```

- ① 与 ② 之间没有依赖，可以并行；但 ② 里的 A1 若选了 D2 的方案 1/2，
  配送页要跟着改一次数据源，**顺序反了会返工**。
- ③ 建议连着 `node scripts/gen-biz-role-matrix.mjs` 一起跑（C4 修完之后），
  让三份需求文档统一引用产物，而不是各抄一份角色表 ——
  今天这三处不一致，起因就是抄了三份。
