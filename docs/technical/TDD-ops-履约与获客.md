# TDD-ops-履约与获客（平台端一期批次 M1-2 / M1-3）

> 状态：**前端已实现 · 后端 2/19** · 后端缺口：fulfillment 0/13 · store 2/6 · 创建 2026-08-06
> ℹ️ **后端覆盖率是数出来的，不是估的**：`grep` 出后端真实的 `@*Mapping("/ops/…")`，
> 与 ops-web `lib/api/https/*.ts` 里的调用逐条比对。口径见 [TDD-后端补齐七域](./TDD-后端补齐七域.md) §0。
> 关联需求：[需求矩阵-三端 §六](../requirements/需求矩阵-三端.md) 的 **P-2.1 / P-2.2 / P-5.1 / P-10.1**
> 依赖：[TDD-ops-web](./TDD-ops-web.md)（脚手架：token / 组件 / 请求层 / 导航 / 权限）
> 决策依据：[ADR-004 去团长化](./ADR/ADR-004-增长模型从孵化团长转向商家自带客流.md) · [ADR-005 履约方式与自提点模型](./ADR/ADR-005-履约方式与自提点模型.md)

---

## 1. 需求摘要

接着已交付的 P-4.1 订单管理、P-11.1 商家治理，按矩阵 §八 的批次顺序推进平台侧：

| 矩阵条目 | 批次 | 为什么是现在 |
|---|---|---|
| **P-10.1 门店主页治理** | M1-2 | 矩阵 §七 把「门店获客」列为**一期最高优先且三端全缺**的链路；一个店主就能启动并立刻产生真实订单 |
| **P-2.1 社区网格 / P-2.2 自提点** | M1-3 前置 | 自提点是履约的主数据。没有 `PickupPoint`，M1-3 的核销与分拣没有落点 |
| **P-5.1 履约调度** | M1-3 | 矩阵 §七「自提履约」链路的平台侧。⚠️ 与 B 端核销台成对交付，单独上线不闭合 |

验收标准：
1. 三个域各自可筛、可查、写操作在 mock 层真落库（重开能读回），状态机非法迁移抛错
2. 数据域裁剪生效：社区运营只看到自己 `communityNo` 的社区与自提点
3. 无权限时出 `ReadOnlyNotice` 而非隐藏入口；导航对应叶子从 `soon` 转为可点
4. `npm run check` 全绿；矩阵覆盖率测试仍然通过

**不在本批**：P-5.2 物流（快递/运力，一期只做快递 + 商家自送，接口位留着）、P-10.1.1 主页模板配置（依赖 C 端门店主页组件定稿）。

---

## 2. 当前架构分析

| 现有能力 | 复用方式 |
|---|---|
| `lib/api` 契约四件套 + `lib/mock/db` | 各加一个域切片，组合根加一行 |
| `DataTable` / `Toolbar` / `FilterSelect` / `Drawer` / `TabHeader` / `ConfirmDialog` | 直接用，不新增组件 |
| `components/status.tsx` | 新增的**全站通用**枚举（自提点类型、核销状态）加在这里；单页专用映射留在页面 |
| `lib/permissions.ts` | 复用既有码：`community:*` / `fulfillment:*` / `store:*`，**不新增模块** |
| `archive.tsx` 软删除件 | 社区与自提点是主数据，走归档而非删除 |

新增组件：**0 个**。本批全部由脚手架已有的组合件拼装 —— 如果需要新组件，说明脚手架漏了东西，应先补组件再写页面。

---

## 3. 方案设计

### 3.1 导航结构调整（少菜单噪音）

脚手架期给这三个域各铺了 4–7 个叶子（逐条对应矩阵 L4）。实现时**合并为「一个对象一个 tab」**：

| section | 合并前（脚手架占位） | 合并后 | 理由 |
|---|---|---|---|
| 社区与网点 | 社区 / 开城围栏 / 自提点建档 / 启停迁移 / 费率 / 临时点监控（6） | 社区网格 · 自提点 · 临时点监控（3） | 开城开关、围栏、启停、费率都是**同一行数据上的字段与动作**，不是独立页面；拆成菜单会让运营在两个页面之间来回找同一个自提点 |
| 履约调度 | 批次 / 分拣 / 核销 / 逾期规则 / 物流 ×3（7） | 到货批次 · 分拣汇总 · 核销监控 · 逾期规则（4，物流仍 soon） | 逾期规则是配置页，与核销监控是「看」与「调」的关系，保留分开 |
| 门店主页 | 模板 / 审核 / 店铺码 / 效果（4） | 合规审核 · 店铺码 · 获客效果（3，模板配置仍 soon） | 模板配置依赖 C 端门店主页定稿，现在做等于猜 |

