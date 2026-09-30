# TDD-元器件 · 接口总览与双角色（一个账号，买家与供应商两套逻辑）

> 2026-09-30 · 状态：**已实现**（§4.1 除 `rfqNewOffers` 外、§4.2 已实现；§4.3 已定维持共用；`rfqNewOffers` 待定）
> 档位：1（新增 1 个端点 · 派单规则改一条 · 订阅消息模板待拍板）
> 前置：[独立服务与第一步](./TDD-元器件-独立服务与第一步.md) · [前端独立与通知矩阵](./TDD-元器件-前端独立与通知矩阵.md) ·
> [运营端接口](./TDD-元器件-运营端接口.md) · [通知补齐](./TDD-元器件-通知补齐.md) · 响应信封见 [响应格式规范](../api/响应格式规范.md)

本文回答两件事：**元器件一共有哪些接口**（小程序买家面、小程序供应商面、运营端、服务间），
以及**同一个登录账号在买家和供应商两个角色之间，逻辑怎么分开**。

---

## 一、先说清「商家」指谁

元器件的「商家」是**供应商**（`elc_supplier`），与电商的商家（`mch_*`、B 端 App、`btk_` 令牌）**没有关系**：

| | 电商商家 | 元器件供应商 |
|---|---|---|
| 账号 | 商家端账号（`mch_account.login_phone`） | **小程序的 C 端账号**（主系统 `usr_no`），与买家同一个 |
| 令牌 | `btk_` | `ctk_`（与买家同一个令牌） |
| 怎么成为 | 入驻审核 | 点一下「成为供应商」，不审核 |
| 元器件认不认 | **一律不认**（主系统认令牌时对商家端令牌回 `valid=false`） | 认 |

所以本文说的「C 端和 B 端」= 元器件小程序里的**买家面**与**供应商面**，是同一个人的两种身份。

---

## 二、接口总览（现状）

统一约定：响应套信封 `{code, msg, data}`；`code=0` 成功。登录态接口在主系统不可用时回 **503**（不是 401 —— 401 会让端上清掉令牌）。

### 2.1 登录与账号 —— 主系统 `/mp/user/**`（elec-app 已接 6 条）

元器件**不自己做登录**：账号、手机号、微信 openid 都在主系统。小程序登录后拿到的 `ctk_` 令牌，买家面与供应商面通用。

| 方法 路径 | 做什么 |
|---|---|
| `POST /mp/user/login` | 登录建户（小程序 `wx.login` 的 code，或手机验证码）→ `ctk_` 令牌 |
| `POST /mp/user/otp/send` | 发手机验证码 |
| `POST /mp/user/phone/bind` | 验证码绑手机 |
| `GET /mp/user/phone/capable` | 能不能用微信一键取号 |
| `POST /mp/user/phone/wx` | 微信一键取号绑手机 |
| `GET /mp/user/profile` · `POST /mp/user/profile` | 读 / 改昵称与头像 |

**手机号是两面共同的门槛**：询价（买家）与成为供应商（供应商）都要先绑，没绑回 `90001`，端上弹手机号闸。

### 2.2 买家面 —— `/elec/c/**`（8 条）

| 方法 路径 | 登录 | 做什么 | 入参 → 出参 |
|---|---|---|---|
| `GET /elec/c/part` | 可匿名 | 搜料号（开头 / 中段 / 「厂牌 + 料号」/ 近似退位）；`suggest=true` 为边打字边联想 | `keyword` `suggest` → `SearchResult` |
| `GET /elec/c/part/lookup` | 可匿名 | 批量查（BOM 粘贴，一行一个，最多 50 行） | `text` → `List<LookupLine>` |
| `GET /elec/c/part/{partNo}` | 可匿名 | 料号详情 + 买家面投影（数量与家数只给档位） | → `PartHit` |
| `POST /elec/c/rfq` | 要 · 要手机号 | 发求购（多行）。提交后自动派给库里有货的供应商 | `RfqReq` → `RfqView` |
| `GET /elec/c/rfq` | 要 | 我的询价 | `page` `size` → `List<RfqView>` |
| `GET /elec/c/rfq/{rfqNo}` | 要 | 询价详情：平台报价 + 供应商报价（代号 A/B/C、已加价） | → `RfqView` |
| `POST /elec/c/rfq/{rfqNo}/accept` | 要 | 接受平台的整单报价 | → `RfqView` |
| `POST /elec/c/rfq/{rfqNo}/line/{lineNo}/accept` | 要 | 按行选中某一条供应商报价 | `{offerNo}` → `RfqView` |

匿名的三条按 IP 限流（`RateLimiter`），且记入「搜索需求」统计；运营端搜料号**不**记。

### 2.3 供应商面 —— `/elec/b/**`（12 条）

