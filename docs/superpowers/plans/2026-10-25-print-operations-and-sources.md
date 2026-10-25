# 打印运营与业务来源 Implementation Plan

状态：业务与测试代码、统一编译、集中软件验收及独立审查完成；保存独立分支，不自动合并或发布。实机与收银来源依赖另行登记。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 完成已确认下一批任务1～4：收口交付状态、运营打印页面、商家浏览器流程验收、通用业务打印来源。

**Architecture:** 继续使用统一PrintService、job/attempt与设备门控。运营端复用现有四个接口及权限；退款和充值凭证只能来自业务所有者已确认、可重放的事实，不查询可变现状拼造历史快照。收银必须先核对真实结算所有者，库存线下出库不作为收款事实。

**Tech Stack:** Java21/Spring/MyBatis、现有Outbox/Job、Next16/React19运营端、Vue/uni-app商家端、现有测试与浏览器工具。

**Spec:** 用户已确认的下一批任务1～4；`docs/technical/TDD-统一打印域兼容整合.md`、`docs/technical/design/打印中心-完整方案与模块封装.md`。

## Global Constraints

- 用户指定先完成代码和测试代码、再统一编译与集中验收；不在任务之间请求重复确认。
- 不连接真实打印机、不启用生产打印、不部署生产、不批量补打历史业务。
- 受理、模拟成功、PDF就绪不等于出纸；UNKNOWN不自动重试或释放设备门控。
- 身份沿用V1的64字符商户/门店/订单/用户引用；所有接口和查询严格校验租户、主体、门店。
- 既有迁移不可改写；新迁移版本先核对占用，不扩known基线，不创建第二套投递所有者。
- 各来源金额、币种、支付/退款状态均以所属业务合同为准，重放不改变冻结快照或重复建单。
- 软件验收与实机/私有COS/生产启用分别记账，缺少真实来源保持关闭并解释原因。

## Review Focus

1. 同编号跨租户/跨门店不能查到、导出或对账对方记录。
2. 异步失败、重复事实、退款部分完成和充值未入账不能产生错误成功凭证。
3. 运营切换门店、迟到请求和重复提交不能复用旧目标或旧幂等意图。
4. 浏览器样例与真实业务来源分别验收；无硬件时不宣称真实出纸完成。
5. 现金收款、赠送金额、权益和退款币种不能以假流水或当前余额替代原始事实。

---

### Task 1: 来源所有者核对与通用业务凭证

**Files:** 现有`trade/order/printing`、`printbridge/port`、退款结果与储值入账所有者；新增来源适配器/不可变事实持久化及专项测试，具体文件清单在执行台账确认。
**Interfaces:** 消费真实已提交的退款/充值事实，产出`PrintSourceFactsPort`（scenes/sourceTypes/resolveReference/snapshot/scan/executionGuard）与现有`PrintFactRef`，经现有Facade产生请求。

- [x] 核对退款、充值、收银的真正终态、冻结合同及事务边界；记录可接通路径和真实缺口。
- [x] 实现具备正式来源的REFUND_RECEIPT、RECHARGE_RECEIPT；按真实所有者合同处理CASHIER_RECEIPT，禁止用库存出库或模拟数据假接通。
- [x] 编写业务终态、跨店/跨租户、64字符身份、同事件重放、部分/失败结果、币种及冻结内容测试。
- [x] 更新场景能力查询、请求/事实对账和商家获取来源入口；缺来源保留禁用说明。

### Task 2: 运营打印管理页面

**Files:** 新增`ops-web/app/printing`页面及`ops-web/lib/api/print-*`类型/方法；修改现有nav、feature gates、延期交付清单；新增页面行为测试。
**Interfaces:** GET `/ops/print/health`、GET `/ops/print/incidents`、POST `/ops/print/reconciliations`、GET `/ops/print/operations/{operationNo}`；查询必须显式tenantNo/entityNo/storeNo，写入必须Idempotency-Key与reason。

- [x] 复用现有组件、认证、数据域与权限，完成健康、异常、对账提交和操作查询。
- [x] 明确UNKNOWN/未完成状态；对账默认试跑，实际执行要求明确范围和理由，复用原后端审计。
- [x] 编写只读/处置权限、表单校验、分页、请求过期/目标切换、重复提交、错误恢复测试。
- [x] 增加真实导航与功能开关，删除四条已交付接口的延期登记；生成契约和目录。

### Task 3: 商家浏览器验收

**Files:** 现有`b-app/src/pages/print-*`、`b-app/tests/print-center.test.ts`及必要的浏览器验收脚本/证据。
**Interfaces:** 使用现有页面与真实API合同；设备、模板、路由、模拟、PDF下载和异常动作按当前allowedActions及版本执行。

- [x] 准备隔离测试环境/固定夹具；记录模拟API浏览器与真实后端HTTP证据的边界。
- [x] 浏览器检查设备登记、模板创建发布、路由、订单来源、模拟详情、PDF下载、异常及权限拒绝。
- [x] 修复实际发现的问题并保留可重跑步骤、截图及测试证据；不连接真实设备。

### Task 4: 状态收口与集中验收

**Files:** `docs/technical/acceptance/print-center/`、实现清单及生成索引。

- [x] 更新旧记录的历史标记与最新证据入口，记录ba44c7f0c正常推送及6213项无失败，保留历史故障原文。
- [x] 所有业务和测试代码完成后统一编译，然后执行后端/前端专项、类型检查与浏览器验收。
- [x] 检查生成物、权限、API孤儿、功能开关与数据域；独立审查来源真实性与运营误操作。
- [x] 按真实结果登记完成/未验收/依赖项，提交独立开发分支；不自动生产发布。

验收结果见 `docs/technical/acceptance/print-center/operations-and-sources-2026-10-25.md`。CASHIER没有真实POS结算所有者，按计划保持关闭；旧版现金退款与不满足凭证合同的金额组成也不冒充已覆盖。
