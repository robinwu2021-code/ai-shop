# TDD · 位置与经营范围

> 状态：**草稿（待确认）** · 2026-09-04
> 关联需求：[PRD-位置与经营范围](../../requirements/PRD-位置与经营范围.md)
> 关联：[TDD-多位置单生效](./TDD-多位置单生效.md) · [TDD-按坐标算可见性](./TDD-按坐标算可见性.md)
> · [C端地址管理-交互方案](./C端地址管理-交互方案.md) · [B端经营范围选择器-交互方案](./B端经营范围选择器-交互方案.md) · ADR-013

---

## 1. 设计约束：改动能收敛到几个点

先把三条既有事实摆出来 —— 它们决定了这份方案的形状，而不是反过来：

| 事实 | 出处 | 对本方案的意义 |
|---|---|---|
| **`reachableCommunities` 是可见性的唯一出口** | `MerchantPortImpl:189` 的注释原话：「上架写社区池、商家详情可达性、履约都只认它。正因为当初收敛到了这一处，换模型才只用改这里，调用方一行不动」 | **楼栋展开与 EXCLUDE 只改这一个方法** |
| `prd_community_pool` 是**派生索引**，`syncPool` 写、`GoodsServiceImpl` 读 | `MerchantGoodsServiceImpl:2215` | 口径一改就要**全量重建**，否则说的和做的对不上 |
| `fence_radius` 已在库里（23 个聚落全设 1000 米），**匹配不读它** | `CommunityServiceImpl.withinRadius` 用全局 `nearby-radius-m` | 提升匹配精度**不加表不加字段** |

**设计目标**：新增的三样能力（楼栋归属、EXCLUDE、按围栏匹配）
各自落在**一个**已有的收敛点上，不铺开。

---

## 2. 领域对象

### 2.1 对象清单

| 对象 | 表 | 职责 | 本次变更 |
|---|---|---|---|
| **Region** 行政区划 | `sys_region` | 国标码树，L1 省 › L4 街道 › L5 村居委会 | 不动 |
| **Community** 聚落 | `cmt_community` | **服务单位**：小区 / 村 / 园区 / 楼栋 | 🆕 `kind=BUILDING`、`parent_no` |
| **ServiceArea** 经营范围 | `mch_service_area` | 商家做哪儿，多粒度、可正可负 | 🆕 `mode=INCLUDE\|EXCLUDE` |
| **Store** 门店 | `mch_store` | 自送圆心 + 半径 | 不动（数据要补录） |
| **CommunityPool** 社区池 | `prd_community_pool` | 商品 × 聚落的**派生索引** | 不动结构，**要重建** |
| **Address** 收货地址 | `usr_address` | 买家的位置 + 坐标 | 不动 |
| **ActiveAddress** 生效位置 | `usr_account.active_address_id` | 我现在按哪儿看货（**单值**） | 不动 |

### 2.2 关联关系

```
sys_region  (L1 省 › L2 市 › L3 区 › L4 街道)
     ▲ region_code                    ▲ region_code（楼栋也要冗余存，见 §3.2）
     │                                │
cmt_community ──────── parent_no ─────┘
  kind = ESTATE | VILLAGE | BUILDING
  fence_radius（覆盖半径）· lat/lng（中心点）
     ▲
     │ ref_code (level=COMMUNITY)          ┌── mode = INCLUDE（默认）
mch_service_area ─────────────────────────┤
  entity_no · level · ref_code · status    └── mode = EXCLUDE
     │
     │  reachableCommunities()  ← **唯一出口**
     ▼
prd_community_pool (community_no × store_no × goods_no)
     ▲
     │  GoodsServiceImpl 按 communityNo 查
     │
usr_address (lat/lng) ──► 匹配最内层聚落 ──► 沿 parent_no 上溯
     ▲
usr_account.active_address_id
```

**两条正交的轴，不要连起来**：

- `mch_store.lat/lng + delivery_radius_m` 是**自送半径**这把尺，只在下单时判距离
- `mch_service_area` 是**经营范围**那把尺，决定商品出现在谁的首页

它们缺省行为相反（范围空=谁也看不见，半径算不出=放行），理由见 PRD §7.3。

### 2.3 同一个覆盖关系的两个方向

