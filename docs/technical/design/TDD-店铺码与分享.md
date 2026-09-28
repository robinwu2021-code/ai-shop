# TDD-店铺码与分享

状态：**边写边实施** · §3.1–3.4 已实现（2026-08-17 起）· **§3.5 / §3.6 已实现并上线（2026-09-28）** —— 见 §6；B 端那一行待发包
关联需求：[B 端功能清单](../../requirements/B端功能清单.md) B-3.3 分享素材 · B-11.12.6 店铺码 · [C 端功能清单](../../requirements/C端功能清单.md) C-ST-05 分享门店
关联：[ADR-004 增长模型](../ADR/) · [小程序上线指南](./小程序上线指南.md)
创建日期：2026-08-17

---

## 1. 起因：现在的分享物料是废的（L1）

ADR-004 定的主获客路径是「商家把店铺码印在包装袋、发进客户群，老客扫码直达」。
这条路上的两件物料，后端都生成得出来：

| 端点 | 产出 |
|---|---|
| `/biz/store/qrcode` | 店铺码 + 一个 URL |
| `/biz/store/share-kit` | 一段可复制的文案 + 同一个 URL |

**而那个 URL 是 `https://shop.example.com/s/<code>` —— 写死的占位域名。**

```java
String url = "https://shop.example.com/s/" + code;   // BizDashboardController:147
new StoreQrcode(..., "https://shop.example.com/s/" + code, ...)  // BizPickupController:122
```

商家复制出去的链接、印在包装袋上的码，**指向一个不存在的地方**。
两个功能点在清单上是「✅ 已实现」，产出的东西却没有一个能用。

> 这不是「域名还没配」——**没配就不该发一个假的**。发假的与不发，
> 在商家那边的区别是：前者他印了 500 张贴纸才发现。

---

## 2. 为什么不是「给 b-app 加转发」（L2）

一开始的方案是「两端都接 `onShareAppMessage`」。盘完否掉了，理由是两条硬事实：

| 事实 | 后果 |
|---|---|
| **b-app 的 `mp-weixin.appid` 是空的** | 它现在不是微信小程序，c-app 才是 |
| 小程序的转发**只能转发自己** | 就算 b-app 有 appid，商家转出去的也是**商家端页面** —— 顾客点开会落进一个他不该用的 App |

**商家把店分享给顾客，靠的从来不是转发，是码与链接。** b-app 现在有这两样，
只是它们指向占位域名。所以这件事的正确形状是**修物料**，不是加转发。

---

## 3. 方案（L3）

### 3.1 店铺码改成真的微信小程序码

用微信 `wxacode.getUnlimited`：扫码直达 c-app 的门店页并带上归因参数。

**为什么它比 H5 链接更适合这个场景**：

| | 小程序码 | H5 链接 |
|---|---|---|
| 依赖备案域名 | ❌ 不需要 | ✅ 需要，**7–20 个工作日** |
| 扫码落点 | 直接进小程序门店页 | 落 H5，还要引导跳小程序 |
| 归因参数 | `scene` 原样带回 | query 参数 |

c-app 的 appid/secret 已经在 `backend/.env.local` 里，通道复用现成的 `shop.wx.*` 配置。

**两个必须知道的约束**：

1. **`getUnlimited` 的码是永久有效的，且每个 appid 总量有限（十万级）** ——
   所以**一店一码、生成后落库复用**，不能每次请求都调一次微信
2. **未发布的小程序要传 `check_path: false`** —— 否则微信校验 `page` 是否存在于
   已发布版本，而 c-app 还没发布过任何版本

### 3.2 通道分层：沿用登录/订阅消息那套

```
WxAcodePort（shop-base/spi）
   ├── WxAcodeGateway      shop.wx.acode.stub=false  真调微信
   └── StubWxAcodeGateway  默认，返回占位图
```

开关跟着 `shop.wx.stub` 走，与 `login.stub` / `subscribe.stub` 同一形状 ——
**接入前置不同的通道各有各的开关**（见《订单状态-统一整理》那次拆分的理由）。

### 3.3 URL 不再造假

`shop.web.base-url` 可配；**没配就返回 null**，不发假链接。
端上据此只显示码、不显示链接 —— 少显示一样东西，比显示一个点不开的链接好。

### 3.4 c-app 补商家页分享

