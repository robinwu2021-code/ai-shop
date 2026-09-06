# TDD：带参抛出的错误码，文案里没有占位符 —— 参数被静默吞掉

状态：**已实现（2026-09-06）** · 档 1（动了错误码与 i18n 词条两项契约）
关联：[工单-服务端评审遗留](工单-服务端评审遗留-文案吞参与构建版本.md) §一/§二 ·
[响应格式规范 §3](../api/响应格式规范.md) · 守卫 `packages/shared/tests/message-placeholder.test.ts`

## 一、一句话

`BizException` 带参抛出、而 `messages.properties` 里那条文案没有 `{0}` 时，
`MessageFormat` 把参数**原样丢掉**：用户看到「请求参数有误」，而调用点以为
「提交审核前要先上传主图」已经送到了。实测 28 处。本单把这 28 处逐个改对。

## 二、为什么是这个方案

三种改法，按参数的性质选，不是按方便选：

| 参数是什么 | 改法 | 为什么不选另外两种 |
|---|---|---|
| **一句写给用户的中文** | 各开专用错误码，把那句话原样搬进三份 properties | 给 `BAD_REQUEST` 这类通用码的文案加 `{0}`**不行**：它还有一堆无参抛出点，加了之后那些地方会渲染出字面的 `{0}`（正向守卫拦的就是这个）。而把中文串直接塞进响应，等于阿语界面弹中文 |
| **一个值**（角色码、SKU 列表、可提金额） | 该码的文案加 `{0}`，前提是**它的每一个抛出点都传参** | 只要还有一个无参抛出点就不能加，同上 |
| **内部细节**（通道未接入、网关原始报文） | 写进日志，无参抛 | 这类信息对用户没有动作价值，且可能带外部厂商的原文 |

**中文文案原样搬，不重写。** 那些句子是当初写代码的人斟酌过的，且已经在线上跑了；
本单只把它们从代码里搬到 i18n 文件里，另配英文与阿语。重写文案是另一件事。

**新码只在段内递增**（[响应格式规范 §3](../api/响应格式规范.md)）：
交易 2xxxx · 内容 104xx · 资金 5xxxx · 准入 7xxxx · 商品与类目 8xxxx。

## 三、结构

23 个新码，分三批落地（每批单独可验证、单独提交）：

### 第一批 · 值类与内部细节（0 新码 + 1 新码，6 处）

| 处 | 现状 | 改法 |
|---|---|---|
| `OpsServiceImpl` 695/752/833 | `STAFF_ROLE_UNKNOWN(role)` | 三个抛出点全都传参 → 文案加 `{0}` |
| `ReservationServiceImpl:105`、`StockPostingServiceImpl:73` | `STOCK_NOT_ENOUGH(缺货清单)` | `STOCK_NOT_ENOUGH` 还有一个无参抛出点（`OrderServiceImpl:610`），不能加 `{0}` → 新码 `STOCK_SHORT_ITEMS(20005)` |
| `PayGatewayRouter:51` | `INTERNAL_ERROR("支付通道未接入：" + ch)` | 装配缺失，属内部细节 → `log.error` 后无参抛 |
| `OrderServiceImpl:1008` | `PAY_CHANNEL_UNAVAILABLE(init.message())` | 网关原始报文 → `log.warn` 后无参抛 |

### 第二批 · 社区与聚落（9 新码，7xxxx，9 处，全在 `CommunityAdminServiceImpl`）

