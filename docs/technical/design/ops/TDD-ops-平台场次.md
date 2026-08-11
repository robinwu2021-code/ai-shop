# TDD-ops-平台场次（`POST /ops/campaigns`）

> 状态：**待确认** · 创建 2026-08-11
> 关联需求：[平台端功能清单](../requirements/平台端功能清单.md) `P-7.2` 活动（秒杀/满减/买赠）· 平台级活动
> 关联代码：`MktCampaign` · `OpsMarketingController` · `CampaignService`
> 缺口登记：`packages/shared/tests/ops-endpoint-exists.test.ts` → `POST /ops/campaigns`

---

## 1. 现状：一个词管了两件事

`/ops/marketing?tab=campaigns` 这个页面现在能做三件事：**列表、停启、归档**，
唯独「新建」点下去是 404 —— ops-web 的 `saveCampaign` 打的
`POST /ops/campaigns` 后端没有。

但缺的不是一个 CRUD 端点。翻开 `MktCampaign` 的第一行注释就能看到问题：

> 商家营销活动。……活动是店铺级的，不跨店。`entityNo` 非空。

**现有的 campaign 是商家的东西**，平台端那三个动作全是**治理**语义
（停一个违规的团、归档一个过期活动），路径复用了同一个词而已。
而 `P-7.2` 要的「平台级活动」是**平台自己出资、跨商家**的场次 ——
双十一满减节、周末秒杀专场。

两者只有名字一样：

| | 商家活动 `mkt_campaign` | 平台场次（要建的） |
|---|---|---|
| 出资方 | 商家（分账时扣商家） | **平台** |
| 归属 | `entityNo` 必填，不跨店 | 跨商家 |
| 谁建 | 商家自己（B 端） | 平台运营 |
| 平台的动作 | 治理（停/归档） | 全生命周期 |
| 结算影响 | 扣商家的钱 | **平台营销费用，商家足额收款** |

> 出资方这条线在 `MktCoupon.funder` 上已经踩过一次：
> 没有它，M7 分账无法判断该扣谁的钱（Q9）。
> 平台场次如果混进 `mkt_campaign`，分账会再撞一次同样的墙。

---

## 2. 方案

### 2.1 选型

| 方案 | 做法 | 结论 |
|---|---|---|
| **A（推荐）新表 `mkt_platform_campaign`** | 平台场次独立建模，与商家活动分表 | ✅ 采用 |
| B 给 `mkt_campaign` 加 `funder` 列，`entityNo` 允许空 | 复用现有表与算价链路 | ❌ 见下 |
| C 不做，页面撤掉新建按钮 | 承认一期不上 | ⚠️ 备选，见 §5 |

**为什么不是 B**（看着最省事的那个）：

1. `entityNo` 可空之后，**现有每一处按 `entityNo` 过滤的查询都要重新审一遍**
   —— 商家在 B 端看自己的活动列表、算价时按店取活动、结算按主体归集，
   任何一处漏判都会让平台场次泄漏进商家视图，或者反过来。
2. `storeNo` 那条已经很复杂的约束（只有 `FULL_CUT` 允许限定门店，
   因为顾客浏览时还没选自提点）要再叠一层「平台场次不适用」。
3. 状态机会分叉：商家活动 `DRAFT→RUNNING`，平台场次要审批与预热。

一张表服务两个出资方，代价会在分账和数据可见性上分期偿还。

**A 的代价**：算价链路要多查一次。用 §2.4 的方式控制。

### 2.2 数据模型

```
mkt_platform_campaign
  campaign_no      场次号
  name             场次名（C 端可见）
  type             FULL_CUT | FLASH        ← 一期只做这两类，见下
  status           DRAFT|PENDING|APPROVED|RUNNING|PAUSED|ENDED
  start_at/end_at  起止（毫秒）
  scope_type       ALL | COMMUNITY | CATEGORY | MERCHANT
  scope_values     JSON，scope_type 的取值列表
  rule_json        类型相关的规则（满减阶梯 / 秒杀折扣与限购）
  budget_minor     场次预算（分）
  max_exposure_minor  最大敞口（分）—— 建场次时算出来存下，见 TDD-营销预算前置
  approved_by/at   审批留痕
  archived_at      归档（与 status 正交，同 ADR 既有口径）
```

**一期只做 `FULL_CUT` 与 `FLASH`，不做 `BUY_GIFT`**：
买赠要动库存（赠品要扣谁的库存、缺货怎么办），
那是履约的问题不是营销的问题，单独排。

**`scope_type` 四选一而不是四个布尔**：叠加范围会让「这个商品到底参不参加」
需要跑一遍集合运算才知道，而 C 端要在商品列表页逐个判断。

### 2.3 状态机与审批