矩阵覆盖率由 `NavLeaf.matrix` 保证 —— **合并菜单不等于丢需求**，被合并的 L4 落到列表列与行内动作上，在页面注释里逐条标注。

### 3.2 数据模型（`lib/types/`）

**community.ts**
- `Community`：城市 / 网格 / 社区三级，`opened` 开城开关（P-2.1.2），`fenceRadius` 覆盖半径（P-2.1.3），可归档
- `PickupPoint`（ADR-005 的核心模型，P-2.2.3）：
  - `type: STORE | NEIGHBOR` —— **两类点的规则完全不同**：STORE 是入驻商家承接、收履约服务费；NEIGHBOR 是团发起人家，零报酬、脱敏更严、作用域只有单个 `group_no`
  - `serviceFeeRate` 仅 STORE 有意义（P-2.2.4，R15 口径未定 → 字段存**费率**不存金额，等口径定了只改结算不改主数据）
  - `acceptCount30d` 近 30 天承接次数（P-2.2.5 职业化风控，阈值 ≥3 触发复核）
  - 状态机 `ACTIVE ⇄ SUSPENDED`，`MIGRATING` 表示迁移中（P-2.2.2）

**fulfillment.ts**
- `ArrivalBatch`：到货批次（P-5.1.1），状态 `PLANNED → DISPATCHED → ARRIVED → SIGNED`
- `SortingRow`：按自提点汇总的分拣行（P-5.1.2），含缺货标记回传
- `RedeemStat`：按自提点的核销监控（P-5.1.3），待核销 / 已核销 / 逾期
- `OverdueRule`：逾期规则（P-5.1.4），`POSTPONE`（顺延）/ `VOID`（作废），带宽限小时数

**store.ts**
- `StorePageAudit`：店招 / 公告的合规审核队列（P-10.1.2），敏感词命中原因随数据下发
- `StoreQrcode`：店铺码批量导出（P-10.1.3，供 BD 地推），含贴纸尺寸与已印数量
- `StoreAcquisition`：门店获客效果（P-10.1.4），扫码 → 进店 → 注册 → 首单四段

### 3.3 契约（`/ops/**`）

```
GET  /ops/communities            listCommunities(q)
POST /ops/communities/{no}/open  setCommunityOpen(no, opened)      // P-2.1.2
POST /ops/communities/{no}/archive|unarchive
GET  /ops/pickups                listPickups(q)
POST /ops/pickups/{no}/status    setPickupStatus(no, status)       // P-2.2.2 启停/迁移
POST /ops/pickups/{no}/fee       setPickupServiceFee(no, rate)     // P-2.2.4（仅 STORE）
GET  /ops/pickups/risky          listRiskyNeighborPickups(q)       // P-2.2.5

GET  /ops/fulfillment/batches    listArrivalBatches(q)
POST /ops/fulfillment/batches/{no}/status  setBatchStatus(no, status)
GET  /ops/fulfillment/sorting    listSorting(q)
GET  /ops/fulfillment/redeem     listRedeemStats(q)
GET  /ops/fulfillment/overdue-rule / POST ...  get/saveOverdueRule  // P-5.1.4

GET  /ops/stores/audits          listStoreAudits(q)
POST /ops/stores/audits/{no}/decide  decideStoreAudit(no, pass, reason)
GET  /ops/stores/qrcodes         listStoreQrcodes(q)
GET  /ops/stores/acquisition     listStoreAcquisition(q)
```

命名沿用既有约定：列表 `list*`、状态推进 `set*Status`、审核 `decide*`；**禁止 `delete*`**。

### 3.4 关键业务规则（写进 mock 层强制，不只是 UI 提示）

