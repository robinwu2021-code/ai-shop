# TDD-运营端增长与归因

状态：**已确认（2026-08-13 用户授权批量推进）**
关联需求：[需求矩阵-三端](../../requirements/需求矩阵-三端.md) §六 **P-9.1 归因引擎** / **P-9.2 裂变活动**
上游：[ADR-004 增长模型从孵化团长转向商家自带客流](../ADR/ADR-004-增长模型从孵化团长转向商家自带客流.md) §6；
[待完成功能清单](../../requirements/待完成功能清单.md) §四 B1「归因优先级与窗口期」
创建日期：2026-08-13
覆盖：9.1.1 归因优先级配置 · 9.1.2 窗口期配置 · 9.1.3 归因链路查询与审计 · 9.1.4 冲突裁决 · 9.1.5 店铺码归因规则 · 9.2.1 邀请有礼配置 · 9.2.2 老带新奖励 · 9.2.3 新客判定口径

---

## 一、一句话

**归因规则直接决定商家付多少佣金**（ADR-004 §6：`STORE_CODE` → `MERCHANT_OWNED` 低费率/零佣金，
其余 → `PLATFORM` 正常费率）—— 所以这一版把「优先级、窗口期、冲突策略」
从**代码里的常量**搬到**运营能改的配置**，并且让审计接口读**真实的归因结果**。

## 二、为什么是这个方案

### D1 审计数据从哪儿来

**从 `mkt_attribution_log` 读，不新造一份平行数据。**

这张表已经在跑：`AttributionServiceImpl.report()` 每次判定都写一行，
**包括「没有改变归属」的那次**（`decision=KEPT`）。表注释解释得很清楚：
争议发生在几个月后，那时唯一能还原当时判定的就是它。

| 方案 | 结论 |
|---|---|
| A. 新建 `growth_trace` 表，归因时双写 | ❌ 同一件事记两处，迟早只剩一处在维护；而运营看的那处如果是新的那份，它一定是错的那份 |
| **B. `AttributionTrace` = `mkt_attribution_log` 的一行投影** | ✅ 采用 |

`AttributionTrace` 的六个字段逐一对上：

| 契约字段 | 来源 | 备注 |
|---|---|---|
| `traceNo` | 新增列 `trace_no` | 老行为空时回落成 `AT{id}` |
| `userNickname` | `spi.user.UserQueryPort.find(userNo).nickname` | 跨域走 Port |
| `source` / `attributedAt` | `source` / `at` | 直译 |
| `sourceRef` | `entity_no` / `inviter_no` / `channel` 三选一 | 按 `source` 取，**不混着取** —— 混着取会让「到底按哪个算的」变成猜谜 |
| `orderNo` | 新增列 `first_order_no` | 由 `ORDER_CREATED` 事件回填，见 D3 |
| `conflictWith` | `prev_source != null && decision != CREATED` 时，指向同一用户的上一条 | 这正是 B1 的现实场景：已归属 A 店又扫了 B 店的码 |
| `riskSignals` | 新增列 `risk_signals` | 判定时同步算出来的同设备/同 IP 信号，与风控事件同一套口径 |

### D2 配置怎么真的生效

`AttributionServiceImpl` 此前把两样东西写死了：
`MktAttribution.weightOf()` 里的优先级 `STORE_CODE(3) > INVITER(2) > CHANNEL(1)`，
和 `@Value("${shop.attribution.window-days:30}")`。

**两样都改成读 `mkt_attribution_rule`。**
不这么做的话，运营端的「归因规则」页面就是一个改完之后什么都不会发生的表单 ——
而它管的是费率档，**「看起来改了其实没改」在这里等于账算错**。

**防住什么**：防住配置面与执行面分家。这个形状本仓踩过一次：
`V21` 的券预算列在、领券那条 UPDATE 的闸门在、页面上的预算进度条也在，
唯独运营改不了它 —— 于是预算恒为 0，闸门永远不生效。

### D3 首单号回填

契约里 `AttributionTrace.orderNo` 是「首单订单号」。它不在归因域内 ——
归因发生时用户还没下单。

| 方案 | 结论 |
|---|---|
| A. 查询时回查订单表 | ❌ 跨域直读 trade 的表，ArchUnit 第 1 条当场拦下 |
| B. 给 `spi.trade` 加一个「查某人首单」的 Port 方法 | ❌ 要改 trade 域的实现类；且每查一次列表就是 N 次跨域调用 |
| **C. 消费 `ORDER_CREATED` 事件，把订单号写回归因行** | ✅ 采用 |

`OrderEvents.OrderCreated` 载荷自带 `orderNo` 与 `userNo`，够用。
消费者写在增长域，交易域一行不改。**幂等**：只在 `first_order_no` 为空时写。

### D4 冲突策略的默认值：`OVERWRITE`，不是 `KEEP_FIRST`

⚠️ 这是与 ops-web mock 的一处**刻意不一致**，必须写下来。

