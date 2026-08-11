# 后端 API 清单（C 端 · B 端 · 平台端）

> 状态：草稿（**待确认**）· 创建 2026-08-05
> 来源：由 [需求矩阵-三端](../../requirements/需求矩阵-三端.md) 逐个 L4 功能点反推得出，并对齐
> [architecture.md](./architecture.md) §5 契约口径、[c-app/src/api/endpoints.ts](../../../c-app/src/api/endpoints.ts)（C 端端点表 = 现有唯一真源）、
> [待完成功能清单](../../requirements/待完成功能清单.md) 的实现状态。
> 定位：本文是**后端接口的权威清单**，回答「后端一共要提供哪些端点、归哪个模块、谁能调、对应矩阵哪一条」。
> 请求/响应字段级细节不在本文，由后续各域的 OpenAPI（`docs/api/openapi.yaml`）承载。

---

## 〇、怎么读这份清单

| 列 | 含义 |
|----|------|
| 矩阵 ID | 对应 [需求矩阵](../../requirements/需求矩阵-三端.md) 的 L4 编号（`C-4.2` / `B-10.2.1` / `P-12.1`），**一一可追溯** |
| 端点 | `METHOD 路径`；`:xxx` 为路径参数 |
| 模块 | 后端模块归属（architecture.md §9 的 `svc-*`） |
| P | 优先级 P0(一期) / P1(二期) / P2(增强) |
| 状态 | ✅ 端点表已有且 mock 已实现 · 🚧 部分 · ⬜ 未开始 |

**统计**（`GET/POST` 同路径成对的按 1 条计）：C 端 `/mp/**` 约 151 条（现存 45 条）· B 端 `/biz/**` 约 101 条 ·
平台端 `/ops/**` 约 172 条 · 公共 5 条 · 回调 8 条，合计约 **437** 条。
其中一期 P0 约 **330** 条，已实现 45 条 —— **缺口 ≈ 285 条**。

---

## 一、通用约定

### 1.1 前缀与令牌池

| 前缀 | 端 | 令牌 | 鉴权模型 |
|------|----|------|---------|
| `/mp/**` | C 端（小程序 / App / H5） | Bearer，`realm=CONSUMER` | **无 RBAC，仅属主鉴权**（后端防 IDOR） |
| `/biz/**` | B 端（商家 / 自提点 / 店员） | Bearer，`realm=CONSUMER` + `bizContext` | **数据域裁剪**：`merchant_no` / `pickup_no` / `group_no` |
| `/ops/**` | 平台端 ops-web | Bearer，`realm=OPERATOR` | **RBAC + 数据域授权** |
| `/callback/**` | 第三方回调 | 各自签名校验，**不走 Bearer** | 验签 + 幂等 |
| `/common/**` | 三端共用（上传、字典、地区） | Bearer 任一池 | — |

> ⚠️ **B 端为什么单独前缀而不是复用 `/mp/**`**：一期 B 端内嵌 C 端小程序（[ADR-001](../ADR/ADR-001-商家端形态与拆分时机.md)），
> 登录态与 C 端**同一个** Bearer，但**数据可见性完全不同** —— C 端是「我买的」，B 端是「我卖的 / 我要核销的」。
> 前缀分开，网关层就能挂不同的数据域拦截器；二期拆独立小程序时，前缀不用改，只换载体。
> 反过来若混在 `/mp/**` 里靠参数区分，越权漏洞会从「拦截器少配一条」变成「每个 handler 都要自己记得判」。

### 1.2 B 端三个裁剪维度（正交，不可互相替代）

| 维度 | 谁持有 | 裁什么 | 典型端点 |
|------|--------|--------|---------|
| `merchant_no` | 商家管理员 / 店员 | **我卖的货**：商品、子订单、结算 | `/biz/merchant/**` |
| `pickup_no` | 自提点商家（`PickupPoint.type=STORE`） | **我要核销的货**（含他家商品，字段裁剪） | `/biz/pickup/**` |
| `group_no` | 团发起人（`type=NEIGHBOR`） | **我发起的团**，零报酬，作用域单团 | **`/mp/groups/**`**（见 §3.6 —— 刻意不放 `/biz`） |

> 三者可同时属于同一个自然人，但**权限不叠加**：作用域由请求路径决定，不由用户身份决定。
> 自提点承接方看到别家商品时，响应体**必须裁到履约必需字段**（取货码、品名、数量、收货人昵称），不含金额与完整手机号（M11 待确认）。

### 1.3 契约口径（沿用 powerbank，architecture.md §5）

- 响应包：`{ code, msg, data }`，`code=0` 为成功
- 分页：请求 `page`/`size`，响应 `{ records, total, page, size }`
- 字段 camelCase；业务单号 `xxxNo`；时间 `xxxAt`（毫秒时间戳）；枚举大写下划线
- **只用 GET / POST**（小程序端 `uni.request` 与既有端点表口径一致，不引入 PUT/DELETE/PATCH）
- **禁止 `delete*`**：软删除语义一律用 `.../archive` 与 `.../unarchive`
- 金额单位统一「分」（`Long`），前端负责格式化；多市场场景响应携带 `currency`

### 1.4 幂等与并发

| 场景 | 机制 |
|------|------|
| 下单 `POST /mp/order` | 请求头 `Idempotency-Key`，24h 内同 key 返回同一 `orderNo` |
| 支付 `POST /mp/order/:orderNo/pay` | 同上；端侧**不自判成功**，以 `payQuery` / 回调为准 |
| 核销 `POST /biz/pickup/verify` | 取货码维度唯一，重复核销返回 `ALREADY_VERIFIED` 而非报错 |
| 分账 `POST /ops/settle/split/:settleNo/execute` | 平台内幂等 + 支付服务商幂等号双保险 |
| 第三方回调 | 以 `outTradeNo + 事件类型` 去重 |

### 1.5 错误码分段

| 段 | 含义 | 示例 |
|----|------|------|
| `0` | 成功 | — |
| `1xxxx` | 通用（参数、鉴权、限流） | `10401` 未登录 · `10403` 无权限 · `10429` 限流 |
| `2xxxx` | 交易 | `20001` 库存不足 · `20002` 价格已变 · `20003` 超出配送范围 |
| `3xxxx` | 履约 | `30001` 已核销 · `30002` 非本自提点 · `30003` 订单已退款 |
| `4xxxx` | 营销 | `40001` 券已领完 · `40002` 券不适用 |
| `5xxxx` | 资金 | `50001` 分账接收方未报备 · `50002` 分账已超期 |
| `6xxxx` | 风控拦截 | `60001` 命中黑名单（带申诉入口） |

---

## 二、C 端 API `/mp/**`

> 现有 45 个端点见 [endpoints.ts](../../../c-app/src/api/endpoints.ts)；下表 ✅ 即指该表已声明且 mock 已实现。
> ⚠️ 现存的 `leaderStats` / `leaderApply` / `verifyPickup` / `leaderOrders` / `pickingList` / `markArrived` 六个端点
> 按 [ADR-004](../ADR/ADR-004-增长模型从孵化团长转向商家自带客流.md) + E10 **迁移到 `/biz/pickup/**`**，见 §4.3。

### 2.1 账号与权限（C-1）· `svc-user`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-1.1 | `POST /mp/user/login`（`grantType=WECHAT_MP\|PHONE_OTP\|APPLE`） | P0 | ✅ |
| C-1.1 | `POST /mp/user/otp/send` 发送短信验证码 | P0 | ⬜ |
| C-1.1 | `POST /mp/user/token/refresh` 会话续期 | P0 | ⬜ |
| C-1.1 | `POST /mp/user/logout` | P0 | ⬜ |
| C-1.1 | `POST /mp/user/phone/bind` 手机号绑定（微信 `getPhoneNumber` / OTP） | P0 | ⬜ |
| C-1.3 | `GET /mp/user/profile` · `POST /mp/user/profile` 我的资料 | P0 | ✅/⬜ |
| C-1.2 | `POST /mp/user/community` 绑定社区 + 自提点 + 常去店 | P0 | ✅ |
| C-1.3 | `POST /mp/user/realname` 实名认证 | P2 | ⬜ |
| C-1.3 | `GET /mp/user/authorization` · `POST /mp/user/authorization` 授权管理 | P0 | ⬜ |
| C-1.3 | `POST /mp/user/deactivate` 注销申请（冷静期）· `POST /mp/user/deactivate/cancel` | P0 | ⬜ |
| C-1.3 | `POST /mp/user/data-export` 个人数据导出 | P1 | ⬜ |

