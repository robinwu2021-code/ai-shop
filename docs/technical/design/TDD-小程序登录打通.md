# TDD-小程序登录打通

状态：**已实现（2026-08-14）· 真机验证待做**
关联需求：[C端功能清单](../../requirements/C端功能清单.md) §一期验收「用户 登录 → …」
关联文档：[小程序上线指南](./小程序上线指南.md) §四 · [安全整改方案](./安全整改方案-认证与账号体系.md) §6.5 · [TDD-c-app](./TDD-c-app.md)
创建日期：2026-08-14

---

## 1. 这是什么（L1）

**小程序上点「登录」现在必然失败。** 后端的 `code2Session` 已经接通，端上的
`uni.login` 也早就写好了，两截各自能跑——但中间那个 `grantType` 的值对不上，
请求进后端直接落到 `default -> BAD_REQUEST`。

本方案只做一件事：把这条链路接上，让小程序能真正登录。**不含微信支付**
（那要商户号，等微信认证）。

---

## 2. 现在断在哪（L2）

```
c-app 登录页  →  ports/auth  →  POST /mp/auth/login  →  AuthServiceImpl
   写死手机号表单      发 WX_PHONE          grantType         只认 WECHAT_MP
        ①                  ②                                   ③ → 400
```

| # | 断点 | 位置 | 症状 |
|---|---|---|---|
| ① | 登录页没调 `loginMethods()`，硬渲染手机号+验证码表单 | [login/index.vue:80](../../../c-app/src/pages/login/index.vue) | 小程序上这两个输入框是**死的**——`wxPhone.acquire()` 忽略入参 |
| ② | 小程序首选是 `wxPhone`，语义是「一键取手机号」，但只发了 login code | [auth.ts:60](../../../packages/shared/src/ports/auth.ts) | `getPhoneNumber` 要 `encryptedData` 服务端解密，端上没实现；且该接口很可能要求微信认证，而本期不认证 |
| ③ | 后端只认 `WECHAT_MP` / `PHONE_OTP` / `APPLE` | [AuthServiceImpl.java:156](../../../backend/shop-core/src/main/java/ai/neargo/shop/user/service/impl/AuthServiceImpl.java) | `WX_PHONE` 落进 `default` 分支，抛 `BAD_REQUEST` |

③ 不是打字错误，是[枚举对账守卫](../../../packages/shared/tests/enum-alignment.test.ts)
里那条挂了账的已知差异：「接的时候两边一起定名」。后端接完了，名还没定——现在定。

---

## 3. 方案（L3）

### 3.1 选型：小程序首选走 `WX_MINI` 静默登录

| 方案 | 拿到什么 | 依赖 | 结论 |
|---|---|---|---|
| **A. `WX_MINI` 静默登录**（推荐） | openid（+ unionid） | 无 | ✅ 采用。后端 `GRANT_WECHAT_MP` 分支已完整支持，**不需要微信认证**，今天就能跑 |
| B. `WX_PHONE` 一键取手机号 | openid + 手机号 | `getPhoneNumber` 授权、服务端解密、**很可能要求微信认证** | ❌ 本期不认证；且服务端解密未实现 |
| C. 保持手机号 OTP | 手机号 | 短信通道 | ❌ 小程序上把微信登录藏起来是反直觉的；且拿不到 openid，支付与订阅消息都无从谈起 |

选 A 还有一条链路上的理由：**微信支付 JSAPI 下单必须带 openid**。先把 openid 拿到，
等商户号下来，支付那段能直接接上，不用回头改登录。

手机号 OTP 不删，降为兜底（`loginMethods()` 里排第二）——用户拒绝微信授权时还有路走。

### 3.2 枚举定名：后端加认 `WX_MINI`，不动 `WECHAT_MP`

| 方案 | 取舍 | 结论 |
|---|---|---|
| **后端把 `WX_MINI` 映射进 `WECHAT_MP` 分支**（推荐） | 端上保留三种微信场景的区分；后端现有测试一行不改（P6） | ✅ |
| 端上统一发 `WECHAT_MP`，删掉三分 | 契约最简，但把「哪种微信场景」的信息扔了 | ❌ `WX_OPEN`（App 微信开放平台）换 openid 走的是 `sns/oauth2/access_token`，**不是同一个端点**，后端迟早要区分 |

### 3.3 改动清单

