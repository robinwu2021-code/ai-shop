# TDD-C 端免登录切商家端

状态：草稿
关联需求：本会话确认（2026-10-07）——C 端已登录用户切到商家运营不再单独登录，用 C/B 账号关联换取商家令牌
创建：2026-10-07 · 最后更新：2026-10-08

> **一句话**：C 端小程序打开即静默微信登录(ctk_)，「商家运营」入口凭 C 端令牌向后端换取商家令牌(btk_)——
> 后端按 `mch_account.user_no == 当前 C 端 user_no` 找到店主身份，有则签 btk_、无则回 `NOT_A_MERCHANT`。
> 关联键是 **user_no**（不是手机号）；该等式由现有「C 端申请→运营审核建号」链路保证，本期**不补写入**。

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 已登录 C 端用户换取商家令牌：是店主则签 btk_、**不撤 ctk_** | `AuthService.switchToMerchant` + `MpUserController` 新端点 |
| AC2 | 未关联商家 → 明确业务码，前端引导入驻 | 新 `ErrorCode.NOT_A_MERCHANT` + 三语 |
| AC3 | 匿名（无 ctk_）调用 → 401 | 端点挂 C 端链；`MpEndpointAuthTest` 需登录清单 |
| AC4 | 前端 `_entry` 自动换、无 OTP；换不到引导入驻；去掉登录闸 | c-app pkg-biz `_entry`（with-biz.mjs 生成）+ endpoints |
| AC5 | 关联键 `mch_account.user_no`（店主行）已可靠成立 | 现有链路（agent 确认，§1） |

| AC6 | **店员**也免登录：商家后台录入的店员手机号，与店员本人 C 端账号的手机号匹配即放行 | `StaffSessionPort`（新 SPI）+ `switchToMerchant` 店员分支 |

| AC8 | 「我的」页按**真实经营身份**给入口：是商家给「商家运营」，不是才给「我也想开店」 | `UserVO.merchantRole` + `AuthService.merchantRoleOf` + `StaffSessionPort.hasActiveStaffAccount` |

**孤立项**：无。明确排除：存量「运营代客进件且当时无 C 端账号」的店主（少数，回退手机号登录/客服）。

### AC6 · 店员那一支（第二轮补的）

店主靠 `mch_account.user_no` 直连 C 端账号；而**店员行往往没有 user_no**
（店员是店主在后台录手机号加进来的，他未必在 C 端注册过同一身份），按 user_no 解析恒为空。
所以店员只能**按号认**：拿当前 C 端用户**本人已验证的完整手机号**去匹配 `mch_account.login_phone`。

- 判定顺序与 `/biz/auth/login` 一致：**店主 → 店员 → 都不是**（自己的店优先；一个人可能既开店又被邻居店加为店员）。
- 复用 `MerchantStaffService#issueStaffSession`（与 `/biz/auth/login` 店员支**同一条路**：同样只认 ACTIVE、
  多主体同样按 `is_primary` 取默认），避免出现「登录能进、切换进不去」或两边进了不同主体。
- 手机号取 `AuthServiceImpl#phoneOf(userNo)`（查 `usr_identity` 的 PHONE 凭证，**完整号**）：
  - 取请求里带来的号 = 报上任意手机号就能登进那个人的店；
  - 取脱敏号 = `where login_phone=?` 永远查不到，表现是「切过去变成不是商家」。
- 微信登录没授权手机号时 `phoneOf` 为空 → **判不了店员身份**，报单独的
  `PHONE_REQUIRED_FOR_MERCHANT(10471)`，端上引导「去绑手机号」。
  **不能并进 `NOT_A_MERCHANT`**：那会对一个已经是店员的人说「你还不是商家，去开店吧」——
  而他要做的只是绑个号。两条码对应端上两条不同的出路（`need-phone` / `not-merchant`）。
- **不并进 `StaffLoginPhonePort`**：那个接口刻意只回布尔、「不回是哪个账号」，为的是不让人靠它枚举
  某手机号是不是商家；签发会话塞进去会破掉那条边界，故单开 `StaffSessionPort`（带 `NONE` fail-closed 兜底）。

### AC8 · 两张卡各判各的（第三轮，2026-10-08 真机上撞到）

真机上两件事一起出现：

- **18126333580 已经是虹选鲜果 / 虹选粮油两家店的店长**，而「我的」页给他的是一张**入驻表**；
- **「商家运营」对一个刚注册、什么店都没有的人也亮着**，点下去才拿到 `NOT_A_MERCHANT`。