### 2.2 社区与网点、地址簿（C-2）· `svc-community` / `svc-user`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-2.1 | `GET /mp/community/nearby` 附近社区与自提点 | P0 | ✅ |
| C-2.1 | `GET /mp/community/:communityNo` 社区详情 | P0 | ⬜ |
| C-2.1 | `GET /mp/pickup/:pickupNo` 自提点详情（地址/营业/到货时间） | P0 | ⬜ |
| C-2.1 | `POST /mp/pickup/:pickupNo/arrival-notice` 到货提醒订阅 | P0 | ⬜ |
| C-2.2 | `GET /mp/user/address` 地址列表 | P0 | ✅ |
| C-2.2 | `POST /mp/user/address` 新增/编辑 | P0 | ✅ |
| C-2.2 | `POST /mp/user/address/:addressId/archive` 删除（软） | P0 | ✅ |
| C-2.2 | `POST /mp/user/address/:addressId/default` 设为默认 | P0 | ✅ |
| C-2.2 | `GET /mp/address/delivery-check` 配送范围校验（LBS） | P0 | ⬜ |
| C-2.2 | `GET /mp/address/region-tree` 省市区树（可缓存） | P0 | ⬜ |

### 2.3 商品与类目（C-3）· `svc-product`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-3.1 | `GET /mp/home` 社区首页聚合（楼层/今日团/秒杀/推荐位） | P0 | ⬜ |
| C-3.1 | `GET /mp/home/channel/:channelCode` 品类频道 | P0 | ⬜ |
| C-3.1 | `GET /mp/seckill/sessions` 秒杀场次 | P1 | ⬜ |
| C-3.2 | `GET /mp/category/tree` 分类树 | P0 | ⬜ |
| C-3.2 | `GET /mp/goods` 商品列表（筛选/排序/分页，**支持 `merchantNo` 店内搜索**） | P0 | ✅ |
| C-3.2 | `GET /mp/search/suggest` 搜索联想 | P1 | ⬜ |
| C-3.2 | `GET /mp/search/hot` 热搜词 · `GET/POST /mp/search/history` 历史 | P1 | ⬜ |
| C-3.2 | `GET /mp/goods/by-barcode` 扫码找货 | P2 | ⬜ |
| C-3.3 | `GET /mp/goods/:goodsNo` 商品详情（五品类形态 + 多规格矩阵） | P0 | ✅ |
| C-3.3 | `GET /mp/goods/:goodsNo/sku-price` 规格选中后实时价格与权益 | P0 | ⬜ |
| C-3.3 | `GET /mp/goods/:goodsNo/service-slots` 服务品类可预约时段 | P0 | ⬜ |
| C-3.4 | `POST /mp/goods/:goodsNo/share` 生成分享参数（带归因） | P0 | 🚧 |
| C-3.4 | `GET /mp/goods/:goodsNo/reviews` 商详评价模块 | P0 | ✅ |

### 2.4 交易（C-4）· `svc-trade` —— **一期最大缺口**

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-4.1 | `GET /mp/cart` 购物车（**按履约方式 + 商家分组**） | P0 | ✅ |
| C-4.1 | `POST /mp/cart/add` · `POST /mp/cart/update` · `POST /mp/cart/remove` | P0 | ✅ |
| C-4.1 | `POST /mp/cart/select` 勾选/全选 | P0 | ⬜ |
| C-4.1 | `POST /mp/cart/clear-invalid` 清空失效区 | P0 | ⬜ |
| C-4.1 | `GET /mp/cart/recommend` 凑单 / 猜你喜欢 | P1 | ⬜ |
| C-4.2 | `POST /mp/order/preview` **结算预览**（拆单 + 运费 + 优惠试算 + 最优券） | P0 | ⬜ |
| C-4.2 | `POST /mp/order` 下单（幂等；**按商家拆子订单**，E3） | P0 | ✅ |
| C-4.2 | `GET /mp/order/fulfillment-options` 可选履约方式（到店自提/邻里自提/商家自送/快递） | P0 | ⬜ |
| C-4.2 | `POST /mp/order/stock/lock` 库存锁定（下单前占位，超时释放） | P0 | ⬜ |
| C-4.3 | `POST /mp/order/:orderNo/pay` 发起支付（微信 JSAPI / App / 支付宝） | P0 | ✅ |
| C-4.3 | `GET /mp/order/:orderNo/pay-result` 支付结果回查（端侧轮询依据） | P0 | ⬜ |
| C-4.3 | `POST /mp/order/:orderNo/pay/cancel` 放弃支付 | P0 | ⬜ |
| C-4.4 | `GET /mp/order` 订单列表（分 tab） | P0 | ✅ |
| C-4.4 | `GET /mp/order/:orderNo` 订单详情（含「由 XX 商家提供并收款」披露） | P0 | ✅ |
| C-4.4 | `GET /mp/order/:orderNo/timeline` 状态时间线 | P0 | ⬜ |
| C-4.4 | `POST /mp/order/:orderNo/cancel` 取消 | P0 | ✅ |
| C-4.4 | `POST /mp/order/:orderNo/urge` 催单 | P0 | ⬜ |
| C-4.4 | `POST /mp/order/:orderNo/rebuy` 再来一单（整单回填购物车） | P0 | ⬜ |
| C-4.4 | `POST /mp/order/:orderNo/confirm-receipt` 确认收货 | P0 | ⬜ |
| C-4.4 | `GET /mp/order/:orderNo/invoice` · `POST /mp/order/:orderNo/invoice` 发票 | P1 | ⬜ |
| C-4.4 | `GET /mp/order/count` 各状态角标数 | P0 | ⬜ |

### 2.5 履约与核销（C-5）· `svc-fulfillment`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-5.1 | `GET /mp/order/:orderNo/pickup-code` 自提码/核销码/兑换码 | P0 | ⬜ |
| C-5.1 | `GET /mp/fulfillment/arrival` 到货通知列表 | P0 | ⬜ |
| C-5.2 | `GET /mp/order/:orderNo/delivery` **商家自送状态**（C-FF-11） | P0 | ⬜ |
| C-5.2 | `GET /mp/order/:orderNo/logistics` 快递轨迹 | P1 | ⬜ |
| C-5.5 | `GET /mp/neighbor-pickup/:groupNo` 邻里自提点信息（**脱敏分阶段**，ADR-005） | P0 | ⬜ |
| C-5.5 | `GET /mp/neighbor-pickup/:groupNo/slots` 取货时段 | P0 | ⬜ |
| C-5.3 | `POST /mp/service-order/:orderNo/reschedule` 服务改期 · `POST /mp/service-order/:orderNo/cancel` 取消 | P1 | ⬜ |
| C-5.3 | `POST /mp/service-order/:orderNo/confirm` 上门服务完成确认 | P1 | ⬜ |
| C-5.3 | `GET /mp/card/times/:cardNo` 次卡余次与消费记录 | P1 | ⬜ |
| C-5.4 | `POST /mp/order/:orderNo/exception` 缺货/少发/破损上报 | P0 | ⬜ |

### 2.6 售后与退款（C-6）· `svc-trade`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-6.1 | `POST /mp/order/:orderNo/after-sale` 申请售后（仅退款/退货退款/换货） | P0 | ✅ |
| C-6.1 | `GET /mp/after-sale/reasons` 售后原因字典（按品类） | P0 | ⬜ |
| C-6.2 | `GET /mp/after-sale` 售后列表 · `GET /mp/after-sale/:afterSaleNo` 详情与时间线 | P0 | ⬜ |
| C-6.2 | `POST /mp/after-sale/:afterSaleNo/cancel` 撤销申请 | P0 | ⬜ |
| C-6.2 | `POST /mp/after-sale/:afterSaleNo/ship` 退货物流回填 | P0 | ⬜ |
| C-6.2 | `POST /mp/after-sale/:afterSaleNo/escalate` 申请平台介入 | P1 | ⬜ |
| C-6.3 | `GET /mp/order/:orderNo/weight-adjust` 称重差价明细（多退少补） | P0 | ⬜ |
| C-6.3 | `POST /mp/after-sale/freshness-claim` 鲜度包赔 | P0 | ⬜ |