**全部要登录，且要是供应商**（入驻那条除外）：不是回 `90002`，被暂停回 `90004`。

| 方法 路径 | 做什么 | 入参 → 出参 |
|---|---|---|
| `GET /elec/b/supplier` | 我的供应商档案；**还没入驻时 data 为 null**（不是 404 —— 端上据此显示「成为供应商」） | → `SupplierView` |
| `POST /elec/b/supplier` | 成为供应商：一点就成，body 可空（要手机号） | `RegisterReq?` → `SupplierView` |
| `PUT /elec/b/supplier` | 补资料（空字段 = 不改） | `RegisterReq` → `SupplierView` |
| `GET /elec/b/stock` | 我的库存（`filter=ALL/EXPIRING/EXPIRED`） | → `List<StockView>` |
| `POST /elec/b/stock/renew` | 「仍有货」：在售库存一键续期 | → `RenewResult` |
| `POST /elec/b/stock/upload` | 上传 .xlsx/.csv → **预演**（一行库存都不动） | `file` `mode` `taxIncluded?` → `BatchPreview` |
| `POST /elec/b/stock/batch/{batchNo}/remap` | 改列映射后重算预演（不用再传文件） | `RemapReq` → `BatchPreview` |
| `POST /elec/b/stock/batch/{batchNo}/apply` | 确认上架 | → `BatchPreview` |
| `GET /elec/b/rfq` | 派给我的求购（`status=SENT/VIEWED/QUOTED/DECLINED`）。**没有任何买家字段** | → `List<DispatchView>` |
| `GET /elec/b/rfq/{dispatchNo}` | 求购详情；看过即记为 VIEWED（响应率的分母） | → `DispatchView` |
| `POST /elec/b/rfq/{dispatchNo}/quote` | 报价；同一条再报 = 改价 | `SupplierQuoteReq` → `DispatchView` |
| `POST /elec/b/rfq/{dispatchNo}/decline` | 拒绝（没货 / 价格做不了 / 其他）；拒绝也算响应 | `DeclineReq?` → `DispatchView` |

### 2.4 运营端 —— `/elec/ops/**`（21 条）

运营令牌（`otk_`）+ 权限码。权限码由主系统按它自己的 RBAC 判完、只把 `ElecInternal.OPS_PERMS` 里的六个结果带过来。
详细出入参见 [运营端接口](./TDD-元器件-运营端接口.md) §2。

| 页 | 方法 路径 | 权限码 |
|---|---|---|
| **供应商** | `GET /elec/ops/supplier` · `GET …/{supplierNo}` · `GET …/{supplierNo}/stock` | `elec:supplier:read` |
| | `PUT …/{supplierNo}` · `POST …/{supplierNo}/suspend` · `POST …/{supplierNo}/resume` | `elec:supplier:manage` |
| **料号与库存** | `GET /elec/ops/part` · `GET /elec/ops/part/{partNo}` · `GET /elec/ops/stock` | `elec:part:read` |
| **基础数据** | `GET/POST /elec/ops/mfr` · `PUT /elec/ops/mfr/{mfrCode}` · `GET/POST …/{mfrCode}/alias` · `GET /elec/ops/mfr/unknown` | `elec:base:manage` |
| **询报价** | `GET /elec/ops/rfq` · `GET /elec/ops/rfq/{rfqNo}` · `GET /elec/ops/quote` | `elec:rfq:read` |
| | `POST …/{rfqNo}/quote` · `POST …/{rfqNo}/close` · `POST …/{rfqNo}/line/{lineNo}/dispatch` | `elec:rfq:quote` |

### 2.5 服务间 —— `/internal/elec/**`（主系统提供，4 条，不经 nginx）

| 方法 路径 | 做什么 |
|---|---|
| `POST /internal/elec/session` | 认令牌 → `{realm, userNo, perms}`（元器件侧缓存 60 秒） |
| `GET /internal/elec/user/{userNo}` | 取绑定手机号 |
| `POST /internal/elec/notify/quoted` | 通知**买家**：QUOTED / NO_SOURCE / OFFER / LINE_NO_OFFER |
| `POST /internal/elec/notify/supplier` | 通知**供应商**：DISPATCH / ACCEPTED / EXPIRING（EXPIRING 只进站内信） |

共享密钥 `X-Internal-Token`，常量时间比较；没配就一律拒绝。

---

## 三、一个账号、两套逻辑：七条规则

同一个 `usr_no` 既可以是买家也可以是供应商。下面七条决定两边怎么分开 —— **R1–R4、R6 已在代码里成立，R5 与 R7 的一部分要改（见 §四）**。