根因是两张卡各判各的：开店卡只看后端开关（`merchant.apply.mp-visible`），
商家运营卡只看 `withBiz && user.isLogin`，**两边都没问「他到底是不是商家」**。

端上判不出来：它手里只有 `merchantNo`（= `usr_account.entity_no`，「常去的店」），
而**店员那一行与他的 C 端账号之间没有任何一列相连** —— 店员是店主在后台录手机号加进来的
（线上实况：`SF202610081123030000587` 的 `user_no` 是 NULL）。拿它判，店长永远被当成还没开店的人。

所以加 `UserVO.merchantRole`（`OWNER` / `STAFF` / null），由后端算：

- **只有 `/mp/user/profile` 填它**，别的返回 UserVO 的端点一律 null —— 判这一项要多查两次，
  而需要它的只有「我的」那一页（`onShow` 已经在调 `loadProfile`）。
- **判定与 `switchToMerchant` 必须同源**：店主 → 店员 → 都不是，连「店主优先」都一样。
  松一档的后果不是多显示一个入口，而是**页面说你是商家、点进去说你不是**。
- 店员那一支走新加的 `StaffSessionPort.hasActiveStaffAccount`（只问不签），
  实现与 `issueStaffSession` **共用同一个查询**（`findActiveStaff`）—— 两处各写一遍就迟早分家。
- **不复用 `StaffLoginPhonePort.isStaffLoginPhone`**：那个**含已停用**（它问的是「这个号能不能被录进白名单」），
  口径更宽，拿来判身份会给一批已停用的店员亮出一个点下去会被拒的入口。
- 在 controller 里组装而不是 `UserService`：那边注入 `AuthService` 会把两个 service 绕成环。

## §1 现状与影响面

- **关联等式已成立**（agent 查实）：`currentUserNo() → mch_entity_apply.user_no → ActivateCommand.ownerUserNo → mch_account.user_no`，全程透传无改写。凭 C 端 user_no 查 `mch_account where user_no=? and is_owner=true` 能查到店主。
  - 申请记号：`MpCatalogController.merchantApply`(`:308`) → `OpsServiceImpl.doCreateApply`(`:465`)
  - 审核建号：`OpsServiceImpl.auditApply`(`:370`) → `MerchantPortImpl.ensureOwnerStaff`(`:1422` `setUserNo`)
- **判店主的现成件**：`BizIdentityResolver.resolve(userNo)` → `BizContext`，`merchantNo` 非空即有经营身份。接口在 `shop-base-auth`，实现 `BizIdentityResolverImpl`(shop-merchant)。
- **签 btk_ 的现成件**：`tokenStore.issue(SessionData.of(LoginUser.merchantByUser(userNo, nickname)))`（`LoginUser.java:147`）。与 `BizAuthController.login`(`:105`) 同构，差别：**本端点不撤 ctk_**。
- **令牌池隔离**：ctk_(CONSUMER)/btk_(MERCHANT)/otk_(OPERATOR)，前缀即池。新端点必须挂 **C 端链**（consumerChain 认 ctk_）——挂 /biz 会被 merchantChain 第一道 401。
- 会被改到：`AuthService`/`AuthServiceImpl`（加方法 + 注入 resolver）、`MpUserController`（加端点）、`ErrorCode` + 后端三语、`MpEndpointAuthTest`、c-app 并包脚本生成的 `_entry` + endpoints。
- 不受影响：现有 /mp 与 /biz 登录、申请/审核链路（零改动）、b-app 原生应用、其它域。

## §2 方案

### 契约变更
- 端点：**新增** `POST /mp/user/switch-to-merchant`（挂 C 端链，鉴权=已登录 ctk_，无请求体）。返回 `{ token }`（btk_）。
- 库表/迁移：**无**。
- 权限码：**无**（btk_ 的作用域由 `merchantByUser` 给 SELF）。
- ErrorCode：**新增** `NOT_A_MERCHANT`（i18n key `err.not_a_merchant`），三语齐。
- i18n：后端 `err.not_a_merchant` zh/en/ar。

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-core/.../user/service/AuthService.java` | 加 `String switchToMerchant(String userNo)` |
| 修改 | `shop-core/.../user/service/impl/AuthServiceImpl.java` | 实现：resolve→非空签 btk_、空抛 NOT_A_MERCHANT；注入 `ObjectProvider<BizIdentityResolver>`（兜底 NONE） |
| 修改 | `shop-core/.../user/api/mp/MpUserController.java` | 加 `@PostMapping("/switch-to-merchant")`，取 `currentUserNo`、调 service、审计 MERCHANT 池 |
| 修改 | `shop-base/.../common/ErrorCode.java` | 加 `NOT_A_MERCHANT` |
| 修改 | 后端三语 i18n | `err.not_a_merchant` |
| 修改 | `MpEndpointAuthTest` | 新端点纳入「需登录」清单 |
| 修改 | `c-app/scripts/with-biz.mjs` | `_entry`：自动换 + 去 OTP 登录闸；按结果分流（进四屏 / 引导入驻） |

### 关键接口
```
// AuthService（shop-core）
String switchToMerchant(String userNo);   // 返回 btk_；非店主抛 BizException(NOT_A_MERCHANT)

