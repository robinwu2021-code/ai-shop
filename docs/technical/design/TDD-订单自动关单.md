# TDD-订单自动关单

状态：**已实现**（2026-08-14）
> ⚠️ §3.3 与 §6 已被 [定时任务清单与调度方案](定时任务清单与调度方案.md) §3 修正：
> `OrderService.closeUnpaid(orderNo, reason)` **已存在**（对账自查在用），关单 Job 只是壳；
> cron 走配置而非写死。并且那份文档指出 **Outbox 投递任务缺失**（全站站内信一条不发），
> 优先级高于本方案。
关联需求：[平台端功能清单](../../requirements/平台端功能清单.md) P-4.2.3 关单策略配置 ·
[需求矩阵-三端](../../requirements/需求矩阵-三端.md) C-PM-05 超时关单 · §七之二 `payTimeoutMinutes`
创建日期：2026-08-13

---

## 1. 需求摘要

`/orders?tab=close` 是一个读得回来、能编辑、有保存按钮的关单策略表单，
而 `GET/PUT /ops/payments/close-rule` 两条端点**都不存在**——保存点下去 404，页面什么都不说。

验收标准：

1. 运营改「未支付 N 分钟后关单」，**真的按新值关单**
2. 没配过时返回默认值，页面打得开（与既有配置的口径一致）
3. 时限有上下限：太短会把正在付款的人关掉（自己制造掉单），太长长期占住库存
4. 留痕：谁在什么时候改的——改参数会改变历史数据的呈现

---

## 2. 当前架构分析

### 2.1 两个发现，都改变了这个任务的形状

**① 后端根本没有自动关单。**
`@EnableScheduling` 在，两个定时任务在跑（`ReconScanJob` 对账扫描、`QualificationExpiryJob` 资质到期），
但**没有任何一个关待支付订单**。全仓搜 `closeOrder` / `autoClose` 零命中。

所以「关单策略配置」配的是一个**不存在的行为**。

**② `payTimeoutMinutes: 15` 是「唯一事实源」表的一行**
（`packages/shared/src/utils/constants/index.ts:258`，由 `scripts/gen-rules-table.mjs` 生成成表）。
那张表是专门为「三份清单各自复述这些数字、谁是权威说不清」立的。
直接在库里再存一份关单时长 = **造第二个事实源**，正是这张表设立来防的事。

目前它唯一的消费者是 `c-app/src/api/mock.ts:546` 的下单页倒计时。

### 2.2 可复用的既有能力

| 能力 | 位置 | 复用方式 |
|---|---|---|
| `SettingService.get/put` | `platform/SettingService.java` | 直接用：存 JSON + 留痕（`updated_by`/`updated_at`） |
| 「没配过返回默认值」口径 | `PlatformConfigServiceImpl` | 照搬：参数表少一行不该让整个页面打不开 |
| 定时任务装配 | `SchedulingConfig` + 两个既有 Job | 照搬 Job 的写法与部署口径 |
| 权限码 | 功能点 `OPS_ORDER__TAB_CLOSE` 的 `perm_code = order:order:modify` | 端点挂 `Perms.ORDER_MODIFY`，与库里登记一致 |

`SettingService.get()` 只返回 JSON，**不返回 `updated_at`/`updated_by`**，而 `CloseRule` 要这两个字段。
按 P1 的顺序（复用 → 扩展 → 新建）这里是**扩展**：加一个返回带元信息的读方法，
既有方法与其测试一字不动。

---

## 3. 方案设计

### 3.1 方案选型

| 方案 | 结论 |
|---|---|
| A 只补两条端点 | ❌ **比现在更坏** |
| **B 端点 + 关单 Job，配置成为运行时真源** | ✅ 采用 |
| C 只做 Job，时长继续写死 | ❌ 需求 P-4.2.3 明确要可配 |

**为什么 A 比现在更坏**：现在是 404——保存失败，至少是个能被发现的症状。
补完端点之后变成「保存成功、值真的存进库了、而永远不生效」，
**没有任何症状**。运营会以为关单策略在按他配的跑，
直到某天发现一批订单挂了三天没关——那时没人会想到是这里。

死按钮的害处是骗人；一个存得下却没人读的配置，骗得更彻底。

### 3.2 唯一事实源怎么办：常量降级为默认值，而不是删掉

不动 `payTimeoutMinutes: 15`，改变它的**身份**：

```
从：关单时长的值
到：关单时长的出厂默认值（没配过时 SettingService 返回它）
```

`gen-rules-table.mjs` 那一行的「谁消费」加一句**「可被平台配置覆盖」**，
表本身仍是唯一事实源——它记的是默认值这件事，仍然只有一份。

