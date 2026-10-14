# TDD-订单售后三端断点修复

状态：已确认（用户 2026-10-14「继续梳理各端的状态关联…然后调整前端代码」）
关联需求：docs/technical/TDD-售后规则-审核与部分退款.md（售后规则本体）；
分析底稿：docs/superpowers/specs/2026-10-14-订单与售后-三端状态关联.md（§4 断点清单、§5 本轮修复）
创建：2026-10-14 · 最后更新：2026-10-14

档位：1 · 依据：三端状态关联 §4 · 动了契约：i18n 词条（三端）、`POST /mp/order/{no}/after-sale` 请求体加可选字段

## §0 对账一 · 需求 → 设计

| AC | 需求（Given / When / Then） | 落点 |
|---|---|---|
| AC1 | 生鲜已签收，买家选「质量问题」申请 → 后端按原因码放行（此前发的是中文文案，任何原因都被拒） | 后端 `ApplyAfterSaleReq.reasonCode` + `AfterSaleServiceImpl.apply` 判码；C 端 `api.applyAfterSale` 发 `reasonCode` |
| AC2 | 不带 `reasonCode` 的老客户端 → 行为与现在一致（`reason` 当码判） | `AfterSaleServiceImpl.apply` 回落 |
| AC3 | `reasonCode` 不在 7 个原因码内 → BAD_REQUEST | 同上 |
| AC4 | 买家「申请平台介入」填的理由 → 后端收到（`appeal`） | C 端 `http.ts raiseDispute` |
| AC5 | C 端开票/查票用**主单号** | C 端 `order/index.vue` 用 `payOrderNo`；`Order.payOrderNo` 类型补声明 |
| AC6 | C 端 `WAIT_OFFLINE_PAY` 订单、`WAIT_RECEIPT` 售后、`PAID` 下一步说明不露键名 | C 端三语词条 |
| AC7 | 用主单号打开（收银台「查看订单」）：单店 → 自动切到该订单视图（售后/码/轨迹齐全）；多店 → 不给售后入口 | C 端 `order/index.vue load()` / `canAfterSale` |
| AC8 | 售后被驳回或已关闭 → C 端仍可再申请（REFUNDED 暂不放开，见分析底稿 §6-1/2） | C 端 `order/index.vue` 入口条件 |
| AC9 | B 端售后卡带「一并退回」提示时，同意/驳回/确认收货按钮照常出现 | B 端 `after-sale/index.vue` 拆 v-if 链 |
| AC10 | B 端核定面板预填并封顶于**买家申请额** | B 端 `paidMaxOf` → `capOf` |
| AC11 | B 端售后 CLOSED 有说明；已核定显示核定额 | B 端 `after-sale/index.vue` + `afterSale.closedHint` |
| AC12 | 运营端已付款单不再出现「代客取消」/干预到「已取消」（后端必拒） | ops-web `ORDER_TRANSITIONS.PAID` |
| AC13 | 运营端干预到「已退款」，或从待付款类干预到「已付款」时，表单内明示「只改状态，不退款/不收款」 | ops-web `exception-tab.tsx` + `copy.ts` |

**孤立项**：无。分析底稿 §4 中资金类 B1/B4、F8 与 P1 后端项不在本 TDD（随写入门 / 行级退款接线做）。

## §1 现状与影响面

- 会被改到的：C 端订单详情、售后申请、订单列表（不改）；B 端售后页；运营端异常单干预、代客取消；后端售后申请入口。
- 明确不受影响：售后状态机、退款执行、结算、订单状态投影；B/运营端订单列表文案（P1，本轮不动）。

## §2 方案

### 契约变更
- 端点：`POST /mp/order/{no}/after-sale` 请求体加可选 `reasonCode`（7 个原因码之一）。不带时沿用 `reason` 判码。
- i18n 词条：
  - c-app（zh-CN/en/ar）：`orderView.WAIT_OFFLINE_PAY`、`orderStatus.WAIT_OFFLINE_PAY`、`afterSale.status.WAIT_RECEIPT`、`afterSale.statusHint.WAIT_RECEIPT`；`order.next.WAIT_FULFILL`（死键，后端只发 PAID）→ 改名 `order.next.PAID`
  - b-app（zh-CN/en/ar）：`afterSale.closedHint`
  - ops-web（copy.ts zh/en）：新增 `interveneMoneyWarn`；删除 `cancelRefundHint`（已付款不再能代客取消，分支不可达）
