# TDD-微信客服接入

状态：已实现（2026-09-30，提交 69168611d）· **配置未填**，线上仍走原生客服会话
关联需求：`docs/technical/reference/API清单.md` **C-17.1**「启动配置（开关/版本/强更/**客服入口**）」·
`docs/requirements/B端功能清单.md`「`CS` 客服：回评价、处理售后、答咨询」

## 1 要解决的是什么

买家在小程序里点「我的 → 联系客服」，**什么都不会发生**。

入口早就在（`c-app/src/pages/me/index.vue` 里的 `open-type="contact"`），
缺的是接待的那一端：微信后台从来没配过客服人员。而平台这边唯一与企业微信相连的东西是
**群机器人**（`WeComBotSender`，生产 env 已配）—— 它是单向的，只能把「有人报名了」
推进群里，**收不到用户的话**。

所以这不是「加一个按钮」，是把接待端接上。

## 2 三条路，选哪条

| | 怎么接 | 客服在哪接待 | 代价 |
|---|---|---|---|
| A 小程序客服消息 | 后台加客服人员，接待方可设为企业微信 | 企微 App | 零代码 |
| **B 微信客服**（企微产品） | `wx.openCustomerServiceChat` | 企微，有会话存档 / 分配 / 机器人 | 前端 + 一档配置 |
| C 自建工单 | 站内会话表 + 运营端接待台 | 运营端网页 | 大 |

**选 B**（2026-09-30 定）。A 更快，但它的会话落在「小程序客服」里，
分配、存档、机器人都要另配；既然企业微信已经有、且**与小程序同主体**，
一步到位走微信客服，省掉将来从 A 迁到 B 的那次搬家。

C 不做：它唯一多出来的是覆盖 B 端 App（店主用的是 App，拿不到小程序的客服能力），
而那是另一个决定，不在这次范围里 —— 见 §7。

## 3 三条会踩空的地方（都查过官方与开放社区，不是推测）

**① `corpId` 必须与小程序绑定。** 没绑就是 `errCode 6`
（`corpId is not bound to current miniprogram`）。同主体不等于已绑定，
要在小程序管理后台把企业微信关联上。

**② iOS 上必须由用户手势<u>直接</u>触发。** 不能 `await` 之后再调 ——
Android 能过，iOS 报「并非点击触发」。
**这一条决定了配置的形状**：`corpId` 与客服链接必须在点击<b>之前</b>就在端上，
所以它们走冷启动的 `bootstrap` 一起下发，而不是点的时候现拉。

**③ 基础库 ≥ 2.20.0。** 低于它调不起来。

`extInfo.url` 的来源：企业微信后台 → 应用管理 → 微信客服 → 客服账号详情 → 接入链接。

## 4 设计

### 4.1 契约

`GET /mp/config/bootstrap` 的返回加一档（`BootstrapConfig`）：

```java
record CustomerService(String corpId, String url) {}
```

**空 = 端上回落**，与 `merchantApp` 那两档同一个口径：缺配置时不发半截。
回落顺序：`corpId`+`url` 都有 → 微信客服；否则 → 原来的 `open-type="contact"`；
H5/App → 平台邮箱（不变）。

### 4.2 配置

```yaml
shop:
  customer-service:
    corp-id: ${SHOP_KF_CORP_ID:}
    url: ${SHOP_KF_URL:}
```

**这两个值不是凭据**（客服链接本来就要发给用户看），放 env 只是为了分环境，
不是为了保密 —— 与群机器人那条 webhook 的理由不同，那条 URL 本身就是凭据。

### 4.3 端上

`c-app/src/pages/me/index.vue`，小程序分支：配齐了就渲染普通行、`@tap` 里
**同步**调 `wx.openCustomerServiceChat`（§3②）；没配齐仍用 `open-type="contact"`。

`fail` 回调要出声：这个 API 失败时静默，而最常见的两种失败
（`corpId` 没绑、基础库太低）在界面上都表现为「点了没反应」——
与修之前的症状一模一样。不打日志的话，上线后无法区分「没配」和「配错了」。

## 5 AC

| AC | 说的是 |
|---|---|
| AC1 | 配齐 `corpId`+`url` 时，`/mp/config/bootstrap` 把它们发给端上 |
| AC2 | 任一为空时，那一档发空串 —— 端上据此回落，而不是发半截 |
| AC3 | 端上 store 收得到这一档；拿不到配置时是空串，不是 undefined |
| AC4 | 配齐时「我的」页走微信客服；没配齐时仍是 `open-type="contact"` |

## 6 测试策略

- 后端：`BootstrapConfigFlowTest` 覆盖 AC1/AC2（配齐 / 缺一个 / 全空）
- 端上：`c-app/tests/config-store.test.ts` 覆盖 AC3；页面分支（AC4）由
  `me-contact.test.ts` 断渲染的是哪一支
- **消融**：把 `BootstrapConfigServiceImpl` 里新加的那一档去掉 → AC1 变红

## 7 不做什么

- **不覆盖 B 端**：店主用 App，小程序的客服能力在 App 上不存在。
  这是决定不是待办（见 [[bapp-h5-is-debug-only]] 同源的取舍）。
- **不接客服消息回调**：`/mp/wx` 的 POST 仍不处理。走微信客服之后会话在企微里，
  平台不留存 —— 要留存是 C 那条路的事。
- **不动官网的 `salesWechatQr`**：那是招商口径，不是客服，两件事。
