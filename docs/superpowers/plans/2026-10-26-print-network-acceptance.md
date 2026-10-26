# 打印网络故障补验 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans inline. 已获用户“继续”授权，复用现有隔离工作区。

**Goal:** 收紧真实HTTP负向断言，并验证断网、响应丢失后的原意图重试不重复创建任务。

**Architecture:** 仅在显式启用的loopback验收代理注入一次性故障；请求丢弃不得触达后端，响应丢弃必须先读完后端响应。记录请求体/幂等键哈希及操作号，不记录令牌或下载授权。生产打印实现保持现有契约。

**Tech Stack:** Node内置HTTP/test、既有Java21隔离验收服务器、真实Chrome。

**Spec:** `docs/technical/acceptance/print-center/release-2026-10-26.md`及本轮已确认的软件补验范围。

## Global Constraints

- 先完成本批代码再集中编译验收；无真实打印、云厂商或生产库操作。
- 不改历史迁移，不扩大已知失败基线，不跳过推送闸门。
- API验收与测试代理均限定loopback；合同夹具不可作为真实后端证据。

## Review Focus

- 500、缺少业务码、错误key不可通过负向验收。
- 丢请求与丢响应证据明确区别；同意图恢复复用原幂等键及操作号。
- 故障控制默认关闭，不能代理任意目标或注入打印投递。
- 网络异常和客户端断开不得使验收代理崩溃或伪造成功。
- 全库MySQL不以H2/部分表/改写历史冒充通过。

### Task 1: 精确错误契约与一次性网络故障代理

**Files:** `scripts/verify-print-release-http.mjs`, `scripts/print-browser-acceptance.mjs`, `scripts/lib/print-acceptance.mjs`, `scripts/tests/print-acceptance.test.mjs`。

- [x] 写测试证明500/缺码不能算权限或冲突；请求丢弃不触达后端，响应丢弃后重试得到相同操作号，日志脱敏。
- [x] 运行Node测试观察失败，再实现严格断言和仅允许三个安全命令端点的一次性代理故障。
- [x] Node测试、脚本语法检查通过；编译既有后端测试，构建两端。

### Task 2: 真实软件补验及MySQL基线诊断

- [x] 启动显式隔离后端，运行精确HTTP验收。
- [x] Chrome点击模拟/运营试跑，注入未送达与响应丢失，恢复重试；记录哈希、操作号、界面证据。
- [x] 检查完整MySQL安全基线路径，保留真实阻断，不覆盖上批证据。
- [x] 全分支独立复核、提交及统一分支合并完成；正常全仓闸门通过。

最终提交号由正常推送后实际远端核对，并在完成消息记录。