// 端点
POST /mp/user/switch-to-merchant   (Authorization: Bearer ctk_...)
  200 { "token": "btk_..." }
  业务码 NOT_A_MERCHANT   // 未关联商家
  401                     // 匿名
```

## §3 选型
| 维度 | 采用 | 不采用 | 理由 |
|---|---|---|---|
| 关联键 | **user_no** | 手机号 | 微信登录可能无手机号、手机号会换；user_no 是 C 端不变主键，且等式已由现有链路保证 |
| 端点位置 | **/mp/user**（C 端链） | /biz/** | merchantChain 只认 btk_，ctk_ 打 /biz 必 401 |
| ctk_ 处理 | **不撤销** | 撤销（同 login） | 用户要继续留在 C 端 |
| 判店主 | `BizIdentityResolver.resolve` | 自写 SQL | 现成件，profileOf 店主分支同款 |

## §4 风险
| 风险 | 影响 | 缓解 |
|---|---|---|
| shop-core 注入 BizIdentityResolver 缺 bean（纯 core 测试） | 装配失败 | `ObjectProvider` 兜底 `NONE`（NONE→全判非商家，fail-closed 安全） |
| 代客进件且当时无 C 端账号的店主 | 免登录查不到 | 回退手机号登录/客服；不在本期自动回填 |
| 新 ErrorCode 漏登记 | BackendI18nParityTest 红、挡所有人 | 枚举 + 三语一起提交，跑 parity 测试 |
| 运行时渲染/鉴权本机难全验 | 真机才暴露 | 发体验版真机验（与 i18n 修复一起） |

## §5 对账三 · 实现 → 需求（测试）
| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| AC1 | `SwitchToMerchantTest#ownerGetsMerchantToken`：店主 user_no → `btk_` 前缀令牌 | ✅ `Tests run: 2, Failures: 0`（12.5s，真跑） | 见下 |
| AC2 | `SwitchToMerchantTest#nonMerchantThrows`：非店主 → `NOT_A_MERCHANT` | ✅ 同上 | ✅ 把判空改成 `if (false)` → `Failures: 1`，还原后回绿 |
| AC3 | `MpEndpointAuthTest`：需登录清单已登记 `POST /mp/user/switch-to-merchant`，匿名调回 401 | ✅ `Tests run: 7, Failures: 0, Errors: 0` | — |
| AC2(i18n) | `BackendI18nParityTest`：`err.not_a_merchant` 三语齐 | ✅ `Tests run: 6, Failures: 0` | — |

| AC6 | `SwitchToMerchantTest#staffFallsBackToPhoneMatch`：解析不到店主时按本人手机号匹配到店员会话 | ✅ `Tests run: 4, Failures: 0` | ✅ 把 `if (phone != null…)` 改成 `if (false)` → **只有店员那条**变红，其余三条仍绿 |
| AC6 | `SwitchToMerchantTest#ownerWinsOverStaff`：两个身份都有时走店主，`verify(never())` 不去问手机号 | ✅ 同上 | — |
| AC7 | `SwitchToMerchantTest#noPhoneAsksToBindRatherThanApply`：没绑号 → `PHONE_REQUIRED_FOR_MERCHANT`（不是 `NOT_A_MERCHANT`），且不去查店员表 | ✅ `Tests run: 5, Failures: 0` | — |

| AC8 | `SwitchToMerchantTest#staffRoleIsStaff`：店员 → `"STAFF"`，且 `verify(never()).issueStaffSession`（只问不签） | ✅ `Tests run: 9, Failures: 0, Errors: 0`（13.6s，真跑） | ✅ 把 `hasActiveStaffAccount(phone)` 改成 `false` → **只有这一条**变红，还原后 9/9 回绿 |
| AC8 | `#ownerRoleIsOwner`：店主 → `"OWNER"`，且不去问手机号 | ✅ 同上 | — |
| AC8 | `#plainUserHasNoRole`：有号但不是店员 → null | ✅ 同上 | — |
| AC8 | `#noPhoneHasNoRole`：没绑号 → null，且不拿空号去查店员表 | ✅ 同上 | — |
| AC8 | `c-app/tests/merchant-recruit.test.ts`「两张卡互斥」：判据取 `merchantRole`、两个 `v-if` 都钉住 | ✅ `7 passed` | ✅ 把 `withBiz && isMerchant` 改回 `withBiz && user.isLogin` → 变红，还原后回绿 |

