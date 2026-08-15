# TDD-运营端风控域

状态：**已确认（2026-08-13 用户授权批量推进）**
关联需求：[需求矩阵-三端](../../requirements/需求矩阵-三端.md) §六 **P-16.2 风控**
上游：[ADR-004 增长模型从孵化团长转向商家自带客流](../ADR/ADR-004-增长模型从孵化团长转向商家自带客流.md) §6
创建日期：2026-08-13
覆盖：16.2.1 刷单识别 · 16.2.2 异常裂变（同设备/同 IP） · 16.2.3 恶意退款画像 · 16.2.4 黑名单与申诉 · 16.2.5 拦截规则配置

---

## 一、一句话

把「谁在薅、薅了多少、拿他怎么办」变成运营看得见、能配、能处置的一层：
**事件由真实交易与真实归因链路自动产生**，运营只做裁决与拉黑，
**这一版不在下单/支付链路上做实时拦截**。

## 二、为什么是这个方案

### D1 事件从哪儿来

| 方案 | 结论 |
|---|---|
| A. 运营手工录入风险事件 | ❌ 那不是风控，是台账。没有任何一条「识别」需求被满足 |
| B. 定时全表扫描订单/售后 | ❌ 全表扫描要跨域直读 trade 的表（ArchUnit 第 1 条当场拦下）；且扫描窗口与阈值一改就要重扫历史 |
| **C. 消费 `sys_outbox` 里已经在发的领域事件**（`ORDER_CREATED` / `AFTER_SALE_REFUNDED`），命中阈值时落一条风险事件 | ✅ 采用 |

选 C 的关键理由是**它不需要动交易域一行代码**。`OrderEvents` 住在
`shop-base/spi/trade`，是模块间的契约包，消费它不构成 `risk → trade` 的依赖；
而事件载荷自带 `userNo` / `orderNo` / `refundMinor`，不用回查主表。

**防住什么**：防住「为了几个数捅穿一层边界」。第一版曾想让风控直接查
`ord_order`，那样交易域改一个列名，风控就跟着炸，而炸的时候没人会想到是风控。

`OrderEvents.OrderCreated` 的类注释里本来就写着「消费方：marketing · message · **risk(行为画像)**」——
这条消费方一直是空的，本域把它补上。

### D2 异常裂变（同设备 / 同 IP）的数据从哪儿来

归因链路是唯一知道「这个人是被谁、从哪台设备带进来的」的地方。
所以 **16.2.2 由增长域在归因判定的那一刻推给风控**（`RiskEventPort`），
而不是风控反过来去查 `mkt_attribution_log`。

**防住什么**：防住 `risk → marketing` 的域间依赖。方向反过来（marketing → spi.risk）
之后，风控域对增长域一无所知 —— 将来换一套增长玩法，风控不用改。

代价：增长域必须先把设备号与 IP 采下来（见 TDD-运营端增长与归因 §4.2），
本域为此**不做任何埋点**。

### D3 拦不拦

⚠️ **这一版只做「看得见 + 能配 + 能拉黑」，不在下单/支付链路上做实时拦截。**

| 方案 | 结论 |
|---|---|
| A. 命中规则时直接拒绝下单 | ❌ 穿透交易域（要在 `OrderServiceImpl` 里插判断），且需求矩阵没有任何一条说「下单被风控拦」的用户可见行为；阈值配错一次就是全站下不了单 |
| **B. `auto_block` 只是**规则上的一个开关**，命中时把事件标成「建议拦截」，真正的拦截点另说** | ✅ 采用 |

**防住什么**：防住「一个还没有样本、没有误杀率数据的模型，直接握住交易主干的生杀权」。
`ErrorCode.RISK_BLOCKED(60001)` 早就存在且**至今没有任何生产调用方** ——
那不是遗漏，那是同一个判断在更早的时候已经做过一次。

`auto_block` 存起来不是装饰：它是运营对每类风险的**处置意愿声明**，
接拦截点时读的就是它，不用再设计一次配置面。

### D4 一个主体同类风险开几张单

**同一 (type, subject) 在处置完成前只有一张待处置事件**，新证据往老事件上追加
（`signals` / `refs` 合并，`hit_count` 递增）。

