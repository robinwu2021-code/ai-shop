# 架构评审：分层落点与 Controller 粒度

状态：reference · 2026-08-26
> 结论用数据得出，不用印象。数据来自 `node scripts/gen-backend-layers.mjs` 与本文附的统计口径。

---

## 0. 一句话

**「Controller 拆得太细」这个判断不成立** —— 中位数 3~5 个端点，是 REST 控制器的正常粒度。
真问题在**另一头**：3 个巨型控制器，其中 `BizMerchantController` 一个类
**40 个端点 / 1022 行 / 九个不相干的节**。

而更值得改的是第三件事：**拆分轴不一致**。同一层里三种轴混用，
所以「新端点该放哪」这个问题每次都要靠人拍。

---

## 1. 数据

| 端 | 控制器 | 端点 | 均值 | 中位 |
|---|---|---|---|---|
| `/biz` | 26 | 178 | 6.8 | 4 |
| `/mp` | 16 | 111 | 6.9 | 3 |
| `/ops` | 54 | 326 | 6.0 | 5 |

分布（96 个控制器）：

```
 1-2 个端点  ████████████████████████ 24
 3-5         ███████████████████████████████████████ 39
 6-10        █████████████████████ 21
11-20        ████████████ 12
21+          ███ 3
```

**两头都要看，但只有一头是问题。**

### 1.1 小的那一头（24 个）：**不是问题**

逐个看过，绝大多数是「一个资源恰好只有一两个动作」：

```
BizUploadController        /biz/upload/image
MpMyCouponController       /mp/my-coupons
BizPushTokenController     /biz/push-token · /unregister
OpsPointsController        /ops/points/overview
```

一个资源只有一个动作，控制器就只有一个方法 —— 这是**资源本身小**，不是拆细。
把它们合并成「杂项控制器」反而更糟：那样的类没有边界，只会一直长。

### 1.2 大的那一头（3 个）：**是问题**

| 控制器 | 端点 | 行 |
|---|---|---|
| `BizMerchantController` | 40 | 1022 |
| `BizGoodsController` | 26 | 657 |
| `OpsPlatformController` | 22 | 379 |

`BizMerchantController` 里有**九个不相干的节**：

```
店铺资料 · 我的资质 · 门店送货方式 · 预约排期 · 提报新社区
收款进件 · 门店管理 · 员工与授权 · 角色
```

路径前缀就有 10 个（`/biz/store` `/biz/merchant` `/biz/staff` `/biz/stores`
`/biz/communities` `/biz/roles` `/biz/role` `/biz/qualifications`
`/biz/role-perms` `/biz/appointment-slots`）。

> ⚠️ **预约排期那一节是我这一轮加进去的。**
> 加的时候理由是「它和送货方式同类，权限也一样（`biz:store`）」——
> 那个理由本身没错，错在**没有回头看这个类已经多大了**。
> 一个 1022 行的类，每个人加的时候都有一个局部合理的理由。

---

## 2. 真正的问题：拆分轴不一致

同一层里三种轴混用：

| 轴 | 例子 |
|---|---|
| **按资源** | `BizGoodsController` · `MpTopicController` · `OpsSettleController` |
| **按聚合根/角色** | `BizMerchantController`（商家这个"人"要用的所有东西） |
| **按能力** | `BizUploadController` · `BizCertController`（识别证照）· `PayCallbackController` |

三种都有道理，混在一起的后果是**「新端点该放哪」每次都要靠人拍**，
而人在赶工时一律选「放进已经存在的那个大的」—— 于是大的越来越大。

**建议定一条**：默认**按资源**（路径第一段），能力型（上传、识别、回调）单列。
一个类里出现第二个路径前缀就是信号。

---

## 3. 分层的其余部分：健康

| 层 | 数 | 判据结果 |
|---|---|---|
| Controller | 99 | 前缀异常 2（都已查明不是缺陷）|
| Service 接口 | 88 | **无实现 0** |
| 实现 | 88+ | impl 无接口 1（新旧券模型并存期）|
| Mapper | 19 | 集中在 `XxxMappers` 嵌套接口，一域一份 |
| 实体 | 151 | 与 153 张表由 `entity-alignment` 守卫强制同步 |
| Port（SPI） | 63 | **无实现 0** |

**Mapper 那一层的做法值得单说**：19 个 `XxxMappers` 容器类装 151 个实体的 Mapper，
而不是 151 个独立文件。这是**对的** —— Mapper 只做单表 CRUD，
一域一个容器让「这个域能碰哪些表」一眼可见；散成 151 个文件，
跨域误用（拿到 `UserMappers` 顺手读商家表）就没有任何视觉阻力。
这一条在 `MerchantMappers` 的类注释里有记录，是从真实事故里来的。

