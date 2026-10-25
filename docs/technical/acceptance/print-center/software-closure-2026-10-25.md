# 订单支付打印软件闭环 · 2026-10-25

范围：落实 PRINT-01～04 的软件闭环与验收；不启用生产打印、不连接真实设备，不代表全仓或实机验收通过。

基线：独立分支 `codex/order-printing`，`369c5466b` 已推送。当前飞鹅 COMPACT_V2 被适配器主动拒绝；Windows、COS、58/80mm 实机待资源。仅 ORDER_PAID 有正式来源适配器，其余19场景为模板/模拟能力。

## 实施与验收计划

沿用完整方案、API设计和现有冻结模型，不增加契约。按用户要求先写完本轮代码和用例，再统一编译、集中执行。

- [x] 真实订单事实与商家接口集成：新增 `PrintOrderSoftwareFlowTest`，覆盖数据库订单快照/Outbox事实事件、商家身份和数据域、重复消费、模拟结果、PDF下载及签名篡改；仅外部设备不参与。
- [x] 通知审查与必要修复：检查完成事件来源和通知开关、收件人、重复投递；新增 `PrintNotifyBridgeTest`。完成证据不足不得伪造完成事件。
- [x] 集中编译与专项验收：打印模块、订单事实、启用装配、新增闭环与通知测试、商家打印交互规则；记录真实数量及失败原因。
- [x] 更新本清单和独立分支验收状态；全仓检查债务另列，不以打印专项替代。

## 验收边界

- 订单支付确认后的冻结事实必须与事件在同一事务内持久化；重复投递不得增加事实或生产任务。
- 模拟执行和PDF导出不产生真实设备投递，不将生产任务改为完成。
- PDF访问要求当前权限和短期签名；错误签名与跨店访问应拒绝。
- 现有领域验收负责实际任务的接收/完成证据、冻结补打；本轮增加宿主应用接线与HTTP验证。
- 外部PUSH是加速通道，不能声称具有设备侧严格恰好一次语义；站内消息通过事件+收件人去重。

## 执行记录

本轮未新增接口、库表、权限码和配置。仅修复通知桥接并补充应用级验收。

### 已修复

1. `PrintNotifyBridge` 原来无论 `channels` 是否包含 `IN_APP` 都创建站内信。现在按冻结策略分别选择站内信/PUSH；空通道不解析收件人、不发送通知。
2. 原来消费者失去 Outbox 租约后仍可能写入下一条站内消息。现在逐个收件人、逐次副作用前检查租约，写站内信后丢失租约也不会继续 PUSH。

PUSH为外部加速通道，重投或网络结果未知时不保证终端严格恰好一次。站内信使用事件号+收件人的稳定去重键，应用数据库验证重复投递后仅有一条记录。

### 集中验证结果

