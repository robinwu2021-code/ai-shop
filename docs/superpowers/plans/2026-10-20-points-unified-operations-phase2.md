# 统一积分操作第二阶段 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. User instructed implementation inline on 2026-10-20. Targeted tests only.

**Goal:** 支付域实现显式积分类型的统一操作 SPI，保持旧账本和接口兼容。
**Architecture:** shop-base 的 PointsOperationsPort 公开不含 pay 实体的契约；pay-domain 使用新的请求回执 journal（复用 pts_inbox）、类型化命名空间及既有发放/预占/退款内核。新业务接入在第三阶段。
**Tech Stack:** Java 21 / Spring / MyBatis / 既有 payTxManager / H2 隔离测试。
**Spec:** docs/superpowers/specs/2026-10-20-points-unified-operations-phase2-design.md

## Global Constraints
- 不改 B/C、规则服务、订单接线；不执行线上金融操作；不推送部署。
- 不做全量和并发测试；复用支付预算/账本/消息/退款证据。
- 旧构造函数和历史 JSON 缺省新字段时保持旧路径。

## Review Focus
- 新旧路径同类型同子单不得双发或双预占；跨类型使用不同凭证。
- requestId 与业务操作号分别去重，改参冲突；不能混淆 ACCEPTED 与到账。
- 新承诺仅 ACTIVE；历史释放、退款及已受理事实可在 DRAINING 履约。
- 受理需完整可信支付事实；不接受任意积分数字与伪造营销子单。
- 余额只读、空账户不创建；账户冻结不移动预占金额。

### Task 1: 固化契约与命名空间
- [x] 编写键碰撞、老 JSON 和新 DTO 参数测试；先运行失败。
- [x] 增加 PointsOperationsPort、PointsOperationKeys、PointsOperationJournal，复用 pts_inbox 回执，requestId/operation 双重去重。
- [x] GrantCommand/HoldCommand/RedeemQuery/ReleaseCommand 增加可选新字段，保留旧构造。

### Task 2: 内核兼容支持
- [x] 新发放凭证 ref 使用 V2 哈希，持仓仍按真实子单和类型定位，拒绝不同承诺复用。
- [x] 报价/预占支持显式类型，根持仓与 HOLD/RELEASE 凭证使用新标识；不改变真实子单号或支付快照。
- [x] 真实内核测试验证两种类型同业务号隔离、重放、改参及旧路径兼容。

### Task 3: 统一入口
- [x] PointsOperationsPortImpl 组合既有查询、发放队列、预占、退款证据和账户控制。
- [x] 受理赠送必须携带已支付事实，归一后交原队列；状态查询映射既有事件与持仓。
- [x] 生命周期事实、零现金及退款证据仍走原端口验证，不允许直接标记核销或退款成功。
- [x] 余额返回稳定 DTO；冻结保留版本、审计、请求幂等。

### Task 4: 验证与文档
- [x] 新接口使用真实支付内核和隔离数据库定向验证；回归旧预占/退款/发放相关非并发用例。
- [x] 更新接入文档，记录支持来源、受理状态、单位与历史兼容。
- [x] 全改动 review；修复重要问题；记录测试结果，不声明已部署。
