# TDD-C端个人资料与密码

状态：已实现
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
| AC1 | `ProfileAndPasswordTest#defaultNicknameHasNoSerialAndIsNotBlank`<br>`#createAccountWritesTheDefaultNickname` | ✅ 9/9 | 消融 1：昵称改回「邻居」拼接 → 只红 `createAccountWrites…` ✅ |
| AC2 | `ProfileAndPasswordTest#nicknameSetDistinguishesPlaceholderFromRealName` | ✅ | 消融 2：`nicknameSet` 恒 true → 红 ✅ |
| AC3 | `#blankNicknameIsRejectedNotIgnored`<br>`#overlongNicknameIsRejectedAtTheBoundary`<br>`#nullNicknameStillMeansNoChange` | ✅ | 消融 3：去掉长度与空白判据 → 红 2 条 ✅ |
| AC4 | `ProfileEndpointsFlowTest#avatarUploadLandsOnTheAccount`<br>`#nonImageBytesRejected`<br>`#missingFileRejected` | ✅ 7/7 | 消融 6：存字节但不写库 → 红 ✅<br>消融 7：去掉 magic number 那一道 → 红 ✅ |
| AC5 | `ProfileEndpointsFlowTest#passwordCanActuallySignYouIn`<br>`#oldPasswordStopsWorkingAfterChange` | ✅ | 消融 8：改密码变成空操作 → 红 ✅ |
| AC6 | `ProfileAndPasswordTest#passwordNotSettableWithoutPhone`<br>`#passwordSettableOncePhoneBound`<br>`#phoneJudgementReadsIdentityNotLegacyColumn`<br>`ProfileEndpointsFlowTest#wechatOnlyUserCannotSetPassword` | ✅ | 消融 4a：整道闸去掉 → 红 2 条（放行那条仍绿）✅<br>消融 4b：恒拒 → 只红放行那条 ✅ |
| AC7 | `ProfileEndpointsFlowTest#passwordStateTracksReality` | ✅ | 含在消融 8 的那一轮里；`canSet` 由 4a/4b 两向钉住 ✅ |
| AC8 | `c-app/tests/login-password-method.test.ts`（4 条） | ✅ 4/4 | 消融 5a：撤掉 `withPassword` → 红 ✅<br>消融 5b：`filter` 改回 `find` → 红 ✅ |

另加一道**鉴权实弹**：`MpEndpointAuthTest`（7 条，+1）把三条新端点放进
`REQUIRES_LOGIN` 并真的不带令牌打一遍。为做到这一点，两条 POST 的参数校验
挪到了方法体里、放在取当前用户之后 —— `@Valid` 跑在方法体之前，
匿名探测只能拿到 10400，那样就只能塞进 `UNDETERMINED`，而那个桶上有棘轮。

**AC6 的消融要特别验。** 它是「拒绝型」判据，最容易写成恒绿 ——
先让测试在**有手机号**的账号上跑一遍确认它会通过，再在无手机号的账号上确认它被拒。
只测后者的话，一个恒抛异常的实现也能让它变绿。

**AC5 的测试要走真实链路**，不能只断言「库里有了一行」：
按 `mockmvc-skips-registered-filters` 的教训，密码登录链路上有注册过滤器，
MockMvc 装不上。这条用 `RANDOM_PORT` 起真实上下文：设密码 → 再用它调
`/mp/user/login`（`grantType=PASSWORD`）→ 拿到 token 才算过。

---

## §6 对账二 · 设计 → 实现

`git diff --stat ce9f3f4d7^..HEAD`（只列代码，文档产物另见下）：

| 文件 | 行 | 与 §2 模块设计 |
|---|---|---|
| `shop-core/.../user/entity/UsrAccount.java` | +15 | ✅ 计划内（`DEFAULT_NICKNAME`） |
| `shop-core/.../user/service/impl/AuthServiceImpl.java` | +1/−1 | ✅ |
| `shop-core/.../user/dto/UserVO.java` | +23 | ✅ |
| `shop-core/.../user/service/UserService.java` | +17 | ✅ |
| `shop-core/.../user/service/impl/UserServiceImpl.java` | +37 | ✅ |
| `shop-core/.../user/api/mp/MpUserController.java` | +66 | ✅ |
| `shop-base/.../common/ErrorCode.java` | +13 | ✅ |
| `shop-store-mybatis/.../media/SysMediaAsset.java` | +23 | ✅（`AVATAR`，**外加 `USER_SCOPE`**，见偏差 2） |
| `shop-app/.../portal/mp/MpAvatarController.java` | 新增 | ✅ |
| `shop-store-mybatis/.../media/ImageProbe.java` | 新增 130 | **偏差 3** |
| `shop-store-mybatis/.../media/MediaUploadService.java` | 新增 82 | **偏差 4** |
| `shop-channel/.../BizUploadController.java` | +141/−140 | **偏差 3/4 的连带**（纯改走共用实现） |
| `c-app/src/pages/me/profile/index.vue` | 新增 263 | ✅ |
| `c-app/src/pages/me/index.vue` | +50 | ✅ |
| `c-app/src/pages/login/index.vue` | +83 | ✅（AC8） |
| `c-app/src/api/{endpoints,contract,http,requests}.ts` | +110 | ✅（**requests.ts 是偏差 5**） |
| `c-app/src/api/mocks/user.ts` | +51 | ✅ |
| `c-app/src/i18n/locale/*.ts` | +78 | ✅ |
| `c-app/src/pages.json` | +12 | ✅ |
| `packages/shared/src/types/user.ts` | +42 | ✅（**`userNo` 是偏差 6**） |
| `packages/shared/src/ports/media.ts` | +9 | ✅（`MAX_AVATAR_BYTES`） |
| `packages/shared/src/utils/constants/index.ts` | +2 | ✅（`ROUTES.profile`） |
| `packages/shared/src/mock/db.ts` | +14 | ✅ |
| `c-app/scripts/gen-openapi.mjs` | +8 | ✅（`RESPONSE_TYPES` 登记） |
| 测试 3 份 | +351 | ✅ |