```
DRAFT ──提交──▶ PENDING ──批准──▶ APPROVED ──到点──▶ RUNNING
                   │                   │                │
                   └──驳回──▶ DRAFT     └──撤销──▶ DRAFT  ├─暂停─▶ PAUSED
                                                          └──到点──▶ ENDED
```

- **平台场次必须审批**：它花的是平台的钱，且跨商家生效。
  与商家活动（自己出钱、自己承担）不同，那边不需要审批。
- 审批权限：建场次 `marketing:govern`，**批准需要更高的码**。
  一期没有独立的审批码，先用 `SUPER_ADMIN` 的通配兜住，
  在 `Perms.java` 里留 TODO —— **不新造一个当前没有消费方的权限码**。
- `RUNNING` 之后只能暂停/结束，不能改规则。改了就是另一场活动。

### 2.4 算价链路接入

现有 `OrderServiceImpl` 已经在下单时读商家活动（`mkt_campaign` 的满减）。
平台场次在**同一处**接入，顺序固定：

```
商品原价
  → 平台场次优惠（本次新增）
  → 商家活动优惠（现有）
  → 券（现有）
```

**平台在前**：平台场次是「这一档商品今天都便宜」，
商家活动是「我这家店再让一点」，券是「你这个人额外有」。
反过来算的话，商家的满减门槛会被平台优惠拉低，等于平台替商家买单。

优惠明细要**按出资方分行**记录进订单，否则分账时无从拆分 ——
这与 `MktCoupon.funder` 是同一件事。

### 2.5 性能

算价多一次查询。用「**当前生效的平台场次通常是个位数**」这个事实：
启动时 + 场次状态变更时刷新一份内存快照，算价读快照不查库。
快照失效走现有的配置刷新机制，不新造一套。

---

## 3. 端点

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/ops/platform-campaigns` | `marketing:govern` | 列表，分页 |
| POST | `/ops/platform-campaigns` | `marketing:govern` | 新建（落 `DRAFT`）|
| PUT | `/ops/platform-campaigns/{no}` | `marketing:govern` | 改，仅 `DRAFT` |
| POST | `/ops/platform-campaigns/{no}/submit` | `marketing:govern` | 提交审批 |
| POST | `/ops/platform-campaigns/{no}/approve` | 更高权限 | 批准/驳回，必须带原因 |
| POST | `/ops/platform-campaigns/{no}/toggle` | `marketing:govern` | 暂停/恢复，必须带原因 |
| POST | `/ops/platform-campaigns/{no}/archive` | `marketing:govern` | 归档，走 `ArchiveService` |

> **路径用 `/ops/platform-campaigns` 而不是 `/ops/campaigns`。**
> 后者已经是商家活动的治理入口，复用会让「这个列表里到底是谁的活动」
> 变成一个要读代码才能回答的问题。ops-web 那一侧同步改调用。

---

## 4. 测试策略

| 场景 | 期望 |
|---|---|
| 新建落 `DRAFT`，未审批不参与算价 | 下单价格不变 |
| `APPROVED` 但未到 `start_at` | 不参与算价 |
| `RUNNING` 时下单 | 优惠命中，**明细按出资方分行** |
| 平台场次 + 商家满减同时命中 | 顺序为平台在前，商家门槛按原价判 |
| `RUNNING` 状态改规则 | 拒绝 |
| 审批驳回不带原因 | 拒绝（与既有的 `reason` 必填口径一致）|
| 分账 | 平台场次的优惠计平台费用，商家足额收款 |
| 归档 | 从默认列表消失，历史订单的优惠明细不受影响 |

---

## 5. 风险与备选

- **这是一个完整的业务域，不是补一个端点**。工作量在算价接入与分账，
  不在 CRUD。如果一期没有平台自出资做活动的预算，
  **方案 C（撤掉新建按钮、在缺口清单里写明「一期不做」）是诚实且正确的选择** ——
  比留一个建完了不参与算价的空壳强。
- 若采纳 C：`ops-endpoint-exists.test.ts` 里那条从
  「待产品决定」改成「刻意不做」并写明理由，ops-web 侧按 `UNIMPLEMENTED` 处理。

**这一条要你先定：一期平台是否真的要自己出钱做场次。**
定了再谈上面的实现任务。

---

## 6. 实现任务（采纳方案 A 时）

- [ ] T1 建表迁移 + 实体 + 枚举登记
- [ ] T2 Service：状态机 + 审批 + 敞口计算（复用 TDD-营销预算前置 的口径）
- [ ] T3 七个端点 + 权限 + 审计留痕
- [ ] T4 算价接入 + 优惠明细按出资方分行
- [ ] T5 内存快照与失效
- [ ] T6 分账侧：平台场次计平台营销费用
- [ ] T7 ops-web 改调 `/ops/platform-campaigns`，新建表单按状态机裁剪
- [ ] T8 测试：算价顺序、状态机、分账各一组

---

确认记录：待确认