| 层 | 文件 | 改什么 |
|---|---|---|
| 端口 | `packages/shared/src/ports/auth.ts` | 新增 `wxMini` 方法（`grantType: "WX_MINI"`，复用现有 `wxLoginCode()`）；`loginMethods()` 小程序分支改为 `[wxMini, phoneOtp]` |
| 页面 | `c-app/src/pages/login/index.vue` | 改为按 `loginMethods()` 渲染；`needsPhone === false` 的方式不显示手机号/验证码框 |
| 后端 | `shop-core/.../AuthService.java` | 新增常量 `GRANT_WX_MINI = "WX_MINI"` |
| 后端 | `shop-core/.../AuthServiceImpl.java` | `case GRANT_WECHAT_MP, GRANT_WX_MINI ->`（同一分支，两个标签） |
| 守卫 | `packages/shared/tests/enum-alignment.test.ts` | `KNOWN_SHARED.GrantType` 豁免收窄为只剩 `WX_PHONE`/`WX_OPEN`，并写清各自还缺什么 |
| i18n | `c-app/src/i18n/locale/*.ts` | 新增 `login.byWxMini` 词条（三语） |

**零硬编码**：`grantType` 的字面量只出现在 `ports/auth.ts` 的方法定义与后端常量里，
页面与业务层一律用 `LoginMethod.id`。

### 3.4 端上契约

```ts
const wxMini: LoginMethod = {
  id: "WX_MINI",
  labelKey: "login.byWxMini",
  primary: true,
  needsPhone: false,
  async acquire() {
    return { grantType: "WX_MINI", principal: await wxLoginCode() };
  },
};
```

后端侧不新增字段：`principal` 依然是 code，`resolveCredentials` 依然产出
`WX_UNIONID` + `WX_OPENID_MP` 两条凭证。

---

## 4. 测试策略

| 层 | 用例 | 位置 |
|---|---|---|
| 后端 | `grantType=WX_MINI` 与 `WECHAT_MP` 走同一分支，产出相同凭证 | `M1UserFlowTest` 加一例 |
| 后端 | 未知 grantType 仍返回 400（防止 `default` 分支被改宽） | 同上 |
| 后端 | 现有 `WECHAT_MP` 全部用例保持绿（P6 回归） | `IdentityUnificationFlowTest` / `TestLogin` |
| 守卫 | 枚举差异数只降不升 | `enum-alignment.test.ts` |
| 真机 | 开发者工具 → 真机预览 → 点微信登录 → 后端日志出现 `[wxauth] code2Session 已启用` 且库里落一条真 openid | 手工 |

**验收的硬标准**：库里那条 `usr_identity` 的 `identity_value` 必须是真 openid
（`o` 开头的 28 位串），不是 code。桩实现返回 `openId = code`，两者形状不同，一眼可辨。

---

## 5. 风险与边界（L4）

- **真机联调要绕域名校验**：服务器域名白名单要求 HTTPS + 已备案。测试期在开发者工具勾
  「不校验合法域名」、手机上开调试模式即可，**不要为此去改 `manifest.json` 的 `urlCheck`**
  （那只影响开发者工具，改了也不解决真机）
- **`WX_PHONE` / `WX_OPEN` 仍是挂账差异**：本方案不接，豁免条目要如实收窄而不是删掉
- **不含支付**：`OrderServiceImpl` 的 `"stub_" + orderNo` 不动。微信认证 + 商户号下来后另开一份方案
- **未定**：拿到 openid 后是否强制补绑手机号。Apple 登录那条分支已经留了同样的问题
  （`AuthServiceImpl` 的 TODO），两者应该一起定，本方案不擅自决定

---

## 6. 实现任务

- [x] `ports/auth.ts` 新增 `wxMini`，调整 `loginMethods()` 小程序分支
- [x] 顺手删掉两处**制造了这个 bug 的东西**：`wxPhone`（从未工作过）与
      `acquireCredential`（「取首选」的壳，正是它让页面显示与实际发出的不一致）