**计划了却没动的**：无。
**计划外的文件**：`ImageProbe` / `MediaUploadService` / `BizUploadController` /
`requests.ts` / `User.userNo` —— 逐条在 §7。

生成产物 18 份（四阶漂移，逐轮跑到 `check-generated-docs` 全绿）：
openapi ×3 · API 清单/详情 · 术语三份 + glossary.json · UI 规范三份 + ui-lib.json ·
后端分层 · 三端权限矩阵 · C 端功能点 · 后端验收清单 · README · 三端对齐 · ui-catalog。

---

## §7 偏差说明

1. **`nicknameSet` 用「与默认值比较」而不是加一列 `nickname_set`。**
   代价：用户真把自己命名为「微信用户」时会被判成未设置。
   接受的理由：这个名字本身就是占位名，重名概率极低；而加一列要写迁移、
   补实体、补 `schema-test.sql` 三处，为一个展示态的边角情况不值。
   缓解：默认值与判据共用 `UsrAccount.DEFAULT_NICKNAME` 一个常量。

2. **多加了 `SysMediaAsset.USER_SCOPE` 哨兵。** §2 只说要加 `AVATAR`。
   做的时候才发现 `entity_no` / `store_no` 都是 `NOT NULL`，而头像不属于
   任何经营主体与门店。把 `userNo` 塞进其中一个的话，运营端存储页会把一个买家
   显示成一家门店，而「各店之和 = 真实字节」这个本来对得上的账会多出一堆假门店。
   于是两列都用 `_USER`，具体是谁记在 `uploaded_by`。
   **已知残留**：`ops-web/app/system/storage-tab.tsx` 只给 `_ENTITY` 配了标签，
   `_USER` 会原样显示。不在本档范围内（它要动运营端的词条与类型），
   但有了头像之后那一页会出现这一档，**记在这里**。

3. **提了 `ImageProbe`（计划外）。** §2 原打算在新 controller 里复用
   `BizUploadController` 的判据，而那两个方法是 `private static`。
   抄第二份的后果是 magic number 那张表会漂：一端补了格式另一端没补，
   症状是「同一张图换个入口就说格式不对」，而没人会想到去比两份常量表。
   纯搬移，一个字节没改（它们是纯函数，不读配置、不碰上下文）。

4. **提了 `MediaUploadService`（计划外）。** 起因是 `ArchitectureTest` 的
   「`portal..` 下的 controller 不许碰 `*Mapper`」—— 头像端点住在 `portal/mp`，
   本来就不能自己记账。顺带把「记账 → 落盘 → 改 ACTIVE」与**刻意不用事务**
   收敛成一份：抄第二份时最容易丢的恰恰是后者，而丢了它的症状是查不出来的孤儿文件。
   B 端那条上传改走同一个 service（`MediaUploadFlowTest` 等 16 条绿）。

5. **两个请求体提成具名类型。** §2 写的是内联对象 `{nickname?, avatar?}`。
   `gen-openapi.mjs` 要求具名类型才生成 `requestBody`，否则入参被静默丢掉。

6. **`User.userNo` 一并补进契约（计划外，但是个真缺口）。**
   `UserVO` 从 C1 双写那天起就在发它，而共享 `User` 只声明了 `cUserNo` ——
   这个字段到端上就被丢掉了，不报错、界面也看不出来。
   AC4 新增的 `uploadAvatar` 让 `biz-contract-fields` 棘轮第一次看见 `UserVO`，
   才暴露出来。**补字段而不是抬基线**：`UserVO` 自己的注释说的迁移方向就是统一到
   `userNo`。（先查清是哪个方法引入的，没有不明不白地动棘轮。）

7. **昵称改用仓库既有的 `prompt()`，没有用微信的 `<input type="nickname">`。**
   §2 写的是后者。改用前者的理由：它是全端一套路径，而 `type="nickname"`
   只在小程序上有，混用等于一个字段两条代码路径。
   代价：小程序用户不能一键填入自己的微信昵称，要手打。
   这是**可加的增量**，不是返工 —— 加它只是在小程序分支上多一个控件。

8. **「省空间」那条规则没有适用。** 记忆里的约定是只在进销存页省空间，
   其余页面不主动改版面。本页按普通 `sh-cells` 列表排。

---

## §8 没做的事（明确不在本档范围）

- **违规昵称 / 头像的运营端处置面**：三端对齐里已判为「兜 · P-13.1 · 🕐」。
  查过 `/ops/` 下没有任何相关端点，ops-web 内容审核只收评价/内容/问答。
- **头像审核**：`sys_media_asset` 有状态机，接审核是独立一档。
- **`page-block-spacing` 守卫只扫 `b-app/src/pages/*/index.vue`**，
  c-app 的页面不在它的扫描面内。扩到 c-app 会立刻点亮一批存量页，
  而一道从第一天起就红的闸门等于没有闸门 —— 单独一档再说。
- **存量昵称不批量改名**：平台替用户改名是越界。线上 23 个账号会在
  「我的」头部看到「去设置昵称」这个入口，由他们自己决定。