`merchant`（商家详情页）此前没有 `onShareAppMessage`，而门店主页有。
分享路径带 `merchantNo`，落 `ROUTES.store`。

### 3.5 `/s/<码>` 真正落到那家店（2026-09-28 补）

**§3.3 修了域名，没修落点。** 生产早就配上了 `SHOP_WEB_BASE_URL=https://www.hxmall.top`，
于是 `StoreLinkServiceImpl` 一直在发 `https://www.hxmall.top/s/<码>` ——
而 nginx 那一侧是这样接的：

```nginx
# 老店铺码链接 —— 官网接管 / 之后不能 404
location ^~ /s/ { return 302 /c/$is_args$args; }
```

`$is_args$args` 只保住了 query，**路径里的码被丢掉**。实测（2026-09-28，服务器本机打）：

```
curl https://www.hxmall.top/s/SMTBA2
→ HTTP/2 302
→ location: https://www.hxmall.top/c/          ← 码没了
```

同一个码后端解析得好好的：`GET /mp/store/by-code?storeCode=SMTBA2` 返回商家
「虹选鲜果」、门店信息与在售商品。**能力齐备、链接在发，中间那一跳把参数扔了** ——
店主分享出去的链接、印在包装上的码，点开都只到 C 端首页。

§4 测试策略最后一行写着「手工：真机扫码 → 落 c-app 门店页 → `merchantNo` 归因写入」，
断的就是这一条 —— 它从来没被真正跑过。

**改法**：nginx 把 `/s/` 反代给后端，后端出一个 `GET /s/{code}`：

| 步 | 行为 |
|---|---|
| 解析 | `mch_store.slug`（§3.6）→ `mch_store.store_code` → `mch_entity.store_code`，命中即止 |
| 命中 | 302 → `{base}/c/#/pages/store/index?storeCode=<码原样>&from=QR`，透传 `g`（商品号）与 `inviter` |
| 不命中 | 302 → `{base}/c/`（**保持今天的行为**：码可能已经印在包装上，给首页比给错误页体面），记一条 warn |

三个决定的理由：

- **为什么不用纯 nginx 正则 302**：那样码写错、门店停业、slug 改过都只能落到一个空页；
  后端这一跳能先确认码存在，也能把扫码埋点与进店归因接上（`from=QR` 决定订单的
  `trafficSource` 与商家费率档，见 ADR-004 §6）。
- **为什么落 c-app H5 而不是官网新做落地页**：官网 `site` 是静态导出，按门店出页面意味着
  每开一家新店都要重新构建官网。H5 这一侧再由 c-app 自己引导「打开小程序」。
- **为什么 302 不是 301**：门店可以改 slug、也可能停业，301 会被浏览器永久缓存住。

**两处容易静默出错的接线**：

1. **全局信封真的裹住了 302**（写这份设计时判断错了一次，实测纠正）。
   本来的推理是「`ResponseEntity<Void>` 没有响应体，`ResponseBodyAdvice` 不触发」——
   不成立：body 为 null 仍然走那一层，302 的响应体里出现了
   `{"code":0,"msg":"success","data":null}`。浏览器不看 302 的 body，
   所以这个错在浏览器里一点都看不出来，是 `assertThat(body).isEmpty()` 抓到的。
   **这是同一个坑第二次**（[[global-envelope-breaks-internal-contracts]] 那次是
   200 + 合法 JSON + 字段全 null）。

   修法：`ApiResponseWrapper` 按路径再排除一段 `/s/`，与 `/internal/` 同一个判法。
   **不用 `sendRedirect` 绕过去** —— 绕过去只解决这一个方法，下一个在 `/s/` 下
   加端点的人会再踩一遍；按路径排除才是把边界写成一条断言。
2. `/s/**` 要进 `SecurityConfig` 的 `publicChain` —— 四条链按 `/biz` `/mp` `/ops` 与
   一份公开清单分流，不显式加就是靠「碰巧没链匹配所以放行」。

### 3.6 门店代码：店主自己定，默认由店名转拼音（2026-09-28 补）

现在的码是 6 位随机串（生产实况：`SMTBA2` = 虹选鲜果·福田店，`V9VTDW` = 默认店）。
它能用，但发出去的链接读不出是谁家的店，店主也没法印在名片上。

