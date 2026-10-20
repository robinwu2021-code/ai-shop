# 多积分赠送规则第一阶段 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Inline execution requested by user; targeted tests only.

**Goal:** 多积分独立配置与统一查询试算，不更改业务记账接入。
**Architecture:** 复用 JDBC 规则存储，增加 shop-base 内部 SPI 与应用层适配实现（组合交易规则与支付类型查询），运营 controller 委托服务，前端使用现有列表抽屉组件。
**Tech Stack:** Java 21 / Spring / JDBC / React / TanStack Query。
**Spec:** docs/superpowers/specs/2026-10-20-multi-type-earning-phase1-design.md

## Global Constraints
- 不改 B/C；不做全量和并发测试；不自动改变订单默认类型。
- 同类型品类覆盖，不跨类型叠加；发布、审计和请求幂等复用。

## Tasks
1. 先写服务测试：多类型隔离、零默认、ACTIVE 校验、币种/品类校验、试算快照；运行确认失败；实现 PointsEarningRulePort / 服务及 controller 查询筛选，保持旧 API 兼容；定向后端验证。
2. 先写页面交互测试：新增类型选择、切换刷新列表/历史/试算、选型提交、类型与范围编辑锁定；确认失败；修改 contracts/HTTP/mock/页面/抽屉与预览；定向前端验证。
3. 更新接口生成产物及使用文档；定向测试、TypeScript、ESLint、生产构建、差异复核；记录实际验证结果。

## Review Focus
- 其他类型初始化不能复制订单类型赠送比例。
- 异步试算切换类型不能展示旧结果；类型筛选不能混用缓存及历史。
- 无默认积分时仍可操作其他 ACTIVE 类型；旧 API 省略 typeCode 兼容。
- 未发布草稿不影响匹配；不允许跨币种隐式折算。
- 历史已退出类型可读不可修改，不改变现有订单快照。
