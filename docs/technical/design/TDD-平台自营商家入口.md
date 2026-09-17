# TDD-平台自营商家入口

> 状态：**一期已落地 · 二期（自营免证件 + 运营建自营店）已落地 · 三期（代商家建）已落地**
> 三期落地时两条方案假设被实况推翻，见「② 协议同意」下的实况栏 —— 改了的是方案不是实况。
> 创建 2026-09-16
> 上游：[ADR-017 资金归集与结算方式](../ADR/ADR-017-资金归集与结算方式.md) §3.4 ·
> 迁移 `V87__merchant_type_three_way.sql`（主体三分）
> 档位：第一期 **1**（端点 + 权限码 + 跨域登记表，无库表变更）·
> 第二期 **2**（加一列 + 改闸门语义）

---

## L1 · 为什么需要一个「特殊入口」

**平台自营商家就是平台自己。** 而现在建商家只有一条路：
C 端用户提交进件资料（营业执照等）→ 运营端审核 → 通过后创建主体。

让平台走这条路，等于**平台向自己提交营业执照，再由自己审核自己**。
这与 `AfterSaleServiceImpl` 里已经记下的那处荒谬是同一个形状：

> 而此前自营单同样派给「商家」—— 而那个商家就是平台自己：
> 消费者申请退款 → 等平台自己审 → 驳回后再升级给平台仲裁。
> 一条本该一步的路走了两段，中间那段还是平台审自己。

那处当时是在售后链路上修的；这里是同一件事在**入驻链路**上的版本。

### 「不需要进件资料」的依据，不是图省事

ADR-017 §3.4 列的四个必要条件，约束的是**代销**——平台帮第三方商家卖，
所以才需要「平台对消费者承担责任，**再向商家追偿**」。

**平台自营不是代销**：货是平台自己的，不存在第三方主体，也不存在追偿对象。
进件资料的全部意义是「核验那个第三方是谁、有没有资格经营」——
自营场景下这个问题不存在，不是被豁免了。

⚠️ **但有一项不能省：`legal_form` 必须是 `ENTERPRISE`。**
它是税务口径（`V87` 的三分：自然人 / 个体工商户 / 企业），决定能不能开票。
平台公司本身就是企业，如实填写，不是走过场。

---

## L2 · 设计

### 端点

```
POST /ops/merchants/self-operated     建平台自营商家
```

路径用**复数** `merchants` —— `/ops` 域的约定（`api-path-naming.test.ts` 钉着）。
第一版写成 `/ops/merchant/...`，那条闸门当场拦下。

| | 第三方进件 | 本入口 |
|---|---|---|
| 谁发起 | C 端用户 | **运营（SUPER_ADMIN）** |
| 资质材料 | 必须交、必须审 | **不需要** —— 见 L1 |
| 申请单 `mch_entity_apply` | 建，走 APPLYING→REVIEWING→APPROVED | **不建** —— 驳回/重提对自营没有意义，留一条永远 APPROVED 的假记录只会污染待审队列 |
| `legal_form` | 申请人填 | 恒 `ENTERPRISE` |
| 覆盖社区 | 审核时勾选 | **scope=COMMUNITY 时同样必须有**（ADR-009）；别的档不要求勾，但要看回读的 `reachableCommunities` |

### ⚠️ 「自营」是两个字段，不是一个

设计第一版只写了 `funds_mode`。读代码时发现它只管一半：

```
mch_entity.funds_mode   = AGGREGATED     钱先进平台账户        （主体级，V81）
mch_store.business_mode = SELF_OPERATED  平台是销售主体        （门店级，V23）
```

只设前者会得到一家**看起来是自营、而售后派给商家自己**的店 ——
`SettleServiceImpl` 每单读的是后者。两个都要写。

**而这两列的建表默认值恰好就是这两个值**，所以「一行不写也对」。
这正是必须显式写一遍的理由：默认值改一次，平台主体就悄悄变成第三方，
没有任何报错。写在代码里，验收用例才有东西可以消融
（`SelfOperatedMerchantFlowTest#bothAxesAreSelfOperated`，
改成 `FUNDS_DIRECT` 实测变红）。

### ⚠️ 经营范围与「可达 0」

第一版把 `communityNos` 写成**恒必填**。落到真库上才发现两件事：

1. **深圳是全市生意，不是几个小区。** `activate` 自己的规则是「只有 scope=COMMUNITY
   才要社区」，恒必填等于在本入口另立一套更严的规矩，把 CITY/PLATFORM 档堵死。
   已改成同口径，并补上 `assertServiceScopeAllowed`（一期启用白名单）——
   `activate` 本身不判它，只有审核那条路判，新路径漏掉就能写进一个没开放的档。