- [x] `login/index.vue` 改为按 `loginMethods()` 渲染
- [x] 三语 `login.byWxMini` / `login.orPhone` 词条（**b-app 也吃这个端口，一并补**）
- [x] 后端 `GRANT_WX_MINI` 常量 + `case GRANT_WECHAT_MP, GRANT_WX_MINI ->`
- [x] 后端用例 2 条（同分支 / default 不许改宽）+ M1UserFlowTest 16 通过
- [x] 收窄 `enum-alignment.test.ts` 的 `GrantType` 豁免
- [x] `mp-weixin` 与 `h5` 两个产物均编译通过；`vue-tsc` c-app / b-app 均无错
- [ ] **真机验证：库里落真 openid** —— 需要人在微信开发者工具里点一次，见 §7

---

## 7. 怎么验（要人做的那一步）

自动化到不了微信开发者工具，这一步必须手动。

```bash
# 1. 起后端（密钥在 backend/.env.local，已 gitignore）
cd backend && set -a && source .env.local && set +a && mvn -o -f shop-app/pom.xml spring-boot:run
# 2. 编译小程序（带热更新）
cd c-app && npm run dev:mp-weixin
```

开发者工具打开 `c-app/dist/dev/mp-weixin`（AppID 已写进 `manifest.json`，会自动读到），
「详情 → 本地设置」勾上**不校验合法域名**，然后点登录页那个「微信一键登录」。

**通过的标准**：`usr_identity` 新增行的 `identity_value` 是 `o` 开头的 28 位真 openid。
桩实现返回的是 `openId = code`（`0` 开头 32 位），形状不同，一眼可辨。

```sql
SELECT identity_type, identity_value, created_at FROM usr_identity ORDER BY id DESC LIMIT 5;
```

后端日志里应有 `[wxauth] code2Session 已启用 appid=wxdb0513c549437ffe`；
没有这行说明 `SHOP_WX_STUB` 没生效，仍在走桩。

> 真机预览（手机扫码）还要把 `c-app/.env` 的 `VITE_API_BASE` 从 `127.0.0.1` 换成
> 电脑的局域网 IP —— 手机上的 `127.0.0.1` 是手机自己。

---

## 8. 补记：微信开关从一个拆成两个（2026-08-14）

真机联调第一次起后端就崩了，暴露出一个设计缺口。

**症状**：`SHOP_WX_STUB=false` 后端起不来 ——

```
IllegalStateException: 订阅消息通道已开启但缺少配置：WX_TPL_ORDER_ARRIVED
```

**根因不是配置错，是开关粒度不够**。`shop.wx.stub` 一个开关管两条通道，
而**两条通道的接入前置不一样**：

| 通道 | 前置 | 拿到时间 |
|---|---|---|
| 登录 `code2Session` | appid + secret | 后台点两下就有 |
| 订阅消息 | mp 后台**报备过的模板号** | 要走报备流程 |

合成一个开关时，「先把登录接通」这个完全合理的中间状态**不可达**——
被订阅消息的 fail-fast 拦在启动阶段。

**改法**：`shop.wx.stub` 降级为两条通道的共同默认值，各自加一个开关。

| 属性 | 管什么 | 环境变量 |
|---|---|---|
| `shop.wx.stub` | 总开关（默认值） | `SHOP_WX_STUB` |
| `shop.wx.login.stub` | `WxAuthGateway` / `StubWxAuthGateway` | `SHOP_WX_LOGIN_STUB` |
| `shop.wx.subscribe.stub` | `WxSubscribeGateway` / `StubWxSubscribeGateway` | `SHOP_WX_SUBSCRIBE_STUB` |

**反方向仍然禁止，而且不靠人记**。原来共享开关的理由是防「库里假 openid + 通道真发」，
这个理由在一个方向上仍然成立。所以 `WxSubscribeGateway` 的构造器多收一个
`shop.wx.login.stub`，为 true 时直接拒绝启动：假 openid 发出去每条都是 40003，
而失败发生在异步发送里，**日志上看是「发过了」**。

守卫用例见 `shop-app/src/test/java/ai/neargo/shop/arch/WxChannelSwitchTest.java`（3 条，全绿）。

> 一个坑记在这里：`mvn spring-boot:run` 的 `shop-core` / `shop-channel` 来自
> `~/.m2` 的已安装 jar，**改了这两个模块不 `mvn install` 就等于没改**——
> 表现是后端照常起来、开关像是没生效。见 [[regenerate-generated-artifacts]] 同类。

---

确认记录：2026-08-14 用户确认「自动进行以上所有步骤」；同日确认「不要用一个开关」→ §8