| # | 规则 | 为什么 | 落在哪 | 现状 |
|---|---|---|---|---|
| R1 | **角色由路径决定，不由令牌决定**。同一个令牌，打 `/c` 是买家、打 `/b` 是供应商；「是不是供应商」由元器件查自己的成员表，**不进令牌** | 供应商是元器件的概念，主系统不该知道；令牌里带角色的话，成为供应商后要重新登录才生效 | `ElecSupplierAccess#of / requireActive` | ✅ |
| R2 | **供应商面从不接收 `supplierNo` 入参**，一律由「令牌 → 成员表」推出 | 入参里能指定供应商号，就能改别人的库存、看别人的派单 | 全部 `/elec/b/**` 控制器只用 `SecurityUtils.currentUserNo()` | ✅ |
| R3 | **两面的数据各走各的前缀**：买家的询价只在 `/c`，供应商的库存与派单只在 `/b`；两面的出参类型不共用 | 共用一个出参类型，给一面加字段就漏到另一面 | `RfqView`（买家）与 `DispatchView`（供应商）是两个类型，后者没有任何买家字段 | ✅ |
| R4 | **暂停只关供应商面**：被暂停的人照样能搜料号、询价、接受报价 | 暂停的是「他作为供应商的信誉」，不是这个人；把他的买家身份一起停了，等于把一个客户也赶走 | 买家面不调 `ElecSupplierAccess` | ✅（缺测试钉住，§四 补） |
| R5 | **自己的求购不派给自己** | 占掉一个派单名额（每行上限 5 家），还能给自己报价、混进报价列表抬高「几家报了价」 | `ElecDispatchServiceImpl#dispatch` | ❌ **要改**（§4.2） |
| R6 | **两面各自匿名**：供应商看到的是 `dispatch_no`、收货地只到省；买家看到的是这一行内的代号 A/B/C | 同一个人两面都有时也成立 —— 任何一面都不带出另一面的数据 | 投影表无供应商列；派单查询不取买家字段 | ✅ |
| R7 | **通知分流**：买家的通知落询价页、供应商的通知落求购列表与库存页；站内信去重键前缀分开（`ELEC_RFQ:` / `ELEC_DISPATCH:` / `ELEC_QUOTE_ACCEPTED:` / `ELEC_EXPIRY:`） | 一个人两种身份的消息混在一起时，点开要落到对的那一面 | `ElecPages` + 两个 Notifier | ✅；**订阅消息额度两面共用**，见 §4.3 |

### 端上怎么切两面（给前端会话）

```
启动 / 回到前台
   └─ GET /elec/me（§4.1，新增）──→ 一次拿到：有没有绑手机、是不是供应商、两面各自的角标
          │
          ├─ supplier == null          → 首页只有买家功能 + 页尾「成为供应商」按钮
          ├─ supplier.status=ACTIVE    → 「我的」里多一个「供应商工作台」入口，带角标
          └─ supplier.status=SUSPENDED → 工作台入口仍在，进去只读，顶部说「已暂停，请联系平台」
```

错误码在两面的处理：

| 码 | 意思 | 买家面 | 供应商面 |
|---|---|---|---|
| 401 | 没登录 / 令牌失效 | 走登录 | 走登录 |
| 503 | 主系统暂时不可用 | 提示稍后再试，**不清令牌** | 同左 |
| 90001 | 没绑手机号 | 弹手机号闸（询价前） | 弹手机号闸（成为供应商前） |
| 90002 | 不是供应商 | —（买家面不会出现） | 跳「成为供应商」 |
| 90003 | 已经是供应商 | — | 当成功处理，刷新档案 |
| 90004 | 供应商被暂停 | —（**买家面永不出现**，R4） | 工作台只读 + 联系平台 |

---

## 四、要改的三处（未实现，等确认）

### 4.1 新增 `GET /elec/me` —— 唯一一个跨两面的接口

**为什么要**：现在端上要知道「是不是供应商」得调 `GET /elec/b/supplier`，要知道两面的角标还得再调三四个列表接口数条数。
而且 `/b/supplier` 放在供应商前缀下、却要给「还不是供应商」的人调，本身就别扭。

**规则**：它是 R3 的**唯一例外**，所以只给**计数与状态，不给任何一面的内容**。

```
GET /elec/me  （要登录；不要手机号 —— 它要告诉端上「你还没绑」）
→ {
    "userNo": "U…",
    "phoneBound": true,
    "supplier": null | {                 // 不是供应商为 null
      "supplierNo": "ES…", "companyName": "…", "status": "ACTIVE|SUSPENDED", "maskCode": "S-3F7K"
    },
    "badges": {
      "rfqNewOffers": 2,                 // 买家：有新报价、还没看过的询价单数
      "dispatchPending": 3,              // 供应商：派给我、还没回话的（SENT + VIEWED）；不是供应商恒为 0
      "stockExpiring": 5                 // 供应商：7 天内到期的在售行数；不是供应商恒为 0
    }
  }
```