2. **换档躲不掉真正的阻塞。** 可见性的唯一出口 `reachableCommunities` 最终
   一律展开成**小区号**：库里一个小区都没有时，CITY 档同样返回空集。
   「区划表里有深圳」与「深圳有小区」是两件事。

所以返回值里加了 `reachableCommunities`，**并在界面上把 0 当失败态显示**。
ADR-009 的必填规则只拦得住「一个社区都没勾」这一种写法，拦不住「勾了，但那一档
展开出来是空的」—— 而后者的表现完全一样：商品能上架、店在列表里、订单永远是零、
任何页面都不报错。校验拦不住的，就把数字报出来。

### 手机号不需要先收验证码

`UserProvisionPort.ensureUserByPhone(phone)` 走的是**登录建户那条路**。
所以运营填个号就能建完，本人日后用这个号登录命中的是同一个账号，
B 端身份（成员行）由 `activate` 一并建好 —— 不需要「先让他去 App 注册」这一步。

### 复用 `merchantAdminPort.activate(...)`，不重写

审核通过那条路里，建主体是一句 `merchantAdminPort.activate(...)`，
它在**同一个事务**里做三件事：建商家 + 配可达范围 + 建分账主体。
`OpsServiceImpl` 的注释写着：

> 少任何一件，商家就是「存在但做不了生意」，而这个故障没有任何报错。

所以新入口调同一个 Port，**不自己拼一套建主体的代码** ——
否则那条「三件事同一事务」的保证会在新路径上悄悄丢掉，而且不报错。

### 权限

新增码 `merchant:selfop:create`，**刻意不写进 `Perms.ROLE_PERMS` 的任何一个角色**。
于是只有持 `*` 的 `SUPER_ADMIN`（`sys_role.wildcard=1`，判权时短路）能调。

不复用 `merchant:admission:update`：那个码 BD 手里有，而自营主体决定资金归集路径
与售后责任归属，不该是招商日常能点的东西。

### 幂等：判据是**人**，与审核链路刻意相反

审核链路按**申请单**判重（`activatedEntityNo`），为的是让「老板申请第二张执照」
不被当成重复点击。本入口没有申请单，且平台自营主体按定义只有一个 ——
同一个号连点两次不该长出两个平台主体（买家会看到两家同名店，谁也说不清哪家是真的）。

命中已有主体时**把它的 `entityNo` 当作 `activatedEntityNo` 传下去**，
走 `activate` 自己的幂等重放支，而不是直接 return：重放支会把可达范围、成员行、
分账主体、默认门店重新对齐一遍。上一次建到一半失败时那条路能补上缺口，
直接 return 只会把残缺状态原样还回去，而界面显示「成功」。

返回值里带 `created` —— 两次都提示「建好了」的话，运营会以为自己建出了两家店。

## L3 · 落到哪儿（含跨域登记）

**放 shop-merchant，不放 shop-core。** 原打算写进 `OpsPlatformController`/`OpsServiceImpl`，
但 `setFundsMode`/`setBusinessMode` 在 shop-merchant 的 `MerchantGovernService` 上，
两个模块是**平级**、只通过 shop-base 的 SPI 通话 —— 放 shop-core 就得为这一件事把
`ActivateCommand` 撑宽。放商家域反而什么都不用改。

`MerchantPortImpl` 本来就依赖 `MerchantGovernServiceImpl`，所以新逻辑另起一个
`SelfOperatedService`（只被控制器用），不塞进治理服务 —— 塞进去就是循环依赖，
症状是整个上下文加载失败，一屏堆栈里真正那句话在最底下。

| # | 位置 | 做了什么 |
|---|---|---|
| 1 | `Perms.MERCHANT_SELFOP_CREATE` | 新码，不配给任何角色 |
| 2 | `SelfOperatedService` / `Impl`（shop-merchant） | 拼装既有零件：ensureUserByPhone → activate → setFundsMode → setBusinessMode |
| 3 | `OpsSelfOperatedController` | 端点 + 审计（幂等命中也记，摘要里写明是不是新建） |
| 4 | `scripts/perm-endpoint-map.mjs` | 规则表加一条；随后 `gen-perm-endpoint-matrix.mjs` 回填基线 |
| 5 | `ops-web`：`nav.ts` / `perm-map.ts` / `point-codes.ts` / 契约 / mock / `self-operated-tab.tsx` | 菜单挂在「入驻与资质」，紧邻进件看板 |
| 6 | `V328__self_operated_function_point.sql` | 菜单点落库（`sort=42`，插在 41 与 50 之间，既有行一行不碰） |
| 7 | `lib/nav.test.ts` 的 `ADDED_SINCE_MERGE` | 见下 |