| 方向 | 问题 | 入口 | 产物 |
|---|---|---|---|
| **B 方向** | 我覆盖谁 | `reachableCommunities(entityNo, storeNo)` | 写进 `prd_community_pool` |
| **C 方向** | 谁覆盖我 | 🆕 `resolveLocation(latE6, lngE6)` | 归属链 → 查池 |

**两边算的必须是同一件事。** 分开实现的话会出现「商家看着自己覆盖了、买家就是看不到」——
而那种问题商家永远查不出来。做法：**B 方向是唯一的写入口，C 方向只读池**，
两者靠 `prd_community_pool` 这一张表对齐，不各算一遍。

---

## 3. 数据库设计

### 3.1 变更清单

```sql
-- V3xx__community_building.sql（迁移号提交前现查，见「迁移号撞车」）

-- ① 楼栋归属。可空：小区/村/园区的 parent 为空，它们直接挂 region_code
ALTER TABLE cmt_community ADD COLUMN parent_no VARCHAR(64) NULL
  COMMENT '所属聚落（楼栋→小区/园区）。为空=顶层聚落，直接挂 region_code';
CREATE INDEX idx_cmt_community_parent ON cmt_community (parent_no);

-- ② 经营范围的正负。默认 INCLUDE —— 存量行不需要迁移
ALTER TABLE mch_service_area ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'INCLUDE'
  COMMENT '覆盖方向：INCLUDE 纳入 / EXCLUDE 排除。展开时先并后减';
```

`kind=BUILDING` **不需要迁移** —— `kind` 已是 `VARCHAR(16)`，加的是取值不是列。
但要同步：枚举登记表（`packages/shared/src/contract/enum-registry.ts`）、三端词条、
B 端选择器、运营端建档校验。

### 3.2 两条约束，都要在应用层保证

**一、楼栋必须同时有 `parent_no` 与 `region_code`。**

不是洁癖：`region_code` 缺失时，「整个西湖区」这类粗粒度范围走前缀展开
（`openCommunityNosUnderRegion`）**命中不到它** —— 而运营端自己的文案早写过这条：
「没挂区划的社区，商家按『整个西湖区』这样勾范围时命中不到它，它只能被逐个点名」。
所以楼栋建档时两个字段都必填，且 `region_code` 取父聚落的那一个（冗余，但省一次 JOIN）。

**二、`parent_no` 只做两层，不做递归。**

园区 › 楼 › 单元 › 户会没完没了，而**单元和户不是服务单位** ——
没有商家按单元框范围，它们属于收货地址的 `house_no`。
应用层校验：`parent_no` 指向的聚落自己的 `parent_no` 必须为空。

### 3.3 迁移与兼容

| # | 步骤 | 为什么不能省 |
|---|---|---|
| 1 | 加两列（默认值让存量行语义不变） | `mode` 默认 INCLUDE = 存量行行为**逐字不变** |
| 2 | 改 `reachableCommunities`（§4.1） | 唯一出口 |
| 3 | **全量重建 `prd_community_pool`** | 存量池行是按「没有 EXCLUDE、没有楼栋」的口径写下来的。不重建的话排除一条也不生效，而 B 端会显示「已排除」—— **说的和做的对不上，且不报错** |
| 4 | 回读核对：重建前后的池行数差 | 差额应当等于「被 EXCLUDE 掉的 + 新增楼栋带来的」。对不上就是展开逻辑写错了 |

第 3 步有现成入口（`OpsPoolController`，幂等），**但要写进上线步骤** ——
不能指望有人记得。

---

## 4. 核心算法

### 4.1 B 方向：`reachableCommunities` 的改造

现有流程（`MerchantPortImpl:189`）与**改动落点**：

```
主体非 ACTIVE → 空                                    ← 不动
     ↓
取 mch_service_area where status=ACTIVE                ← 🔧 拆成 includes / excludes 两组
     ↓
门店 SUBSET 裁剪（mch_channel_area）                    ← 不动
     ↓
┌─ EXPRESS 开着 → 全部开放社区
├─ includes 为空 → deliveryOn ? 全部开放社区 : 空       ← 🔧 判据从 areas 换成 includes
└─ 否则 → 逐条展开 includes                            ← 🔧 加楼栋子级
     ↓
🆕 减去 excludes 的展开集合
     ↓
返回
```

**四处改动，逐条说明为什么是这样：**