`ops-web/lib/types/growth.ts` 把 `KEEP_FIRST` 注成「默认」，
`lib/mock/db/growth.ts` 的种子也是 `KEEP_FIRST`。
但**后端已经上线并被测试钉住的行为是覆盖**：
`M6aStoreAttributionFlowTest.laterStoreCodeOverridesEarlier` 的标题就是
「已归属 A 店的用户扫 B 店码：**覆盖**，且留痕可回放（P-9.1.5）」，
断言 `/mp/store/mine` 的第一家是后扫的 M0002。

所以后端种子取 `OVERWRITE`。

**防住什么**：防住「让文档好看」而把一条已通过测试的既有行为改掉。
矩阵 B1 这一条本来就写着「未拍板」，可配是它的解 —— 不是拿 mock 的占位值当决议。
拍板成 `KEEP_FIRST` 的那天，改的是 `mkt_attribution_rule` 里的一行数据，不是代码。

三种策略在引擎里的语义（**只在来源强度相同时才有分歧**，弱来源永远不覆盖强来源）：

| 策略 | 同强度再次归因 |
|---|---|
| `KEEP_FIRST` | 保持原归属，写一条 `KEPT` |
| `OVERWRITE` | 覆盖，写一条 `REPLACED` |
| `ASK_USER` | **暂按 `KEEP_FIRST` 处理并留痕注明**：C 端没有「问一下用户」的交互，做一半会得到一个悄悄不覆盖的 `OVERWRITE` |

### D5 裂变奖励只能是券

`RewardType` 类型上只有 `COUPON`，运行时再挡一次。
理由照抄 ADR-004：去团长化后不存在现金激励 —— 一旦发现金，
职业薅羊毛立刻回来，**且归因作弊有了直接变现路径**（这正好是风控域存在的另一半理由）。

### D6 邀请台账：为什么要有 `mkt_fission_invite`

`FissionCampaign.invitedCount` / `convertedCount` 如果按「全部 INVITER 归因」现算，
多个活动并存时算出来的是同一个数 —— 页面上两个活动的数据一模一样，
而没有任何报错。

台账表一行 = 一次「在某活动生效期间、由某人邀来某人」，于是：
- `invitedCount` = 台账行数，`convertedCount` = 其中 `first_order_no` 非空的行数（**真数**）
- **新客判定**（9.2.3）有地方落：同一设备 / 同一手机号只算一次新客
- 奖励发放的幂等键有地方落：`uk(fission_no, invitee_no)`

## 三、结构

```
 /mp/store/{no}/enter ─┐                        ┌─▶ mkt_attribution（当前归属）
 /mp/attribution/report┼─▶ AttributionService ──┼─▶ mkt_attribution_log（留痕 = 审计源）
        （带 deviceId / IP）      │              └─▶ RiskEventPort（同设备/同 IP → 风控）
                                  │
                        mkt_attribution_rule ◀── /ops/attribution-rule
                        （优先级/窗口期/冲突策略/新客因子）
                                  │
                                  ▼
                        FissionService ──▶ mkt_fission_invite ──▶ 发券（CouponService）
                                  ▲                  ▲
                        mkt_fission_campaign      ORDER_CREATED（回填首单 + 转化计数）
                                  ▲
                        /ops/fission-campaigns
```

包落点：`shop-core` 的 `ai.neargo.shop.marketing.attribution`（已存在，扩写）
与 `ai.neargo.shop.marketing.fission`（新建）。**归因与裂变同属 marketing 域**，
不新造一个 `growth` 域 —— 它们与券共享出资方、预算与发放链路，拆开就要立刻造三个 Port。

## 四、详细设计

### 4.1 端点（6 条，路径与形状以 `ops-web/lib/api/https/growth.ts` 为准）

| 方法 | 路径 | 权限码 |
|---|---|---|
| GET | `/ops/attribution-rule` | `growth:attribution:read` |
| POST | `/ops/attribution-rule` | `growth:attribution:update` |
| GET | `/ops/attribution-traces` | `growth:attribution:read` |
| GET | `/ops/fission-campaigns` | `growth:fission:read` |
| POST | `/ops/fission-campaigns` | `growth:fission:update` |
| POST | `/ops/fission-campaigns/{fissionNo}/enabled` | `growth:fission:update` |

GET 与 POST `/ops/attribution-rule` **返回同一个 VO** —— 契约守卫按路径归集返回类型，
两者形状不同会被判成冲突，而前端保存后也确实要用返回值刷新表单。

### 4.2 采集：deviceId 与 IP

`AttributionService.Clue` 增加 `deviceId` / `ip` 两个分量，由 `MpStoreController`
从请求体与 `X-Forwarded-For`/`RemoteAddr` 填入。

**为什么在 Controller 取**：`ArchitectureTest.domainsMustNotTouchWebRuntime` 禁止
领域服务碰 web 运行时。取值是 web 层的事，判定是域的事。

**防住什么**：没有这两列，P-16.2.2「异常裂变（同设备/同 IP）」就只能退化成
「某人邀请人数多」—— 那不是需求写的那件事，而看起来很像。

### 4.3 校验（照 `ops-web/lib/api/mocks/growth.ts`）