三组在**干净 HEAD 副本 + 仅本次改动**上一起跑：`BUILD SUCCESS`。

**AC8 在 H5（mock）上验到的一半**：把 `merchantRole` 置成 `STAFF`，「我也想开店」当场消失；置回 null 又出现 —— 可证伪。
**另一半验不了**：mock 构建里 `VITE_WITH_BIZ` 没设，「商家运营」那张卡恒不渲染，
拿它当对照量会得到一个毫无信息的绿。那一半只能在并包的体验版上看。

店员那条用例**真的往 `usr_identity` 插了一条 PHONE 凭证**，没有把 `phoneOf` mock 掉 ——
店员分支的前提就是「这个 C 端账号有已验证的手机号」，mock 掉等于没测到那个前提
（第一版就是因为没插这行而红：用户没号 → 直接落 NOT_A_MERCHANT，根本没走到匹配）。

**消融已做**（AC2）：撤掉 `switchToMerchant` 的判空分支 → 非商家用例立刻变红，还原后回绿。证明它测的就是那个分支，不是假绿。

### 踩到并修掉的一个坑：新测试连累了别的测试类

`SwitchToMerchantTest` 用 `@MockitoBean` 替换 `BizIdentityResolver`，这会让它**拿到一个新的 Spring 上下文**；
而新上下文跑在共用的 `jdbc:h2:mem:shop` 上，会把 `schema-test.sql` **再执行一遍** ——
其中 `UPDATE mch_admission_policy SET legal_form='NATURAL_PERSON' WHERE legal_form='MICRO'`
第二次执行就撞 `uk_admission_legal_form(legal_form, tenant_no)` 唯一键，
于是 **`MpEndpointAuthTest` 整个类 7 个用例全部 Error（context 起不来）**。

症状极具误导性：报错指向一条与本次改动毫不相干的 SQL、且 `schema-test.sql` 自己没有任何改动，
**我因此一度误判成「别人的半成品导致、与我无关」**。真正把它钉死的是基线对照 ——
同一个干净 HEAD 副本，不带我的改动 7/7 绿、带上我的改动 7 个全错，嫌疑才落到自己头上。

修法照抄 `PlaceResolveChainTest` 已有的解法（它的注释正写着这个坑）：给这个类
`@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:switch-merchant;...")` 另开一个库。

> **教训**：共享工作区里出现「看起来与我无关」的红，不能凭「报错指向别处」就撇清 ——
> 判据只能是**干净 HEAD 副本上加/不加我的改动的差集**（[[claim-failures-with-baseline]]）。

## §6 对账二 · 设计 → 实现

```
 shop-app/src/main/resources/i18n/messages.properties        |  2 +   # err.not_a_merchant
 shop-app/src/main/resources/i18n/messages_ar.properties     |  2 +
 shop-app/src/main/resources/i18n/messages_en.properties     |  2 +
 shop-app/src/test/.../arch/MpEndpointAuthTest.java          |  1 +   # 新端点登记进需登录清单
 shop-base/src/main/.../common/ErrorCode.java                |  5 +   # NOT_A_MERCHANT(10470)
 shop-core/src/main/.../user/api/mp/MpUserController.java    | 19 ++   # POST /mp/user/switch-to-merchant
 shop-core/src/main/.../user/service/AuthService.java        | 11 ++   # switchToMerchant 声明
 shop-core/src/main/.../user/service/impl/AuthServiceImpl.java | 29 ++ # 实现 + 可选注入 BizIdentityResolver
 c-app/scripts/with-biz.mjs                                  | 81 +++  # _entry 免登录换取 + i18n 模板 $t→t 修复
 shop-app/src/test/.../scenario/SwitchToMerchantTest.java    | 新增    # AC1/AC2
```

| 差异 | 说明 |
|---|---|
| TDD 列了、实际没动：后端新增端点以外的任何库表/权限码 | 符合设计：零迁移、零权限码 ✅ |
| TDD 没列、实际改了：`with-biz.mjs` 的 i18n 模板 `$t(`→`t(` 改写 | 这是**上一版体验版露 key 的修复**，与本 TDD 同批发版，顺带记在这里 |
| 范围内未做：店员免登录 | 店员行常无 `user_no`，按设计保留手机号登录兜底（§0 已声明排除） |