### 2.7 营销与优惠（C-7）· `svc-marketing`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-7.1 | `GET /mp/coupon` 领券中心 | P0 | ✅ |
| C-7.1 | `POST /mp/coupon/:couponNo/receive` 领券 | P0 | ✅ |
| C-7.1 | `GET /mp/coupon/mine` 我的券包（可用/已用/过期） | P0 | ⬜ |
| C-7.1 | `POST /mp/coupon/best` 最优券试算（结算页调用） | P0 | ⬜ |
| C-7.2 | `GET /mp/promotion/active` 当前满减/限时/买赠 | P0 | 🚧 |
| C-7.2 | `GET /mp/promotion/newcomer` 新人礼包 · `POST .../claim` 领取 | P0 | ⬜ |
| C-7.3 | `GET /mp/card/mine` 我的卡包（储值卡/次卡/兑换券） | P0 | ✅ |
| C-7.3 | `GET /mp/point/account` 积分账户 | P2 | ✅（开关关） |
| C-7.3 | `GET /mp/point/records` 积分流水 | P2 | ✅（开关关） |
| C-7.3 | `GET /mp/merchant/point/account` 商家积分账户（B 端接收，ADR-006） | P2 | ✅（开关关） |
| C-7.3 | `GET /mp/merchant/point/records` 商家积分流水 | P2 | ✅（开关关） |
| C-7.4 | `GET /mp/member/plans` 会员卡 · `POST /mp/member/subscribe` 开通 · `POST /mp/member/unsubscribe` 退订 | P1 | ⬜ |
| C-7.4 | `GET /mp/member/benefits` 权益中心 | P1 | ⬜ |

### 2.8 团购与求团（C-8）· `svc-marketing`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-8.1 | `GET /mp/group-buy` 商家团列表 · `GET /mp/group-buy/:groupNo` 详情 | P0 | ✅ |
| C-8.1 | `POST /mp/group-buy/:groupNo/join` 参团 | P0 | ✅ |
| C-8.1 | `POST /mp/group-buy` C 端开团（可勾选「送到我家」，C-GB-06） | P0 | ✅ |
| C-8.1 | `GET /mp/group-buy/:groupNo/members` 参团邻居 | P0 | ⬜ |
| C-8.1 | `POST /mp/group-buy/:groupNo/share` 分享参数（「还差 N 人」） | P1 | ⬜ |
| C-8.2 | `GET /mp/group-request` 求团列表 · `GET /mp/group-request/:requestNo` 详情 | P0 | ✅ |
| C-8.2 | `POST /mp/group-request` 发起求团 | P0 | ✅ |
| C-8.2 | `POST /mp/group-request/:requestNo/interest` +1 / 取消 | P0 | ✅ |
| C-8.2 | `GET /mp/group-request/:requestNo/quotes` 多商家报价对比 | P0 | ⬜ |
| C-8.2 | `POST /mp/group-request/:requestNo/choose` 发起人选定报价（**锁价**） | P0 | ✅ |
| C-8.2 | `POST /mp/group-request/:requestNo/confirm` 二次确认下单 | P0 | ✅ |
| C-8.2 | `GET /mp/group-request/:requestNo/price-history` 改价/毁约公示（ADR-003） | P0 | ⬜ |

### 2.9 增长与归因（C-9）· `svc-marketing`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-9.3 | `POST /mp/attribution/report` **进店/邀请归因上报**（优先级 店铺码>邀请人>渠道） | P0 | ⬜ |
| C-9.1 | `POST /mp/share/scene` 生成分享场景值（卡片/朋友圈/海报通用） | P0 | 🚧 |
| C-9.1 | `POST /mp/share/poster` 服务端合成海报（含小程序码） | P0 | 🚧 |
| C-9.1 | `GET /mp/invite/rewards` 邀请有礼进度与奖励（**发券，非现金**） | P0 | ⬜ |
| C-9.2 | `POST /mp/activity/:activityNo/assist` 助力/砍价 | P1 | ⬜ |
| C-9.2 | `GET /mp/activity/newcomer-zone` 新人专区 | P0 | ⬜ |

### 2.10 门店主页（C-10）· `svc-product` + `svc-marketing` —— **一期最高优先级**

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-ST-01 | `GET /mp/store/:merchantNo` 门店主页聚合（**游客可访问，不经首页与选社区**） | P0 | ⬜ |
| C-ST-08 | `GET /mp/store/by-code` 扫码进店解析（`storeCode` → `merchantNo`） | P0 | ⬜ |
| C-ST-02 | `GET /mp/store/:merchantNo/frequent` **我在本店的常买清单**（按频次排序） | P0 | ⬜ |
| C-ST-03 | `POST /mp/store/:merchantNo/rebuy` 一键再来一单（**失效品与涨价品显式标出**） | P0 | ⬜ |
| C-ST-04/05 | `GET /mp/store/:merchantNo/notice` 公告与履约说明 | P0 | ⬜ |
| C-ST-06 | `GET /mp/goods?merchantNo=` 店内搜索（复用商品列表） | P0 | ✅ |
| C-ST-07 | `POST /mp/store/:merchantNo/favorite` 收藏/取消本店 | P0 | ⬜ |
| C-ST-09 | `POST /mp/store/:merchantNo/enter` 进店埋点 + 绑 `merchant_no` + 写 `trafficSource` | P0 | ⬜ |
| C-ST-10 | `GET /mp/store/mine` 我的常去店（首页入口） | P0 | ⬜ |

### 2.11 商家展示与入驻入口（C-11 / C-13.1）· `svc-user`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-13.1 | `GET /mp/merchant` 商家列表/搜索 · `GET /mp/merchant/:merchantNo` 详情 | P0 | ✅ |
| C-13.1 | `GET /mp/merchant/visited` 我买过的商家 | P0 | ✅ |
| C-13.1 | `GET /mp/merchant/:merchantNo/score` 评分与三维度依据 | P0 | ⬜ |
| C-11.x | `POST /mp/merchant/apply` 入驻申请提交 | P0 | ✅ |
| C-11.x | `GET /mp/merchant/apply/status` 审核状态与补交项 | P0 | ⬜ |

### 2.12 评价与信用（C-13.2）· `svc-product`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-13.2 | `GET /mp/review` 评价列表（排序/筛选） | P0 | ✅ |
| C-13.2 | `POST /mp/review` **发表评价**（订单完成后，计入商家评分） | P0 | ✅ |
| C-13.2 | `POST /mp/review/:reviewNo/like` 点赞/取消 | P0 | ✅ |
| C-13.2 | `GET /mp/review/pending` 待评价订单 | P0 | ⬜ |

### 2.13 消息与客服（C-14）· `svc-message`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-14.1 | `GET /mp/message` 消息列表 · `POST /mp/message/:messageNo/read` · `POST /mp/message/read-all` | P0 | ✅ |
| C-14.1 | `POST /mp/message/subscribe` 订阅消息授权上报（模板 ID + 结果） | P0 | ⬜ |
| C-14.1 | `POST /mp/push/token` App 推送 token 注册 | P1 | ⬜ |
| C-14.2 | `POST /mp/ticket` 提交工单 · `GET /mp/ticket` 我的工单 · `GET /mp/ticket/:ticketNo` 详情 | P0 | ⬜ |
| C-14.2 | `GET /mp/help/faq` 帮助中心 | P0 | ⬜ |
| C-14.3 | `GET /mp/favorite` 收藏 · `POST /mp/favorite/toggle` · `GET /mp/footprint` 足迹 | P1 | 🚧 |

