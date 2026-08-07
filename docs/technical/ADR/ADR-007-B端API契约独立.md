# ADR-007 B 端 API 契约独立（~~`/mb/**`~~ → `/biz/**`），不复用 ShopApi

状态：**已决策（2026-08-05 用户确认）· 2026-08-06 修订前缀 `/mb` → `/biz`**

> ### 2026-08-06 修订：前缀改为 `/biz/**`
>
> 本 ADR 原定 `/mb/**`，后端独立实现成了 `/biz/**`。发现时两边**一条也对不上** ——
> b-app 45 条接口没有一条能调通后端，且双方都在各自往前走。
> （之所以拖到这天才发现：对齐守卫 `api-align.py` 本身是瘸的，见 [契约漂移清单](../../api/契约漂移清单.md)。）
>
> **改 b-app，不改后端。** 理由是代价与风险，不是谁对谁错：
> `/biz` 在后端出现 127 次，含 `SecurityConfig`、`BizContextFilter`、DataScope 以及约 10 个场景测试；
> b-app 那边只是一张端点表里的 45 个字符串。
>
> 本 ADR 的**理由完全不受影响** —— 下面讲的是「两套前缀，网关按前缀就能拦」，
> 这跟字面用哪三个字母无关。变的只是字面量：全文的 `/mb/**` 一律读作 `/biz/**`。
关联：[ADR-001 商家端形态与拆分时机](./ADR-001-商家端形态与拆分时机.md) · [ADR-002 结算走分账](./ADR-002-结算走微信支付分账.md)

> ⚠️ **并发开发说明**：本文写于 `b-app` 在另一会话中开发期间。
> 本次**未改动 b-app 的任何文件**，也未改动 c-app 的契约 —— 只落结论，供另一会话按此推进。
> 仓库长期多会话并行，提交请只用显式文件路径，勿用 `git add -A`。

---

## 1. 决策

**B 端（商家/团长端）另起一份契约 `MerchantApi`，端点前缀 `/mb/**`，不复用 C 端的 `ShopApi`（`/mp/**`）。**

---

## 2. 为什么不复用

### 2.1 鉴权边界根本不同（最主要的理由）

| | C 端 `/mp/**` | B 端 `/mb/**` |
|---|---|---|
| 鉴权模型 | **仅属主鉴权** —— 我只能看我自己的单 | **范围鉴权** —— 我能看我店/我自提点里所有人的单 |
| 主体 | `cUserNo` | `merchantNo` / `leaderNo` |
| 越权后果 | 看到别人的订单 | 看到整个社区的订单与客户手机号 |

同一个 `orderList`，在两端是**完全不同的两个查询**。塞进一个契约，后端的属主判断会写成一堆 `if (isB) ... else ...` —— 这正是越权漏洞的高发地带。

分成两套前缀之后，**网关层就能按前缀拦**：C 池 token 调 `/mb/**` 直接 401，不必逐个接口判断。

### 2.2 演进节奏不同

商家端的经营功能会快速迭代（选品、批量改价、对账、导出），C 端契约需要保持稳定。
混在一起，B 端每次加接口都会让 C 端的 `ShopApi` 变一次，也让 openapi.yaml 无谓地翻动。

### 2.3 限流与审计策略不同

商家端有批量操作（批量上下架、批量核销、导出对账单），限流阈值和审计留痕要求都与 C 端不同。
前缀分开后这些策略可以直接按路径配置。

---

## 3. 边界：什么共享，什么不共享

### 共享（`packages/shared`）—— 已经就位

| 目录 | 内容 | 为什么能共享 |
|------|------|-------------|
| `types/` | 领域模型（Order / Goods / Merchant / …） | **同一个订单，两端看的是同一个东西**。类型分家会立刻产生「C 端的 Order 和 B 端的 Order 字段对不上」 |
| `strategies/` | 计价 × 5、履约 × 6 | 计价规则不能有两套 —— 有两套就必然对不上账 |
| `utils/` | money / datetime / format / promotion | 金额与时区的口径必须全局一致 |
| `ports/` | 端能力（扫码、支付、分享…） | 两端都是 uni-app，端差异抽象相同 |
| `design/` | token / 图标 | 视觉体系一致 |

### 不共享（各自 app 内）

| 内容 | 理由 |
|------|------|
| `api/contract.ts` | 见 §2，两端语义不同 |
| `api/endpoints.ts` | 前缀不同（`/mp` vs `/mb`），且端点集合不同 |
| `api/http.ts` | 按各自端点表生成 |
| `i18n/` | 文案面向不同角色。「订单」对买家是「我的订单」，对商家是「待处理」 |
| `stores/` | 状态语义不同（C 端有购物车，B 端没有） |

### mock 的处理：**数据各自分开，契约严格对齐**

```
c-app/src/api/mock.ts  ← 各自独立的 mock 数据，互不共享
b-app/src/api/mock.ts  ←
        ↓ 都必须符合 ↓
   同一份服务端 API 契约（入参 / 出参）
```

**为什么数据要分开**：两端的测试场景需求完全不同 —— B 端要造几十单来测分拣单的汇总与分页，
C 端只需要两三单跑通下单流程。共用一份 db，一端造数据就会污染另一端，谁都没法安心改。

**分开的代价与对策**：开发期「C 端下单 → B 端立刻能核销」这个联调场景断了。
对策不是共享 db，而是**两端各自的种子数据用同一套编号规则**（`SO`/`GB`/`RQ` 前缀 + 相同的
goodsNo / pickupNo），需要联调时手工对齐一次即可。真接后端后这个问题自然消失。