| 码 | key | 中文（原样搬自代码） |
|---|---|---|
| 70049 `COMMUNITY_ALREADY_OPEN` | `err.community.already_open` | 「{0}」已经开通了，直接在列表里勾选即可，不用提报 |
| 70050 `COMMUNITY_APPLY_DUPLICATE` | `err.community.apply_duplicate` | 这个小区你已经提报过，正在等运营处理 |
| 70051 `REGION_NOT_FOUND` | `err.community.region_not_found` | 区划不存在：{0} |
| 70052 `COMMUNITY_REGION_NOT_STREET` | `err.community.region_not_street` | 聚落要挂在街道/镇（9 位码）下，当前：{0} |
| 70053 `COMMUNITY_ORIGIN_ALREADY_OPEN` | `err.community.origin_already_open` | 这个村已经开通过聚落，驳回本条并让商家直接勾选既有的 |
| 70054 `COMMUNITY_PARENT_TOO_DEEP` | `err.community.parent_too_deep` | 归属只做两层：「{0}」自己已经挂在别的聚落下面了 |
| 70055 `COMMUNITY_PARENT_NO_STREET` | `err.community.parent_no_street` | 「{0}」还没有归属的街道，先补上再建楼 |
| 70056 `COMMUNITY_STREET_UNRESOLVED` | `err.community.street_unresolved` | 定不出这个位置属于哪个街道，换个点或从行政区划里选 |
| 70057 `COMMUNITY_APPLY_SUBMITTED` | `err.community.apply_submitted` | 已提交，等运营核对后就能加入 |

### 第三批 · 商品 / 内容 / 行业 / 经营范围 / 提现 / 授权码（13 新码，13 处）

| 码 | key | 中文 |
|---|---|---|
| 10459 `FAQ_ANSWER_REQUIRED` | `err.content.faq_answer_required` | 上架前答案不能为空 |
| 50011 `WITHDRAW_PENDING_EXISTS` | `err.settle.withdraw_pending_exists` | 还有一笔提现在处理中，完成后才能再提 |
| 50012 `WITHDRAW_OVER_WITHDRAWABLE` | `err.settle.withdraw_over_withdrawable` | 可提金额不足：当前可提 {0} 元 |
| 70058 `PAY_CHANNEL_UNKNOWN` | `err.pay.channel_unknown` | 未知支付通道：{0} |
| 70059 `INDUSTRY_NOT_FOUND` | `err.industry.not_found` | 行业不存在：{0} |
| 70060 `SERVICE_SCOPE_EMPTY` | `err.merchant.service_scope_empty` | 至少要开放一档经营范围 —— 全关等于所有商家都保存不了门店 |
| 70061 `AUTH_CODE_NOT_FOUND` | `err.merchant.auth_code_not_found` | 授权码不存在：{0} |
| 70062 `AUTH_CODE_IN_USE` | `err.merchant.auth_code_in_use` | 还有类目要求这个授权码，先把它们改到别的码上或归档，再停用 |
| 80019 `GOODS_COVER_REQUIRED` | `err.goods.cover_required` | 提交审核前要先上传主图 |
| 80020 `GOODS_TITLE_TOO_SHORT` | `err.goods.title_too_short` | 标题至少 {0} 个字 |
| 80021 `GOODS_TITLE_TOO_LONG` | `err.goods.title_too_long` | 标题最多 {0} 个字 |
| 80022 `GOODS_TITLE_BANNED_WORD` | `err.goods.title_banned_word` | 标题里的「{0}」不能用 |
| 80023 `GOODS_TITLE_BANNED_WORD_REASON` | `err.goods.title_banned_word_reason` | 标题里的「{0}」不能用：{1} |

> 禁售词分两个码而不是一个带可选后缀：词表里的「原因」是可空字段，
> 拼一个空后缀会在英文与阿语里留下一个吊着的标点。

**`AUTH_CODE_IN_USE` 顺带修一处用错的码**：`AuthCodeAdminServiceImpl:84` 原本抛
`CATEGORY_IN_USE`(80002)，而那条文案说的是「类目下还有商品」——
与「授权码还被类目要求着」不是一回事，运营看到的提示指向错误的对象。

## 四、验收标准与测试

| AC | 判据 | 测试方法 |
|---|---|---|
| AC1 | 没有任何抛出点给无占位文案传参 | `message-placeholder.test.ts` 反向断言 → 基线从 28 归零、删行 |
| AC2 | 没有任何带占位文案被无参抛出 | 同文件正向断言（既有） |
| AC3 | 每个错误码都有三语文案 | `BackendI18nParityTest#everyErrorCodeHasAMessage` + `allLocalesHaveTheSameKeys` |
| AC4 | 每条文案都有码指着 | `BackendI18nParityTest#everyMessageBelongsToAnErrorCode` |
| AC5 | 码不重号、key 不共用 | `ErrorCodeUniqueTest` |
| AC6 | 新码在响应格式规范 §3 登记 | `spec-completeness.test.ts`「错误码分段表与 ErrorCode.java 一致」（**本单第 8 步先把它修活**：它读的模块路径 `shop-common` 早已改名 `shop-base`，`existsSync` 为假直接 return，从未跑过） |
| AC7 | 渲染出来的文案里不残留 `{0}` | `MessagesRenderTest`（新增）：对每个带占位的 key 用真实 MessageSource 渲染，断言结果不含 `{` |