### 顺手修的两处闸门问题

- **`gen-perm-seed.mjs --emit-point-codes` 会改写冻结表。** 跑一次它就把 33 行既有
  `point_code` 重写成别的（`/stores` → `OPS_MERCHANT`、`/jobs` → `OPS_IAM`…）——
  菜单合并之后派生源变了，而这张表的语义是「冻住不动」。`sys_role_point` 存的就是它，
  改一行等于把既有授权静默指向别处，且是**放宽**方向。本次只手写追加自己那一行，
  没有跑生成器回写。**这个生成器仍然是坏的**，另开一单修。
- **`nav.test.ts` 的 AC2 没有新增口。** 它比对合并前的可见性快照，
  于是此后任何一个新叶子都会让它变红，而「重新生成基线」等于拿改后的代码给自己出题。
  加了 `ADDED_SINCE_MERGE`：新增要手写一行并写明是什么，
  而「少了」永远没有豁免口 —— 那一半才是这条断言存在的理由。

## L4 · 验收（`SelfOperatedMerchantFlowTest`，8 条全绿）

| 要验的 | 怎么验 | 结果 |
|---|---|---|
| 两条轴都是自营 | 查库回读 `funds_mode` 与 `business_mode` | ✅ 消融（改 `FUNDS_DIRECT`）实测变红 |
| `legal_form=ENTERPRISE` | 同上 | ✅ |
| 分账主体一并建好 | `mch_payment_merchant` 有对应行 —— 这条正是「复用 Port」要保住的 | ✅ |
| 覆盖范围一并写好 | `mch_entity_community` 行数 = 传入社区数 | ✅ |
| COMMUNITY 档没社区就拒 | 空集合与 null 各一条 → `BizException` | ✅ |
| CITY 档不要求社区 | 建得出来，且 `serviceScope` 回读为 CITY | ✅ |
| 可达数是真算的 | 与 `reachableCommunities()` 逐值相等，**不断言大于零** —— 建完就是 0 正是要报出来的事实 | ✅ |
| 没开放的档要拒 | `serviceScope="ABC"` → 400（白名单） | ✅ |
| 手机号格式 | `12345678901`（位数够、号段不存在）→ 拒 | ✅ |
| 幂等 | 同号两次 → 同 `entityNo`，主体数只 +1，第二次 `created=false` | ✅ |
| 不产生申请单 | `mch_entity_apply` 行数不变 | ✅ |
| BD 点不动 | 角色×端点矩阵里这条只出现在 SUPER_ADMIN 名下（`ops-perm-matrix.test.ts` 逐格钉住） | ✅ |

## 第二期 · 自营主体不该再登记一遍证件

> 2026-09-16 实际建店时暴露的。第一期只解决了「不用交进件资料」，
> 没解决「建完之后那些读证件的闸门怎么办」。

### 事实：三道闸各读各的，只有一道真拦

建虹选鲜果时要授「水果」类目，界面显示 **缺「营业执照（食用农产品）」，无法授权**。
于是给它补登了一条证件 —— 而那条证件的编号是空的，因为平台公司的
统一社会信用代码不在手边。**一条半截的记录比没有更坏**：它看起来像核验过了。

把三处读证件的地方挨个查过之后，口径是这样的：

| 闸门 | 读什么 | 今天真拦吗 |
|---|---|---|
| 类目授权勾选（ops-web `authorize-tab`） | 主体已登记的**证件名** | **只在前端禁用勾选框** —— 后端 `MerchantAuthCodeService.setCodes` 注释里写明「这里没有校验证件」 |
| 上架 `requireCategoryAuthorized` | `mch_entity.category_codes` **授权码**，不读证件 | `shop.category.gate.enforce=false`，只记不拦 |
| 线下付款 `PayModeServiceImpl` | `QualificationPort.hasValidQualification(BUSINESS_LICENSE)` | **真拦** |
| 弱主体准入 `requireListingAllowed` | 保证金 / 禁售品类 | `ENTERPRISE` 档保证金 0、不禁资质类目 → 对自营主体不生效 |

也就是说，那条证件只买到了一件事：**把前端一个禁用的勾选框点开**。

