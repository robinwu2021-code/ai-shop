# TDD-收件人物流触达与分享裂变

状态：**一期已实现 · 待部署**（2026-10-10；后端+前端+运营端已提交，单测/vue-tsc 绿）· 档位 **3**
档位：**3**（新表 ×2、新 /mp 匿名端点、新 C 端页面、新通知场景、新 Aliyun 模板、微信 URL Link 接入）
关联：[TDD-来单多渠道通知](TDD-来单多渠道通知.md)（短信/留痕复用）· [TDD-物流轨迹多渠道](TDD-物流轨迹多渠道.md)（轨迹复用）· [ADR-004 增长模型](ADR/ADR-004-增长模型从孵化团长转向商家自带客流.md)（裂变归因）

> **用户原话（2026-10-10）**：
> 「优化小程序端的订单交互。订单页面需要显示收件地址，基于收件地址再给出详细的订单状态以及物流信息。
> 订单开始发货后，系统给收件人推送一条短信。短信包含短链，点击跳转订单详情页。
> 同时消费者也可以分享订单详情页给真实的收件人，目的是为了裂变。
> 以上方案包含在运营端可以查看推送详情。」
>
> **已定边界（2026-10-10）**：自提柜/快递柜的**取件码不支持** —— 快递100 与微信物流查询组件
> 都在消费承运商同一份轨迹数据，码被丰巢/申通在承运商那一层掩掉了（生产数据已验证）。
> 码由丰巢直接短信/微信服务通知发给收件人，不经过任何轨迹接口。本方案不碰它。

## L1 一句话

买家下单 → 发货时**系统给收件人发一条短信**，短信里一条短链点开落到一个**免登录的「看件页」**
（物流 + 收货信息，隐去价格与买家身份）；买家也能把这个页面**分享**给真实收件人，
新用户打开即裂变入口；全程的短信/分享推送在**运营端「发送记录」可查**。

---

## §0 需求 → 设计

| # | 需求 | 落点 |
|---|---|---|
| 1 | 优化小程序订单交互 | C 端 `pages/order` 重排：收货信息卡 + 物流进度 |
| 2 | 订单页显示收件地址 | `OrderVO.Receiver` 已有（name/phone/address），端上展示 |
| 3 | 基于收件地址给详细状态+物流 | 物流轨迹已有（复用 `trace`），按「配送进度」呈现 |
| 4 | 发货后给**收件人**发短信 | 新场景 `SHIP_TO_RECIPIENT`，收件人号来自订单，复用短信通道 |
| 5 | 短信含短链 → 订单详情 | 新**短链服务** + **微信 URL Link** → 免登录看件页 |
| 6 | 买家分享订单详情给收件人（裂变） | 复用 `onShareAppMessage` + `withAttribution`（ADR-004 已有归因），落到看件页 |
| 7 | 运营端查看推送详情 | 复用 `sys_notify_log` + 「发送记录」页，新 bizType |

---

## §1 核心：免登录的「看件页」（整个方案的地基）

需求 4（短信点开）与需求 6（分享给收件人）**都指向同一件事**：
让一个**不是买家、也没登录**的人（真实收件人）看到这一单的配送情况。
现有订单详情 `GET /mp/order/{orderNo}` 按**当前登录用户**判归属，收件人打不开。
所以要新开一条**免登录、token 授权、只读、信息收窄**的看件链路。

### 1.1 访问令牌

**无状态签名 token**，不进库：`base64(payload).sig`，payload = `{subOrderNo, exp}`，
`sig = HMAC-SHA256(payload, SHIP_TRACK_KEY)`。复用 `NotifyCredCipher` 同一类做法
（密钥走 env，一次配好）。无状态的好处：短信/分享里带的就是它，不用查库核销；
`exp` 控制有效期（建议发货后 30 天，够配送 + 售后窗口）。

### 1.2 端点

```
GET /mp/track?t=<token>    （匿名，进 /mp 鉴权白名单）
```

返回一个**收窄的** VO（不是完整 OrderVO）：

| 给 | 不给 |
|---|---|
| 配送状态（已发货/运输中/派送中/已签收） | **实付金额**（买家隐私，裂变也不需要） |
| 物流轨迹节点（text/time/location） | 买家昵称 / userNo |
| 收件人**名**与地址（收件人自己要核对「是不是发给我的」） | 优惠明细 / 支付信息 |
| 商家店名 + 商品摘要（「XX 店的一袋米」，首件 + 件数） | 完整商品清单与单价 |
| 收件人手机号**掩码** | 核销码 / 买家的任何凭证 |

**为什么收窄**：这条链路任何拿到链接的人都能打开（短信转发、分享到群都可能）。
把它当「谁都可能看到」来设计 —— 隐去价格与买家身份，只留「你有一个包裹，到哪了」。
这也让裂变体验更干净（像在收礼物，不是在看别人的账单）。

