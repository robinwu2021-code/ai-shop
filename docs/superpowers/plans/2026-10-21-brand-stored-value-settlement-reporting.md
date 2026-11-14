# P6 清分结算、经营汇总与跨主体正式开放 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 按当前会话直接执行，未经明确要求不派生子代理。

**Goal:** 按业务模块分阶段交付B端品牌与门店双线储值，每阶段完成整体定向验收后独立上线。

**Architecture:** Merchant组织与授权、Core会员及活动、Pay发行/账务/清分明确分层。每阶段包含数据库、Java、接口和对应前端，不把前端全部拖到最后。跨主体品牌资金在结算闭环后才开放。

**Tech Stack:** Java 21 / Maven / Spring / MyBatis / MySQL / Flyway；Vue / uni-app / TypeScript / Vitest；现有outbox、payclient及统一UI组件。

**Spec:** [系统设计](../../technical/TDD-品牌与门店双线储值-系统设计.md) · [API](../../technical/TDD-品牌与门店双线储值-API清单.md) · [数据库](../../technical/TDD-品牌与门店双线储值-数据库设计.md) · [功能矩阵](../../requirements/B端品牌与跨店储值-功能矩阵与原型.md)

## Global Constraints

- tenant边界不放宽；品牌与主体无直接归属；主体与支付商户1:N，复用mch_payment_merchant。
- 品牌与单店发行分开；钱包唯一tenant/issuer/customer/currency，不按支付渠道拆分；不自动合并余额。
- 活动Core，权益账务Pay；金额规则仅一份真源；规范化JSON摘要，不依赖MySQL保存原文字节。
- 账号所有人不等于所有主体财务管理员；明确运营、发行、付款及收款确认的资源权限。
- 不改已执行迁移；开发前重新查目标分支和环境版本，不预占V544等版本号；原单与余额不得复制或清零。
- 阶段内每轮编译及本次基本定向单测，阶段末统一测试本阶段新增业务及受影响接缝；不跑全项目全量、不重复已通过且未受影响的回归。
- 每阶段通过后使用现有标准部署流程上线；包含B端页面，涉及C端则同步发布；版本及health必须核对。真实微信发送仍待模板及授权，不假称成功。
- 已授权历史一分钱测试不等于以后可任意造账；自动化金融验证优先独立测试库。服务器实账验证只使用后续明确授权的测试范围和金额。
- 只在隔离工作区开发，阶段提交整合到codex/ai-shop-codex；保留远端并发改动，不覆盖共享工作区。

## Review Focus

1. 同租户但不同资源授权不能越权；P1 rejectTenantWideAccess／merchantEntityMismatch锁定。
2. 会员身份重复和手机号复用不合并资金；P2 phoneCollisionDoesNotMerge锁定。
3. 已收款未知结果、余额映射及并发额度不能重复记账；P3 receivedUnknownResumesOriginal与P4硬限及额度测试锁定。
4. 改草稿、规则收紧、切门店及替换账户不得复用旧审批/授权；P4 changedDraftOrPolicyInvalidatesDecision、P5 wrongIssuerAuthorizationRejected锁定。
5. 支付登记不能冒充到账，统计不得重复计活动或混入别的发行方；P6 paymentIsNotReceipt／generateBillTwiceConsumesClearingOnce锁定。

## 阶段交付

活动/发行方/门店汇总、品牌主体清分账单、单店内部对账、争议调整、付款登记与独立收款确认；完成后开放跨经营主体品牌储值。

依赖：P5。原型：s19, s20, s21, s22, s31, s34。

API主交付编号：RP01, RP02, RP03, RP04, RP05, CL01, CL02, CL03, CL04, CL05, CL06, CL07, CL08, CL09, CL10, CL11, CL12。

新表：`sv_settlement_bill`, `sv_settlement_bill_item`, `sv_settlement_payment`, `sv_settlement_event`, `sv_activity_daily_summary`。只迁移本阶段需要结构，不一次上线26张表。

### P6-T01 来源批次报表

**Files（新增或改造位置，尚未实现）：**
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/storedvalue/report/StoredValueSummaryService.java`

**Interfaces:**
- Consumes: P5凭证、批次及清分事实。
- Produces: `IssuerSummary summarize(AuthorizedReportScope scope, ReportPeriod period)`；期初+期间变动=期末；品牌与单店独立；活动按原分配归因，人数笔数按原业务去重。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P6-T02 账期账单与内部对账

**Files（新增或改造位置，尚未实现）：**
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/storedvalue/settlement/StoredValueSettlementService.java`

**Interfaces:**
- Consumes: 完整清分水位和P5清分行。
- Produces: `GenerationResult generate(SettlementScope scope, Period period, CommandKey key)`；按issuer/payer/payee/currency/period唯一；item独占原行；同主体只内部对账，无假付款。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P6-T03 付款确认与争议调整