**必须同时明确的一条**：C 端下单页倒计时目前读的是常量。
配置一旦能改，倒计时与真实关单时刻就会不一致——
**用户看着还剩 3 分钟，订单已经关了**。所以 C 端要改成读下单接口返回的 `payDeadlineAt`
（`mock.ts` 里已经有这个字段，接真后端时由服务端算）。
这条跨到 C 端，见 §3.5 分批。

### 3.3 模块设计

**新增**

| 模块 | 路径 | 职责 |
|---|---|---|
| `CloseRuleService` | `shop-core/.../trade/CloseRuleService.java` | 读（带默认值）/ 写（校验上下限）/ 留痕 |
| `OpsCloseRuleController` | `shop-core/.../trade/api/ops/` | 两条端点，挂 `Perms.ORDER_MODIFY` |
| `OrderAutoCloseJob` | `shop-core/.../trade/job/` | 按配置扫 `WAIT_PAY` 超时单并关闭 |

**扩展**

| 模块 | 变更 |
|---|---|
| `SettingService` | 加 `GetResult get(key, defaultJson, withMeta)`——既有 `get/put` 不动 |
| `scripts/perm-endpoint-map.mjs` | 补 `/ops/payments/close-rule` 的规则（现在匹配不到任何规则） |
| `packages/shared` 守卫 | 接通后从 `KNOWN_GAPS` 删掉那两行 |

### 3.4 核心接口与配置

```java
public record CloseRuleVO(int unpaidMinutes, int remindBeforeMinutes,
                          boolean autoRefundOnLateCallback,
                          Instant updatedAt, String updatedBy) {}
```

上下限**不硬编码在校验里**，与既有配置一样写成具名常量：

| 常量 | 值 | 理由 |
|---|---|---|
| `MIN_UNPAID_MINUTES` | 5 | 再短会把正在输密码的人关掉，等于自己制造掉单 |
| `MAX_UNPAID_MINUTES` | 1440 | 超过一天的占用，库存锁定（15 分钟）早就失效了 |
| `MAX_REMIND_LEAD` | 提醒必须 < 关单时长 | 否则提醒发在下单之前，永远不会触发 |

Job 的扫描周期取 `1 分钟`（`@Scheduled(cron)`），与既有两个 Job 一样写在类上。

### 3.5 分批：本批做什么、不做什么

**本批（配置真的驱动关单）**

- 两条端点 + `CloseRuleService` + `OrderAutoCloseJob`
- 常量降级为默认值 + 规则表注明可覆盖

**不在本批，且理由写明**

| 项 | 为什么押后 |
|---|---|
| `autoRefundOnLateCallback` **真的退款** | 支付回调目前是 Stub（`PayCallbackController.stubPaid`）。<br>对着 Stub 做自动退款验不出真东西，等微信真付落地一起做。<br>**本批仍存这个开关并在关单时读它**，只是迟到回调路径先只记日志 |
| C 端倒计时改读 `payDeadlineAt` | 跨到 c-app，且 c-app 当前整体走 mock（`VITE_USE_MOCK`）。<br>登记进 c-app 待办，与「翻转 mock 开关」同批做 |

押后的两项都会写进文档，不留在口头。

---

## 4. 测试策略

| # | 场景 | 层 |
|---|---|---|
| 1 | 没配过时返回默认值（= 常量 15），页面打得开 | 后端集成 |
| 2 | 保存后读回是新值，且 `updatedBy` 是操作人 | 后端集成 |
| 3 | ★★★ **改配置后 Job 真的按新时长关单**——改成 5 分钟，造一个 6 分钟前的待支付单，跑 Job，单被关 | 后端集成 |
| 4 | 上下限：`unpaidMinutes` 传 1 / 传 9999 / 提醒 ≥ 关单时长 → 拒绝 | 后端集成 |
| 5 | Job 只关 `WAIT_PAY`，不碰已支付 / 已取消 / 未到期的 | 后端集成 |
| 6 | 无 `order:order:modify` 的角色调 PUT → 403 | 后端集成 |
| 7 | 守卫：`KNOWN_GAPS` 里那两行删掉后仍绿 | packages/shared |

场景 3 是重点：**它是唯一能证明这个配置不是摆设的测试**。
写法上要真的调 Job 的入口方法，不能只断言 Service 读到了新值——
读到了新值而没人拿它去关单，正是 §3.1 方案 A 的那个坑。

场景 5 同样重要：关单是**不可逆**的写操作，扫错范围会关掉已付款的订单。

---

## 5. 风险与注意事项