### ⚠️ 关键分叉：`funds_mode` 认不出「平台自己」

最省事的写法是「归集即免证件」。**不能这么写。**

`FUNDS_AGGREGATED` 的定义原文是「归集：用户付给平台户，平台是销售主体（**代销**）」——
它同时盖着两种生意：

- **平台自营**：货是平台自己的，没有第三方主体，证件就是平台的证件；
- **代销**：货是第三方的，平台只是销售主体。ADR-017 §3.4 要求的正是
  「平台对消费者承担责任，**再向商家追偿**」—— 而追偿的前提是那个第三方被核验过。

`mch_store.business_mode` 同样认不出来：它的建表默认值就是 `SELF_OPERATED`（V23），
每一家新店一出生都是这个值。

**把「免证件」挂在这两个字段的任何一个上，都会顺手豁免掉代销的第三方商户，
而且没有任何地方会报错。** 这正是本文档第一期写进页面警告里的那句话
——「它在库里与真正的自营主体长得一模一样，日后分不出来」—— 今天应验了。

### 方案：先把标记建出来，再谈豁免

**① 加一列 `mch_entity.self_operated`（tinyint，默认 0）。**

它回答的是一个别处回答不了的问题：**这个主体是不是平台自己**。
不复用 `funds_mode`（那是钱走哪条路）、也不复用 `business_mode`（那是谁是销售主体，
且默认值就是自营）。三个问题三根轴，合并任意两根都会在某一档上判错。

**只有 `POST /ops/merchants/self-operated` 这一条路能把它置 1**，
且没有任何接口能把它改回来 —— 「这家店是不是平台自己」不是运营日常能翻的开关。

**② 「免证件」只挂在这一列上。**

`QualificationPort.hasValidQualification(entityNo, ...)` 对 `self_operated=1` 的主体
直接返回 true，理由写在实现处：**平台自营的证件就是平台自己的证件**，
它不该被逐个主体再登记一遍 —— 登记出来的那几条既没人核验，
也与平台的真实执照没有任何关联，只是为了骗过一道闸门。

这一条与仓库里已有的先例同形：`MerchantPortImpl#payCapability` 里
`platformIsSeller || !FALSE.equals(invoiceCapable)` —— 平台是销售主体时，
开票能力直接放行，因为票是平台开的。证件是同一个道理。

**③ ops-web 的类目授权勾选，对自营主体不禁用。**
那是三道闸里唯一真的挡住过人的一道（它挡住了我），而它在前端。

**④ 收尾：撤掉虹选鲜果那条占位证件。**
走 `POST /ops/qualifications/{qualNo}/revoke`，不直接删库。

### 不做什么

- **不动代销那一档。** 归集的第三方商户仍然要证件，一个字不放宽。
- **不在平台层建一份「平台执照」记录。** 那是另一件事（平台主体的合规档案），
  真要做该有自己的方案，而不是顺手塞进商家资质表里。
  本期的口径是：自营主体**不问**证件，而不是「用平台的证件替它答」。
- **不碰 `shop.category.gate.enforce`。** 那道闸今天是「只记不拦」，
  它该不该拦是独立的决定，混在本期里改会让两件事的验收纠缠在一起。

### L4 · 验收（每条都要能失败）

| 要验的 | 怎么验 |
|---|---|
| 自营主体无证件也能开线下付款 | `hasValidQualification` 返回 true；把 `self_operated` 置 0 这条要红 |
| **代销主体仍然要证件** | `funds_mode=AGGREGATED` 且 `self_operated=0` → 仍然拦。**这条是防静默放宽的那一条，必须能失败** |
| 标记只能由自营入口置位 | 全仓库除 `SelfOperatedServiceImpl` 外无第二处写它（守卫扫源码） |
| 类目授权对自营不禁用 | ops-web 用例：自营 + 无证件 → 勾选框可用；非自营 + 无证件 → 仍禁用 |
| 撤证件之后类目授权不丢 | `category_codes` 仍是 `["FRESH_FRUIT"]` —— 授权码与证件是两份数据 |

---

## 第三期 · 运营端代商家建主体与门店（待评审）

> 2026-09-16 提出。**它与第一、二期不是同一件事**，这一节的大半篇幅就是为了说清
> 「哪里必须不一样」—— 因为这两条路在界面上长得几乎一模一样，最容易被合成一条。

### 为什么不能和自营入口共用一条路

自营入口能跳过进件，是因为 **L1 那个论证**：进件资料的意义是「核验那个第三方是谁」，
而自营不存在第三方。**代商家建的时候，第三方是存在的。** 论证不成立，豁免就不成立。