### 1.3 C 端页面

新页 `pages/track/index?t=<token>`（C 端主包，匿名可进）：
- 顶部：配送进度条（物流为中心，需求 3）
- 中部：轨迹时间线（复用 `sh-trace`，`showMap` 看隐私决定）
- 底部：收件信息（名 + 掩码号 + 地址）、商家店名、商品摘要
- 若打开者是**新用户**：裂变引导位（见 §5）

---

## §2 发货触发给收件人发短信（需求 4）

### 2.1 触发

消费方在 `NotificationConsumer`，监听 **`SUB_ORDER_SHIPPED`**（已有事件）。
**只在 EXPRESS（快递）履约下发** —— 自提单没有「寄给收件人」的语义，收件人就是买家本人。

### 2.2 收件人与内容

- 收件人号：`order.receiver.phone`（订单里现成）。**发给收件人，不是买家**。
- 新 Aliyun 模板 `SHIP_TO_RECIPIENT`（要报备，几小时）：
  ```
  您有一个包裹已发出，点击查看物流 ${url}
  ```
  `${url}` 是短链（§3）。⚠️ 短信里带链接，**链接域名要在阿里云短信后台报备白名单**，
  否则模板审核不过（这是做短信带链最常卡的一关）。

### 2.3 与「来单给商家」的短信是两条独立场景

| | 来单（已上线） | 发货给收件人（本方案） |
|---|---|---|
| 场景 | `SUB_ORDER_PAID` | `SUB_ORDER_SHIPPED` |
| 收件人 | 店主 + 额外号 | **订单收件人** |
| 模板 | `SMS_512580867`（金额） | 新模板（短链） |
| 开关 | `mch_notify_pref`（门店级） | 平台级（给买家/收件人的触达，不归商家开关） |

### 2.4 留痕

走现有 `NotifyLoggingSmsPort` → `sys_notify_log`，`bizType = SHIP_NOTIFY`（新），
`target = 收件人掩码号`。失败（模板审核中/号空）照 §来单的口径记 FAILED，不影响主流程。

---

## §3 短链 + 微信 URL Link（需求 5）

短信里不能直接放小程序页路径（短信点不开小程序页），要两跳：

```
短信里的短链  https://s.hxmall.top/<code>
      │ 302
      ▼
微信 URL Link  https://wxaurl.cn/xxxx   （urllink.generate 生成，打开小程序指定页）
      │
      ▼
小程序  pages/track/index?t=<token>
```

### 3.1 短链服务

- 新表 `lnk_short(code, target, biz_type, created_at, expires_at, hits)`。
- `code` 短随机串（6~8 位，BizKey 随机段同款）。
- 子域名 **`s.hxmall.top`**（用户选定）：nginx 新 server 块 → 后端 `/<code>` → 查 target → 302。顺带 `hits++`。
- 需 DNS（DNSPod 加 A 记录）+ 证书（`*.hxmall.top` 泛域名证书，若无则单独签 s.hxmall.top）。
- **为什么要短链而不直接放 URL Link**：微信 URL Link 很长（`wxaurl.cn/` + 一长串），
  短信按 70 字一条计费，长链接直接多花一条的钱；短链把每条压回一条。

### 3.2 微信 URL Link

- `urllink.generate`（微信接口）：我们已有 WX appid/secret（订阅消息在用），可直接调。
- 生成指向 `pages/track/index?query=t%3D<token>` 的 URL Link，有效期与 token 对齐。
- **成本**：URL Link 有生成配额（微信侧），按发货量评估；超了要申请提额。

---

## §4 C 端订单交互重构（需求 1、2、3）

现有 `pages/order/index.vue` 之上：
- **收货信息卡**：收件人名 + 掩码号 + 地址，放在显眼处（需求 2）。
- **配送进度**（需求 3）：EXPRESS 单把状态呈现成进度条
  （已发货 → 运输中 → 派送中 → 已签收），下面接轨迹时间线（复用 `trace` 与 `sh-trace`）。
- 「看件页」与订单详情**共用展示组件**，只是看件页信息收窄、免登录。

---

## §5 分享裂变（需求 6）

### 5.1 分享动作

买家在订单详情 `onShareAppMessage`（已有）→ 分享卡落到 **`pages/track/index?t=<token>`**
（不是买家自己的订单详情，而是收窄的看件页）。带 `withAttribution`
（`inviterNo = 买家 userNo`，ADR-004 已有）—— 谁分享带来的新用户可归因。

### 5.2 裂变点

看件页判断打开者：
- 老用户 / 买家本人：正常看件。
- **新用户（没有 C 端会话）**：看件之余给一个**裂变引导位**。