| 动作 | 拒绝条件 | 错误码 |
|---|---|---|
| saveAttributionRule | `priority` 不是三个来源的**全序**（不重不漏） | `ATTRIBUTION_PRIORITY_INVALID(40006)` |
| saveAttributionRule | `windowDays` 不在 1–90 | `ATTRIBUTION_WINDOW_INVALID(40007)` |
| saveAttributionRule | `newUserFactors` 为空 | `BAD_REQUEST` —— 一个因子都不选 = 所有人都是新客，新人券会被无限领 |
| saveFissionCampaign | `rewardType != COUPON` | `FISSION_REWARD_MUST_BE_COUPON(40008)` |
| saveFissionCampaign | 任一张数为负 | `BAD_REQUEST` |
| saveFissionCampaign | 两边张数都是 0 | `BAD_REQUEST` |
| saveFissionCampaign | `couponNo` 找不到券模板 | `NOT_FOUND` |

半个优先级表在冲突时会随机裁决 —— 这就是要求全序的理由，
后端不接受「只填了两个」这种在界面上看起来很正常的输入。

### 4.4 新客判定（9.2.3）

按 `newUserFactors` 逐条判，**任一条命中「见过」就不算新客**：

| 因子 | 判据 |
|---|---|
| `DEVICE` | 该 `deviceId` 已在 `mkt_fission_invite` 里出现过 |
| `PHONE` | 该用户手机号后四位 + userNo 已出现过（完整号码不出 `UserQueryPort`，B12） |

非新客照样落台账行（`is_new_user=0`），**但不发奖励**。
落行是为了让「邀了 100 个人只有 3 个算数」这件事在数据里看得见 ——
不落行的话，运营只会看到一个莫名其妙偏低的 `invitedCount`。

### 4.5 奖励发放

走既有的 `CouponService.issue(couponNo, "SINGLE_USER", desc, userKey, count, "SYSTEM")`，
**不新建一条发券链路** —— 那条链路上挂着预算硬闸门（超预算整批拒绝，不部分发放）。

发放失败（券停用、预算耗尽）不抛出去打断归因：
`mkt_fission_invite.reward_error` 记下原因，`rewarded=0`。

**防住什么**：防住「因为一张券发不出去，用户扫码进店这件事整个失败」。
归因是主流程，发券是副作用 —— 副作用不能反过来杀死主流程。

## 五、取舍记录

| # | 冲突 | 让了谁 | 理由 |
|---|---|---|---|
| T1 | mock 默认 `conflictPolicy=KEEP_FIRST` | **后端种子取 `OVERWRITE`** | 见 D4。既有测试钉住的是覆盖 |
| T2 | `ASK_USER` 是个合法枚举值 | **暂等价于 `KEEP_FIRST`，并在留痕里注明** | C 端没有那一步交互。做成「悄悄不覆盖」的话，选了它的运营会以为用户被问过了 |
| T3 | mock 的 `sourceRef` 长这样：`shop_M903_c2（邻家便利）` | **后端出 `M0001（老张粮油店）`** | 括号里的店名是给人读的，编号才是能查的。形状一致，取值口径不同 |
| T4 | 9.2.4 素材模板 | **不做** | 契约里没有任何素材相关方法；素材归内容域 P-15.1（`/ops/materials`，已实现）。在增长域再造一套素材表会和它打架 |
| T5 | 归因链路 `riskSignals` 与风控的 `signals` 是两个字段 | **共用同一套中文短语** | 类型注释原话：「与风险事件同一套口径」。两套口径的话，运营在两个页面看到同一件事的两种说法 |
| T6 | `AttrSource` 与 `TrafficSource` 名字不同、含义相关 | **不合并** | `enum-registry` 已把它记成 `MERGE` 待办，`type-alignment.KNOWN_DRIFT.TrafficSource` 也登记了。合并要三处词表一起动，不属于本批 |

## 六、验证

`backend/shop-app/src/test/java/ai/neargo/shop/scenario/OpsGrowthFlowTest.java`，
手机号段 `146xxxxxxxx`（全仓 grep 确认未被占用）。

**归因审计走真链路**，不造数据：
1. C 端扫码进店（`/mp/store/{merchantNo}/enter`，带 `deviceId`）→ 下单
2. `dispatchPending()` 把 `ORDER_CREATED` 投出去 → 首单号回填
3. 运营端 `/ops/attribution-traces` 查到这条链路：`source=STORE_CODE`、
   `sourceRef` 带 M0001、`orderNo` 是刚才那一单

另外三条：
- 把 `windowDays` 改成 1、优先级改成 `INVITER > STORE_CODE > CHANNEL`，
  再报一次归因 —— 结果跟着变（**证明配置真的在驱动引擎**，不是摆设）
- 同一设备连续带进 N 个用户 → 风控域出现 `ABNORMAL_FISSION` 事件（跨域联动）
- 邀请有礼：建活动 → 启用 → 邀来新客 → `invitedCount` 变 1；
  非 `COUPON` 奖励 / 两边都 0 张 / 不存在的券模板一律被拒