### 2.14 系统与配置（C-17）· `svc-config`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-17.1 | `GET /mp/config/theme` 运营下发默认皮肤/节日皮肤 | P0 | ⬜ |
| C-17.1 | `GET /mp/config/bootstrap` 启动配置（开关/版本/强更/客服入口） | P0 | ⬜ |
| C-17.2 | `GET /mp/config/i18n/:locale` 服务端文案包 | P0 | ⬜ |
| C-17.3 | `GET /mp/config/markets` 市场/货币/时区/汇率 | P0 | ⬜ |

---

## 三、B 端 API `/biz/**`

> 载体一期是 C 端小程序内的商家专区 + ops-web 商家视图（ADR-001）。**同一份后端端点服务两个载体**，
> 差别只在 ops-web 额外开放批量导入导出这类重管理端点（下表标注「ops-web」）。

### 3.1 入驻与账号（B-11.1）· `svc-user`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| B-11.1.1 | `POST /biz/apply/submit` 提交入驻（主体类型 个人/个体户/企业） | P0 | ⬜ |
| B-11.1.2 | `POST /biz/apply/qualification` 资质上传（营业执照/许可证） | P0 | ⬜ |
| B-11.1.3 | `POST /biz/apply/category` 经营类目申请 | P0 | ⬜ |
| B-11.1.4 | `POST /biz/apply/settle-account` **结算账户报备**（分账接收方，后端持有，C 端不暴露） | P0 | ⬜ |
| B-11.1.5 | `GET /biz/apply/status` 审核状态与补交项 · `POST /biz/apply/supplement` 补交 | P0 | ⬜ |
| B-11.1.6 | `GET /biz/agreement` · `POST /biz/agreement/sign` 协议签署 | P0 | ⬜ |
| B-11.1 | `GET /biz/context` **当前身份与数据域**（是否商家/是否自提点/可用 `merchantNo`、`pickupNo`） | P0 | ⬜ |

### 3.2 店铺与获客（B-11.2）· `svc-product` —— **与 C-10 成对交付**

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| B-11.2.1 | `GET /biz/store/profile` · `POST /biz/store/profile` 店铺资料（logo/简介/标签） | P0 | ⬜ |
| B-11.2.2 | `POST /biz/store/business-hours` 营业时间与地址 | P0 | ⬜ |
| B-11.2.3 | `POST /biz/store/service-area` 服务范围（LBS 半径/围栏） | P0 | ⬜ |
| B-11.2.4 | `GET /biz/store/badge` 认证标状态 | P0 | ⬜ |
| B-11.2.5 | `GET /biz/store/decoration` · `POST /biz/store/decoration` **店铺装修**（店招/公告/主推排序，手机端极简） | P0 | ⬜ |
| B-11.2.6 | `GET /biz/store/qrcode` **我的店铺码**（含可打印版/贴纸尺寸） | P0 | ⬜ |
| B-11.2.7 | `POST /biz/store/share-material` **分享素材一键生成**（卡片+海报+群发文案） | P0 | ⬜ |
| B-11.2.8 | `GET /biz/store/customers` 客户与复购数据（进店/复购率/沉默客户） | P1 | ⬜ |

### 3.3 商品（B-11.3）· `svc-product`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| B-11.3.1 | `GET /biz/goods` 我的商品列表 · `GET /biz/goods/:goodsNo` 详情 | P0 | ⬜ |
| B-11.3.1 | `POST /biz/goods` 新建/编辑（五品类模板） | P0 | ⬜ |
| B-11.3.2 | `POST /biz/goods/:goodsNo/sku` 多规格 SKU 矩阵 | P0 | ⬜ |
| B-11.3.3 | `POST /biz/goods/:goodsNo/i18n` 多语言文案录入 | P0 | ⬜ |
| B-11.3.4 | `POST /biz/goods/:goodsNo/market-price` **多市场分别定价**（B6） | P0 | ⬜ |
| B-11.3.5 | `POST /biz/goods/:goodsNo/on-shelf` · `POST /biz/goods/:goodsNo/off-shelf` 上下架 | P0 | ⬜ |
| B-11.3.5 | `POST /biz/goods/:goodsNo/submit-audit` 提审 | P0 | ⬜ |
| B-11.3.6 | `POST /biz/goods/:goodsNo/stock` 库存/预售额度 | P0 | ⬜ |
| B-11.3.7 | `POST /biz/goods/:goodsNo/media` 图片视频 | P0 | ⬜ |
| B-11.3.8 | `POST /biz/goods/import` · `GET /biz/goods/export` 批量导入导出（**ops-web**） | P1 | ⬜ |

### 3.4 订单与配送（B-11.4）· `svc-trade` / `svc-fulfillment`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| B-11.4.1 | `GET /biz/order` 订单列表（**仅本 `merchantNo` 子订单**） · `GET /biz/order/:subOrderNo` 详情 | P0 | ⬜ |
| B-11.4.2 | `POST /biz/order/:subOrderNo/accept` 接单 · `POST /biz/order/:subOrderNo/prepare` 备货 · `POST /biz/order/:subOrderNo/ship` 发货 | P0 | ⬜ |
| B-11.4.3 | `POST /biz/order/:subOrderNo/waybill` 运单回填 | P0 | ⬜ |
| B-11.4.4 | `POST /biz/order/:subOrderNo/verify` 到店核销（**商家自有门店核销，与自提点核销台区分**） | P0 | ⬜ |
| B-11.4.5 | `POST /biz/order/:subOrderNo/shortage` 缺货处理 · `POST /biz/order/:subOrderNo/substitute` 替换品发起 | P0 | ⬜ |
| B-11.4.6 | `GET /biz/delivery/config` · `POST /biz/delivery/config` **自送范围/起送价/免运门槛** | P0 | ⬜ |
| B-11.4.7 | `GET /biz/delivery/pending` 待配送列表 · `POST /biz/delivery/:subOrderNo/delivered` **一键已送达** | P0 | ⬜ |
| B-11.4 | `GET /biz/order/export` 订单导出（**ops-web**） | P1 | ⬜ |

> ⚠️ **商家自送不做骑手系统**（ADR-005 §5）：只有「待配送列表」与「已送达」两个端点，**不提供位置回传**。

### 3.5 自提点履约台（B-10）· `svc-fulfillment` —— **由现有 `leader*` 端点迁移（E10）**

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| B-10.1.1 | `GET /biz/pickup/overview` 履约总览（今日待核销/到货批次/本月服务费） | P0 | ✅→迁移 |
| B-10.2.1 | `POST /biz/pickup/verify` **扫码核销**（幂等；失败原因：已核销/非本点/已退款） | P0 | ✅→迁移 |
| B-10.2.2 | `GET /biz/pickup/verify/search` 输码/搜码查单 | P0 | ⬜ |
| B-10.2.3 | `POST /biz/pickup/verify/batch` 批量核销 | P0 | ⬜ |
| B-10.2.4 | `POST /biz/pickup/verify/proxy` 代核销（**强制留痕**） | P0 | ⬜ |
| B-10.2 | `GET /biz/pickup/orders` 本 `pickup_no` 全部订单（**含他家商品，字段裁剪**） | P0 | ✅→迁移 |
| B-10.3.1/2 | `GET /biz/pickup/picking` 分拣单（按商品视图 / 按用户视图） | P0 | ✅→迁移 |
| B-10.3.3 | `GET /biz/pickup/picking/export` 打印/导出 | P0 | ⬜ |
| B-10.3.4 | `POST /biz/pickup/picking/shortage` 缺货标记回传 | P0 | ⬜ |
| B-10.4.1 | `POST /biz/pickup/batch/:batchNo/receive` 签收到货批次 | P0 | ✅→迁移 |
| B-10.4.2 | `POST /biz/pickup/batch/:batchNo/damage` 破损/短少上报 | P0 | ⬜ |
| B-10.5.1 | `GET /biz/pickup/service-fee` 履约服务费明细（R15/B9 口径待定） | P0 | ⬜ |
| B-10.5.2 | `GET /biz/pickup/service-fee/settlement` 结算周期展示 | P0 | ⬜ |

### 3.6 邻里自提 · 团发起人轻核销（作用域 `group_no`）· `svc-fulfillment`