⚠️ **「给什么」是产品决策**（见 §8）：只是「看到」不叫裂变，要有让他留下来的理由 ——
首单券 / 关注店铺 / 进入商家。归因已经能记（inviterNo），缺的是**激励内容**。

---

## §6 运营端查看推送详情（需求 7）

- 发货短信、分享带来的触达都进 `sys_notify_log`。
- 运营端「发送记录」页（刚上线、已认企微群）**直接能查** —— 新增 bizType `SHIP_NOTIFY`
  进 `bizLabel` 文案即可。
- 若要看短链点击效果（发了多少、点了多少），`lnk_short.hits` 另给一个小看板
  （可选，二期）。

---

## §7 数据库

| 表 | 用途 | 新/复用 |
|---|---|---|
| `lnk_short` | 短链：code → target、有效期、点击量 | **新** |
| `sys_notify_log` | 短信/触达留痕 | 复用（加 bizType） |
| `ord_sub_order` / 收件人 | 收件人号/名/地址 | 复用（已有） |
| `lgs_waybill_node` | 物流轨迹 | 复用（已有） |

token 无状态，不建表。

---

## §8 要你拍板的关键决策

| # | 决策 | 选项与推荐 |
|---|---|---|
| A | **看件页隐私口径** | ✅ 收窄视图（隐价格、隐买家身份；给物流+收货+店名+商品摘要） |
| B | **发货短信成本** | ✅ 一期全发（所有 EXPRESS 单），看量再收 |
| C | **裂变激励** | ✅ 一期只做归因（inviterNo），二期再加本店首单券 |
| D | **短链域名** | ✅ **`s.hxmall.top`**（用户选定）。要：DNSPod 加 A 记录 + 证书 + 阿里云短信后台报白名单 |
| E | **新 Aliyun 模板** | 发货短信要新模板报备（几小时）。文案见 §2.2，带 ${url} 变量 |

---

## §8.1 一期模块清单（文件级）

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `shop-core/…/trade/track/ShipTrackToken.java` | token 签发/校验（HMAC-SHA256，key 走 env） |
| 新增 | `shop-core/…/trade/api/mp/MpTrackController.java` | `GET /mp/track`（匿名，白名单） |
| 新增 | `shop-core/…/trade/dto/TrackVO.java` | 收窄视图 VO |
| 新增 | `shop-app/…/db/migration/V39x__lnk_short.sql` | 短链表 |
| 新增 | `shop-core/…/link/ShortLinkService.java` + entity/mapper | 生成短码、查 target、hits++ |
| 新增 | `shop-core/…/link/api/ShortLinkController.java` | `GET /{code}`（s.hxmall.top 的匿名 302），profile 独立或 api |
| 新增 | `shop-core/…/notify/WxUrlLinkClient.java` | 微信 `urllink.generate` |
| 修改 | `NotificationConsumer` | `SUB_ORDER_SHIPPED` → 给收件人发短信（EXPRESS） |
| 修改 | `SmsPort` + `AliSmsGateway` + `StubSmsGateway` | `sendShipToRecipient(phone, url)` |
| 修改 | `application.yml` | `SHIP_TRACK_KEY`、`ALI_SMS_TPL_SHIP`、短链域名 |
| 修改 | ops `bizLabel` / copy | 新 bizType `SHIP_NOTIFY` |
| 新增 | c-app `pages/track/index.vue` + pages.json | 免登录看件页 |
| 修改 | c-app `pages/order/index.vue` | 收货信息卡 + 配送进度；分享落到看件页 |
| 鉴权 | `/mp/track` 进白名单；`/s` 域名独立放行 | 匿名可访问 |
| 部署 | DNSPod s.hxmall.top、nginx server、证书、阿里云模板+白名单 | 运维步骤 |

## §9 分期建议

- **一期（骨架）**：短链服务 + URL Link + 免登录看件页 + 发货短信（无激励）+ 运营端留痕。
  打通「发货 → 收件人收短信 → 点开看物流」，裂变只做归因。
- **二期（裂变激励）**：新用户看件页的券/引导，短链点击看板。
- **三期（可选）**：若后面谈下丰巢/菜鸟直连，再补取件码（本期明确不做）。

## §10 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-10 | 草稿；用户「给出详细的设计方案」；取件码确认不支持 |
| 2026-10-10 | 用户确认：短链 s.hxmall.top，其余按推荐 → 进一期实现 |
| 2026-10-10 | 一期落地（a176eb73a 后端骨架 + 6cf46ce62 前端/运营端）：令牌/短链/看件端点/发货短信/看件页/收货卡/SHIP_NOTIFY 标签。**待部署**：SHIP_TRACK_KEY、s.hxmall.top(DNS+nginx+证书)、阿里云 ship 模板报备+短链域名白名单、urllink 接通后切 stub=false。买家主动分享(裂变激励)归二期。 |
