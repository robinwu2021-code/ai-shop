# 线上交易赠送积分规则改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将默认/品类交易赠送规则从积分设置拆分，形成可追溯的服务端匹配和独立运营入口。
**Architecture:** 交易域管理规则及快照，支付域继续提供兑换和记账能力，运营端复用列表、抽屉组件。
**Tech Stack:** Spring、MyBatis、MySQL、Next.js、React Query。
**Spec:** docs/superpowers/specs/2026-10-20-online-points-earning-design.md

## Global Constraints
- 保留订单默认积分，不修改 B/C，不做跨币种和营销叠加。
- 新增表与字段兼容历史；仅定向测试，不做全量与并发。

## Review Focus
- 显式 0% 不能落回默认；停用覆盖应恢复继承。
- 保存草稿不能改变新订单使用的已生效修订。
- 重复请求和旧版本修改不能覆盖规则。
- 历史固定赠送与订单快照不能变成新的比例。
- 预算不足重试不能重复入账，退款事实优先于补发。

### Task 1: 规则服务及接口
- [x] 测试品类继承、明确不赠送、版本与幂等。
- [x] 新增交易规则、修订存储、管理接口及预览接口；复用平台类目。
- [x] 定向验证服务端。

### Task 2: 订单及支付链路
- [x] 测试规则快照和旧订单兼容。
- [x] 接入服务端商品品类匹配及规则修订快照；复用现有发放/完成/退款事实。
- [x] 检查预算不足的受控重试和追踪。

### Task 3: 运营 UI
- [x] 测试独立规则页面与类型页移除旧编辑入口。
- [x] 新增菜单、列表、编辑/详情抽屉、计算预览，完善 API 类型和模拟实现。
- [x] 定向测试和类型检查。

### Task 4: 兼容与交付
- [x] 补充菜单迁移、接口及使用记录；审核本次差异。
- [x] 对接独立积分发布流程，构建和上线结果以专项发布日志、线上 VERSION 为准，不混入其他任务。
