# TDD-门店获客埋点与看板

> 状态：**G1 + 漏斗已落地（2026-09-02，V290）· G2 店铺码待做**
>
> | 部分 | 状态 |
> |---|---|
> | G1 匿名扫码埋点（`mkt_store_visit`，落在 `by-code`） | ✅ |
> | 获客漏斗聚合 + `GET /ops/stores/acquisition` | ✅ 后端；前端仍走 mock（`IS_MOCK` 未翻） |
> | 平台看板 `funnel()` 前两环接同一口径 | ✅ |
> | 闸门 V1–V4 / V6（`StoreAcquisitionFlowTest`） | ✅ 5 条全绿 |
> | G2 `printed` 运营录入 + `GET /ops/stores/qrcodes` | ⬜ 未做 |
> | V5（printed 未登记显示 null） | ⬜ 随 G2 |
>
> **落地时发现的一处坑（已修，值得记）**：`selectMaps` 的键**大小写随库而变** ——
> H2 把 `AS entityNo` 折成全小写 `entityno`，MariaDB 保留写法。
> 只试「原样 + 大写」会在 H2 上取到 null，而后果不是报错：
> 看板照常返回一行，数字全对，只有 `merchantNo`/`merchantName` 是 null。
> 现改为忽略大小写取值。这正是本仓「H2 绿 ≠ 生产对」那类问题的又一例。
>
> 原状态：设计待确认（2026-09-02）
> 起因：[门店注册与进件-运营端对齐与开发计划](../../requirements/门店注册与进件-运营端对齐与开发计划.md) §六 —— WS-B1/B2/B3 三个 mock 端点卡在同一处，不是缺接口，是**缺指标源**。
> 上游：[ADR-004 增长模型](../ADR/) · [TDD-运营端增长与归因](./TDD-运营端增长与归因.md) · [TDD-店铺码与分享](./TDD-店铺码与分享.md) · [分享激励与首页曝光-方案](./分享激励与首页曝光-方案.md)
> 目标端点：`GET /ops/stores/acquisition`（获客看板）· `GET /ops/stores/qrcodes`（店铺码）

---

## 一、一句话

**漏斗的后三段今天就有数据，缺的只有第一段。**
归因链路（`mkt_attribution_log`）已经在跑并且逐条留痕，`进店 / 新客 / 首单`
都能直接 group by 算出来 —— 真正没有采集的是**「还没登录的人扫了码」**那一次。
所以这件事的正确形状是：**补一个匿名落地埋点 + 一层聚合**，
不是从零建一套埋点体系。

---

## 二、现状核查（这一节是本方案的地基，逐条核过代码）

### 2.1 已经建好并在跑的

| 能力 | 落点 | 证据 |
|---|---|---|
| 进店埋点 + 归因判定 | `POST /mp/store/{merchantNo}/enter` → `AttributionService.report()` | `MpStoreController:70` |
| **每次判定逐行留痕**（含未改变归属的 `KEPT`） | `mkt_attribution_log` | `MktAttributionLog` |
| 归因规则可配（优先级/窗口期/冲突策略） | `mkt_attribution_rule` + `/ops/attribution-rule` | `OpsGrowthController:55/67` |
| 归因链路审计查询 | `GET /ops/attribution-traces`（按 userNo/merchantNo/source 筛） | `OpsGrowthController:86` |
| **首单回填** | `ORDER_CREATED` 事件写回 `mkt_attribution_log.order_no` | TDD-增长与归因 D3 |
| 真实微信小程序码（一店一码、落库复用） | `GET /biz/store/qrcode` → `StoreCodeService.acodeBase64()` | `BizPickupController:173` |
| 扫码落地解析 | `GET /mp/store/by-code?storeCode=` → `StoreCodeService.resolve()` | `MpStoreController:56` |

另外两处**已经存在、但与本方案相关**的东西（核查后补记）：