**消融**：把任一处改回「带参抛通用码」，AC1 变红并点名到文件行号；
把任一条新文案从 `messages_ar.properties` 删掉，AC3 变红。

## 五、偏差说明

**与设计的三处偏差，都在实现时才看清：**

1. **多了一个码：`COMMUNITY_REGION_REQUIRED`(70058)。** 设计里「区划粒度不对」是一条，
   实现时发现原代码在 `code == null` 时传的是「未填」这个词 —— 合成一条的话，
   文案里的「当前：{0}」后面会跟一个空白。原作者刻意区分过没填与填错，所以拆成两条。
   23 → 24 个新码。

2. **`AuthCodeAdminServiceImpl:84` 顺带换掉了一个用错的码。** 它抛的是
   `CATEGORY_IN_USE`(80002)「该类目下还有商品或子类目」，而实情是「授权码还被类目要求着」——
   两回事。新开 `AUTH_CODE_IN_USE`(70063)。这不在原设计里，但不换的话运营看到的提示
   指向错误的对象，而本单的整个目的就是让提示说对话。

3. **多做了一条守卫：占位符个数三语一致**（`BackendI18nParityTest`）。
   写阿语文案时意识到：三语键集一致管的是「有没有这一条」，
   而一条文案在中文里有 `{0}{1}`、在阿语里只有 `{0}`，同样会把参数静默丢掉，
   且现有的两向守卫都看不见（它们只比对码与中文）。补上这条，三者才闭合。

**第 8 步（修活失效守卫）本不属于本单**，是实现时撞上的：加码要在
`响应格式规范.md` §3 登记，而那道对账守卫读的模块路径 `shop-common` 早已改名，
`existsSync` 为假直接 return —— 从未跑过，于是文档只手抄了 25 条而枚举有 160 条。
一并修了：路径改对、不存在时不再静默 return 而是报红、§3 按枚举补齐 184 条。

## 六、实现清单（设计→实现对账）

```
backend/shop-base/.../ErrorCode.java                    +24 码
backend/shop-app/src/main/resources/i18n/messages*.properties   +24 ×3 条，另 err.staff.role_unknown 加 {0}
backend/shop-core/.../community/.../CommunityAdminServiceImpl.java   10 处
backend/shop-core/.../product/.../MerchantGoodsServiceImpl.java       4 处
backend/shop-core/.../platform/impl/IndustryServiceImpl.java          2 处
backend/shop-core/.../platform/impl/ServiceScopeServiceImpl.java      1 处
backend/shop-core/.../message/impl/MessageServiceImpl.java            1 处
backend/shop-core/.../trade/.../OrderServiceImpl.java                 1 处（改日志）
backend/shop-merchant/.../AuthCodeAdminServiceImpl.java               2 处
backend/shop-inventory/.../ReservationServiceImpl.java                1 处
backend/shop-inventory/.../StockPostingServiceImpl.java               1 处
backend/pay/pay-domain/.../WithdrawServiceImpl.java                   2 处
backend/pay/pay-channel/.../PayGatewayRouter.java                     1 处（改日志 + 补 Logger）
backend/shop-app/src/test/.../BackendI18nParityTest.java             +1 条守卫
packages/shared/tests/spec-completeness.test.ts                       修活路径
packages/shared/known-guard-failures.txt                              基线 28 → 删行
docs/api/响应格式规范.md                                              §3 补齐 184 条
docs/technical/reference/*（生成物）                                  重新生成
```

设计里列了却没动的文件：无。出现在实现里而设计没列的：上面三处偏差涉及的那些。