> ⚠️ **与 §3.5 是两套权限，端点不可复用**（E16）：发起人**零报酬**，作用域限单个 `group_no`，
> 且**不需要商家身份**（C 端普通用户即可调用）。地址与手机号脱敏更严（ADR-005 §4）。
>
> ⚠️ **前缀是 `/mp/` 而不是 `/biz/`，这是一处已修正的设计错误**：早期把它归到 `/biz/group/**`，
> 但 `/biz/**` 的 `BizIdentityResolver` 对非商家返回空作用域并 fail-closed 403 ——
> 而团发起人**恰恰就是普通用户**。放在 `/biz` 下会导致邻里自提永远无法核销，
> 且症状是 403 而非 404，排查时极易误判为「权限没配对」。
> 作用域由**路径里的 `groupNo` + 发起人属主校验**决定，不需要 B 端身份。

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| C-FF-09 | `POST /mp/groups/:groupNo/verify` 发起人逐单核销 | P0 | ⬜ |
| C-FF-10 | `POST /mp/groups/:groupNo/receive` 批次签收 | P0 | ⬜ |
| C-FF-08 | `GET /mp/groups/:groupNo/orders` 本团订单（**脱敏：仅昵称/品名/数量/取货码**） | P0 | ⬜ |
| ADR-005 | `GET /mp/groups/hosted` 我发起的团（轻核销入口） | P0 | ⬜ |
| ADR-005 | `POST /mp/groups/:groupNo/pickup-point` 设置「送到我家」地址与取货时段 | P0 | ⬜ |
| B15 | `POST /mp/groups/:groupNo/orders/:orderNo/no-show` 标记未取（超时后转自送或退款） | P0 | ⬜ |

### 3.7 售后（B-11.5）· `svc-trade`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| B-11.5.1 | `GET /biz/after-sale` 待处理列表 · `GET /biz/after-sale/:afterSaleNo` 详情 | P0 | ⬜ |
| B-11.5.2 | `POST /biz/after-sale/:afterSaleNo/approve` 同意 · `POST /biz/after-sale/:afterSaleNo/reject` 驳回（附举证） | P0 | ⬜ |
| B-11.5.3 | `POST /biz/after-sale/:afterSaleNo/receive` 退货收货确认 | P0 | ⬜ |
| B-11.5.4 | `POST /biz/after-sale/:afterSaleNo/escalate` 争议上升平台 | P0 | ⬜ |

### 3.8 团购供给（B-11.6）· `svc-marketing`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| B-11.6.1 | `POST /biz/goods/:goodsNo/group-config` 配 `{起团人数, 团购价}` | P0 | ⬜ |
| B-11.6.2 | `POST /biz/group-buy` 商家开团 · `GET /biz/group-buy` 我的团与进度 | P0 | ⬜ |
| B-11.6.3 | `GET /biz/group-request/pool` 可报价需求单池 | P0 | ⬜ |
| B-11.6.3 | `POST /biz/group-request/:requestNo/quote` **提交报价**（单价/起订量/说明/有效期，**不事前审核**，ADR-003） | P0 | ⬜ |
| B-11.6.4 | `POST /biz/quote/:quoteNo/revise` 改价（**留痕 + 涨价公示**） · `POST /biz/quote/:quoteNo/withdraw` 撤回 | P0 | ⬜ |
| B-11.6.5 | `GET /biz/credit` 履约承诺与毁约记录 | P0 | ⬜ |

### 3.9 评价（B-11.7）· `svc-product`

| 矩阵 ID | 端点 | P | 状态 |
|---------|------|:-:|:-:|
| B-11.7.1 | `GET /biz/review` 我的评价列表 | P0 | ⬜ |
| B-11.7.2 | `POST /biz/review/:reviewNo/reply` 商家回复 | P0 | ⬜ |
| B-11.7.3 | `POST /biz/review/:reviewNo/appeal` 恶意差评申诉 | P0 | ⬜ |
| B-11.7.4 | `GET /biz/review/score-board` 评分与三维度看板 | P1 | ⬜ |

### 3.10 营销 · 结算 · 员工 · 数据（B-11.8~11.11）

| 矩阵 ID | 端点 | 模块 | P | 状态 |
|---------|------|------|:-:|:-:|
| B-11.8.1 | `GET/POST /biz/coupon` 商家券 | `svc-marketing` | P1 | ⬜ |
| B-11.8.2 | `GET/POST /biz/promotion` 店铺满减 | `svc-marketing` | P1 | ⬜ |
| B-11.8.3 | `POST /biz/activity/:activityNo/enroll` 报名平台活动 | `svc-marketing` | P1 | ⬜ |
| B-11.9.1 | `GET /biz/settle/bills` 结算单（应分/已分/分账状态） | `svc-settle` | P0 只读 | ⬜ |
| B-11.9.2 | `GET /biz/settle/bills/:settleNo` 账单明细与对账 | `svc-settle` | P0 只读 | ⬜ |
| B-11.9.3 | `POST /biz/settle/withdraw` 提现申请 · `GET /biz/settle/withdraw` 到账状态 | `svc-settle` | P1 | ⬜ |
| B-11.9.4 | `GET /biz/settle/invoice` 发票与个税说明 | `svc-settle` | P1 | ⬜ |
| B-11.9.5 | `GET /biz/settle/rate-card` **费率说明**（自带客流 vs 平台客流分档，R16/B10） | `svc-settle` | P0 | ⬜ |
| B-11.10.1 | `GET/POST /biz/staff` 店员账号 · `POST /biz/staff/:staffId/archive` | `svc-user` | P1 | ⬜ |
| B-11.10.2 | `POST /biz/staff/:staffId/role` 角色权限（**无财务**） | `svc-user` | P1 | ⬜ |
| B-11.10.3 | `GET /biz/staff/audit-log` 操作日志 | `svc-user` | P1 | ⬜ |
| B-11.11 | `GET /biz/stats/overview` 销量/GMV/转化 · `GET /biz/stats/score-trend` · `GET /biz/stats/quote-rate` | `svc-report` | P1 | ⬜ |

### 3.11 服务与配送人员（B-5，P1）· `svc-fulfillment`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| B-5.5 | `GET/POST /biz/service/capacity` 产能与时段库存 · `POST /biz/service/schedule` 排班 · `POST /biz/service/reschedule/:orderNo/approve` 改期审批 | P1 |
| B-5.6 | `GET /biz/service/tasks` 我的服务单 · `POST /biz/service/:taskNo/accept` · `GET /biz/service/checkin` 出发到达打卡 · `GET /biz/service/start` · `GET /biz/service/complete` · `GET /biz/service/evidence` 凭证 | P1 |
| B-5.7 | `GET /biz/courier/tasks` 配送单 · `POST /biz/courier/:taskNo/accept` · `GET /biz/courier/location` 位置回传 · `GET /biz/courier/signed` 签收 | P1 |

---

## 四、平台端 API `/ops/**`

> 载体 ops-web（PC Web）。全部端点走 **RBAC + 数据域授权**，所有写操作**强制审计留痕**，
> 高危操作（打款、分账、封禁、强制下架、环境切换）额外要求二次校验（P-1.1.5）。

### 4.1 账号与权限（P-1）· `svc-ops`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-1.1.1 | `POST /ops/auth/login` · `POST /ops/auth/logout` · `GET /ops/auth/me` | P0 |
| P-1.1.1 | `GET/POST /ops/staff` 员工账号 · `POST /ops/staff/:staffId/archive` · `POST /ops/staff/:staffId/reset-password` | P0 |
| P-1.1.2 | `GET/POST /ops/role` 角色 · `POST /ops/role/:roleId/permissions` RBAC 授权 | P0 |
| P-1.1.3 | `POST /ops/staff/:staffId/data-scope` 数据域授权（商家/社区/自提点） | P0 |
| P-1.1.4 | `GET /ops/audit-log` 操作审计日志（可按对象/人/时间检索） | P0 |
| P-1.1.5 | `POST /ops/auth/step-up` 敏感操作二次校验 | P0 |

