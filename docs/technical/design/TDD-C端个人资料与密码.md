# TDD-C端个人资料与密码

状态：草稿
关联需求：docs/requirements/C端功能清单.md §C-AC-08
创建：2026-10-05 · 最后更新：2026-10-05
档位：1（动了端点 · `pages.json` · i18n 词条 · `SysMediaAsset` 类型常量）

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 建户不再给「邻居+后四位」这种带编号的名字 | `AuthServiceImpl#createAccount` 默认值改 `微信用户` |
| AC2 | 昵称还没设过时，界面上看得出来、并且能点进去设 | `me/index.vue` 头部卡 + `UserVO#nicknameSet` |
| AC3 | 能改昵称，服务端挡住空白与超长 | `UserServiceImpl#updateProfile` 补判据 |
| AC4 | 能改头像（选图 → 上传 → 落到账号上） | 新端点 `POST /mp/user/avatar` + `SysMediaAsset.AVATAR` |
| AC5 | 能设置 / 修改登录密码 | 新端点 `POST /mp/user/password` |
| AC6 | 没绑手机号的人不能设密码（设了也永远登不进来） | `UserService#assertPasswordSettable` |
| AC7 | 端上知道该显示「设置密码」还是「修改密码」 | 新端点 `GET /mp/user/password` |
| AC8 | 密码设完真的能用来登录 | `login/index.vue` 改 `loginMethods({ withPassword: true })` |

**孤立项**：
- 没落点的 AC：无
- 挂不上 AC 的设计：无

AC8 看起来超出「修改密码」的字面范围，但它挂在 AC5 上：不开登录入口，
AC5 做完就是又一个没人读的配置屏（`c-app/src/pages/login/index.vue:20`
调的是不带参的 `loginMethods()`，密码方式根本不在返回列表里）。

---

## §1 现状与影响面

### 线上实况（2026-10-05 查 `ai_shop`，不是推断）

| 量 | 值 | 对方案的影响 |
|---|---|---|
| `usr_account` 总数 | 23 | |
| 昵称以「邻居」开头 | **23**（100%） | 没有一个人设过昵称 → AC2 的入口对所有人显示是对的 |
| `avatar` 非空 | **0** | **没有历史包袱**：`<text>` 换 `<image>` 不必兼容任何存量值 |
| 只有 `WX_OPENID_MP`、无 PHONE | 21 / 23 | 「改密码」对 91% 的买家不可用 → 它在界面上是次要项，且要说明原因 |
| `PASSWORD` 凭证 | 3 条 | 见下 |

### `PASSWORD` 凭证与 B 端共用一行 —— 这条最容易漏

`usr_identity.PASSWORD` 按 `user_no` 存一行，而 `mch_account` 有 `user_no` 列
（商家账号挂在 `usr_account` 上）。线上 3 条 PASSWORD 里 2 条属于商家账号，
是 `/biz/auth/password` 写的。**同一个自然人在 B 端和 C 端是同一个密码**。

这不是缺陷（同一个人同一个密码是对的），但必须显式说出来：
店主在 C 端改了密码，他的 B 端 App 登录密码跟着变。界面上要有这句话。

### 线上已有一条「设了密码但永远登不进来」的死数据

第 3 条 PASSWORD 属于 `U202609161449430000150` —— 它**既没有 PHONE 也没有
WX_OPENID_MP**。而 `AuthServiceImpl#loginByPassword` 的第一步是按 PHONE 凭证
找人，所以这个密码永远用不上。

AC6 那道闸因此不是我凭想象加的 —— 线上已经有一条这样的数据了。

### 相关现有模块

| 路径 | 职责 | 这次怎么处理 |
|---|---|---|
| `shop-core/.../user/api/mp/MpUserController.java` | C 端用户端点 | 加 3 个方法 |
| `shop-core/.../user/service/impl/UserServiceImpl.java:190` | `updateProfile` 已实现（nickname+avatar，null=不改） | 补长度判据，逻辑不动 |
| `shop-core/.../user/service/impl/AuthServiceImpl.java:333` | `setPassword` / `hasPassword` 已实现 | **不改**，只加 C 端入口 |
| `shop-channel/.../media/api/BizUploadController.java:81` | `/biz/upload/image`，三步非事务写法 | **不复用这个端点**（ADR-007 前缀纪律），复用它背后的 `MediaStore` |
| `b-app/src/api/http.ts:411` | `uploadFile` 实现 | 照它在 c-app 补一份 |
| `packages/shared/src/ports/auth.ts:167` | `loginMethods({withPassword})` | 调用点传参，策略本身不动 |

