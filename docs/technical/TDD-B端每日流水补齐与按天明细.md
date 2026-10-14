# TDD-B 端每日流水补齐与按天明细

状态：**已实现**
档位：1（动了端点查询参数 + 对外 JSON 多一个字段 + i18n 词条；不动库表、权限码、配置项、路由）
关联：[TDD-供应商结算与双轨资金](TDD-供应商结算与双轨资金.md) §2.3 第 2 条 / §3.3（草稿待确认）、
[PRD-商家资金到账与对账](../requirements/PRD-商家资金到账与对账.md)、
[ADR-011 商家资金走自营供应商模式](ADR/ADR-011-商家资金走自营供应商模式.md)（已决策 2026-09-29）
创建：2026-10-09 · 最后更新：2026-10-09

## §0 对账一 · 需求 → 设计

用户 2026-10-09：「b 端 app 要展示收入清单以及每天的收入总额」。

**查完现状：两半都已经在了**，缺的是它们之间那条路，以及已定设计漏掉的两列。

| AC | 需求（一句话） | 落点 | 新/旧 |
|---|---|---|---|
| AC1 | 每天一行，带净额与笔数 | `income/index.vue` 每日流水 | **已有** |
| AC2 | 逐笔清单，带完整扣款分解 | `settle/index.vue` | **已有** |
| AC3 | 每天那行**也要说佣金与服务费** | `income/index.vue` 两行文案 | 新（设计已定、实现漏了） |
| AC4 | 每天那行**点开能看是哪几笔** | `/biz/settle/bills?day=` + 清单页筛选态 | 新（设计已定、实现漏了） |
| AC5 | 筛出来的笔数与净额**必须等于**那天显示的 | 共用同一处日界算法 | 新 |
| AC6 | 清单行上的日期是**成交日**，不是入库时刻 | `SettleBillVO.accruedAt` | 新 |
| AC7 | 能看上月，不止近 30 天 | 收入页三枚区间胶囊 | 新 |

**孤立项**

- 没落点的 AC：无。
- **挂不上 AC 的设计**：无。
- **明确不做**见 §2 末。

## §1 现状与根因

### 两半都在，但断着

| 用户要的 | 在哪 | 长什么样 |
|---|---|---|
| 每天的收入总额 | 「收入」页「每日流水」`income/index.vue:211` | 日期 + 当天净额 + 笔数；退款/快递费只在非零时出现 |
| 收入清单 | 「结算单」页 `settle/index.vue:295` | 一笔子订单一行，成交额/运费收支/佣金/服务费/净额/应结日/批次 |

两个入口在「我的」上**并列两行**（`me/index.vue:240`、`:244`），互不相通。

### 已定设计漏了两处实现

`TDD-供应商结算与双轨资金` §2.3 第 2 条原文：

> **每日流水**（新增）：一行一天 = 成交/退款/**佣金/服务费**/净额/单数，**点开看明细**

实测对照（2026-10-09，我自己读的源码）：

| 设计里写了 | 后端 | 端上 |
|---|---|---|
| 成交 / 退款 / 净额 / 单数 | ✅ `DailyFlowVO` | ✅ 模板在用 |
| **佣金 / 服务费** | ✅ `DailyFlowVO.commissionMinor/serviceFeeMinor` | ❌ **零引用** |
| **点开看明细** | ❌ 没有按天的查询口子 | ❌ 那一行不可点 |

所以 AC3/AC4 不是新需求，是**契约已经把数送到端上、而页面没拿**。
这正是 [[write-without-readback]] 那条坑的另一面：后端加了字段，端上没接。

### 「我的钱少在哪」只剩这张表能答 —— 而它漏了最大的两笔

`DailyFlowVO.freightCostMinor` 的注释自己写着：

> 提现入口撤掉之后（ADR-011 §6），商家问「这个月我的钱少在哪」只剩这张表能答