**新增 `mch_store.slug`，不改 `store_code`。** V298 的注释把理由写死了：
「已经印出去的码不作废」。`store_code` 是物料上的那一串，改它等于让店里贴的那张纸失效
（同类教训见 [[stable-id-must-not-embed-movable]]）。两列并存、解析时都认，
新链接优先用 slug。

| 项 | 取值 |
|---|---|
| 格式 | `^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$` —— 小写、数字、连字符，3–32 位，首尾不是连字符 |
| 唯一 | `uk_mch_store_slug`（全平台唯一：它是 URL 的一段） |
| 保留词 | `s c b dl api ops ops-web admin login download static assets` —— 现在它们在 `/s/` 之下撞不着，但将来若做顶级短链就会撞，先禁比以后改容易 |
| 默认值 | **不由后端生成**：b-app 设置页按店名给一个拼音建议，店主可改 |

**拼音为什么在端上转**：`~/.m2` 里没有任何拼音库（pinyin4j / TinyPinyin 都没有），
而本仓库的 maven 一律离线跑（`-o`）—— 后端加这个依赖装不上。
端上 npm 能联网装，且「给个建议让人改」本来就是前端该干的事：
后端只校验格式与唯一性，不替店主决定名字。

**大小写不会撞**：slug 强制小写，`store_code` 是 6 位大写。但 MySQL 的默认 collation
不区分大小写，所以解析顺序必须固定（slug 先、code 后，命中即止）而不是"哪个查得到用哪个"。

---

## 4. 测试策略

| 层 | 用例 |
|---|---|
| 后端 | 未配 `base-url` 时 `url` 为 null，**不是占位域名** |
| 后端 | 同一商家两次请求拿到**同一个码**（落库复用，不重复调微信） |
| 后端 | `acode.stub=true` 时返回占位图且不发网络请求 |
| 守卫 | 全仓库不再出现 `shop.example.com` |
| 手工 | 真机扫码 → 落 c-app 门店页 → `merchantNo` 归因写入 |

---

## 5. 边界

- **不做 R3 分享激励**（点击计数、曝光位、费率归属）：需求 §3.2 那个
  「商家越分享费率越高」的冲突**待拍板**，做了也是白做
- **朋友圈分享 `onShareTimeline`**：两端一处都没有。它与本方案正交，单列
- **码的存储**：一期直接把微信返回的 PNG 转 base64 存库/回传。
  接了对象存储之后改存 URL —— 那时候这一层不用动

---

## 6. §3.5 / §3.6 的实施记录（2026-09-28）

### 6.1 设计 → 实现对账

| TDD 里说的 | 实际落在哪 |
|---|---|
| `/s/{code}` 后端解析 + 302 | `portal/link/StoreShortLinkController.java`（新） |
| 三级解析 slug → store_code → 主体 code | `StoreCodeServiceImpl.resolveTarget` 加第一段 |
| nginx 交给后端 | `deploy/tencent/nginx/www.hxmall.top.conf`：删掉 `^~ /s/` 前缀规则、把 `s` 加进反代正则 |
| `/s/**` 进公开链 | `SecurityConfig.publicChain` 的 securityMatcher |
| 信封排除 `/s/` | `ApiResponseWrapper.SHORT_LINK_PATH_PREFIX`（**设计里判断错了一次，见 §3.5 第 1 条**） |
| `mch_store.slug` + 唯一键 | `V357__store_slug.sql` · `MchStore.slug` · `schema-test.sql`（生成） |
| 格式与保留词一份真源 | `merchant/StoreSlugs.java` |
| 两个专门错误码 | `ErrorCode` 10465/10466 + 三份 messages + `响应格式规范.md` §3 分段表 |
| 设代码的 /biz 端点 | `POST /biz/store/{storeNo}/slug` · `StoreAdminService.setSlug` · `BizEndpointPermTest` 判权表 |
| 链接优先用代码 | `StoreCodeService.linkCodeOf` · `StoreVO.shareUrl`（后端拼好发下来） |
| B 端设置入口 + 拼音建议 | `b-app/src/utils/slug.ts`（pinyin-pro）· `pages/stores/index.vue` 的 facts 行 |

**偏差两处**（都写进了正文，不只记在这里）：

