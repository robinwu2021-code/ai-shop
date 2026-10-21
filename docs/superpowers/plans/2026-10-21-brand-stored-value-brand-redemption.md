# P5 品牌储值、双账户与跨店核销 Implementation Plan

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

品牌活动创建发布、品牌账户入账、双账户选账、同主体跨店核销、C端issuer目录及消费通知定位。同步生成不可变清分事实；跨主体接受能力待P6账单闭环完成后开放。

依赖：P4。原型：s11, s12, s14, s15, s16, s17, s18, s32。

API主交付编号：BA01, BA02, BA03, BA04, BA05, BA06, BA07, BA08, MB04, US01, US02, US03, MP01, MP02。

新表：`sv_clearing_entry`。只迁移本阶段需要结构，不一次上线26张表。

### P5-T01 品牌发行与双账户查询

**Files（新增或改造位置，尚未实现）：**
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/storedvalue/issuer/StoredValueIssuerQueryService.java`
- `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/StoredValueActivityService.java`

**Interfaces:**
- Consumes: P1品牌准入、P2会员、P3账户、P4活动发布。
- Produces: `List<AvailableAccount> accounts(StoreExecutionContext store, VerifiedCustomer customer)`；一品牌一发行方，STORE与BRAND同客两个账户；无账户返回null，不因会员共享建空钱包。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P5-T02 选账贯穿订单与授权

**Files（新增或改造位置，尚未实现）：**
- `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStoredValueController.java`
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/EntitlementServiceOrderServiceImpl.java`

**Interfaces:**
- Consumes: 明确选中的issuer/account、Core报价及已有消费者授权。
- Produces: `ConsumptionPreview preview(StoreExecutionContext store, CustomerRef customer, AccountRef account, ServiceItems items)`；预览、授权、预占、扣减固定同一账户；不组合余额；旧请求只选本店，禁止自动优先扣品牌。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P5-T03 清分事实及消息/C端读取

**Files（新增或改造位置，尚未实现）：**
- `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/storedvalue/clearing/StoredValueClearingService.java`
- `backend/shop-app/src/main/java/ai/neargo/shop/portal/mp/MpStoredValueController.java`

**Interfaces:**
- Consumes: 原凭证和批次分配、issuer配置版本。
- Produces: `ClearingResult record(CommittedUsage usage, FrozenAllocations allocations); MyIssuerPage issuers(ConsumerContext context, Cursor cursor)`；生成清分事实防重，品牌C端只出现一次；通知按usage去重定位，不以消息发送证明扣款。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P5-T04 B/C双账户页面

**Files（新增或改造位置，尚未实现）：**
- `b-app/src/pages/stored-value-account/index.vue`
- `b-app/src/pages/stored-value/components/StoredValueRechargeSheet.vue`
- `c-app/src/pages/stored-value/index.vue`
- `c-app/src/pages/stored-value-records/index.vue`

**Interfaces:**
- Consumes: 前三任务接口。
- Produces: `MB04/BA/US/MP契约、issuer范围opaque引用`；本店60/品牌130分开；切店只改本店；品牌/门店方案底部明确切换；消费记录带实际发生店。

- [ ] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [ ] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [ ] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [ ] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [ ] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

## 阶段末统一验收用例

| 用例 | 输入或条件 | 必须结果 |
|---|---|---|
| `chooseOneWalletOnly` | 本店6000、品牌13000，选择品牌核销5000 | 品牌剩8000，本店仍6000；余额不足不暗中合并 |
| `switchStorePreservesBrandWallet` | A本店6000、B本店2000，同品牌账户13000 | 切B显示2000/13000；A的customerRef与选择状态不复用 |
| `wrongIssuerAuthorizationRejected` | 授权绑定品牌账户，提交时替换为本店账户或改金额 | 拒绝，原消费者授权不能移用；清分与通知不重复 |

新增后端测试类：`DualIssuerAccountIsolationTest`, `BrandStoredValueRedemptionTest`, `StoredValueIssuerNotificationTest`；测试归相应业务模块，必要的HTTP接缝用shop-app场景测试。B端行为测试：`b-app/tests/stored-value-dual-account-flow.test.ts`。

运行阶段末测试命令（目标类届时必须存在，不能把零测试当通过）：

```bash
# 在backend目录，使用JDK 21
mvn -pl shop-app,pay/pay-svc -am -DskipTests test-compile
mvn -pl shop-app,pay/pay-svc -am -Dtest=DualIssuerAccountIsolationTest,BrandStoredValueRedemptionTest,StoredValueIssuerNotificationTest -Dsurefire.failIfNoSpecifiedTests=false test
# 在仓库根目录
npm run type-check -w ai-shop-b-app
npm run test -w ai-shop-b-app -- tests/stored-value-dual-account-flow.test.ts
```

期望：编译成功；列出的测试类均有实际tests run>0且失败0；前端退出0。命令只跑列出的新测试，不省略-Dtest变成全量。C端在P5/P6发生改动时追加对应type-check及issuer页面定向用例，不重测无关C端页面。

## 上线与开放

Pay及Core选账/授权协议→B端品牌入口与办理/核销→C端H5/小程序；全部客户端和恢复能力就绪后开放同主体品牌储值，跨主体显示明确尚未开通原因。

**开放限制：**暂不开放跨经营主体品牌收款/核销；已有跨主体成员仍可共享会员。该限制按系统结算能力判断，不另建手工门店白名单；P6通过后同品牌有效门店完整开放。

**回滚：**品牌新资金发生后不可回滚到仅store选账版本。可停品牌新发行，保留双账户查询、历史核销及原单恢复；清分源事件必须可恢复补齐。

- [ ] 本阶段全部编码完成后，执行上面的阶段测试及本阶段MySQL增量演练，核对唯一键/JSON/金额等真实数据库语义；不以H2代替MySQL结构验收。
- [ ] 用真实API联调本阶段新流程及权限失败分支，B端模拟器或真机确认主要交互；已通过链路只在新改动影响处重测。
- [ ] 核对待部署提交、迁移备份、权限及开通能力；Core/Pay协议需兼容发布，不在窗口中交叉不兼容写入。
- [ ] 使用标准部署脚本发布适用服务与前端，检查health、进程版本、H5 VERSION及资源；涉及App/小程序时构建并上传对应包，正式提审/发布另按用户授权。
- [ ] 上线后只做版本、权限和本阶段代表性读写/原单恢复冒烟，登记阶段交付报告；出现新缺陷只测修复点及受影响接缝。
- [ ] 将状态标记为“已上线”，记录提交、服务/前端包版本、迁移号、测试结果、兼容限制和回滚说明后再进入下一阶段。