### 可直接复用（已在 reference/ 搜过同名概念）

- `MediaStore` + `sys_media_asset` 的 PENDING→ACTIVE 对账写法 —— 原样照搬，**不加 `@Transactional`**（理由见 `BizUploadController` 那段注释：包进事务会留下查不出来的孤儿文件）
- `AuthService#setPassword` 的「不收旧密码」口径 —— C 端沿用，理由同 B 端
- `me/index.vue` 现有的「绑定手机号 ›」写法 —— AC2 的「设置昵称 ›」照它来

### 会被改到的已在跑功能

- **B 端订单列表 / 参团邻居墙 / 履约查询**的买家展示名：`MerchantOrderServiceImpl:1098`、`GroupServiceImpl:683/704`、`FulfillmentQueryPortImpl:194` 都读 `nickname` 并各自 `orElse` 兜底。AC1 改默认值为 `微信用户` 而**不是空串**，正是为了保住这三处不变空。
- C 端登录页：AC8 多出一个「密码登录」tab。
- `/mp` 端点总数 +3 → 契约四处与生成产物都要重跑。

### 明确不受影响

- B 端 `/biz/auth/password` 两条端点：一行不改
- `usr_account` / `usr_identity` 表结构：**不加列**（`avatar VARCHAR(512)` 够用）
- 运营端：不新增页面与权限码
- 头像审核：不接（`sys_media_asset` 有状态机，接审核是独立一档）

---

## §2 方案

### 契约变更

**端点（3 条新增）**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/mp/user/avatar` | multipart `file`，上限 2MB，`bizType=AVATAR`；返回 `{url}` |
| POST | `/mp/user/password` | body `{password}`；已登录即授权，**不收旧密码**；无 PHONE 凭证时拒 |
| GET | `/mp/user/password` | `{hasPassword}` |

`POST /mp/user/profile`（已存在）**后端不动**，只补端上登记 ——
它目前在 `c-app/src/api/endpoints.ts` 里一条都没有，端上无从调用。

**库表 / 迁移**：无。不加列、不加表、不写迁移。

**权限码**：无（`/mp` 走会话鉴权，不走 `@perm`）。三条都**不进匿名白名单**。

**i18n 词条**（三语 `zh-CN` / `en` / `ar`）：
```
me.profile            个人资料
me.setNickname        设置昵称 ›
profile.avatar        头像
profile.nickname      昵称
profile.nicknameHint  1–20 个字
profile.password      登录密码
profile.passwordSet   设置密码
profile.passwordEdit  修改密码
profile.passwordNeedPhone   先绑手机号才能设密码
profile.passwordSharedHint  这个密码在商家端登录时也用它
profile.saved         已保存
```

**配置项**：无。头像大小上限写成常量（`AVATAR_MAX_BYTES = 2MB`），不做配置 ——
没有调它的场景，配置项多一个就多一处没人读的开关。

**枚举 / 常量**：`SysMediaAsset` 加 `AVATAR` 常量，并进 `BizUploadController` 之外
的新端点白名单。**注意**：`SysMediaAsset` 进 `packages/shared` 的枚举对账，
加值要同步 `enum-registry.ts`。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `backend/shop-core/.../user/service/impl/AuthServiceImpl.java` | `createAccount` 默认昵称 → `微信用户`（AC1） |
| 修改 | `backend/shop-core/.../user/service/impl/UserServiceImpl.java` | `updateProfile` 补昵称判据；新增 `assertPasswordSettable`（AC3/AC6） |
| 修改 | `backend/shop-core/.../user/service/UserService.java` | 加 `assertPasswordSettable` 声明 |
| 修改 | `backend/shop-core/.../user/dto/UserVO.java` | 加 `nicknameSet`（AC2） |
| 修改 | `backend/shop-core/.../user/api/mp/MpUserController.java` | 3 个端点（AC4/AC5/AC7） |
| 修改 | `backend/shop-channel/.../media/entity/SysMediaAsset.java` | 加 `AVATAR` |
| 新增 | `backend/shop-core/.../user/api/mp/MpAvatarUpload...` 或并入 MpUserController | 见下「上传放哪儿」 |
| 修改 | `backend/shop-core/.../common/ErrorCode.java` | 新码 `PHONE_REQUIRED_FOR_PASSWORD`（四处登记） |
| 新增 | `c-app/src/pages/me/profile/index.vue` | 资料页 |
| 修改 | `c-app/src/pages/me/index.vue` | 头部卡可点 + `<text>`→`<image>` + 「设置昵称 ›」 |
| 修改 | `c-app/src/pages/login/index.vue` | `loginMethods({ withPassword: true })`（AC8） |
| 修改 | `c-app/src/api/http.ts` | 补 `uploadFile` |
| 修改 | `c-app/src/api/endpoints.ts` · `contract.ts` · `mocks/user.ts` | 契约四处 |
| 修改 | `c-app/src/pages.json` | 新页登记 |
| 修改 | `c-app/src/i18n/locale/{zh-CN,en,ar}.ts` | 词条 |
| 重跑 | `scripts/gen-ui-catalog.py` 等生成器 | 产物 |

**上传放哪儿**：`MediaStore` 在 `shop-channel`，而 `MpUserController` 在 `shop-core`。
`shop-core` 不能依赖 `shop-channel`（域间依赖，`ArchitectureTest` 会拦）。
→ 上传端点落在 **`shop-app/portal/mp/`**（跨域组合住 app 层，与 `reportbridge` 同一口径），
路径仍是 `/mp/user/avatar`，由它调 `MediaStore` 存盘、再调 `UserService#updateProfile` 落库。