统一编译（退出码0）：

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f backend/pom.xml -pl shop-app -am test-compile -DskipTests
```

最终打印专项回归（退出码0；72项，失败0、错误0、跳过0；此命令也重新编译最终代码和用例）：

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f backend/pom.xml -pl shop-app,print/print-agent -am test \
  -Dtest='Print*Test,OrderPrintFactRecorderTest,CompactReceiptAcceptanceTest,ReceiptSoftwareAcceptanceTest,AgentDurabilityTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

| 验收类 | 项数 | 结果 |
|---|---:|---|
| PrintStatePolicyTest / PrintFontInitializationTest | 7 | 通过 |
| CompactReceiptAcceptanceTest | 9 | 通过 |
| PrintDatabaseAcceptanceTest | 8 | 通过 |
| ReceiptSoftwareAcceptanceTest | 26 | 通过 |
| AgentDurabilityTest | 6 | 通过 |
| OrderPrintFactRecorderTest | 4 | 通过 |
| PrintEnabledAssemblyTest | 3 | 通过 |
| PrintOrderSoftwareFlowTest（本轮新增） | 2 | 通过 |
| PrintNotifyBridgeTest（本轮新增） | 7 | 通过 |

商家端：`cd b-app && npm test -- --run tests/print-center.test.ts`，5项通过。

消融：临时将 `PrintNotifyBridge` 恢复为 HEAD 中原实现，运行 `PrintNotifyBridgeTest`，7项中4项按预期失败：空通道、仅PUSH、发送前租约已失效、站内信后丢失租约。随后恢复修复并运行上述72项最终回归通过。

### 新增应用级覆盖及边界

- 使用真实登录、入驻审核、商家身份与当前门店权限；未替换打印来源、存储或HTTP鉴权。
- 订单夹具从“已确认支付”边界进入 `recordPaid`，真实持久化冻结事实和Outbox；源事务回滚后两者均不存在。此用例不覆盖下单和支付通道全流程。
- 重复记录及事件重投只有一条事实、一条请求。默认自动策略关闭时请求保持跳过，不反向补打历史订单。
- 修改订单商品后，原事实不变；使用真实FACT模拟，解析最终下载PDF，确认原商品、88.00金额和订单号存在，修改后的商品名不存在。
- 模拟重投幂等，模拟及导出未创建生产任务，也未产生 `PRINT_BATCH_RESOLVED`。
- 签名篡改返回明确拒绝；其他店主不能查询/领取导出，也不能引用本店事实模拟；同店配送角色无打印权限时无法查询导出、领取下载授权或模拟。
- 完成通知的应用数据库重投验收使用显式完成事件夹具；生产任务从接收到完成、冻结补打等由 `PrintDatabaseAcceptanceTest` 覆盖。不能将夹具事件视为真实设备完成证据。
- 沿用全局H2上下文隔离机制，未新增共享内存数据库。没有修改前端页面，本轮前端验证仅覆盖既有打印交互规则，不属于浏览器端到端验收。

独立审查发现PDF内容与员工动作权限断言不足，已补齐。关于本类缺少H2隔离的初始意见，经核实全局 `IsolatedH2ContextCustomizerFactory` 已处理，审查者撤回该意见。

## 后续任务状态

| 任务 | 当前结论 / 下一步 |
|---|---|
| PRINT-01 | 本记录已统一软件验收证据；本轮已复验并纳入本地提交，远端仍为此前推送基线 |
| PRINT-02 | 已补真实订单事实、商家HTTP、模拟与PDF链路；浏览器全流程交互仍需验收 |
| PRINT-03 | 状态策略和数据库容错原有专项重新通过；真实云端/本地故障仍待设备 |
| PRINT-04 | 通知策略与租约修复、站内信去重验证完成；真实设备完成通知待实机 |
| PRINT-05 | 全仓尚未通过：此前正常pre-push在8批测试首批发现ActivityBenefitCoordinatorFlowTest、BatchTotalInvariantTest、BizDashboardAndReviewFlowTest、AfterSaleRefundExecutionTest等失败。本轮未重跑或修复这些集成线问题，不认定为已通过或均与打印无关 |
| PRINT-06 | 运维健康/异常/对账/原操作页面仍待开发，后端4接口已存在 |
| PRINT-07 | 飞鹅简洁V2仍主动拒绝提交，协议适配与实机确认待办 |
| PRINT-08 | Agent软件持久化专项通过，Windows安装、驱动与设备验收待办 |
| PRINT-09 | 生产部署与COS/密钥/Job/Outbox组合验证待办，默认不开放打印 |
| PRINT-10 | 其余19场景仅模板/模拟，逐个接入真实业务来源后才能启用 |

未执行生产迁移、生产开关、设备请求、合并或推送；历史全仓失败未通过本轮专项结果豁免。


## 提交前复验 · 2026-10-25

按用户“先完成已经开发完成任务的测试以及提交”的要求，本次重新从clean开始编译和集中回归，不依赖上轮结果：

- 后端：上述Maven专项命令将 `test` 改为 `clean test`，72项通过，失败0、错误0、跳过0，BUILD SUCCESS（退出码0）。
- 商家端：`cd b-app && npm test`，58个文件、293项全部通过（退出码0）。
- 商家类型：`cd b-app && npm run type-check`，vue-tsc通过（退出码0）。
- Schema生成：`node --test scripts/tests/print-schema-generation.test.mjs`，1项通过（退出码0）。
- 提交前逐项审查本轮5个文件，`git diff --check` 通过；未包含原共享工作区的改动。

提交范围为通知通道/租约修复、2个新增测试类以及2份验收记录。本次创建本地提交，不推送、不合并。全仓检查、浏览器全流程、真实设备和生产部署的待验收边界不变。