| 东西 | 状态 | 与本方案的关系 |
|---|---|---|
| `DashboardServiceImpl.funnel()` | **四环只给后两环**，代码注释写着「前两环需要埋点，而平台没有任何扫码/进店的事件表」 | 它是本方案诊断的**独立佐证**。G1 补上之后，**这个 funnel 必须改成读同一个数据源**，否则平台会有两个不一样的漏斗数（见 §3.3 口径唯一） |
| `mbr_member_source`（`source_type` / `store_no` / `occurred_at` / `is_first`） | 表在、形状对，但 `SOURCE_SCAN` / `SOURCE_SHARE` 两个常量**声明了从来没有写入方** | 形状最接近，但**不能承载匿名扫码**：会员行是「首笔已支付订单 / 手工入会」时才建的，且必须挂在一个 user 上。匿名访客没有 user —— 而那正是要测的那一层。详见 §3.1 |

`mkt_attribution_log` 的列**恰好就是漏斗的维度**：

| 列 | 漏斗里的角色 |
|---|---|
| `entity_no` | 哪个**主体**（聚合维度，见 §3.0 粒度） |
| `source` | `STORE_CODE` = 扫店铺码来的 |
| `decision` | `CREATED` = 这个人此前没有归属 ≈ 新客 |
| `user_no` | 去重算 UV |
| `order_no` | 非空 = 转化成首单 |
| `at` / `created_at` | 时间分桶 |
| `device_id` / `ip` / `risk_signals` | 防刷（与风控同一套口径） |

### 2.2 真正缺的（只有两处）

| # | 缺口 | 为什么它是缺口 |
|---|---|---|
| **G1** | **匿名扫码没有任何记录** | `by-code` 只做解析、不落任何行（`StoreCodeServiceImpl.resolve()` 只 select）；而 `enter` 要求登录（`SecurityUtils.currentUserNo()`）。于是**漏斗最宽的那一层（扫了但没注册的人）恒为 0** —— 而它正是「这批贴纸有没有用」的答案 |
| **G2** | **`printed`（印刷量）没有来源** | 它是**线下事实**（BD 印了多少张贴纸），系统里不可能自动知道。ops-web 契约 `StoreQrcode.printed` 要它 |

> ⚠️ **不要用「填 0」糊过去**：`scanCount=0 / printed=0` 在界面上与「真的没人扫」
> 完全一样，而这正是本仓反复踩的那类假绿 —— 一个看起来在工作的看板，
> 读数恒为零，没有任何报错。

---

## 三、方案

### 3.0 先定粒度：漏斗按**主体**算，不按门店

这是最容易搞错的一处 —— 「门店获客」这个名字会让人以为粒度是 `mch_store`。**不是。**

| 事实 | 出处 |
|---|---|
| 店铺码是**一主体一码**：`mch_entity.store_code`，`resolve()` 查的是 `MchEntity::getStoreCode` | `StoreCodeServiceImpl:39` |
| 归因落的是 `mkt_attribution_log.entity_no`（主体），没有门店维度 | `MktAttributionLog` |
| ops-web 的契约 `StoreAcquisition` / `StoreQrcode` 用的也是 `merchantNo` | `ops-web/lib/types/store.ts` |

三处口径本来就是一致的（主体），**所以本方案跟着它们走**。

> **为什么不顺手做成门店级**：一个主体只有一个码，物理上无法区分「这一扫是文三路店还是学院路店」。
> 要门店级得先**一店一码**（改 `store_code` 的归属、B 端按门店发码、存量主体补码），
> 那是另一件事，且会牵动分享物料与已印出去的贴纸。**本方案不含它**，
> 但新表留 `store_no` 列（可空）—— 将来一店一码落地时不用改表。

### 3.1 G1：补一次匿名落地埋点（本方案的唯一新增采集）

新增 `mkt_store_visit`（append-only），在**扫码落地那一刻**写一行，**不要求登录**：

```
扫小程序码 → /mp/store/by-code?storeCode=XXX
                    │
                    ├─ resolve() 解析出 merchantNo（已有）
                    └─ ★ 新增：记一行 mkt_store_visit（匿名也记）
                              visit_no / entity_no / store_code
                              store_no（可空，为将来一店一码留位，见 §3.0）
                              user_no（可空 —— 未登录就是空，这正是要测的那一层）
                              device_id / ip / ua_hash（防刷）
                              at / tenant_no
```

