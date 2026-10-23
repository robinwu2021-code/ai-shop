# P3 储值发行基础、渠道收款与旧账升级 Implementation Plan

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

每店映射单店发行方，保留已有账户、批次及凭证；新增渠道收款路由和发行方上下文，旧单店储值在新账务维度继续正常办理。品牌发行方可配置草稿但不激活收款。

依赖：P1, P2。原型：s13, s17, s18。

API主交付编号：IS01, IS02, IS03, IS04, IS05, RC01, RC02, RC03, RC04, RC05, RC06, RD01, RD02, IN01, IN06, IS06。

新表：`sv_issuer`, `sv_issuer_version`, `sv_issuer_store_access`, `sv_release`, `sv_release_tier`, `sv_issue_quota`, `sv_issue_quota_reservation`, `sv_customer_issuer_index`。只迁移本阶段需要结构，不一次上线26张表。

### P3-T01 发行方与收款路由

**Files（新增或改造位置，尚未实现）：**
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/storedvalue/issuer/StoredValueIssuerService.java`

**Interfaces:**
- Consumes: P1主体/商户可信授权、P2身份。
- Produces: `IssuerView configure(IssuerScope scope, IssuerConfigCommand command)`；一范围一发行方；指定主体旗下多个渠道商户，不按支付渠道拆钱包；线下不伪造商户。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P3-T02 旧账审计与唯一键切换

**Files（新增或改造位置，尚未实现）：**
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/storedvalue/migration/StoredValueIssuerMigrationService.java`

**Interfaces:**
- Consumes: 旧account/lot/voucher/hold及可信身份。
- Produces: `MigrationReport audit(MigrationBatch batch); CutoverResult cutover(VerifiedManifest manifest)`；建立映射和对账清单；保留原accountNo与金额；冲突阻断；条件式新唯一键替换。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P3-T03 单店原单与额度计数

**Files（新增或改造位置，尚未实现）：**
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/storedvalue/issuer/IssuerRechargeService.java`

**Interfaces:**
- Consumes: IssuerScope、原收款事实、既有Posting/Redemption。
- Produces: `RechargeProgress confirmOffline(StoreExecutionContext context, IssuerRechargeCommand command)`；新增issuer快照、原单resolve与取消栅栏；未到账额度提交/释放与账本原子一致。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P3-T04 B端发行设置及单店兼容

**Files（新增或改造位置，尚未实现）：**
- `b-app/src/api/stored-value-issuers.ts`
- `b-app/src/pages/stored-value/index.vue`
- `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStoredValuePlanRechargeController.java`

**Interfaces:**
- Consumes: IS/RC/RD接口，BRAND暂不可办理。
- Produces: `IssuerConfig/Recharges DTO，保持旧门店路径语义`；界面明确本店范围；旧接口映射单店，不显示或扣品牌资金；渠道收款凭据与实际主体一致。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

## 阶段末统一验收用例

| 用例 | 输入或条件 | 必须结果 |
|---|---|---|
| `migrationPreservesFacts` | 已有1分充值/核销及零余额账户，保留原单号 | 账户、批次、余额、冻结、凭证和幂等全部勾稽 |
| `channelDoesNotSplitWallet` | 同发行方顾客分别微信和支付宝充值 | 同账户；原收款商户和渠道分别冻结 |
| `receivedUnknownResumesOriginal` | 已收款后过账超时，重放同一幂等命令 | 只到账一次；不重复收款、不TTL释放已收款额度 |

新增后端测试类：`IssuerAccountMigrationTest`, `IssuerCollectionRouteTest`, `IssuerRechargeRecoveryTest`；测试归相应业务模块，必要的HTTP接缝用shop-app场景测试。B端行为测试：`b-app/tests/stored-value-issuer-context.test.ts`。

运行阶段末测试命令（目标类届时必须存在，不能把零测试当通过）：

```bash
# 在backend目录，使用JDK 21
mvn -pl shop-app,pay/pay-svc -am -DskipTests test-compile
mvn -pl shop-app,pay/pay-svc -am -Dtest=IssuerAccountMigrationTest,IssuerCollectionRouteTest,IssuerRechargeRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test
# 在仓库根目录
npm run type-check -w ai-shop-b-app
npm run test -w ai-shop-b-app -- tests/stored-value-issuer-context.test.ts
```

期望：编译成功；列出的测试类均有实际tests run>0且失败0；前端退出0。命令只跑列出的新测试，不省略-Dtest变成全量。C端在P5/P6发生改动时追加对应type-check及issuer页面定向用例，不重测无关C端页面。

## 上线与开放

先兼容数据库增量，再Pay、Core/BFF和B端发行设置。旧账审计及受控切换完成后启用单店issuer选账；未完成映射不切写。

**开放限制：**不把旧店余额迁成品牌余额；不提前删旧唯一键；已收款原单按原事实恢复。quota先接账务计数，门店政策发布治理在P4启用，P4后旧接口不再有绕过路径。

**回滚：**索引切换前可回退兼容版本；切换写入后保留issuer读写兼容，禁止直接回滚未改造旧程序。新增金融事实只能向前修复，不还原旧余额快照覆盖新交易。

- [ ] 本阶段全部编码完成后，执行上面的阶段测试及本阶段MySQL增量演练，核对唯一键/JSON/金额等真实数据库语义；不以H2代替MySQL结构验收。
- [ ] 用真实API联调本阶段新流程及权限失败分支，B端模拟器或真机确认主要交互；已通过链路只在新改动影响处重测。
- [ ] 核对待部署提交、迁移备份、权限及开通能力；Core/Pay协议需兼容发布，不在窗口中交叉不兼容写入。
- [ ] 使用标准部署脚本发布适用服务与前端，检查health、进程版本、H5 VERSION及资源；涉及App/小程序时构建并上传对应包，正式提审/发布另按用户授权。
- [ ] 上线后只做版本、权限和本阶段代表性读写/原单恢复冒烟，登记阶段交付报告；出现新缺陷只测修复点及受影响接缝。
- [ ] 将状态标记为“已上线”，记录提交、服务/前端包版本、迁移号、测试结果、兼容限制和回滚说明后再进入下一阶段。