而这张表**说了快递费（小头），没说佣金与服务费（大头）**。
按费率卡 `merchantOwnedRate` / `platformRate` 的量级，佣金通常是扣款里最大的一项。

### 清单页显示的日期不是每日流水聚合用的那个

`SettleBillVO` **没有 `accruedAt`**，清单行显示的是 `createdAt`。
而每日流水按 `accruedAt` 聚合（`SettleServiceImpl#dailyFlows`，口径与运营端三维统计逐字一致）。

**这是 AC4 的真实障碍**：筛 `day=10-08` 之后，行上显示的 `monthDay(createdAt)`
可能是 10-07 —— 看起来像筛错了，而商家下一步是打客服电话说「你们筛坏了」。
所以 AC6 不是顺手做的美化，是 AC4 能不能成立的前置。

### 为什么这个缺口一直没人发现

每日流水与结算单**各自都是对的**，每一页单独看都完整。
断的是两页之间那条路，而**没有任何一道闸门会去查「两页之间有没有路」**。

## §2 方案（= 实施计划）

### 契约变更

| 项 | 改什么 |
|---|---|
| 端点 | `GET /biz/settle/bills` 增加可选查询参数 `day`（`yyyy-MM-dd`，按**成交日**筛）。路径、方法、权限码不变 |
| 对外 JSON | `SettleBillVO` 增加 `accruedAt`（`Long`，可空 —— 存量行没有成交日） |
| i18n | 新增词条见下表，三语齐 |
| 库表 / 权限码 / 配置项 / 路由 | **无** |

**不加索引**：`TDD-供应商结算与双轨资金` §3.2 已拍板「一期不加复合索引，等有性能证据再加」。
`day` 下推成 SQL `between` 已经把传输量从全量降到一天，这一步不需要新索引撑。

新增 i18n 词条（×3 语言）：

| 键 | 中文 |
|---|---|
| `income.dailyCommission` | 佣金 {a} |
| `income.dailyFee` | 服务费 {a} |
| `income.rangeLast30` | 近 30 天 |
| `income.rangeThisMonth` | 本月 |
| `income.rangeLastMonth` | 上月 |
| `settle.dayFilter` | {d} 这一天 |
| `settle.dayBills` | {n} 笔 · 合计 {a} |
| `settle.dayClear` | 看全部 |
| `settle.accruedNone` | 无成交日 |

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `SettleServiceImpl#dayRange(String day)` | **私有工具，返回 `[fromMs, toMs]`**。`dailyFlows` 与 `billsFor` 都调它 |
| 修改 | `SettleService#merchantBills` | 加 `String day` 形参 |
| 修改 | `SettleServiceImpl#billsFor` | `day` 非空时 SQL `between accrued_at` |
| 修改 | `SettleBillVO` | 加 `accruedAt` |
| 修改 | `SettleServiceImpl#toVO` | 带上 `accruedAt` |
| 修改 | `BizSettleController#bills` · `BizSettleAppService#bills` + impl | 透传 `day` |
| 修改 | `b-app/src/api/{contract,http}.ts` | `mSettleList(allStores?, day?)` |
| 修改 | `b-app/src/api/mocks/settle.ts` | 跟上 `day` 与 `accruedAt` |
| 修改 | `packages/shared/src/types/merchant.ts` | `SettleBill.accruedAt` |
| 修改 | `b-app/src/pages/income/index.vue` | 佣金/服务费两行；整行可点跳转；三枚区间胶囊 |
| 修改 | `b-app/src/pages/settle/index.vue` | `onLoad` 读 `day`；筛选态；行上日期改成交日 |
| 新增 | `b-app/tests/income-daily-detail.test.ts` | 判据见 §5 |
| 新增 | `BizDailyFlowFlowTest` 的新用例（同文件，不另起类） | **不另起 `@SpringBootTest`** —— 多一个 context 会挤掉缓存里的，见本仓库已踩过的那次回归 |
| 重跑 | `node scripts/check-generated-docs.mjs --check` | 提交**之前**跑，不是提交之后 |