1. **信封会裹 302**。设计时写的是「`ResponseEntity<Void>` 没有响应体，
   `ResponseBodyAdvice` 不触发」—— 不成立。是 `assertThat(body).isEmpty()` 抓到的，
   浏览器不看 302 的 body，所以这个错在浏览器里完全看不出来。
2. **`shareUrl` 是后来加的**。原设计让端上按域名拼链接，写到一半发现那会让域名有两处真源
   （而它已经错过一次：写死 `shop.example.com`），改成后端拼好发下来。

### 6.2 实现 → 需求对账

| AC | 测试方法 | 结果 |
|---|---|---|
| 扫码/点链接落到那家店 | `StoreShortLinkFlowTest#shortLinkCarriesCodeAndAttribution` | ✅ Location 上有 storeCode 与 from=QR |
| 老链接的 `?g=` 不丢 | `#goodsParamIsPassedThrough` | ✅ |
| 码不认识不给 404 | `#unknownCodeFallsBackToHomeNot404` | ✅ 302 → `/c/` |
| 码不能变成开放重定向 | `#illegalCodeCharsNeverReachLocation` | ✅ 容器层 400 + 白名单拦 `abc$evil` |
| 302 不带信封 | `#redirectIsNotWrappedInApiEnvelope` | ✅ 响应体为空 |
| 设了代码链接仍落同一家店、老码不失效 | `#slugResolvesToTheSameStore` | ✅ |
| 清掉代码要真的清掉 | `#clearingSlugActuallyClearsIt` | ✅ 含回读库 |
| 坏写法与撞车各给自己的错误码 | `#invalidSlugIsRejectedWithItsOwnCode` · `#takenSlugIsRejected` | ✅ |
| 建议值一定通得过后端判据 | `b-app/tests/slug-suggest.test.ts` 5 条 | ✅ 含超长截断与多音字 |

**两次消融**（都红了）：

- 去掉 `&from=QR` → `shortLinkCarriesCodeAndAttribution` 红（耗时 13.84 → 14.25s，
  确认是重跑而不是读旧报告）
- 把 `setSlug` 那句 `set` 换成 `updateById` → `clearingSlugActuallyClearsIt` 红，
  **而且只红在「从库里读回来的也要是空」那一行** —— 上一条断言（返回值为 null）照样通过。
  这正是 `updateById` 跳 null 的症状，也说明只信返回值的测法会假绿。

### 6.3 上线记录（2026-09-28）

| 件 | 状态 |
|---|---|
| V357 迁移 | 21:22:53 已应用（`flyway_schema_history` success=1）。**从此冻结** |
| 后端 `/s/{code}` | 已在线上 —— 另一个会话修 V356 事故重新部署时带上了这批提交 |
| nginx | 22:02 已 reload（备份 `.www.hxmall.top.bak-20260928-220235`） |
| b-app 门店卡那一行 | **未发包**，要等下次 b-app 发版 |

线上实测（服务器本机打，本机 DNS 走内网代理会假死）：

```
/s/SMTBA2           → 302  /c/#/pages/store/index?storeCode=SMTBA2&from=QR
/s/V9VTDW?g=G0001   → 302  /c/#/pages/store/index?storeCode=V9VTDW&from=QR&g=G0001
/s/NOSUCHCODE       → 302  /c/
```

第一条正是暴露缺陷的那条探针 —— 改之前它是 `location: /c/`。

回归（正则里加了 `s`，要确认没误伤）：官网 `/` · `/c/` · `/b/` · `/ops-web/` 全 200，
`/common/master-data` 与 `/actuator/health` 200（后端反代没坏），
官网首页标题仍是「虹选 · 好物 — 社区门店的线上经营系统」。

### 6.4 还没做的

- **B 端的二维码图片与 App 微信分享**：拍板时定的是「先只做链接本身」
- **c-app 落地页**：`/s/` 落到 c-app 的门店页，那一页本来就能接 `storeCode` / `from=QR`，
  这次一行没改。微信里打开后引导「打开小程序」是另一件事
- **B 端要发包**：门店卡那一行在 App 里才看得到，b-app 发版时跟着走

---

确认记录：2026-08-17 用户「按建议执行 / 开工」·
2026-09-28 §3.5/§3.6 用户「后端短链 302 / 门店自定义代码（默认店名转拼音）/ 先只做链接本身」