**① 分组的判据是 `includes` 不是 `areas`。**
一个开了自送、只写了 `EXCLUDE 3 幢` 的商家，语义是「我上门送、不限范围，**但不送 3 幢**」，
应当得到「全部开放社区 − 3 幢」。若沿用 `areas.isEmpty()` 判断，
他会因为「有 area 行」而跳过 fallback、展开出空集 —— **变成谁也看不到**。
结果相反，且不报错。

**② EXCLUDE 要减在最后，对所有 include 分支一视同仁**（含 EXPRESS 与 fallback 的「全部开放社区」）。
否则会出现「快递商家排除不掉任何地方」这种说不通的差别。

**③ 展开一条 INCLUDE：**

| level / ref | 展开为 |
|---|---|
| `COMMUNITY` → 小区 / 村 / 园区 | **它自己 + 它的全部子楼栋**（`parent_no = ref`） |
| `COMMUNITY` → 楼栋 | 它自己 |
| `STREET` / `DISTRICT` / `CITY` / `PROVINCE` | `openCommunityNosUnderRegion(ref)` 前缀展开（楼栋因为冗余了 `region_code`，天然被包含） |

**④ 展开一条 EXCLUDE：规则与 INCLUDE 完全相同。**
排除一个园区就排除它的全部楼栋 —— 用同一个展开函数，不写两套。
两套的下场是有一天它们不一样了，而没有任何测试会发现。

**待审的处理**：`status != ACTIVE` 的 **INCLUDE** 不生效（沿用现状）；
**EXCLUDE 不看 status** —— 缩小自己的范围不需要审核，
而让一条待审的排除「暂时不生效」等于在审核期内把商家不想服务的地方照样露出去。

### 4.2 C 方向：`resolveLocation`

新增一个查询方法（社区域），端上与后端共用：

```java
/** 一个坐标解析出来的位置上下文 */
record LocationContext(
    String innermostNo,      // 最内层聚落，顶栏显示它；null = 一个围栏都没落进
    List<String> chainNos,   // 归属链上的全部聚落（含 innermost）
    boolean coarse           // 坐标是不是模糊的（区级）
) {}
```

```
① 候选 = 所有 status=OPEN 且 有坐标 的聚落，且 distance(我, 中心) <= 它自己的 fence_radius
                                                              ↑ 🔧 今天用的是全局 5000
② innermost = 候选里 kind 优先级最高的（BUILDING › ESTATE/VILLAGE）；同档取距离近的
③ chain = innermost 沿 parent_no 上溯（最多两跳）
④ 商品池 = prd_community_pool where community_no in chain
⑤ 按 goodsNo / merchantNo 去重 → 按门店到我的距离排
```

**② 为什么层级优先于距离**：站在楼门口时，隔壁小区的中心可能比本楼中心更近。
按距离取会把「我在 3 幢」判成「我在隔壁小区」，而两者的商品池不同。

**⑤ 为什么必须去重**：商家同时框了小区和其中一栋楼时，站在楼里两条都命中。
这个重复不报错，只让首页看起来像数据坏了。

**`coarse=true` 时不执行 ①–③**：模糊定位误差约 5 公里，而围栏是 1000 米（小区）
到 150 米（楼栋）量级 —— **匹配出来的是噪音不是结果**。此时降级为「按区给候选列表」。

### 4.3 两端一致性怎么保证

不靠人记，靠三条：

1. **只有一个写入口**：`reachableCommunities` → `syncPool`。C 端不自己算覆盖
2. **展开函数只有一份**：INCLUDE 与 EXCLUDE 共用；小区展开子楼栋的逻辑也只有一份
3. **守卫**（§9）：造一个「框小区 + 排除其中一栋楼」的场景，
   断言 B 方向算出的池与 C 方向站在那栋楼里查到的结果**互补**

---

## 5. API 设计

### 5.1 C 端 `/mp`

| 端点 | 变更 | 说明 |
|---|---|---|
| `GET /mp/community/nearby` | 🔧 改判据 | 用每个聚落的 `fence_radius`；出参加 `kind`、`parentNo` |
| 🆕 `GET /mp/location/resolve?latE6=&lngE6=` | 新增 | 返回 `LocationContext`：最内层 + 归属链。端上顶栏与商品查询都用它 |
| `GET /mp/goods` | 不改签名 | 后端内部把 `communityNo` 换成「归属链 in (…)」 |