### 关键设计决定（三条，都有理由）

**① 日界算法必须是同一处代码，不是同一套规则。**
`dailyFlows` 现在在 Java 里按 `ZoneId.systemDefault()` 算日界并取「`to` 那天最后一毫秒」
（不是次日零点 —— 后者会把次日零点整那一笔算进来）。`day` 筛选若自己再写一遍，
两处迟早走岔，而**走岔的表现正是 AC5 要拦的那个**：点开 7 笔的那天看到 6 笔。
抽成 `dayRange()` 让 AC5 变成结构上成立，而不是靠巧合成立。

**② 筛选态要把无关的卡收起来。**
结算单页顶上有四个入口卡 + 费率卡 + 积分卡。带 `day` 进去时它们一个都不相关 ——
商家是来核一天的账的，不是来改积分开关的。`day` 非空时只渲染筛选条 + 逐笔。

**③ 复用结算单页，不新开一屏。**
那一页的逐笔渲染里有实测攒出来的东西：运费四态（自寄 / 代寄未称重 / 已扣 / 超重）、
批次挂起原话照抄、多店才显示门店与收款号。抄一份必然走岔，
而结算页注释自己就警告过这件事（「两套实现迟早有一套忘了跟上授权模型的变化」）。

### 退款那笔的口径（AC5 的陷阱）

`dailyFlows` 对 `REVERSED` 的处理是：**只计 `refundMinor`，不冲减当天 `gross/net`**
（被退的那笔在它自己成交那天已经记过）。所以 `day` 筛出来的行里会**有** `REVERSED` 行，
而它们的 `netMinor` 不属于那天的 `netMinor`。

断言要分开写，不能写成一个「合计相等」：

```
非 REVERSED 行的 netMinor 之和 == 那天的 netMinor
   REVERSED 行的 netMinor 之和 == 那天的 refundMinor
所有行数                        == 那天的 billCount
```

写成一个合计的话，**退款那天永远对不上，而其余每一天都是绿的** —— 那种红会被当成偶发。

### 明确不做

- **月份任选 / 日历选择器**：只给「近 30 天 / 本月 / 上月」三枚。
  再往前翻没有需求证据，而 `/bills` 与 `/daily-flow` 底下都是无分页全量
  （`selectList`，`SettleServiceImpl:807`）—— 放开任意区间等于放开一个没有上限的查询。
- **把默认从「近 30 天」改成「本月」**：月初打开会只剩一两行，
  商家第一反应是「我的数据没了」。默认不动。
- **分页 / 日快照表**：`TDD-供应商结算与双轨资金` §3.2 已拍板「物化留到有证据时」，
  理由是第二真源必须与明细保持一致，而这类不一致在本仓库出过不止一次。
  **但本 TDD 要记一笔**：两个接口都是无上限全量，撞 <1s 预算只是时间问题（见 §8）。
- **给 `/bills` 补详情页**：`GET /biz/settle/bills/{settleNo}` 在
  `known-app-backend-orphan.txt:50`，那份名单的表头已判过「列表端点返回的就是
  `List<SettleBillVO>`，与详情同构」—— 要动的是删后端那个，不是给它补入口。