### 对齐靠什么保证 —— 不能靠自觉

数据分开之后，**唯一要统一的就是 API 的入参与出参**。三道机制，缺一不可：

| 机制 | 保证什么 | 怎么强制 |
|------|---------|---------|
| **共享 `packages/shared/types`** | **出参**形状一致 | mock 与 http 实现都声明为 `XxxApi` 类型，TS 编译期拦截 |
| **命名的请求类型 + `satisfies`** | **入参**形状一致 | `http.ts` 里发出去的 body 用 `satisfies XxxReq` 标注，字段错了编译不过 |
| **OpenAPI 是唯一真源** | 前端与服务端一致 | 两端各自 `gen:api` 出 spec，**服务端照 spec 生成 controller/DTO**，不手抄 |

> ⚠️ 三者的次序很重要：**spec 由端点表 + 类型生成，服务端由 spec 生成**。
> 反过来（先写服务端再让前端对齐）就会退化成人工比对，必然漂移。

> ⚠️ 初版 spec 只规定了出参，`requestBody` 是空的 `{type:"object"}`、query 参数一个没有 ——
> 那样的 spec 生成不出可用的 DTO。**入参必须和出参一样有命名类型**，见 §5。

## 4. C 端契约里现存的 B 端方法怎么办

`ShopApi` 里目前有 9 个方法在职责上属于 B 端：

```
merchantPointAccount  merchantPointRecords  merchantApply
leaderStats  leaderApply  verifyPickup  leaderOrders  pickingList  markArrived
```

**现在不动它们**，理由是 [ADR-001](./ADR-001-商家端形态与拆分时机.md) 定的一期形态：
一期团长的轻操作内嵌在 C 端（团长就是普通用户升上来的，让他为了核销装第二个小程序不现实）。

**迁移时机**：等 b-app 具备完整经营台、且商家密度上来之后，按下面的顺序搬：

| 阶段 | 动作 |
|------|------|
| 现在 | B 端新写的接口一律进 `MerchantApi`；C 端这 9 个**保持不动** |
| b-app 经营台可用后 | `verifyPickup` / `pickingList` / `markArrived` / `leaderOrders` 先迁（这几个是纯商家操作，C 端用户不需要） |
| 二期拆包时 | `leaderStats` / `merchantPoint*` 迁走；C 端只保留 `leaderApply`（入驻申请是 C 端用户发起的，天然属于 C 端） |

⚠️ 迁移期间**两端都能调的接口，后端要按 token 池分别做鉴权**，不能因为「反正是同一个接口」就放松属主判断。

---

## 5. B 端应当遵循的口径（与 C 端一致，不要另发明）

这些是全站口径，B 端照抄即可 —— 不一致会让后端要写两套解析：

- 响应包 `{ code, msg, data }`，`code: 0` 为成功
- 分页 `{ records, total, page, size }`
- camelCase；单号 `xxxNo`；时间 `xxxAt`（UTC 毫秒）；枚举大写下划线
- 金额一律**最小货币单位整数**
- **禁止 `delete*`**，软删除用 `archive*` / `unarchive*`
- 写操作带幂等 key
- mock 的 `delay()` 必须**返回深拷贝** —— 直接交出 db 活对象会导致「状态变了但界面不动」，
  这个坑 C 端已经踩过一次（支付成功但收银台停在待支付）

### 入参必须有命名类型

写操作的 body 与读操作的 query **都要有命名的请求类型**（`XxxReq`），放在各自 app 的
`src/api/requests.ts`（不进 shared —— 按 §3 的边界，contract 层不共享）。

```ts
// c-app/src/api/requests.ts
export interface CartAddReq { goodsNo: string; skuNo: string; qty: number }

// c-app/src/api/http.ts —— satisfies 让「实际发出去的 body」受编译期检查
cartAdd: (goodsNo, skuNo, qty) =>
  call<CartItem[]>("cartAdd", undefined, { goodsNo, skuNo, qty } satisfies CartAddReq),
```

这样 spec 里的 `requestBody` / `parameters` 才是真实且可生成 DTO 的，
而不是一个骗人的空对象。

**建议 B 端同样建端点表 + 生成 OpenAPI**：
C 端已有 `c-app/scripts/gen-openapi.mjs`，复制过去把路径换成 b-app 的端点表即可，
输出到 `docs/api/openapi-mb.yaml`。后端就有了两份可直接生成 controller 的 spec。

---

## 6. 待确认

| # | 事项 |
|---|------|
| 1 | B 端 token 池：是 auth-core 的独立 B 池，还是同池不同 scope？（建议独立池，与前缀分离配套） |
| 2 | 团长与商家是同一套身份还是两套？现在 `leaderNo` 与 `merchantNo` 是分开的，B 端登录后要能同时承载两种角色 |
| 3 | B 端是否需要 App？[ADR-001](./ADR-001-商家端形态与拆分时机.md) 的结论是不做，除非要蓝牙小票打印 |

---
确认记录：2026-08-05 用户确认「B 端 API 契约另起一份」；b-app 已在另一会话启动，本文只落结论未改代码

> 编号说明：本文原拟为 ADR-005，落盘时发现另一会话已占用 005（履约方式与自提点模型）
> 并将积分方案重编为 006，故顺延为 007。