| | 平台自营（一/二期，已落地） | 代商家建（本期） |
|---|---|---|
| 主体是谁 | 平台自己 | **第三方（真实商户）** |
| `self_operated` | 1 | **0，且没有任何路径能置 1** |
| 证件 | 不问（见二期） | **必须有，一张不能少** —— ADR-017 §3.4 |
| 通道进件 | 不需要（归集） | **需要** |
| `funds_mode` | 恒 `AGGREGATED` | 按政策定，不由本入口决定 |
| 订阅额度（开店） | 不吃（额度是卖给商家的商品） | **照吃** |
| 谁是 owner | 平台账号 | **商户本人的手机号** |
| 申请单 | 不建 | **建，并走现有审核队列** |

### 设计：代填申请单，不新造建主体的路

**建主体的唯一入口仍然是「审核通过 → `merchantAdminPort.activate`」**，本期只加一个
「谁来填这张表」的分支：BD 在店里把执照拍下来、当场替老板填完，落进现有审核队列。

```
POST /ops/merchant/apply/on-behalf     代商户提交进件申请
```

内部调的就是今天 `/biz/merchant/apply` 调的那个 `OpsService.createApply` ——
`SubmitApplyCommand` 的第一个参数本来就是 `userNo`，本期只是把它从
「当前登录人」换成「按手机号解析出来的商户」（`UserProvisionPort.ensureUserByPhone`，
与自营入口同一条路）。于是 `requireSubjectAllowedByIndustry`、`requireLicenseIfNeeded`、
`assertServiceScopeAllowed`、「一人一份进行中的申请」这几道闸**一条都不用重写**。

**刻意不做「填完即激活」。** 制单与审核分离在这个仓库里是有先例的
（权限码细化那次的原话：「财务里最该分离的制单与付款共用一把钥匙」）。
代填之后仍然进队列，审核那一步天然就是「另一个人看一眼」——
而这恰恰是代填这件事最需要的一层：**资料是运营录的，核验不该也是同一个人**。

### 门店：放开给第三方，但额度照吃

二期给自营主体开了 `POST /ops/merchants/{merchantNo}/stores`，里面硬拦了非自营
（理由：运营能绕过商家吃掉他买的额度）。本期把它改成**两支**：

- `self_operated=1` → 现在这样，跳过额度；
- 第三方 → 走 `StoreAdminService.create`，**`requireStoreQuota` 照常生效**。

额度是卖给商家的商品，代建不该让他凭空多一家店。超额时报的错要说清是额度，
而不是「请求参数有误」—— 否则 BD 会在店里反复改店名。

### 两件拍过板的事（2026-09-17 落地）

**① 替真人开账号 → 取 (a)，(c) 卡在外部动作上。**

账号照建（不建的话审核通过时没有 owner 可挂），代价由 **(a) 首次登录告知**兜住：
b-app 入驻页顶部一屏说明「这份资料由平台运营人员代为提交」，并请他本人补勾协议。
驱动它的是 `MerchantApplyVO.onBehalf` —— **给布尔不给代填人账号**：
商户要知道的是「这不是我自己填的」，运营的员工标识不该发给外部商户；
运营端要查是谁填的走审计日志。

**(c) 短信没做，不是忘了。** 线上 `SHOP_SMS_STUB=false`，通道是真的；
卡点在 `SmsPort` 只有 `sendOtp`，而阿里云要预先报备模板 —— 模板 ID 是外部动作，
编不出来。要做的话先在阿里云后台报备一个告知类模板，再给 `SmsPort` 加一个重载。

**② 协议同意 → 记录做了，那条界线画不出来。**

`agreed_at` 落了库，代填单一律留空，商户本人经 `POST /biz/merchant/agreement/accept`
补勾（参数取登录身份，**不接受「替谁勾」**——开那个参数等于把刚拦住的事从后门放回来）。