> **为什么新开 `resolve` 而不是让端上拿 `nearby` 自己挑第一条**：
> 「最内层」的判据（层级优先于距离）是**业务规则**，放端上就会有三份实现
> （c-app / b-app / 将来的 H5），而它们迟早不一样。

### 5.2 B 端 `/biz`

| 端点 | 变更 | 说明 |
|---|---|---|
| `GET /biz/communities` | 🔧 出参加 `kind`、`parentNo` | 选择器要能把楼栋挂在小区下展示 |
| `GET /biz/store` · `POST /biz/store` | 🔧 `serviceAreas[]` 每项加 `mode` | 沿用现有的整体保存，不新开端点 |
| 🆕 `GET /biz/store/scope-preview` | 新增 | 「这个范围覆盖多少聚落 / 多少活跃买家」（PRD B11/B12） |

**`mode` 放在既有的 `ServiceArea` 结构里**，不新开一张「排除表」：
排除与纳入是同一件事的两个方向，分表会让「同一个对象同时出现在两边」
这种矛盾输入更难在保存时发现。

### 5.3 运营端 `/ops`

| 端点 | 变更 |
|---|---|
| `POST /ops/communities` | 🔧 支持 `kind=BUILDING` + `parentNo`（两个必填校验） |
| `PUT /ops/communities/{no}/fence` | 已有（`setFence`）；🔧 加**影响预览**：改半径会多/少覆盖多少人 |
| `GET /ops/communities/{no}/coverage` | 🆕 这个聚落：多少买家、几家商家、多少商品 |
| `GET /ops/coverage/gaps` | 🆕 供需缺口：缺供给 / 缺需求 / 空聚落 / **算不了的** |
| `GET /ops/coverage/health` | 🆕 坐标健康度：没标点的门店、没坐标的地址 |
| `GET /ops/merchants/{no}/coverage` | 🆕 商家覆盖明细，**EXCLUDE 单列** |
| `POST /ops/pool/rebuild` | 已有，幂等 |

> **`/ops/coverage/gaps` 必须单独返回「算不了的」那一格**（没坐标的地址、没标点的门店、
> 未开通的聚落）。混进「没需求」的后果是运营去某个片区撤商家，
> 而那里只是没人标过坐标 —— **分母写错的分析比没有分析更危险**。

### 5.4 契约登记清单

新增/改动端点要走的登记（漏一处就是「端上调不到」或「文档说反话」）：

- [ ] `backend` 控制器 + `@Profile`
- [ ] `packages/shared/src/types` 的出入参类型（**每个字段都要 JSDoc**，`spec-completeness` 会查）
- [ ] 各端 `api/endpoints.ts`（注释不许夹在 `{` 与 `method:` 之间，否则静默不进 spec）
- [ ] 各端 `api/contract.ts` + `http.ts` + mock
- [ ] `MpEndpointAuthTest` 的鉴权分类（新 `/mp` 端点不登记会红）
- [ ] 跑 `gen-openapi.mjs` × 3 + `gen-api-detail.mjs`，产物一起提交

---

## 6. 端上改动

| 端 | 改动 | 备注 |
|---|---|---|
| c-app | `location` store 改调 `resolve`，保存归属链 | 现有 `syncCommunityFromActive` 那一跳保留，链取代单值 |
| c-app | 顶栏显示 `innermost` 的名字 | 链上其余的不展示 |
| c-app | 切换地址七条规则（PRD §6.1.2） | 其中「无坐标时提示」是新的 |
| c-app | 模糊定位不用于匹配 | `getLocationDetailed` 已带 `fuzzy` 标记，据它分流 |
| b-app | 选择器支持楼栋（挂在小区下展示）与 EXCLUDE | 复用现有两个 Tab，加一个「排除」态 |
| b-app | 范围为空时明说「谁也看不到你」 | PRD B13 |
| ops-web | 楼栋建档、围栏维护、三张分析视图 | 新页面走 `nav.ts` + 迁移登记（菜单在库里） |

---

## 7. 风险与回滚