---

## 4. 从原型到库的完整链路：哪些环节有判据

| 环节 | 判据 | 谁盯 |
|---|---|---|
| 原型 → 页面 | `PROTO_ANCHORS` / `PROTOTYPES` | `gen-ui-catalog.py --check` ✅ pre-push |
| 页面 → 端点（C 端） | 功能点是否有页面调用 | `gen-c-feature-matrix.mjs` ✅ |
| 页面 → 端点（运营端） | 双向：调了没后端 / 做了没入口 | `check-ops-contract` + `check-ops-orphan` ✅ pre-push |
| 契约四件套 | 契约/端点表/mock/http 一一对应 | `type-alignment.test.ts` ✅ |
| 端点 → 权限 | `/biz` 逐条登记 | `BizEndpointPermTest` ✅ |
| 端点 → 权限 | `/ops` 角色×端点矩阵 | `ops-perm-matrix.test.ts` ⚠️ 40 个端点未登记 |
| Controller → Profile | 路径前缀与部署隔离一致 | `ControllerProfileTest` ✅ |
| 分层落点 | Controller 只能住两处 | `ArchitectureTest` ✅ |
| 域间依赖 | 不许跨域直连，只走 SPI | `ArchitectureTest` ✅ |
| 实体 ↔ 表 | 一一对应 | `entity-alignment` ✅ |
| 迁移可解析 | DDL 能被重放 | `ddl-parsable.test.ts` ✅ |
| **Controller 粒度** | 一个类装几个资源 | `check-controller-cohesion.mjs` ✅ 本次新增 |
| **拆分轴一致性** | — | ❌ 没有机器判据，靠 §2 那条约定 |

**链路本身是完整的**：十二处有判据，只剩「拆分轴一致性」这一处靠约定 ——
而它本质上是个命名问题，机器判不了。

---

## 5. 建议

### 5.1 该做的（小、可验证）

**给 Controller 粒度加一条守卫。** ✅ **已落地**：
`scripts/check-controller-cohesion.mjs` + `backend/known-fat-controllers.txt`。

判据不是端点数（那会逼人为凑数而拆），而是**一个类里的路径第一段种数**。
这条判据的好处是它**指向重构方向**：超标直接说明这个类装了不止一个资源。

**阈值定 3 不是 1。** 定 1 试过：99 个里 44 个超标，而大半是同一个资源的近亲
（`members` / `member-tags` / `member-settings` 是一件事的三个面，
拆成三个控制器不会让谁更好找）。3 挑出 10 个，最少的也有 3 个不相干的段 ——
**判据宁可宽**：误报一多就没人看了，真正的那几个会跟着被埋掉。

现状 10 个，两个突出：

| 控制器 | 资源数 | 建议 |
|---|---|---|
| `BizGoodsController` | 11 | goods 之外挂着 8 种 `spec-*` → 规格单列一个控制器 |
| `BizMerchantController` | 10 | 见 §5.2 |

⚠️ `MpCatalogController`（6 个）**可能是合理的** —— 它是 C 端首页的 BFF 聚合。
拆之前先确认这一点，别为了达标拆掉一个有意为之的聚合层。

### 5.2 该做但不急（大、要排期）

拆 `BizMerchantController`。按路径前缀分，九个节大致落成：

```
BizStoreProfileController      /biz/store          店铺资料
BizQualificationController     /biz/qualifications 我的资质
BizStoreFulfillmentController  /biz/stores/*/fulfillment + /biz/appointment-slots
BizStoreAdminController        /biz/stores         门店管理
BizStaffController             /biz/staff /biz/roles /biz/role-perms
BizMerchantController          /biz/merchant       只剩主体本身
（社区提报与收款进件各自已有去处）
```

⚠️ **这是纯搬家，必须单独一次提交、不夹带任何行为变化。**
混在一起的话，出问题不知道回滚哪个 —— 与四轴对账那一步 6a/6b 的分法同一个理由。

### 5.3 不该做

- **不要合并那 24 个小控制器。** 它们是资源本身小，合并会造出没有边界的杂项类。
- **不要按端点数定阈值。** 那会逼人把一个资源硬拆成两半来达标。

---

## 6. 这份评审的局限

- 端点数与前缀数是**静态扫描**得来的，`@RequestMapping` 在类上、方法上拼接的情况
  按类上那个算，可能与实际路由差一点
- 「拆分轴」的归类是人判的，没有机器判据 —— 5.1 那条守卫落地后才有