### 关键接口

```java
// UserService
/** 设密码的前置：必须已绑手机号。否则密码永远登不进来（见 §1 那条死数据） */
void assertPasswordSettable();

// UserVO 加一个字段
/** 昵称是不是用户自己设的。false = 还是平台给的占位名，端上据此显示「设置昵称 ›」 */
boolean nicknameSet
```

`nicknameSet` 怎么判：**不比字符串**。比字符串的话用户真把自己叫「微信用户」
就被当成没设过，而且默认值将来一改，这个判据静默失效。
→ 判据是 `usr_account.nickname_set` ... 但这要加列，而 §2 说了不加列。
**定稿取法**：`UserVO.of` 里按 `nickname != null && !nickname.equals(DEFAULT_NICKNAME)` 算，
并把 `DEFAULT_NICKNAME` 提成 `UsrAccount` 的常量，让默认值与判据**同一个来源**。
代价写进 §7 偏差说明。

---

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融 |
|---|---|---|---|
| AC1 | `AuthServiceTest#新账号默认昵称不带编号` | | 改回「邻居」拼接 → 红 |
| AC2 | `UserVOTest#默认昵称算未设置` + `#用户自己设的算已设置` | | `nicknameSet` 恒 true → 红 |
| AC3 | `UserServiceTest#昵称空白被拒` `#昵称超20字被拒` | | 去掉判据 → 红 |
| AC4 | `MpAvatarUploadTest#上传后落到账号上` `#超2MB被拒` `#非图片被拒` | | 去掉 bizType 白名单 → 红 |
| AC5 | `MpUserPasswordTest#设密码后可用它登录` | | 端点不写库 → 红 |
| AC6 | `MpUserPasswordTest#没绑手机号时拒绝设密码` | | 去掉 `assertPasswordSettable` → 红 |
| AC7 | `MpUserPasswordTest#hasPassword随设置变化` | | 恒返回 false → 红 |
| AC8 | `packages/shared` vitest `auth#登录页带密码方式` | | 调用点去掉 `withPassword` → 红 |

**AC6 的消融要特别验。** 它是「拒绝型」判据，最容易写成恒绿 ——
先让测试在**有手机号**的账号上跑一遍确认它会通过，再在无手机号的账号上确认它被拒。
只测后者的话，一个恒抛异常的实现也能让它变绿。

**AC5 的测试要走真实链路**，不能只断言「库里有了一行」：
按 `mockmvc-skips-registered-filters` 的教训，密码登录链路上有注册过滤器，
MockMvc 装不上。这条用 `RANDOM_PORT` 起真实上下文：设密码 → 再用它调
`/mp/user/login`（`grantType=PASSWORD`）→ 拿到 token 才算过。

---

## §6 对账二 · 设计 → 实现

（实现完贴 `git diff --stat` 的文件清单，与 §2 模块设计逐行比）

---

## §7 偏差说明

（实现中出现的与本设计不一致之处写在这里，先改文档再改代码）

1. `nicknameSet` 用「与默认值比较」而不是加一列 `nickname_set`。
   代价：用户真把自己命名为「微信用户」时会被判成未设置。
   接受的理由：这个名字本身就是占位名，重名概率极低；而加一列要写迁移、
   补实体、补 `schema-test.sql` 三处，为一个展示态的边角情况不值。
   缓解：默认值与判据共用 `UsrAccount.DEFAULT_NICKNAME` 一个常量，
   避免「改了默认值、判据静默失效」。