| 风险 | 处置 |
|---|---|
| Job 在多实例部署下重复执行 | 与既有两个 Job 同口径（`SchedulingConfig` 已说明 api/ops 部署不解析 `@Scheduled`）；沿用不新造 |
| 关单是不可逆写操作 | 场景 5 专测扫描范围；Job 内按 `status = WAIT_PAY AND created_at < now - N` 精确圈定 |
| 改小配置后一批老单立刻被关 | 这是预期语义（配置即时生效），但要在保存时的确认文案里说明影响条数 |
| 配置与 C 端倒计时不一致 | §3.2 已点名；本批不改 C 端，登记进 c-app 待办 |
| 常量降级后有人以为可以删 | `gen-rules-table.mjs` 那一行注明「默认值，可被平台配置覆盖」 |

---

## 6. 实现任务

- [x] ~~T1 `SettingService` 扩展带元信息的读~~ —— **不需要**。既有惯用法
      （`PlatformConfigServiceImpl.stamp`）就是把 `updatedAt`/`updatedBy` 写进 JSON 本身，
      按「复用 → 扩展 → 新建」这里是复用。方案写「扩展」是没看清既有代码
- [x] T2 `CloseRuleService` + Impl：默认值 + 上下限校验 + 留痕
- [x] T3 `OpsCloseRuleController`：GET 挂 `ORDER_READ`、PUT 挂 `ORDER_MODIFY`
      （方案写的两条都挂 MODIFY —— 读写分开是权限码细化的第一条原则，客服要看得到）
- [x] T4 `OrderAutoCloseJob` + **下单读配置盖章**（方案漏了后半，见 §7）
- [x] T5 `OrderCloseRuleFlowTest` 6 条，三条关键路径各自验红
- [x] T6 常量降级为默认值（`TRADE_RULES.payTimeoutMinutes` 的注释改写）
- [x] T7 `perm-endpoint-map` 补两条规则 + `KNOWN_GAPS` 删两行 + 基线补两行
- [ ] T8 押后两项登记进各自待办文档
- [x] T9 全量回归（846 跑过，3 条失败属并行会话的提现功能）

---

确认记录：待确认

---

## 7. 实现时对方案的三处修正

写代码时撞到的、方案里判断错了的地方。记在这里而不是口头带过。

### 7.1 关单能力**早就存在**，缺的只是调用方

方案 §2.1 写「全仓搜 `closeOrder` / `autoClose` 零命中」，据此判断「后端根本没有自动关单」。

实际是 `OrderService.closeExpiredOrders(now)` 一直都在，而且完整
（关单 → 释放库存 → 退券 → 退积分，全部幂等）。**缺的是生产调用方** ——
全仓生产代码对它零引用，只有 `M3TradeFlowTest` 在手动调。

**与本轮的 Outbox 是同一个形状**：能力写完了、测试自己把它调起来，
于是缺的那个调度器在测试里完全不可见。搜索词差一点就会得出完全不同的结论，
而两种结论对应的工作量差了一个数量级。

### 7.2 配置的生效方式是「下单盖章」，不是「关单现算」

`ord_order.pay_deadline_at` 是下单那一刻写死的，`closeExpiredOrders` 只认这一列。
所以**方案 §3.3 只列了 Job，漏掉了真正关键的那一半**：
下单链路要读配置去算这个 deadline。只做 Job 的话，配置存进库了、Job 也在跑，
而每一单仍按写死的 15 分钟关 —— 正是方案 §3.1 判 A 案「比现在更坏」的那个形状，
只是换了个地方发生。

由此**方案里两条风险不成立**：

| 方案原文 | 实际 |
|---|---|
| §5「改小配置后一批老单立刻被关」 | 不会。老单的章早盖好了，改配置只影响此后的新单 |
| §3.2「配置与 C 端倒计时不一致」 | 不会。端上读的就是这枚章，一致性由构造保证。<br>**但常量注释仍要改**：端上必须读 `payDeadlineAt` 而不是 `payTimeoutMinutes` |

### 7.3 测试第一版**对一个什么都不做的 Job 是全绿的**

第一版只断言了「不该关的没被关」（未到期的、已支付的）。
红检时把 Job 方法体换成 `int n = 0;` —— **六条全绿**。

只测「不该发生的没发生」，对一个空实现是完全无害的，
而空实现正是这次要修的缺陷本身的形状。补了「到点后真的被关」才验出红。

同一次红检还暴露出 `createPaidOrder` 少走支付回调，订单其实停在 `WAIT_PAY` ——
「已支付的单被关掉了」那条断言真的红过一次，差点被当成扫描范围的生产缺陷去改。