| 方案 | 结论 |
|---|---|
| A. 每命中一次开一张 | ❌ 刷单的人一晚上下 200 单 = 200 张待处置，队列直接失去可用性 |
| **B. 开着的合并，处置后再命中才开新的** | ✅ 采用 |

实现靠 `dedup_key` 唯一索引：待处置期间 `dedup_key = type|subject`，
处置之后改写成 `event_no`（自己唯一），于是下一次命中能重新开单。

**防住什么**：防住 at-least-once 投递造成的重复事件 —— Outbox 明确是至少一次，
消费者必须自己幂等。这里的幂等键是 `risk_signal_hit(type, ref)` 的唯一索引：
同一张订单/售后单重投多少次，只会被计一次。

### D5 拉黑必须有到期时间

`until` 必填、且必须晚于当前时间。ops-web 的契约注释写得很直白：
「无期限拉黑没有申诉出口，那是产品事故不是风控严格」。后端照此硬校验。

申诉通过 = `active` 置 false，**记录保留**（留痕，不是删除）。

## 三、结构

```
                    ┌─────────────────────────────┐
  ORDER_CREATED ───▶│                             │
AFTER_SALE_REFUNDED▶│  RiskOutboxConsumer         │
                    │   （幂等：risk_signal_hit）  │
                    └──────────────┬──────────────┘
                                   ▼
  marketing.attribution ──▶ RiskEventPort ──▶ RiskEventService
      （同设备/同 IP）        (shop-base/spi)        │
                                                    ▼
                              risk_signal_hit ──▶ risk_event ──▶ /ops/risk-events
                                     ▲                                  │裁决
                              risk_rule（阈值/自动拦截）                  ▼
                                                            risk_blacklist ◀─ /mp/risk/appeal
```

包落点：`shop-core` 的 `ai.neargo.shop.risk`（新业务域，已登记进
`ArchitectureTest.DOMAINS`，从此受「域间不得互相依赖」约束）。

| 表 | 说明 |
|---|---|
| `risk_event` | 风险事件。三类同表用 `type` 区分 —— 拆三张表会让「这个人同时命中几类」看不出来，而那恰恰最该优先处理 |
| `risk_signal_hit` | 命中流水（append-only）。**阈值判定的唯一数据源**，也是幂等键的宿主 |
| `risk_blacklist` | 黑名单 + 申诉 |
| `risk_rule` | 一类一条，阈值 + 是否自动拦截 |

## 四、详细设计

### 4.1 端点（7 条，路径与形状以 `ops-web/lib/api/https/risk.ts` 为准）

| 方法 | 路径 | 权限码 |
|---|---|---|
| GET | `/ops/risk-events` | `risk:event:read` |
| POST | `/ops/risk-events/{eventNo}/decide` | `risk:event:handle` |
| GET | `/ops/blacklists` | `risk:blacklist:read` |
| POST | `/ops/blacklists` | `risk:blacklist:update` |
| POST | `/ops/blacklists/{blackNo}/appeal` | `risk:blacklist:update` |
| GET | `/ops/risk-rules` | `risk:rule:read` |
| POST | `/ops/risk-rules/{type}` | `risk:rule:update` |

外加一条 C 端（**契约之外，本域自建**）：`POST /mp/risk/appeal` —— 被拉黑者提申诉。
没有它，`decideBlacklistAppeal` 永远等不到 `appealStatus=PENDING`，
是一条**结构上不可达的端点**（死接口）。

### 4.2 校验（照 `ops-web/lib/api/mocks/risk.ts` 的状态机）

| 动作 | 拒绝条件 | 错误码 |
|---|---|---|
| decide | 事件不是 `PENDING` | `RISK_EVENT_HANDLED(60002)` |
| decide | `verdict` 空白 | `BAD_REQUEST` —— 排除也要写理由：下次同一主体再命中时得知道上次为什么放过 |
| addBlacklist | `subject`/`reason`/`until` 任一空白 | `BAD_REQUEST` |
| addBlacklist | `until` 不晚于当前 | `BAD_REQUEST` |
| addBlacklist | 该 subject 已有生效中的记录 | `BLACKLIST_DUPLICATE(60003)` |
| appeal（裁决） | `appealStatus != PENDING` | `BLACKLIST_NO_APPEAL(60004)` |
| appeal（裁决） | `verdict` 空白 | `BAD_REQUEST` |
| saveRiskRule | `threshold <= 0` | `BAD_REQUEST` —— 0 等于全量拦截 |