1. **NEIGHBOR 点不能配费率**（ADR-005 §4：零报酬）→ `setPickupServiceFee` 对 NEIGHBOR 抛错
2. **未签收的批次不能进分拣**（P-5.1.1→5.1.2 有序）→ 批次状态机非法迁移抛错
3. **逾期规则的宽限小时数 ≥1**，`VOID`（作废）必须有宽限期 —— 到点即作废会直接产生客诉
4. **临时自提点 30 天承接 ≥3 次**触发风控标记（ADR-005 §F6 建议阈值），阈值走常量不硬编码
5. **审核驳回必须带原因**，原因会原样出现在商家 B 端 —— 空原因抛错

### 3.5 数据域裁剪

社区运营（`COMMUNITY_OPS`）登录时带 `communityNo`，列表 q 自动带上，mock 层 `scopeHit` 过滤。
自提点列表按 `communityNo` 收敛；**跨社区的自提点对该角色不可见**，与后端行为一致（矩阵 §2.3）。

---

## 4. 测试策略

| 测试 | 断言 |
|---|---|
| `lib/mock/db/community.test.ts` | 开城开关落库；NEIGHBOR 配费率抛错；数据域裁剪；归档过滤 |
| `lib/mock/db/fulfillment.test.ts` | 批次状态机非法迁移抛错；分拣只取已签收批次；逾期规则校验（宽限 ≥1） |
| `lib/mock/db/store.test.ts` | 驳回必须带原因；审核落库后队列不再出现；获客漏斗单调不增 |
| `lib/api/contract.test.ts`（已有） | 新增方法 mock/http 两侧一致、无 `delete*` |
| `lib/nav.test.ts`（已有） | 矩阵覆盖率不回退；合并菜单后 group 仍相邻 |

---

## 5. 风险与注意事项

1. **P-5.1 单独上线不闭合**：B 端核销台（B-10.2）不做，货到了没人能核销。平台端先行只是为了让 B 端有对接面。
2. **R15 履约服务费口径未定**（矩阵 M8）：本批只存费率字段与展示，**不做结算计算**，等口径定了在 P-12.1 里接。
3. **临时点风控阈值是建议值**（ADR-005 F6 待确认），放在 `lib/constants.ts`，改一处生效。
4. 门店主页的**模板配置**故意不做：C 端 `C-ST-01` 未定稿，先做模板等于两头返工。

---

## 6. 实现任务

- [x] T1 types：community / fulfillment / store 三个域 + 状态机迁移表
- [x] T2 mock db + 契约切片（contracts / mocks / https）+ 组合根接线
- [x] T3 页面：`/communities`（3 tab）、`/fulfillment`（4 tab）、`/stores`（3 tab）
- [x] T4 导航：合并叶子、`ready` 标记、矩阵编号回填
- [x] T5 测试 + `npm run check` 全绿 + 浏览器实机验证

## 7. 实现记录（2026-08-06）

- 交付页面：`/communities`（社区网格 / 自提点 / 临时点监控）、`/fulfillment`（批次 / 分拣 / 核销 / 逾期规则）、`/stores`（合规审核 / 店铺码 / 获客效果）
- 新增组件：**0 个**（全部由脚手架已有组合件拼装，符合 §2 的约束）
- 校验：`npm run check` = tsc 0 error + vitest **158 passed**；`npm run build` 静态导出 10 路由
- 实机验证：自提点列表（类型/费率/状态/行内动作）、逾期规则非法值被拒且出全局错误 toast、门店审核队列默认只出待审

### 实现期发现并修掉的问题

1. **未交付的域在 Rail 上仍可点** —— 静态导出下点进去是 404。已给 12 个未建 section 加 `soon`，并用 `nav.test.ts` 的「已交付清单」双向锁死（未建不可点 / 已建不许还灰着）。
2. `sectionDefaultHref` 的旧测试钉死在某个 section 上，交付一个域就要改一次测试 —— 已改成对全部 section × 全部角色的通用断言。

### 未做（明确）

- P-5.2 物流（快递轨迹 / 运力配置 / 运费模板）：一期只做快递 + 商家自送，接口位留着
- P-10.1.1 主页模板配置：等 C 端门店主页（C-ST-01）定稿
- 履约服务费的**结算计算**：R15 口径未定（矩阵 M8），本批只存费率与展示

---
确认记录：2026-08-06 用户「继续开发剩余的功能」授权按矩阵批次顺序推进；本批已实现