| 决策 | 取舍 |
|---|---|
| **为什么不复用 `mkt_attribution_log`** | 归因表回答「这个用户属于谁」，一人一条有效、有 30 天窗口；访问要回答「这家店最近被扫了多少次」。混用会让**归因窗口被扫码反复刷新**——[分享激励方案 §2.2-D](./分享激励与首页曝光-方案.md) 已经否过同一件事，这里保持一致 |
| **为什么不复用 `mbr_member_source`**（形状最像的那张） | 它有 `source_type`/`store_no`/`occurred_at`/`is_first`，连 `SOURCE_SCAN` 常量都声明好了 —— 但**会员行必须挂在一个 user 上**，而且是「首笔已支付订单 / 手工入会」时才建。匿名扫码没有 user、也远没到成为会员，**硬塞进去要么写不进、要么把「会员」的定义改成「扫过码的人」**，后者会污染所有按会员数算的地方。<br>**但 `SOURCE_SCAN` 那条线仍该接上**：等这个访客真的注册/下单成为会员时，用他的 `device_id` 回溯 `mkt_store_visit` 把来源标成 SCAN —— 那是本方案的**下一步**，不在本次范围 |
| **为什么不复用 `mkt_share_click`** | 那张表（若落地）记的是分享链接点击，与店铺码是两个物料、两条路径；且它属于曝光激励的计分口径，掺进获客漏斗会让两个数互相污染 |
| **user_no 可空** | 空值不是脏数据，**它就是「未注册访客」这一层本身**。设成 NOT NULL 会把要测的东西直接测没 |
| **写入不能拖慢落地页** | `by-code` 是扫码后的第一屏。埋点写入失败**必须吞掉异常**（记 WARN），绝不能让一次埋点失败变成「扫码进不去店」 |
| **防刷** | 同 `device_id` + 同 `entity_no` 在 N 分钟内只计一次 UV（去重在**聚合层**做，明细层照实记）——明细层去重会让风控看不到刷的痕迹 |

### 3.2 G2：`printed` 由运营录入，不猜

印刷量是线下事实。两条路，取第二条：

| 方案 | 结论 |
|---|---|
| A. 系统估算（按导出次数×每次张数） | ❌ 编一个看起来精确的数。BD 导出一次可能印 0 张，也可能印 500 张 |
| **B. 运营在店铺码页手工录入本次印刷量，累加留痕** | ✅ 采用。它本来就是一次**线下动作的登记**，与保证金流水同一类 |

落 `mch_store_qrcode_print`（`entity_no` / `qty` / `size` / `operator` / `at` / `remark`），
`printed` = 该店累加。**没录过就显示「未登记」而不是 0** —— 与「印了 0 张」分开。

### 3.3 聚合：先现算，不建汇总表

```sql
-- 获客漏斗（按店，时间区间内）
scan       = count(*)            from mkt_store_visit     where entity_no=? and at∈[from,to]
scanUv     = count(distinct coalesce(user_no, device_id))
enter      = count(distinct user_no) from mkt_attribution_log where entity_no=? and source='STORE_CODE'
register   = count(*)            from mkt_attribution_log where decision='CREATED'
firstOrder = count(*)            from mkt_attribution_log where order_no is not null
convRate   = firstOrder / nullif(scanUv,0)
```

| 决策 | 取舍 |
|---|---|
| **不建物化汇总表 / 不加定时任务** | 商家是几百家、访问是万级这个量级，`group by entity_no` 直接算得动。先上汇总表要同时承担「回填历史」与「跑批漏跑」两种故障，而它们比慢查询难查得多。**等真慢了再加**，届时判据是明确的（P95 查询耗时） |
| **⚠️ 走 SQL 聚合，而不是跟现有看板的写法** | 本仓现有看板（`DashboardServiceImpl` / `TradeStatsPortImpl`）是 `selectList` **全量捞进内存再 stream 聚合**。**这次刻意不照抄**：订单是千级、扫码是数量级更高的东西，把访问明细全量捞进 JVM 只是在等一次 OOM。这是一处**有意的偏离**，写下来是为了下一个人不会「统一一下风格」把它改回去 |
| **口径写在一处** | 聚合只在 `StoreAcquisitionService` 一处，运营端/看板/报表都走它。**并且 `DashboardServiceImpl.funnel()` 的前两环要改成读它** —— 否则平台首页的漏斗和门店获客看板会给出两个不一样的「扫码数」，而没有任何报错 |
| **时间区间必填** | 不给区间就是「有史以来」，那个数只会越来越大且不能用于判断趋势 |