- **改「我的」上那两行的排布**：`me/index.vue` 注释写明那是补位、以做「钱」那条线的人为准。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC3 | `income-daily-detail.test.ts#AC3 每日那行说了佣金与服务费` | ✅ | ✅ 删掉佣金那行 → 红（2 条） |
| AC3 | 同上 `#AC3 佣金与服务费只在非零时出现` | ✅ | ✅ 同上一次消融一起红 |
| AC4 | `BizDailyFlowFlowTest#dayFilterReturnsOnlyThatDay` | ✅ | — （由下面那条的消融覆盖） |
| AC4 | `income-daily-detail.test.ts#AC4 每日那行可点` | ✅ | ✅ 删掉 `@tap` → 红（2 条） |
| **AC5** | `BizDailyFlowFlowTest#dayFilterAgreesWithThatDaysRow` | ✅ | ✅ **`dayRange` 换 UTC → 只有这一条红，点名准确** |
| AC5 | 同上（分开断言 net / refund，见 §2） | ✅ | 同上 |
| AC6 | `BizDailyFlowFlowTest#billsCarryAccruedAt` | ✅ | — |
| AC6 | `income-daily-detail.test.ts#AC6 清单行显示成交日` | ✅ | — |
| AC7 | `income-daily-detail.test.ts#AC7 三枚区间胶囊` | ✅ | — |
| 回归 | `BizDailyFlowFlowTest#nullDayKeepsOldBehaviour` | ✅ | — |
| AC4 | `BizDailyFlowFlowTest#undatedRowsNeverMatchAnyDay` | ✅ | — |

**跑过的实际数字**：`BizDailyFlowFlowTest` 11/11（原 6 + 新 5）、
`b-app` 全量 180/180（27 个文件）、`income-daily-detail.test.ts` 15/15、
`vue-tsc --noEmit` 0 错。全部在 **HEAD 干净副本**上跑（工作区有并行会话的在途改动，
直接在工作区跑分不清谁的红）。

### 写测试时自己踩的一个坑（记下来）

`income-daily-detail.test.ts` 第一次跑红了两条，而**代码里一个问题都没有** ——
我在注释里写了「不用 toISOString」和那个动态键的形状，于是
`not.toContain("toISOString")` 恒红。修法是给反向断言加一层 `code()` 去注释，
不是改注释迁就断言。

## §5b 界面自己看过（H5 预览，mock）

| 看的 | 结果 |
|---|---|
| 收入页每日流水 | 佣金 / 服务费 / 快递费都出来了，且**都只在非零时出现**（09-29 那天自带客流零佣金，就没有佣金那一行） |
| 三枚区间胶囊 | 「上月」→ 5 行全在 2026-09；「本月」→ 只剩 2026-10-08；默认停在「近 30 天」 |
| 点开 2026-10-08 | 跳 `settle?day=2026-10-08`，筛选条写「2026-10-08 这一天」，小计 **「1 笔 · 合计 ¥20.50」** |
| **AC5 在界面上** | 那一笔的服务费 ¥0.30 与收入页那一行的「服务费 ¥0.30」逐字对上 |
| 筛选态 | 四个入口卡 / 费率卡 / 积分卡都收起来了；「看全部」点完全部回来 |
| 存量行 | 清单里那一行显示「无成交日」，收入页底下「另有 1 笔没有成交日期，合计 ¥67.00」仍在 |

⚠️ **中间出过一次假读数**：点「看全部」后读到「0 笔」，看着像没生效。
实际是共享 dev server 的 HMR 把页面整个重挂了（控制台 `[vite] hot updated` + `connecting`），
读到的是重挂后 `bills` 还没拉回来的那一瞬。**重点一次、同一批里读**，结论才成立。

**AC5 是本次的核心判据。** 它要拦的不是「功能有没有」，而是
**「两个口径有没有走岔」** —— 而走岔的样子是「点开 7 笔的那天看到 6 笔」，
不报错、不变红、只在某个跨日界的订单上出现一次。
所以它的消融不是「删掉功能」，是**换掉时区** —— 只有这样才证明那条断言真的在量日界。

**前端那几条是源码断言，代价说清**：b-app 没有 `@vue/test-utils`，
而给它装一个会打断小程序构建（本仓库已知坑）。源码断言只能证明「那几行在文件里」，
证不了渲染结果 —— 所以界面本身**另外用 H5 预览自己看一遍**（§6 填截图结论）。

## §6 对账二 · 设计 → 实现