`rfqNewOffers` 需要知道「买家看过没有」：`elc_rfq` 加一列 `buyer_viewed_at`，`GET /elec/c/rfq/{rfqNo}` 时写入；
「有新报价」= 最新一条有效供应商报价或平台报价晚于它。

`/elec/b/supplier` **保留**（供应商工作台里要完整档案），只是端上不再拿它判身份。

### 4.2 R5：自己的求购不派给自己

| 入口 | 改法 |
|---|---|
| 自动派单 `dispatch()` | 派之前查买家本人所在的供应商（`ElecSupplierAccess#of(buyerRef)`），把它从候选里去掉 |
| 运营手工指派 `dispatchTo` | 指派名单里含买家本人的供应商时回 `10400` —— 运营多半是按电话找的人，没意识到是同一个人 |

**不改的**：买家搜料号时照样看得到自己的货（投影是全市场的，只到档位，看到自己的那份没有信息泄露）。

### 4.3 订阅消息额度：两面共用一个模板（**已定：维持共用**，2026-09-30）

现状：买家的「询价有结果」与供应商的「有新求购 / 报价被选中」用的是**同一个订阅模板**（场景 `ELEC_QUOTED`），
额度按「用户 × 模板」记。对只有一种身份的人没有问题；**两种身份都有的人**，一次授权会被先到的那件事用掉。

| 方案 | 做法 | 代价 |
|---|---|---|
| **A. 维持共用（推荐）** | 不改 | 双重身份的人偶尔收不到某一面的微信提醒；**站内信是必达的那一份**，不丢事 |
| B. 拆两个模板 | 买家一个、供应商一个，额度各算各的 | 小程序后台要多选一个模板（要你扫码）；端上多一次授权弹窗 —— 多数人不会点第二次 |

推荐 A：受影响的只是「既询价又供货」的少数人，且丢的只是加速通道。等真的有人因此漏了生意再拆，那时判据是具体的。

---

## 五、边界

**不做的**：

- **一家供应商多个成员**（老板 + 业务员各自登录）：成员表已经留了 `role`，接口不做。做的那天，R2 不变（仍由令牌推出），
  改的只是 `ElecSupplierAccess#of` 在一人属多家时选哪家 —— 那时要加一个「切换当前供应商」的接口
- **电商商家账号直接用于元器件**：两套账号体系，不打通（§一）
- **接口版本号**：元器件还没有外部调用方，路径不带版本；独立出去对外开放时再加

**待拍板**：

1. ~~§4.3 订阅消息选 A 还是 B~~ → **A，维持共用**（2026-09-30 定）
2. §4.1 的 `rfqNewOffers` 要不要做（要给 `elc_rfq` 加一列）。**未定**：`/elec/me` 先不带这个字段，
   以后加上是只增不改，已有调用方不受影响

---

## 六、对账

| AC | 需求 | 落点 | 测试 | 消融 |
|---|---|---|---|---|
| AC1 | 端上一次调用拿到身份与两面角标；成为供应商后不用重新登录 | `ElecMeController` · `ElecMeServiceImpl` · `MeDtos` | `ElecRolesFlowTest#ac1_meForPlainBuyer` · `#ac1ac2_meForSupplier` | — |
| AC2 | `/elec/me` 不带任何一面的内容 | 出参只有 userNo / phoneBound / supplier / badges | `#ac1ac2_meForSupplier`（字段集合精确相等） | — |
| AC3 | 自己的求购不自动派给自己 | `ElecDispatchServiceImpl#dispatch` 排除 `access.of(buyerRef)` | `#ac3_noSelfDispatch` | 去掉排除 → 红在「自己那一面收不到自己的求购」✅ |
| AC4 | 运营不能把求购指派给买家本人的供应商 | `ElecRfqServiceImpl#opsDispatch` | `#ac4_opsCannotDispatchToBuyerSelf` | 去掉判断 → 红在第 112 行 ✅ |
| AC5 | 被暂停的供应商照样能询价、接受报价；供应商角标归零 | 买家面不调 `ElecSupplierAccess`；`ElecMeServiceImpl` 暂停时角标为 0 | `#ac5_suspendedSupplierCanStillBuy` | — |

`mvn -o -pl elec/elec-svc -am test`：10 个类 84 条，0 红。`/elec/me` 的鉴权由 `ElecEndpointAuthTest` 自动覆盖。

**与 §4.1 的偏差**：`badges` 暂不含 `rfqNewOffers`（待定，见 §五）。以后加是只增不改。
**没改 `packages/shared/src/types/elec.ts`**：前端会话正在改这个文件（工作区里有它未提交的改动），`MeView` 的 TS 类型由前端会话按 §4.1 的形状补。
