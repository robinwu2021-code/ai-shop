# P4 门店活动、多档位、上层管控与审批 Implementation Plan

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
- 按用户最新要求，阶段内先整体完成编码和编译，随后统一执行本阶段新增业务及受影响接缝的定向测试链；失败只复测修复项及受影响接缝，不跑全项目全量、不重复无关已通过回归。
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

完整交付单店多档位活动、顶层限制、提交审批、批准发布和停止新发行；品牌门店仍能发本店活动。品牌活动使用的协调内核同时准备，但品牌资金入口留P5。

依赖：P3。原型：s10, s23, s25, s26, s27, s28, s29, s30, s33。

API主交付编号：PL01, PL02, PL03, PL04, PL05, SA01, SA02, SA03, SA04, SA05, SA06, SA07, SA08, AP01, AP02, AP03, IN02, IN03, IN04, IN05。

新表：`sv_issue_policy`, `sv_issue_policy_version`, `pmt_sv_release_link`, `pmt_sv_approval`。只迁移本阶段需要结构，不一次上线26张表。

### P4-T01 Pay策略与办理额度约束

**Files（新增或改造位置，尚未实现）：**
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/storedvalue/policy/StoredValueIssuePolicyService.java`

**Interfaces:**
- Consumes: P1管理范围、P3额度池。
- Produces: `EffectivePolicy evaluate(StoreIssueScope scope, Currency currency); IssuePermit reserve(RechargeIntent intent)`；全局/品牌门店/单店约束从严；审批OR、允许AND；发布和实际办理均执行，锁内不RPC Core。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P4-T02 Core活动与发布恢复

**Files（新增或改造位置，尚未实现）：**
- `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/StoredValueActivityService.java`

**Interfaces:**
- Consumes: Pay prepared release/hash、策略评估。
- Produces: `ActivitySubmission submit(ActivityScope scope, DraftRevision draft, ActorContext actor)`；复用pmt_activity及版本，金额在Pay；单店范围固定；PREPARING/PAY_READY/ACTIVE持久恢复。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P4-T03 审批及旧接口封口

**Files（新增或改造位置，尚未实现）：**
- `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/StoredValueActivityApprovalService.java`
- `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStoredValuePlanController.java`

**Interfaces:**
- Consumes: 精确draftHash和最新EffectivePolicy。
- Produces: `ApprovalResult decide(ApprovalContext context, ApprovalDecision command)`；同人自批默认拒绝；草稿变化STALE；旧plan创建只能通过治理适配或明确要求升级。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P4-T04 B端治理闭环

**Files（新增或改造位置，尚未实现）：**
- `b-app/src/pages/stored-value-policy/index.vue`
- `b-app/src/pages/stored-value-approval/index.vue`
- `b-app/src/pages/stored-value-plan-edit/index.vue`

**Interfaces:**
- Consumes: PL/SA/AP接口。
- Produces: `Policy/Activity/Approval契约和底部档位组件`；100送30被20%阻断，调整后提交待审批；未批准不出现可办理；批准后仍仅本店。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

## 阶段末统一验收用例

| 用例 | 输入或条件 | 必须结果 |
|---|---|---|
| `strictestPolicyAndNoApprovalBypass` | 全局30%、品牌门店20%，门店100送30 | 发布/办理/审批均拒绝；100送20可按规则提交 |
| `changedDraftOrPolicyInvalidatesDecision` | 申请后改金额，或策略从20%收紧为10% | 旧审批STALE或BLOCKED_POLICY，不激活Pay发行配置 |
| `publishAckLostDoesNotDoubleActivate` | Pay激活成功Core没收到ack后恢复 | 原release只激活一次；未知阶段不对外假称可办理 |

新增后端测试类：`StoreIssuancePolicyTest`, `StoredValueApprovalRevisionTest`, `StoredValuePublishRecoveryTest`；测试归相应业务模块，必要的HTTP接缝用shop-app场景测试。B端行为测试：`b-app/tests/stored-value-governance-flow.test.ts`。

运行阶段末测试命令（目标类届时必须存在，不能把零测试当通过）：

```bash
# 在backend目录，使用JDK 21
mvn -pl shop-app,pay/pay-svc -am -DskipTests test-compile
mvn -pl shop-app,pay/pay-svc -am -Dtest=StoreIssuancePolicyTest,StoredValueApprovalRevisionTest,StoredValuePublishRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test
# 在仓库根目录
npm run type-check -w ai-shop-b-app
npm run test -w ai-shop-b-app -- tests/stored-value-governance-flow.test.ts
```

期望：编译成功；列出的测试类均有实际tests run>0且失败0；前端退出0。命令只跑列出的新测试，不省略-Dtest变成全量。C端在P5/P6发生改动时追加对应type-check及issuer页面定向用例，不重测无关C端页面。

## 上线与开放

Core活动与Pay策略/发布协议成对上线，再B端管控、审批及门店活动页面；本阶段旧新单店充值路径全部执行同一管控。

**开放限制：**超硬限制不可审批绕过；审批绑定草稿修订与hash，批准再查新规则；Core与Pay未完成激活确认不显示可办理。停止仅阻断新充值，不冻结旧余额。

**回滚：**已生成审批、策略或发布版本不可删除；只能停新发、保留原单及版本恢复。旧程序若不识别治理规则不能回退成为写入口。

- [ ] 本阶段全部编码完成后，执行上面的阶段测试及本阶段MySQL增量演练，核对唯一键/JSON/金额等真实数据库语义；不以H2代替MySQL结构验收。
- [ ] 用真实API联调本阶段新流程及权限失败分支，B端模拟器或真机确认主要交互；已通过链路只在新改动影响处重测。
- [ ] 核对待部署提交、迁移备份、权限及开通能力；Core/Pay协议需兼容发布，不在窗口中交叉不兼容写入。
- [ ] 使用标准部署脚本发布适用服务与前端，检查health、进程版本、H5 VERSION及资源；涉及App/小程序时构建并上传对应包，正式提审/发布另按用户授权。
- [ ] 上线后只做版本、权限和本阶段代表性读写/原单恢复冒烟，登记阶段交付报告；出现新缺陷只测修复点及受影响接缝。
- [ ] 将状态标记为“已上线”，记录提交、服务/前端包版本、迁移号、测试结果、兼容限制和回滚说明后再进入下一阶段。