### 4.2 社区与自提点（P-2）· `svc-community`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-2.1.1 | `GET/POST /ops/region` 城市/网格 · `GET/POST /ops/community` 社区主数据 | P0 |
| P-2.1.2 | `POST /ops/community/:communityNo/enable` · `POST /ops/community/:communityNo/disable` 开城开关 | P0 |
| P-2.1.3 | `POST /ops/community/:communityNo/fence` 覆盖范围与围栏 | P0 |
| P-2.2.1 | `GET/POST /ops/pickup-point` 自提点建档（地址/营业/到货时间） | P0 |
| P-2.2.2 | `POST /ops/pickup-point/:pickupNo/enable` · `POST /ops/pickup-point/:pickupNo/disable` · `POST /ops/pickup-point/:pickupNo/migrate` 迁移 | P0 |
| P-2.2.3 | `GET /ops/pickup-point` 支持按 `type=STORE\|NEIGHBOR`、`scope` 过滤（ADR-005） | P0 |
| P-2.2.4 | `POST /ops/pickup-point/:pickupNo/service-fee-rate` 履约服务费费率（**仅常驻点**） | P0 |
| P-2.2.5 | `GET /ops/pickup-point/temporary-monitor` **临时点职业化监控**（30 天 ≥3 次触发风控） | P0 |

### 4.3 类目与商品（P-3）· `svc-product`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-3.1.1 | `GET/POST /ops/category` 三级类目树 · `POST /ops/category/:categoryNo/sort` | P0 |
| P-3.1.2 | `GET/POST /ops/category/:categoryNo/attr-template` 品类属性模板（五品类） | P0 |
| P-3.1.3 | `POST /ops/category/:categoryNo/i18n` 类目多语言 | P0 |
| P-3.1.4 | `POST /ops/category/:categoryNo/qualification` 类目资质要求 | P0 |
| P-3.2.1 | `GET/POST /ops/goods` 平台自营商品维护 · `GET /ops/goods/:goodsNo` | P0 |
| P-3.2.2 | `GET /ops/goods/audit-queue` 提审队列 · `POST /ops/goods/:goodsNo/audit` 通过/驳回 | P0 |
| P-3.2.3 | `POST /ops/goods/:goodsNo/force-off-shelf` 强制下架（高危） | P0 |
| P-3.2.4 | `POST /ops/goods/import` 批量导入 · `POST /ops/goods/batch-price` 批量改价 | P0 |
| P-3.2.5 | `GET /ops/goods/i18n-queue` · `POST /ops/goods/:goodsNo/i18n-audit` 翻译审核（R9） | P0 |
| P-3.2.6 | `GET/POST /ops/market/pricing` 多市场定价 · `GET/POST /ops/market/fx-rate` 汇率 | P0 |
| P-3.3.1 | `POST /ops/goods/:goodsNo/presale-quota` 预售额度 | P0 |
| P-3.3.2 | `POST /ops/goods/:goodsNo/cutoff-time` 截单时间 | P0 |
| P-3.3.3 | `GET /ops/stock/oversell-alert` 超卖告警 | P0 |

### 4.4 订单与支付（P-4）· `svc-trade`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-4.1.1 | `GET /ops/order` 全量订单检索（多维度） | P0 |
| P-4.1.2 | `GET /ops/order/:orderNo` 详情与**拆单视图**（主单 → 子订单 → 分账） | P0 |
| P-4.1.3 | `POST /ops/order/:orderNo/adjust-price` 改价 · `POST /ops/order/:orderNo/change-address` · `POST /ops/order/:orderNo/change-pickup` | P0 |
| P-4.1.4 | `POST /ops/order/proxy-create` 代客下单 · `POST /ops/order/:orderNo/proxy-cancel` 代客取消 | P0 |
| P-4.1.5 | `GET /ops/order/exception` 异常单池 · `POST /ops/order/:orderNo/exception/resolve` | P0 |
| P-4.2.1 | `GET /ops/payment/flow` 支付流水核对 · `GET /ops/payment/reconcile` 对账差异 | P0 |
| P-4.2.2 | `POST /ops/payment/:payNo/compensate` 掉单补偿 | P0 |
| P-4.2.3 | `GET/POST /ops/payment/close-policy` 关单策略配置 | P0 |

### 4.5 履约与物流（P-5）· `svc-fulfillment`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-5.1.1 | `GET/POST /ops/fulfillment/batch` 到货批次与配车 | P0 |
| P-5.1.2 | `GET /ops/fulfillment/picking-summary` 按自提点汇总分拣 | P0 |
| P-5.1.3 | `GET /ops/fulfillment/verify-monitor` 核销监控与逾期看板 | P0 |
| P-5.1.4 | `GET/POST /ops/fulfillment/overdue-policy` 逾期规则（顺延/作废，B2） | P0 |
| P-5.2.1 | `GET/POST /ops/logistics/provider` 快递面单/轨迹对接配置 | P0 |
| P-5.2.2 | `GET/POST /ops/logistics/capacity` 运力配置 | P0 |
| P-5.2.3 | `GET/POST /ops/logistics/freight-template` 运费模板与超区规则 | P0 |
| P-5.2.4 | `GET/POST /ops/logistics/third-party` 第三方运力对接（一期仅快递 + 商家自送） | P1 |

### 4.6 售后治理（P-6）· `svc-trade`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-6.1.1 | `GET /ops/after-sale` 工单池 · `GET /ops/after-sale/:afterSaleNo` 详情 | P0 |
| P-6.1.2 | `GET/POST /ops/after-sale/instant-refund-policy` 极速退阈值（金额/品类/时限） | P0 |
| P-6.1.3 | `POST /ops/after-sale/:afterSaleNo/arbitrate` 平台介入裁决 | P0 |
| P-6.1.4 | `POST /ops/after-sale/:afterSaleNo/liability` **责任判定与赔付归属**（平台/供货商家/自提点商家，M4） | P0 |
| P-6.1.5 | `POST /ops/after-sale/:afterSaleNo/refund` 执行退款（**含回退分账**，E4） | P0 |

### 4.7 营销与内容位（P-7）· `svc-marketing`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-7.1.1 | `GET/POST /ops/coupon/template` 券模板（满减/折扣/新人/定向） | P0 |
| P-7.1.2 | `POST /ops/coupon/:couponNo/issue` 发放（领券中心/定向/自动） | P0 |
| P-7.1.3 | `GET/POST /ops/coupon/:couponNo/budget` 预算与库存 | P0 |
| P-7.1.4 | `GET /ops/coupon/:couponNo/effect` 核销效果 | P0 |
| P-7.2.1 | `GET/POST /ops/activity/seckill` 限时特价/秒杀场次 | P0 |
| P-7.2.2 | `GET/POST /ops/activity/discount-ladder` 满减阶梯 | P0 |
| P-7.2.3 | `GET/POST /ops/activity/gift` 买赠 | P0 |
| P-7.2.4 | `GET/POST /ops/activity/newcomer` 新人礼包 | P0 |
| P-7.3.1 | `GET/POST /ops/cms/floor` 首页楼层与 Banner | P0 |
| P-7.3.2 | `GET/POST /ops/cms/topic` 专题页 | P0 |
| P-7.3.3 | `GET/POST /ops/cms/channel-layout` 频道布局 | P0 |
| P-7.3.4 | `POST /ops/cms/:cmsNo/scope` 投放范围（社区/市场/语言） | P0 |
| P-7.3.5 | `POST /ops/cms/:cmsNo/schedule` 定时上下线 | P0 |
| P-7.4 | `GET/POST /ops/member/plan` 会员卡与权益 · `GET /ops/member/subscriptions` 续费退订 | P1 |

### 4.8 团购与求团撮合（P-8）· `svc-marketing`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-8.1.1 | `GET /ops/group-buy/audit-queue` · `POST /ops/group-buy/:groupNo/audit` 团模板审核 | P0 |
| P-8.1.2 | `GET/POST /ops/group-buy/params` 成团参数（人数/价/口径 G1-G3） | P0 |
| P-8.1.3 | `POST /ops/group-buy/:groupNo/refund-diff` 成团差价退回调度 | P0 |
| P-8.1.4 | `GET /ops/group-buy/monitor` 团监控 | P0 |
| P-8.2.1 | `GET /ops/group-request` 需求单池与看板 | P0 |
| P-8.2.2 | `POST /ops/group-request/:requestNo/assign` **人肉指派商家报价** | P0 |
| P-8.2.3 | `GET/POST /ops/group-request/auto-push-rule` 类目自动推送（中期） | P1 |
| P-8.2.4 | `GET /ops/quote/audit-trail` 报价监控与改价留痕审计 | P0 |
| P-8.2.5 | `POST /ops/merchant/:merchantNo/quote-restrict` 毁约判定与限制报价（G6/B5） | P0 |
| P-8.2.6 | `POST /ops/group-request/:requestNo/take-down` 违规需求单下架 | P0 |