### 4.3 三类识别

| 类型 | 触发源 | 主体 | 默认阈值 | 判据 |
|---|---|---|---|---|
| `FAKE_ORDER` | `ORDER_CREATED` | `USER`（userNo） | 10 | 24 小时内下单数 ≥ 阈值 |
| `MALICIOUS_REFUND` | `AFTER_SALE_REFUNDED` | `USER`（userNo） | 5 | 30 天内退款次数 ≥ 阈值 |
| `ABNORMAL_FISSION` | 归因判定（`RiskEventPort`） | `DEVICE`（设备号或 IP） | 5 | 窗口内同一设备/IP 归因的**不同用户数** ≥ 阈值 |

阈值全部读 `risk_rule`，运营改完立刻生效（不缓存）。
`risk_rule` **读时自愈**：三条缺哪条补哪条，不靠迁移里的 INSERT ——
迁移的 INSERT 不会进 `schema-test.sql`（生成器只重放 DDL），
靠它做种子的话单测库里永远是空表。

### 4.4 分页与返回形状

四条列表里只有 `/ops/risk-events`、`/ops/blacklists` 是 `Promise<Page<T>>`，
返回 `PageData<T>`；`/ops/risk-rules` 契约是 `RiskRule[]`，返回裸 `List`。

**防住什么**：运营端列表页按 `{records,total}` 渲染，返回裸数组会被当成空页 ——
接口 200、数据几十条、页面显示「暂无数据」，且控制台一条错误都没有。

## 五、取舍记录

| # | 冲突 | 让了谁 | 理由 |
|---|---|---|---|
| T1 | 需求写「拦截规则配置」，直觉是要真拦 | **不拦** | 见 D3。规则先做成配置 + 事件记录，实际拦截点另说 —— 这一条按任务书明确要求写进记录 |
| T2 | mock 里 `subject` 是昵称（「用户8821」「阿May」） | **后端存标识**（`userNo` / `merchantNo` / 设备号），另给 `subjectName` 做展示 | 昵称会改、会重名，按昵称拉黑等于按一个随时会变的字符串封人。契约里只有 `subject`，`subjectName` 是多出来的字段，前端不读也不会坏 |
| T3 | mock 的 `signals` 是中文短语（「同设备」「短时集中」） | **跟随 mock**，后端也出中文短语 | 它是给人看的证据摘要，不是枚举。真要做成枚举得先有风控口径，现在编一套只会让人照着它做决定 |
| T4 | 事件里不给风险分值 | **不给** | 照 `ops-web/lib/types/risk.ts` 的原话：分值口径要等有真实样本后由风控定，现在编一个看起来很准的分数，只会让人照着它做决定 |
| T5 | 恶意退款「画像」听起来要一张用户画像表 | **不建画像表** | 画像 = 命中流水的聚合，`risk_signal_hit` 已经是那份流水。另存一张画像表迟早出现「画像说 7 次、点进去只有 4 次」 |
| T6 | `RISK` 角色后端此前没有任何风控权限 | **补齐 6 个新码** | 见 `Perms.java` 的说明。此前它拿的 4 个码里有 2 个是从 `order:view` 带过来的售后码，风控本不该有 —— 那两条**不动**，收权是另一件事，一次只做一件 |

## 六、验证

`backend/shop-app/src/test/java/ai/neargo/shop/scenario/OpsRiskFlowTest.java`，
手机号段 `145xxxxxxxx`（全仓 grep 确认未被占用 —— 共用号段导致过假绿：
不同测试类复用同一账号，「筛选失效」和「筛选正确」在只数行数的断言下一模一样）。

真链路，不造数据：
1. 同一个买家连续下单 → `dispatchPending()` → `/ops/risk-events` 里出现 `FAKE_ORDER`
2. 阈值改到 2 → 再来一个买家下 2 单就命中（证明配置真的在起作用，不是硬编码）
3. 拉黑 → 该用户 `/mp/risk/appeal` 提申诉 → 运营裁决通过 → `active=false`、记录仍在
4. 无到期时间 / 重复拉黑 / 空结论 / 阈值 0 一律被拒
