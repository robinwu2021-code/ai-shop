# P1 经营组织、品牌与支付商户 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking. 按当前会话直接执行，未经明确要求不派生子代理。

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

[P1详细设计](../../technical/TDD-P1-经营组织与品牌管理-详细设计.md)：5张表完整字段、21项API、授权来源、绑定状态机、幂等及上线验收。

## 阶段交付

交付经营管理空间；品牌、主体、支付商户、门店分别管理；门店可关联品牌，但此时不自动开放品牌资金或改变旧会员范围。

依赖：设计冻结及存量盘点。原型：s01, s02, s03, s04, s05, s06, s07, s08, s09, s24。

API主交付编号：MG01, MG02, MG03, MG04, MG05, MG06, MG07, BR01, BR02, BR03, BR04, BR05, BR06, BR07, ST01, ST02, ST03, ST04, ST05, MG08, MG09。

新表：`mch_management_space`, `mch_management_grant`, `mch_brand`, `mch_brand_store_binding`, `mch_brand_change`。只迁移本阶段需要结构，不一次上线26张表。

### P1-T01 管理空间与资源授权

**Files（已实现位置）：**
- `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/brand/ManagementScopeService.java`

**Interfaces:**
- Consumes: 现有认证身份。
- Produces: `ManagementContext resolve(ActorContext actor, String managementNo)`；显式品牌、门店、主体及支付商户授权；同tenant不等于全资源授权。

- [x] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [x] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [x] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [x] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [x] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P1-T02 品牌及门店配置

**Files（已实现位置）：**
- `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/brand/BrandService.java`

**Interfaces:**
- Consumes: ManagementContext。
- Produces: `BrandChangeResult change(StoreContext store, BrandChangeCommand command)`；品牌CRUD、有效绑定唯一性、影响检查及可恢复变更单；不直接改资金归属。

- [x] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [x] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [x] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [x] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [x] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P1-T03 支付商户候选

**Files（已实现位置）：**
- `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/brand/PaymentMerchantScopeService.java`

**Interfaces:**
- Consumes: 主体财务／配置授权。
- Produces: `List<PaymentMerchantBrief> candidates(EntityContext entity, String channel, String scene)`；复用MchPaymentMerchant，检查主体归属、ACTIVE进件及授权支付方式。

- [x] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [x] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [x] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [x] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [x] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P1-T04 B端顶层与门店入口

**Files（已实现位置）：**
- `b-app/src/pages/management/index.vue`
- `b-app/src/pages/brand-list/index.vue`
- `b-app/src/pages/brand-edit/index.vue`
- `b-app/src/pages/brand-detail/index.vue`

**Interfaces:**
- Consumes: 前三任务接口。
- Produces: `ManagementApi/BrandApi DTO与MG/BR/ST契约一致`；品牌、主体、支付商户、门店平级；store配置品牌底部弹框；同页不重复切店。

- [x] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [x] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [x] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [x] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [x] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

## 阶段末统一验收用例

| 用例 | 输入或条件 | 必须结果 |
|---|---|---|
| `rejectTenantWideAccess` | 账号只获A品牌管理权，请求B品牌及B主体支付商户 | 拒绝，不因tenant相同放行 |
| `twoActiveBrandBindingsConflict` | 同门店并发绑定两品牌 | 最多一个ACTIVE，另一个冲突 |
| `merchantEntityMismatch` | 主体甲选择主体乙的微信商户 | 拒绝；主体有效但渠道商户冻结也拒绝 |

新增后端测试类：`BrandScopeAuthorizationTest`, `PaymentMerchantOwnershipTest`；测试归相应业务模块，必要的HTTP接缝用shop-app场景测试。B端行为测试：`b-app/tests/brand-management-flow.test.ts`。

运行阶段末测试命令（目标类届时必须存在，不能把零测试当通过）：

```bash
# 在backend目录，使用JDK 21
mvn -pl shop-app,pay/pay-svc -am -DskipTests test-compile
mvn -pl shop-app,pay/pay-svc -am -Dtest=BrandScopeAuthorizationTest,PaymentMerchantOwnershipTest -Dsurefire.failIfNoSpecifiedTests=false test
# 在仓库根目录
npm run type-check -w ai-shop-b-app
npm run test -w ai-shop-b-app -- tests/brand-management-flow.test.ts
```

期望：编译成功；列出的测试类均有实际tests run>0且失败0；前端退出0。命令只跑列出的新测试，不省略-Dtest变成全量。C端在P5/P6发生改动时追加对应type-check及issuer页面定向用例，不重测无关C端页面。

## 上线与开放

Merchant/Core/BFF + B端组织页面。品牌门店关系为组织功能；旧单店储值正常运行，不触发资金承接。

**开放限制：**有存量资金或未完成单的品牌变更继续阻断或进入变更申请；Pay准入联动准备到P3，不能仅改brandNo获得资金权限。

**回滚：**仅增量表和组织数据，可关闭新入口并回退兼容应用；保留新增品牌及授权审计。若已有门店品牌关联，回退前确认旧程序不会据此扩权。

- [x] 本阶段全部编码完成后，执行上面的阶段测试及本阶段MySQL增量演练，核对唯一键/JSON/金额等真实数据库语义；不以H2代替MySQL结构验收。
- [x] 用真实API联调本阶段新流程及权限失败分支，B端模拟器或真机确认主要交互；已通过链路只在新改动影响处重测。
- [x] 核对待部署提交、迁移备份、权限及开通能力；Core/Pay协议需兼容发布，不在窗口中交叉不兼容写入。
- [x] 使用标准部署脚本发布适用服务与前端，检查health、进程版本、H5 VERSION及资源；涉及App/小程序时构建并上传对应包，正式提审/发布另按用户授权。
- [x] 上线后只做版本、权限和本阶段代表性读写/原单恢复冒烟，登记阶段交付报告；出现新缺陷只测修复点及受影响接缝。
- [x] 将状态标记为“已上线”，记录提交、服务/前端包版本、迁移号、测试结果、兼容限制和回滚说明后再进入下一阶段。

阶段验收已完成，发布证据见 [P1交付记录](../../technical/delivery/P1-经营组织与品牌管理-交付记录.md)。P2未启动。