### 4.9 归因与裂变（P-9）· `svc-marketing`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-9.1.1 | `GET/POST /ops/attribution/priority` 归因优先级（**店铺码 > 邀请人 > 渠道**） | P0（B1 未拍板） |
| P-9.1.2 | `GET/POST /ops/attribution/window` 窗口期（默认 30 天） | P0 |
| P-9.1.3 | `GET /ops/attribution/chain` 归因链路查询与审计 | P0 |
| P-9.1.4 | `POST /ops/attribution/:recordNo/resolve-conflict` 冲突裁决 | P0 |
| P-9.1.5 | `GET/POST /ops/attribution/store-code-rule` **店铺码归因规则**（已归属 A 店又扫 B 店码） | P0 |
| P-9.2.1 | `GET/POST /ops/growth/invite-reward` 邀请有礼（**发券，非现金**） | P0 |
| P-9.2.2 | `GET/POST /ops/growth/referral` 老带新奖励 | P0 |
| P-9.2.3 | `GET/POST /ops/growth/new-user-rule` 新客判定口径（设备+手机号） | P0 |
| P-9.2.4 | `GET/POST /ops/growth/poster-template` 海报/卡片素材模板 | P0 |

### 4.10 门店主页治理（P-10）· `svc-product`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-10.1.1 | `GET/POST /ops/store/template` 店铺主页模板配置 | P0 |
| P-10.1.2 | `GET /ops/store/audit-queue` · `POST /ops/store/:merchantNo/audit` 店招/公告合规审核（敏感词+图片） | P0 |
| P-10.1.3 | `POST /ops/store/qrcode/batch-generate` · `GET /ops/store/qrcode/export` 店铺码批量导出（供 BD 地推） | P0 |
| P-10.1.4 | `GET /ops/store/acquisition-board` 门店获客看板（扫码→进店→首单） | P0 |

### 4.11 商家治理（P-11）· `svc-user`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-11.1.1 | `GET /ops/merchant/apply-queue` · `POST /ops/merchant/apply/:applyNo/audit` 入驻审核 | P0 |
| P-11.1.2 | `POST /ops/merchant/:merchantNo/badge` 认证标授予/撤销 | P0 |
| P-11.1.3 | `POST /ops/merchant/:merchantNo/category-grant` 类目权限授权 | P0 |
| P-11.1.4 | `POST /ops/merchant/:merchantNo/penalty` 违规处置 · `POST /ops/merchant/:merchantNo/ban` 封禁（高危） | P0 |
| P-11.1.5 | `GET /ops/merchant/:merchantNo/credit` 信用档案（毁约次数） | P0 |
| P-11.1.6 | `POST /ops/merchant/:merchantNo/tier` 商家分层 `Merchant.tier` | P0 |
| P-11.1 | `GET /ops/merchant` 商家检索 · `GET /ops/merchant/:merchantNo` 详情 | P0 |

### 4.12 结算与资金（P-12）· `svc-settle`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-12.1.1 | `GET /ops/settle/receiver` 分账接收方报备状态 · `POST /ops/settle/receiver/:merchantNo/sync` | P0 |
| P-12.1.2 | `POST /ops/settle/bill/generate` 结算单生成（**按子订单**） · `GET /ops/settle/bill` | P0 |
| P-12.1.3 | `POST /ops/settle/split/:settleNo/execute` 分账指令 · `POST /ops/settle/split/:settleNo/retry` 重试 | P0 |
| P-12.1.4 | `GET /ops/settle/split/timeout` **超时兜底**（超期解冻回平台） | P0 |
| P-12.1.5 | `POST /ops/settle/split/:settleNo/reverse` 退款回退分账 | P0 |
| P-12.1.6 | `GET/POST /ops/settle/commission-rule` 平台佣金扣取规则 | P0 |
| P-12.1.7 | `GET/POST /ops/settle/traffic-rate` **按 `trafficSource` 分档计费**（R16/B10） | P0 |
| P-12.1.8 | `GET/POST /ops/settle/pickup-service-fee` 自提点履约服务费结算（R15/B9） | P0 |
| P-12.2.1 | `GET /ops/settle/withdraw` · `POST /ops/settle/withdraw/:withdrawNo/approve` 提现审批（高危） | P1 |
| P-12.2.2 | `GET/POST /ops/settle/withdraw-limit` 限额与频次 | P1 |
| P-12.2.3 | `GET /ops/settle/tax` 个税代扣与申报 | P1 |
| P-12.2.4 | `GET /ops/settle/voucher` 结算凭证/发票 | P1 |
| P-12.2.5 | `POST /ops/settle/business-registration` 一键代办个体工商户（ADR-002） | P1 |

### 4.13 评价治理（P-13）· `svc-product`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-13.1.1 | `GET /ops/review/audit-queue` · `POST /ops/review/:reviewNo/audit` 评价审核与敏感词 | P0 |
| P-13.1.2 | `POST /ops/review/:reviewNo/image-audit` 图片审核 | P0 |
| P-13.1.3 | `GET /ops/review/appeal` · `POST /ops/review/appeal/:appealNo/arbitrate` 差评申诉裁决 | P0 |
| P-13.1.4 | `GET/POST /ops/review/score-algorithm` **评分算法参数**（权重/新商家保护期/衰减，R11/B4） | P0 |
| P-13.1.5 | `GET /ops/review/fraud-detect` 刷评识别 | P0 |

### 4.14 消息与客服（P-14）· `svc-message`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-14.1.1 | `GET/POST /ops/message/template` 订阅消息模板管理 | P0 |
| P-14.1.2 | `GET/POST /ops/message/push-task` 推送任务与人群 | P0 |
| P-14.1.3 | `POST /ops/message/broadcast` 站内信发布 | P0 |
| P-14.1.4 | `GET/POST /ops/message/frequency-control` 触达频控 | P0 |
| P-14.2.1 | `GET /ops/ticket` 工单池 · `POST /ops/ticket/:ticketNo/assign` 分派 | P0 |
| P-14.2.2 | `GET /ops/ticket/:ticketNo/session` 会话 · `GET/POST /ops/ticket/knowledge` 知识库 | P0 |
| P-14.2.3 | `GET /ops/ticket/proxy-action-log` 代客操作留痕（M6 阈值待定） | P0 |
| P-14.2.4 | `GET/POST /ops/help/faq` FAQ/帮助中心维护 | P0 |

### 4.15 素材与内容（P-15）· `svc-marketing`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-15.1.1 | `GET/POST /ops/material` 商品文案/图/海报模板 | P0 |
| P-15.1.2 | `POST /ops/material/video` 短视频素材 | P0 |
| P-15.1.3 | `POST /ops/material/:materialNo/distribute` 按社区/语言分发 | P0 |
| P-15.1.4 | `POST /ops/material/:materialNo/visibility` 商家可见范围 | P0 |
| P-15.2 | `GET /ops/content/audit-queue` 种草审核 · `GET/POST /ops/content/rank-rule` 榜单 · `GET /ops/content/qa` 问答管理 | P1 |