### 3.4 端点

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/ops/stores/acquisition` | `store:page:read` | 漏斗，按 `from`/`to`/`keyword`/分页；返回 `Page<StoreAcquisition>` |
| GET | `/ops/stores/qrcodes` | `store:qrcode:export` | 店铺码 + `scanCount`（区间内）+ `printed`（累计，未登记为 null） |
| POST | `/ops/stores/{merchantNo}/qrcode/print` | `store:qrcode:export` | 登记一次印刷量，留痕 |

> 前两条**已在 ops-web 契约里**（`lib/api/https/store.ts`），路径与形状照它，不另起名。
> 第三条是新增（前端要加一个「登记印刷量」的小表单）。

---

## 四、影响面与不做的事

**要改的**：`by-code` 落地埋点（1 处）· 新表 2 张 · 聚合服务 1 个 · ops 端点 3 条 · 前端摘 2 个 mock + 1 个录入表单。

**明确不做**：
- ❌ 不建通用事件总线 / 埋点 SDK —— 本次只有一个事件（扫码落地），为它建一套框架是拿最贵的方案解最小的问题
- ❌ 不动归因引擎的判定逻辑 —— 它有测试钉着（`M6aStoreAttributionFlowTest`），本方案只读它的留痕
- ❌ 不做主页模板配置（WS-B3）—— 那一条依赖 C 端门店主页定稿，与埋点无关，本方案不含它

---

## 五、验证口径（闸门，先写再做）

| # | 断言 | 防的是 |
|---|---|---|
| V1 | 匿名（无 token）打 `by-code` 后，`mkt_store_visit` **多一行且 `user_no` 为空** | 「漏斗第一层恒为 0」这个静默失败 |
| V2 | 埋点写入抛异常时，`by-code` **仍然 200 并返回门店** | 埋点拖垮落地页 |
| V3 | 走完「扫码→注册→下单」后，该店 acquisition 的四个数**都 > 0** | 断言恒真（四个数全 0 也能"通过"的那种测试） |
| V4 | 同设备 5 分钟内扫 3 次：`scan=3` 而 `scanUv=1` | 去重放错层（明细去重会让风控瞎） |
| V5 | 没登记过印刷量的店，`printed` 是 **null 而不是 0** | 「未登记」与「印了 0 张」混成一个数 |
| V6 | 同一区间内，平台看板 `funnel()` 的扫码数 **等于** 获客看板各店扫码数之和 | 两个漏斗各算各的（同一个指标两个数，且都「看起来对」） |

> V3 尤其重要：本仓多次踩到「数字对了但不说明问题」——
> 所以它必须**先造出真实的一条链路再断言非零**，不是断言字段存在。

---

## 六、待确认

| # | 事项 | 草案 |
|---|---|---|
| 1 | UV 去重窗口 | 5 分钟（同 device + 同店） |
| 2 | 未登录访客的标识用什么 | 小程序侧无 cookie；用 `device_id`（前端生成并持久化）+ IP 兜底 |
| 3 | `mkt_store_visit` 保留多久 | 90 天明细，之后按天汇总归档（**先不做归档**，到量再说） |
| 4 | 获客看板归谁看 | 现挂 `store:page:read`（BD）；财务是否需要另说 |
| 5 | **要不要现在就做「一店一码」** | 建议**不做**（§3.0）。现在是一主体一码，多门店商家看不到分店维度。要做得改发码归属 + 存量补码 + 已印贴纸作废，是独立一件事 |
| 6 | 会员来源 `SOURCE_SCAN` 何时接上 | 建议**下一步**：访客注册成会员时按 `device_id` 回溯 `mkt_store_visit` 标来源。本次不做，但新表已留 `device_id` 供回溯 |