| 风险 | 表现 | 对策 |
|---|---|---|
| 池重建漏做 | B 端显示「已排除」，C 端照样看得到，**不报错** | 写进上线步骤；重建后回读行数差并核对 |
| `includes` 判据改错 | 只写了 EXCLUDE 的自送商家**全平台消失** | §4.1-① 是专门的用例（A12/A13） |
| 楼栋缺 `region_code` | 粗粒度范围命中不到它，商家以为覆盖了 | 建档必填 + 运营端列出「没挂区划的聚落」 |
| 围栏收紧后覆盖骤减 | 5000 → 各自 1000，**候选变少是预期内的**，但可能暴露出「某些聚落围栏设得太小」 | 灰度：先出一版「新旧口径各算一遍」的对比报表，再切 |
| `parent_no` 递归超两层 | 归属链无限上溯 | 应用层校验 + 上溯最多两跳 |

**回滚**：两列都是加列且有默认值，回滚 = 把 `reachableCommunities` 改回去 + 重建池。
**迁移本身不回滚**（已应用的迁移是冻结的）。

---

## 8. 实现任务（对齐 PRD 分期）

**二期 · 先把分母修对**

- [ ] 匹配读 `fence_radius`（`CommunityServiceImpl.withinRadius`）+ 新旧口径对比报表
- [ ] `GET /mp/location/resolve` + c-app 接上
- [ ] `GET /ops/coverage/health` 坐标健康度
- [ ] 门店坐标补录（运营侧动作，非代码）

**三期 · 楼栋与精确覆盖**

- [ ] 迁移：`parent_no` + `mode`
- [ ] `kind=BUILDING`：枚举登记表 + 三端词条 + 校验
- [ ] `reachableCommunities` 四处改动（§4.1）
- [ ] **全量重建池 + 回读核对**
- [ ] b-app 选择器支持楼栋与 EXCLUDE
- [ ] ops 楼栋建档、围栏维护 + 影响预览
- [ ] `GET /ops/merchants/{no}/coverage`（EXCLUDE 单列）

**四期 · 让两端看见彼此**

- [ ] `GET /ops/communities/{no}/coverage` · `GET /ops/coverage/gaps`
- [ ] `GET /biz/store/scope-preview`（B11/B12）

---

## 9. 测试策略

**这一块的缺陷有个共同点：不报错。** 所以判据必须是「能证伪的量」，不是「接口能通」。

| # | 用例 | 判据 | 消融（撤掉实现必须变红） |
|---|---|---|---|
| T1 | 围栏从 1000 改成 200 | 站在 500 米外**不再**匹配到它 | 改回全局半径 → 红 |
| T2 | 框小区 + 排除 3 幢 | 站 3 幢看不到、站 5 幢看得到 | 去掉减法 → 红 |
| T3 | 框街道 + 排除某园区 | 该园区**全部楼栋**都看不到 | 排除不展开子级 → 红 |
| T4 | 自送商家**只有** EXCLUDE | 得到「全部 − 排除」，**不是空** | 判据用 `areas` 而非 `includes` → 红 |
| T5 | 商家同时框小区与其中一栋楼 | 池里那件商品**只有一行** | 去掉去重 → 红 |
| T6 | 站在楼栋里 | 顶栏是楼栋（层级优先），商品是链上并集 | 按距离取最内层 → 红 |
| T7 | 模糊坐标 | **不匹配聚落**，降级给候选 | 不分流 → 红 |
| T8 | **B/C 互补** | B 算出的池 ⊇ C 站在链上任一点查到的 | 两边各算一遍 → 红 |
| T9 | 池重建幂等 | 连跑两次行数一致 | — |

T8 是最重要的一条：它守的是「两边算的是同一件事」，
而那正是「商家看着自己覆盖了、买家就是看不到」的唯一防线。

---

## 10. 待确认

1. **围栏切换要不要灰度？** 5000 → 各自 1000 会让候选变少（这是修正，不是回归），
   但可能暴露「某些聚落围栏设得太小」。建议先出对比报表再切。
2. **`BUILDING` 默认围栏**：建议 150 米，且运营端默认值**按 `kind` 分档**。
3. **EXCLUDE 是否进审核队列**：本方案按「不进」设计（缩小范围不影响别人）。
   若运营担心变相拒单，改动点只在 §4.1 的「EXCLUDE 不看 status」那一行。
4. **`resolve` 要不要缓存**：一次定位一次解析，量不大；但首页每次进都调的话值得加会话级缓存。