### 4.16 数据与风控（P-16）· `svc-report` / `svc-risk`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-16.1.1 | `GET /ops/report/gmv` GMV/订单/客单 | P0 |
| P-16.1.2 | `GET /ops/report/rank` 社区与商家排行 | P0 |
| P-16.1.3 | `GET /ops/report/merchant` 商家经营 | P0 |
| P-16.1.4 | `GET /ops/report/acquisition-funnel` **获客漏斗**（扫码→进店→注册→首单）与拉新成本 | P0 |
| P-16.1.5 | `GET /ops/report/fulfillment-quality` 核销率/逾期率/售后率 | P0 |
| P-16.1.6 | `GET /ops/report/traffic-source` `trafficSource` 结构分析 | P0 |
| P-16.2.1 | `GET /ops/risk/fraud-order` 刷单识别 | P0 |
| P-16.2.2 | `GET /ops/risk/abnormal-fission` 异常裂变（同设备/同 IP） | P0 |
| P-16.2.3 | `GET /ops/risk/refund-abuse` 恶意退款画像 | P0 |
| P-16.2.4 | `GET/POST /ops/risk/blacklist` 黑名单 · `POST /ops/risk/appeal/:appealNo/resolve` 申诉 | P0 |
| P-16.2.5 | `GET/POST /ops/risk/rule` 拦截规则配置 | P0 |

### 4.17 系统配置（P-17）· `svc-config`

| 矩阵 ID | 端点 | P |
|---------|------|:-:|
| P-17.1.1 | `GET/POST /ops/config/theme` 全局默认/节日皮肤下发（C-TH-05） | P0 |
| P-17.1.2 | `GET/POST /ops/config/i18n` 语言与文案回落规则（R9） | P0 |
| P-17.1.3 | `GET/POST /ops/config/market` 市场/货币/时区/汇率 | P0 |
| P-17.1.4 | `GET/POST /ops/config/rule-text` 规则文案（退款/自提/称重差价） | P0 |
| P-17.1.5 | `GET/POST /ops/config/feature-flag` 开关与灰度（含 `FEATURES.points`） | P0 |
| P-17.1.6 | `GET/POST /ops/config/tenant` 归属键 `tenant_no=MAIN` 预留 | P0 |

---

## 五、公共与回调

### 5.1 公共 `/common/**`

| 端点 | 说明 | P |
|------|------|:-:|
| `POST /common/upload/token` 直传凭证（对象存储） | 售后凭证、评价晒单、商家资质、商品图 | P0 |
| `POST /common/upload` 服务端中转上传（小程序兜底） | 同上 | P0 |
| `GET /common/dict/:dictCode` 字典 | 售后原因、类目属性、状态枚举 | P0 |
| `GET /common/region` 行政区划 | 地址簿、门店地址 | P0 |
| `GET /common/health` 健康检查 | 发布探针 | P0 |

### 5.2 第三方回调 `/callback/**`（**不走 Bearer，各自验签 + 幂等**）

| 端点 | 来源 | 说明 | P |
|------|------|------|:-:|
| `POST /callback/wechat/pay` | 微信支付 | 支付结果 | P0 |
| `POST /callback/pay/stub` | **本地/测试通道** | S2 联调用，共享密钥验签；**接真支付后删除** | 仅 dev |
| `POST /callback/wechat/refund` | 微信支付 | 退款结果 | P0 |
| `POST /callback/wechat/profit-share` | 微信支付 | **分账结果**（ADR-002） | P0 |
| `POST /callback/alipay/pay` · `/callback/alipay/refund` | 支付宝 | App 端支付 | P1 |
| `POST /callback/logistics/trace` | 快递服务商 | 轨迹推送 | P1 |
| `POST /callback/sms/report` | 短信通道 | 送达回执 | P0 |
| `POST /callback/push/report` | APNs/FCM/厂商 | 推送回执 | P1 |
| `POST /callback/content-audit` | 内容安全服务 | 图片/文本审核异步结果 | P0 |

### 5.3 内部定时任务（无 HTTP 入口，MQ / 调度触发，列出以免遗漏）

订单超时关单 · 库存锁定释放 · 预售截单 · 到货提醒下发 · 自提逾期处置 ·
成团判定与差价退回 · 报价有效期失效 · 结算单生成 · 分账执行与重试 · 分账超时兜底 ·
积分过期（开关关）· 归因窗口过期 · 风控画像重算 · 数据看板离线聚合

---

## 六、按一期批次（M1-1 ~ M1-6）拆的交付顺序

| 批次 | 端点范围 | 数量（约） |
|:---:|---------|:---:|
| **M1-1 交易闭环** | `/mp` §2.4 全部 + §2.5 C-5.1 + §2.6 售后 + §2.2 地址簿；`/biz` §3.4 最小集；`/ops` §4.4 | 55 |
| **M1-2 入驻 + 门店主页** | `/mp` §2.10 + §2.11；`/biz` §3.1 §3.2 §3.3；`/ops` §4.10 §4.11 | 50 |
| **M1-3 履约闭环** | `/mp` C-5.1/5.2；`/biz` §3.5 §3.6；`/ops` §4.5 | 35 |
| **M1-4 营销与评价** | `/mp` §2.7 §2.12；`/biz` §3.9；`/ops` §4.7 §4.13 | 45 |
| **M1-5 团购与消息** | `/mp` §2.8 §2.13；`/biz` §3.8；`/ops` §4.8 §4.14 | 45 |
| **M1-6 资金** | `/biz` §3.10 结算只读；`/ops` §4.12 | 25 |
| 贯穿 | §5 公共与回调 · §2.9 归因 · §4.9 归因 · §2.14/§4.17 配置 | 45 |

> M1-1 与 M1-3 **不可拆开验收**：只做结算不做核销，`/biz/pickup/verify` 缺位，订单永远到不了 `COMPLETED`，
> 评价（M1-4）也就没有数据来源。

---

## 七、待确认（影响端点形态，不只是字段）

| # | 事项 | 影响哪些端点 |
|---|------|-------------|
| A1 | **B 端是否复用 C 池 Bearer**（本文假设复用 + `bizContext` 数据域） | `/biz/**` 全部；若另起 `realm=MERCHANT`，登录链路要多一套 |
| A2 | **`/ops/**` 是新建还是复用现有 ops-web 后端**（矩阵 M2） | `/ops/**` 全部的起点 |
| A3 | **双入口价格同源**（R17/B11）：价格挂 `merchant_no + sku`，社区池降为筛选视图 | `/mp/goods`、`/mp/order/preview`、`/biz/goods` 的价格模型 |
| A4 | **自提点承接方可见字段清单**（M11/B12） | `/biz/pickup/orders`、`/biz/pickup/picking` 的响应裁剪 |
| A5 | **售后责任归属与出资方**（M4） | `/ops/after-sale/:no/liability` 与结算扣款端点是否需要三方比例入参 |
| A6 | **履约服务费口径**（R15/B9：按单/按件/保底） | `/ops/pickup-point/:no/service-fee-rate` 的配置结构 |
| A7 | **`trafficSource` 费率档位**（R16/B10） | `/ops/settle/traffic-rate`、`/biz/settle/rate-card` |
| A8 | **积分跨商家清算**（B8，一期开关关闭） | `/mp/point/**`、`/biz` 积分接收端点是否需要清算维度 |
| A9 | **平台端是否多语言**（M7，建议一期仅中文） | `/ops/**` 是否统一接受 `Accept-Language` |
| A10 | 契约包用 `{code,msg,data}` 还是 commons 的 `{code,message,data}`（architecture.md §12.5） | 三端全部端点的响应包 |

---

## 八、关联文档

- 需求矩阵：[需求矩阵-三端](../../requirements/需求矩阵-三端.md)（本文每一行的来源）
- C 端细则：[C端功能清单](../../requirements/C端功能清单.md) · 实现状态：[待完成功能清单](../../requirements/待完成功能清单.md)
- 架构与契约口径：[architecture.md](./architecture.md) · [TDD-c-app](../design/TDD-c-app.md)
- C 端端点表（现存唯一真源）：[c-app/src/api/endpoints.ts](../../../c-app/src/api/endpoints.ts)
- 决策：[ADR-001 商家端形态](../ADR/ADR-001-商家端形态与拆分时机.md) · [ADR-002 分账结算](../ADR/ADR-002-结算走微信支付分账.md) · [ADR-003 报价不审核](../ADR/ADR-003-报价不审核而用锁价公示信用防加价.md) · [ADR-004 去团长化](../ADR/ADR-004-增长模型从孵化团长转向商家自带客流.md) · [ADR-005 履约方式与自提点](../ADR/ADR-005-履约方式与自提点模型.md) · [ADR-006 积分](../ADR/ADR-006-积分方案.md)

---
确认记录：待用户确认