```
后端
  backend/pay/pay-domain/.../pay/SettleService.java            merchantBills 加 day
  backend/pay/pay-domain/.../pay/dto/SettleBillVO.java         加 accruedAt
  backend/pay/pay-domain/.../pay/impl/SettleServiceImpl.java   dayRange() + billsFor 下推 + toVO
  backend/shop-app/.../payclient/BizSettleAppService.java      透传
  backend/shop-app/.../payclient/impl/BizSettleAppServiceImpl.java
  backend/shop-app/.../portal/biz/pay/BizSettleController.java @RequestParam day
端上
  packages/shared/src/types/merchant.ts        SettleBill.accruedAt + 两条注释
  b-app/src/api/{contract,http}.ts             mSettleList(allStores?, day?)
  b-app/src/api/mocks/settle.ts                day 筛 + accruedAt + 每日流水改按成交日分天
  b-app/src/i18n/locale/{zh-CN,en,ar}.ts       9 条 ×3
  b-app/src/pages/income/index.vue             佣金/服务费两行 + 行可点 + 三枚区间胶囊
  b-app/src/pages/settle/index.vue             onLoad day + 筛选态 + 行上日期改成交日
测试
  backend/shop-app/src/test/.../BizDailyFlowFlowTest.java      +5 条
  backend/shop-app/src/test/.../M5AfterSaleFlowTest.java       跟签名
  b-app/tests/income-daily-detail.test.ts                      新增 15 条
生成物（在只带本次改动的干净副本上跑，避免生成器吃进并行会话的脏区）
  docs/api/openapi-b.yaml · docs/api/openapi.yaml
  docs/technical/design/ui-lib.json
  docs/technical/reference/glossary.json · 中英文对照-词条.md
  docs/technical/README.md
```

### 偏差说明

**一处与 §2 不同**：§2 只列了 `openapi-b.yaml`，实际 `openapi.yaml`（C 端那份）也变了 ——
`SettleBill` 的类型定义在 `packages/shared`，两端的 spec 都从那里取 schema。
不是设计错了，是我漏算了共享类型的影响面。

**差点把别人的改动一起提交**：三份 locale 在主树里**已经是脏的** ——
并行会话加了一条 `trace.atLocker` 还没提交。我是在那份脏文件上加的词条，
于是它既进了我的 `git diff`，也**被生成器写进了我重跑的词表产物**。

两处都修了：产物在剔掉那一行之后重出（`中英文对照-词条.md` 的新增回到正好 9 条），
locale 文件按 hunk 挑着入库（那一条独占一个 hunk，与我的两段不相邻）。

**这件事值得记一笔**：生成物会把工作区里别人的在途改动一起固化，
而产物的 diff 看起来和源码一样「是我改的」。
共享目录里跑生成器，要么在只带自己改动的副本上跑，要么跑完逐条看新增。

**`docs/technical/design/规范-页面.md` 故意没动**：它在 HEAD 上也是过期的
（122 → 123 页），但主树里**另一个会话正在改它**。覆盖过去就是踩掉别人的改动。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-09 | 草稿。用户 2026-10-09「需要」= 确认按本方案做 AC3/AC4/AC5/AC6/AC7 |
| 2026-10-09 | **已实现**。测试与消融见 §5，界面实测见 §5b，产物与偏差见 §6 |

## §8 本 TDD 范围外、但已记下的一笔

`merchantBills` 与 `dailyFlows` 共用的 `billsFor` 是
**无上限、无分页的 `selectList`**（`SettleServiceImpl:807`）。
加了 `day` 之后「看一天」这条路有上限了，**但「看全部」那条没有** ——
而全站预算是每个接口 <1s（[[api-latency-budget-1s]]）。

不在本 TDD 里做（见 §2「明确不做」第三条的理由），但**也不该靠这份文档被读到才知道**：
等商家单量上来，先红的是结算单页首屏。
