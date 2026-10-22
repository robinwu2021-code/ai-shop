# P2 平台身份与品牌会员共享 Implementation Plan

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

## 阶段交付

同品牌门店识别同一会员；独立门店会员关系独立；本店私有资料保留。交付B端一个搜索入口和来源店展示，仍不开放品牌钱包。

依赖：P1。原型：s16, s17。

API主交付编号：MB01, MB02, MB03。

新表：`mbr_scope_member`, `mbr_store_profile`, `mbr_scope_legacy_link`。只迁移本阶段需要结构，不一次上线26张表。

### P2-T01 稳定身份及历史映射

**Files（已实现位置）：**
- `backend/shop-core/src/main/java/ai/neargo/shop/member/service/MemberScopeService.java`

**Interfaces:**
- Consumes: P1可信品牌范围。
- Produces: `ScopeMember enroll(MemberScope scope, VerifiedCustomer customer, String sourceStoreNo)`；复用canonical顾客与user绑定；人档不重复创建；旧主体会员到品牌／单店关系可审计映射。

- [x] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [x] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [x] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [x] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [x] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P2-T02 会员共享与私有资料

**Files（已实现位置）：**
- `backend/shop-core/src/main/java/ai/neargo/shop/member/service/MemberScopeQueryService.java`

**Interfaces:**
- Consumes: MemberScope。
- Produces: `MemberSummary find(StoreExecutionContext store, MemberSearch query)`；跨主体同品牌识别，跨品牌隔离，本店备注和顾问不共享。

- [x] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [x] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [x] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [x] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [x] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P2-T03 选择引用与兼容桥接

**Files（已实现位置）：**
- `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/StoredValueMemberSelectionService.java`

**Interfaces:**
- Consumes: P1范围、P2身份。
- Produces: `CustomerSelection select(StoreExecutionContext store, CustomerSelect request)`；扩展现有来源检查与短时引用；旧单店选账保持单店，不预造品牌账户。

- [x] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [x] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [x] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [x] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [x] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

### P2-T04 B端统一搜索与详情

**Files（已实现位置）：**
- `b-app/src/pages/stored-value-account/index.vue`
- `b-app/src/api/member-scopes.ts`

**Interfaces:**
- Consumes: MB01/02/03。
- Produces: `MemberAccountSummary/CustomerSelection`；联想+搜索按钮，搜索结果能识别未储值品牌会员；无新增会员重复入口。

- [x] 写本任务负责的新行为断言；测试名及数据采用下方阶段验收用例，不能写只检查实现字段存在的镜像测试。
- [x] 只运行本任务基本定向测试，确认旧行为不足；新类尚未存在时先确认编译错误指向本任务，而不是无关基线。
- [x] 实现以上接口与对应API/DTO、迁移/权限种子；迁移号在本阶段开工盘点后分配；所有接口范围按Spec校验。
- [x] 运行本轮受影响模块编译和基本定向测试；前端同步使用统一UI及三语词条，记录真实结果。
- [x] 提交本任务及更新任务表；阶段联调留本阶段编码完成后统一执行，不在任务之间重复整个链路。

## 阶段末统一验收用例

| 用例 | 输入或条件 | 必须结果 |
|---|---|---|
| `sameBrandDifferentEntities` | A/B店不同经营主体、同品牌，同一顾客 | B可识别，customerNo保持一致 |
| `crossBrandPrivateProfileHidden` | 同主体不同品牌，或读取另一店私有备注 | 不返回未授权数据 |
| `phoneCollisionDoesNotMerge` | 两个历史人档手机号相同但身份证据不足 | 输出冲突，不合并会员资金 |

新增后端测试类：`MemberBrandScopeTest`, `MemberIdentityConflictTest`；测试归相应业务模块，必要的HTTP接缝用shop-app场景测试。B端行为测试：`b-app/tests/member-brand-search.test.ts`。

运行阶段末测试命令（目标类届时必须存在，不能把零测试当通过）：

```bash
# 在backend目录，使用JDK 21
mvn -pl shop-app,pay/pay-svc -am -DskipTests test-compile
mvn -pl shop-app,pay/pay-svc -am -Dtest=MemberBrandScopeTest,MemberIdentityConflictTest -Dsurefire.failIfNoSpecifiedTests=false test
# 在仓库根目录
npm run type-check -w ai-shop-b-app
npm run test -w ai-shop-b-app -- tests/member-brand-search.test.ts
```

期望：编译成功；列出的测试类均有实际tests run>0且失败0；前端退出0。命令只跑列出的新测试，不省略-Dtest变成全量。C端在P5/P6发生改动时追加对应type-check及issuer页面定向用例，不重测无关C端页面。

## 上线与开放

Core会员/BFF + B端会员与搜索页面。已有单店储值继续使用原customer身份和本店账户。

**开放限制：**未确认历史身份、NULL归属及同手机号冲突不自动合并。品牌识别只给最小摘要，不开放他店私有服务档案或财务。

**回滚：**新会员关系与旧memberNo保留映射；停用品牌共享查询可回到旧单店读取，不删除新关系、不回写历史人档。

- [x] 本阶段全部编码完成后，执行上面的阶段测试及本阶段MySQL增量演练，核对唯一键/JSON/金额等真实数据库语义；不以H2代替MySQL结构验收。
- [x] 用真实API联调本阶段新流程及权限失败分支，B端模拟器或真机确认主要交互；已通过链路只在新改动影响处重测。
- [x] 核对待部署提交、迁移备份、权限及开通能力；Core/Pay协议需兼容发布，不在窗口中交叉不兼容写入。
- [x] 使用标准部署脚本发布适用服务与前端，检查health、进程版本、H5 VERSION及资源；涉及App/小程序时构建并上传对应包，正式提审/发布另按用户授权。
- [x] 上线后只做版本、权限和本阶段代表性读写/原单恢复冒烟，登记阶段交付报告；出现新缺陷只测修复点及受影响接缝。
- [x] 将状态标记为“已上线”，记录提交、服务/前端包版本、迁移号、测试结果、兼容限制和回滚说明后再进入下一阶段。

实施细节及实际DTO见[实施设计](../../technical/TDD-P2-品牌会员共享-实施设计.md)。
