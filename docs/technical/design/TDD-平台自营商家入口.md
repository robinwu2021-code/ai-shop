# TDD-平台自营商家入口

> 状态：**已落地** · 创建 2026-09-16 · 落地 2026-09-16
> 上游：[ADR-017 资金归集与结算方式](../ADR/ADR-017-资金归集与结算方式.md) §3.4 ·
> 迁移 `V87__merchant_type_three_way.sql`（主体三分）
> 档位：**1** —— 新增一个 `/ops` 端点，动了权限码与跨域登记表；无库表变更

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

## L4 · 边界

- **不做「自营商品」的特殊逻辑**：商品、库存、价格都走现有那套。自营与否只体现在主体属性上。
- **不碰通道进件**（`mch_payment_merchant`）：那是支付通道的开户，自营主体照样要开，走原路。