**Files（新增或改造位置，尚未实现）：**
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/storedvalue/settlement/StoredValueSettlementPaymentService.java`

**Interfaces:**
- Consumes: 已有账单、主体财务授权及私有凭据。
- Produces: `PaymentView register(PayerContext payer, PaymentCommand command); BillView confirmReceipt(PayeeContext payee, ReceiptCommand command)`；部分付款累计不超应付；收款主体岗位独立确认；已结算调整进入后续账期，渠道商户与主体不混用。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P6-T04 B端报表结算与跨主体放行

**Files（新增或改造位置，尚未实现）：**
- `b-app/src/pages/stored-value-summary/index.vue`
- `b-app/src/pages/stored-value-settlement/index.vue`
- `b-app/src/pages/stored-value-settlement-detail/index.vue`

**Interfaces:**
- Consumes: 前三任务及P5跨店链路。
- Produces: `RP/CL契约、CrossEntityReadiness检查结果`；品牌/单店Tab、独立财务查看范围、来源明细和手工结算；能力验收通过后完整开放品牌有效门店。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

## 阶段末统一验收用例

| 用例 | 输入或条件 | 必须结果 |
|---|---|---|
| `amountsAndScopeReconcile` | 品牌1000000+300000−260000=1040000；单店A48000/B2000 | 各自勾稽；门店余额合计50000但不可组合支付，不计入品牌余额 |
| `generateBillTwiceConsumesClearingOnce` | 同账期并发生成两次，同清分行重复消息 | 唯一账单及唯一bill_item，不重复计应付 |
| `paymentIsNotReceipt` | 应付120000，登记40000再80000，付款人尝试确认收款 | 登记不等于SETTLED；付款人拒绝，收款岗位确认合计120000后才SETTLED |
| `crossEntityBrandFlowCloses` | 主体甲结算、主体乙店充值办理、主体丙店服务核销 | 实际渠道商户属于收款主体，清分到丙，来源与账单可追溯，无伪造甲收款事实 |

新增后端测试类：`StoredValueClearingStatementTest`, `StoredValueSettlementIdempotencyTest`, `StoredValueReceiptAuthorizationTest`, `StoredValueActivitySummaryTest`；测试归相应业务模块，必要的HTTP接缝用shop-app场景测试。B端行为测试：`b-app/tests/stored-value-settlement-flow.test.ts`。

运行阶段末测试命令（目标类届时必须存在，不能把零测试当通过）：

```bash
# 在backend目录，使用JDK 21
mvn -pl shop-app,pay/pay-svc -am -DskipTests test-compile
mvn -pl shop-app,pay/pay-svc -am -Dtest=StoredValueClearingStatementTest,StoredValueSettlementIdempotencyTest,StoredValueReceiptAuthorizationTest,StoredValueActivitySummaryTest -Dsurefire.failIfNoSpecifiedTests=false test
# 在仓库根目录
npm run type-check -w ai-shop-b-app
npm run test -w ai-shop-b-app -- tests/stored-value-settlement-flow.test.ts
```

期望：编译成功；列出的测试类均有实际tests run>0且失败0；前端退出0。命令只跑列出的新测试，不省略-Dtest变成全量。C端在P5/P6发生改动时追加对应type-check及issuer页面定向用例，不重测无关C端页面。

## 上线与开放

Pay报表/账单worker和API→BFF与B端报表结算页面→完整跨主体联调→全品牌接受能力。渠道商户结算继续原支付系统，不冒充issuer内部清分。

**开放限制：**跨主体开放前必须可查询清分源、生成防重账单、付款登记/收款确认角色隔离及未结异常恢复。没有真实微信模板不阻断账务发布，但必须准确显示渠道未配置而非发送成功。

**回滚：**账单及已确认收款不能删表或还原金额。停新跨主体发行，保留原账户消费处理、收支、清分和结算恢复；差错用原单冲正和后续调整，不能直接改历史。

- [ ] 本阶段全部编码完成后，执行上面的阶段测试及本阶段MySQL增量演练，核对唯一键/JSON/金额等真实数据库语义；不以H2代替MySQL结构验收。
- [ ] 用真实API联调本阶段新流程及权限失败分支，B端模拟器或真机确认主要交互；已通过链路只在新改动影响处重测。
- [ ] 核对待部署提交、迁移备份、权限及开通能力；Core/Pay协议需兼容发布，不在窗口中交叉不兼容写入。
- [ ] 使用标准部署脚本发布适用服务与前端，检查health、进程版本、H5 VERSION及资源；涉及App/小程序时构建并上传对应包，正式提审/发布另按用户授权。
- [ ] 上线后只做版本、权限和本阶段代表性读写/原单恢复冒烟，登记阶段交付报告；出现新缺陷只测修复点及受影响接缝。
- [ ] 将状态标记为“已上线”，记录提交、服务/前端包版本、迁移号、测试结果、兼容限制和回滚说明后再进入下一阶段。

## 2026-10-24 implementation binding

Detailed execution table: ../../technical/任务表-品牌与门店双线储值-P6执行.md

P5 compatibility published; financial opening blocked P3. Execute the full P6 module batch then compile and one focused test chain. Include durable financial organization membership saga (P6-05), keep P5 direct-operation fence until coordinated replacement is complete. Settlement posting-created_at period prevents late-commit omission; preserve original occurred_at for attribution. No bank calls or historical-original writes. Candidate migrations575/576 must be rechecked before publication.