- 库表 / 权限码 / 配置项：无。

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `backend/.../trade/api/mp/MpTradeController.java` | `ApplyAfterSaleReq` 加 `reasonCode` |
| 修改 | `backend/.../trade/service/AfterSaleService.java` | `ApplyCommand` 加 `reasonCode`，保留 4 参构造 |
| 修改 | `backend/.../trade/service/impl/AfterSaleServiceImpl.java` | 判码用 `reasonCode ?: reason`；非法码 BAD_REQUEST |
| 修改 | `backend/shop-app/src/test/.../scenario/M5AfterSaleFlowTest.java` | AC1–3（复用现成的造单/拨进度工具，不另起类） |
| 修改 | `c-app/src/api/http.ts`、`contract.ts`、`mocks/aftersale.ts` | applyAfterSale 带 reasonCode；raiseDispute 发 appeal |
| 修改 | `c-app/src/pages/after-sale/index.vue` | 传 `reason.value` 为码 |
| 修改 | `c-app/src/pages/order/index.vue` | AC5/7/8 |
| 修改 | `packages/shared/src/types/trade.ts` | `Order.payOrderNo?` |
| 修改 | `c-app/src/i18n/locale/{zh-CN,en,ar}.ts` | AC6 |
| 修改 | `b-app/src/pages/after-sale/index.vue`、`b-app/src/i18n/locale/*` | AC9–11 |
| 修改 | `ops-web/lib/types/order.ts`、`app/orders/{exception-tab,proxy-tab}.tsx`、`app/orders/copy.ts` | AC12–13 |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 / 验证 | 跑过 | 消融 |
|---|---|---|---|
| AC1 | `M5AfterSaleFlowTest#fresh_received_reason_code_quality_with_text_allowed` | ✅ 40/40 绿 | 实现前先跑：`expected:<0> but was:<20009>` 红 ✅ |
| AC2 | `M5AfterSaleFlowTest#fresh_received_non_quality_rejected` / `#fresh_received_quality_allowed`（老客户端只发 reason） | ✅ | —（既有用例，行为不变） |
| AC3 | `M5AfterSaleFlowTest#unknown_reason_code_rejected` | ✅ | 实现前先跑：`expected:<10400> but was:<0>` 红 ✅ |
| AC4 | 类型：`contract.raiseDispute(afterSaleNo, appeal)` + `http.ts` 发 `{ appeal }`；`vue-tsc` 绿 | ✅ | —（无端上可挂载的断言点，靠字段名与后端 `EscalateReq(appeal)` 对齐） |
| AC5 | `c-app/tests/order-page.test.ts` · AC5 开票按主单号查 | ✅ 19/19 | 页面换回改前版本 → 红 ✅ |
| AC6 | i18n 闸门（三语键集一致 + 引用必存在）；`order-view.test.ts` 六个状态都写了说明（改按契约 PAID） | ✅ pre-push 全绿 | 测试原先断言死键 WAIT_FULFILL，已改 |
| AC7 | `order-page.test.ts` · AC7 单店切订单视角 / 多店不给售后入口 | ✅ | 页面换回改前版本 → 两条都红 ✅ |
| AC8 | `order-page.test.ts` · AC8 驳回/关闭可再申请、已退款不放开 | ✅ | 页面换回改前版本 → 红 ✅ |
| AC9 | `b-app/tests/after-sale-approve.test.ts` · 提示行之后另起 v-if 链 | ✅ 187/187 | 页面换回改前版本 → 红 ✅ |
| AC10 | `after-sale-approve.test.ts` · `approveCap` 取申请额 | ✅ | 函数为新抽出，旧页面不调用它，消融不适用；断言本身对「取实付」实现为红 |
| AC11 | `vue-tsc`（b-app）+ i18n 闸门（`afterSale.closedHint` 三语） | ✅ | — |
| AC12–13 | ops-web `tsc` + pre-push ops-web 722 条断言 0 红 | ✅ | — |

**整套闸门**：`bash .githooks/pre-push </dev/null` @ 75e0b17c5 → 14 道全过；后端全量 3403 跑 / 0 红。

## §6 对账二 · 设计 → 实现

主提交 `442160057`（23 个文件）与 §2 模块设计逐行一致。偏差：

| 差异 | 说明 |
|---|---|
| 测试落在 `M5AfterSaleFlowTest` 而非新类 | 复用现成的造单 / 拨履约进度工具（§2 已同步改） |
| 新增 `b-app/src/shared/approve-cap.ts` | 为 AC10 可单测而抽出（b-app 无组件测试设施） |
| 附带：生成物重生成（openapi ×2、API 详情、词条/常量、表清单、分层清单、组件/页面规范、schema-test.sql） | 闸门要求，内容只含本轮与订单内核的改动；`规范-页面.md` 工作区未提交版与 HEAD 生成结果逐字相同后才提交 |
| 附带：c-app 4 个测试文件跟上 10-10 原型重排 / 极速退废除 | 远端分支上本就 5 条红，不修推不上去 |
| 附带：新内核守卫欠账（BAD_REQUEST 带参、ord_shipment 枚举、waybill_no 同名、mermaid） | 订单服务 2.0 内核提交时漏的闸门项 |
| 附带：`known-handrolled-ui.txt` 登记 c-app 售后页系统弹框 | 保订阅授权的点击链，理由写在清单里 |