> **⚠️ 实况把方案里的两条假设推翻了，写在这里以免下一个人照着方案去找：**
>
> 1. **系统里没有提现实现。** 全后端 grep「提现 / withdraw」只在 `SettleService`
>    的注释里出现过（「与提现单 APPROVED → PAID 同一条规矩」）。
>    所以「未补勾前可经营不可提现」**没有挂载点**，这条界线今天画不出来。
> 2. **协议勾选此前从没落过库。** `agreed` 一路从 `MpUserController` 传到
>    `AuthService.LoginCommand`，而 `AuthServiceImpl` 一次都没引用它；
>    b-app 那段协议正文还写着「演示环境占位文本」。
>    所以 `agreed_at` 不只是给代填用的新列，**它是这套系统第一次真的记录协议同意**。
>
> 因此 `agreed_at` 两条路都写 NULL，语义是「有没有拿到过同意的凭据」。
> 按「反正是他自己提交的所以算同意」回填存量，等于凭空造一条法律事实。
>
**补勾之前那家店能做什么 → 定为「只提示不拦截，但提示常驻到他勾为止」**（2026-09-17）。

拦上架或拦收款都被否掉：那会打断一家**已经审核通过**的店的生意，
而协议没勾是<b>平台流程造成的</b>（运营代填时不能替他勾）—— 代价不该由商户承担。
而「不可提现」今天没有挂载点（见上面实况栏第 1 条）。

所以约束落在可见性上，不落在能力上：
`MerchantProfileVO.agreementPending` → b-app 工作台常驻一条待办，直到他补勾。
**不常驻不行**：代填的商户只在入驻页见过一次这件事，而审核通过之后他就不去那一页了。

> ⚠️ **判据是「代填 **且** 没勾」，两半缺一不可。**
> 存量单子的 `agreed_at` 同样全是空的，只按「没勾」判会让全体存量商家
> 明天一早都看到「你还没同意协议」—— 而他们当初确实勾过。
> 一条对所有人恒亮的提示等于没有提示。
> 这个「且」由 `MerchantStatusMappingTest#agreementPendingNeedsBothHalves` 钉着，
> 且**做过消融**：删掉 `onBehalf` 那一半它会红。
>
> （第一版用例写在场景测试里、断言 VO 的两个字段 —— 同一次消融**一条都不红**，
> 它测的是原料不是那个「且」。判据本身要能证伪。）

**提现落地之后**再回来决定要不要把这条升级成真拦截。

**③ 落地时新增的一条（方案里没有）：代填的人不能审自己填的那一张。**

TDD 原文说「新码给 BD 与超管」，而 **BD 手里已经有 `merchant:apply:audit`** ——
照做就是同一个人既制单又审核，恰好推翻这一期「制单与审核分离」的论证。
把码从 BD 手里拿走也不行：那样代填这件事根本没人做得了。
所以分离**钉在数据上**：`OpsServiceImpl.auditApply` 拦
`submitted_by == 当前审核人`（403）。角色怎么配都绕不过去。

### L3 · 要动的地方

| # | 位置 | 做什么 |
|---|---|---|
| 1 | `OpsMerchantApplyController`（新） | `POST /ops/merchants/apply-on-behalf` —— **复数**，跟随 `/ops` 现行约定（原文写的单数路径会撞 `api-path-naming` 闸门） |
| 2 | `Perms` | 新码 `merchant:apply:onbehalf`，给 BD 与超管（**与 `selfop:create` 分开** —— 那个只给超管） |
| 3 | `SelfOperatedService#addStore` | 第三方那一支加 `requireStoreQuota`（**没有改走 `StoreAdminService.create`**：那条路不带货架与 `createdBy`，换过去会丢掉这两样；只把额度闸接上，其余逐字不动） |
| 4 | `mch_entity_apply` | 加 `submitted_by`（代填人）与 `agreed_at`；两者都影响上面那两条决定 |
| 5 | ops-web | `/merchants?tab=on-behalf` 代填表单 + 审核抽屉上的代填标记 |
| 6 | 跨域登记 + 生成物 | 见第一期 L3 |

### L4 · 验收（每条都要能失败）

| 要验的 | 怎么验 |
|---|---|
| **代建的主体 `self_operated` 恒为 0** | 建完查库；这条守住二期的豁免不外溢 |
| **代建仍然要证件** | 不传执照 → `requireLicenseIfNeeded` 拒 |
| 代建仍然进审核队列 | `mch_entity_apply` +1 且状态 PENDING，**不直接 ACTIVE** |
| 第三方开店照吃额度 | 超额 → `STORE_QUOTA_EXCEEDED`，不是 400 |
| 自营开店仍不吃额度 | 二期那条用例不变红 |
| 代填人留痕 | `submitted_by` 非空，审计日志点名运营账号 |

---

## L4 · 边界

- **不做「自营商品」的特殊逻辑**：商品、库存、价格都走现有那套。自营与否只体现在主体属性上。
- **不碰通道进件**（`mch_payment_merchant`）：那是支付通道的开户，自营主体照样要开，走原路。
