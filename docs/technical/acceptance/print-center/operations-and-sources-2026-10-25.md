# 打印运营与退款、充值来源验收

状态：本批软件范围验证完成；代码保存于独立开发分支，未推送、合并、部署或启用生产。实机及私有COS另行验收。

基线为已正常推送的 `ba44c7f0c`，本批隔离开发分支为 `codex/print-operations-and-sources`。上一批完整结果见 [最终推送结果](remote-push-final-results.json)，与本批测试分别记账。

## 交付范围

- 运营端复用既有健康、异常、对账、操作查询四个接口，显式选择租户、主体与门店。对账默认试跑，实际执行需确认目标、填写原因并携带幂等键。UNKNOWN 与 PENDING 继续显示为未完成。
- 退款、充值沿用 REFUND、RECHARGE 场景合同，业务所有者事务冻结事实，经既有 Outbox 和统一打印引擎处理。新表 `biz_print_fact` 仅保存不可变业务事实，没有第二套任务或设备状态机。
- 充值使用独立事件保存本次实际本金、赠送金额和入账后余额，保持既有 StoredValueCredited 事件合同不变。读取凭证需要充值及储值账户读取权限，权限冻结到文档并覆盖模拟、历史记录和 PDF 下载。
- 退款仅接入具有可靠终结合同的业务路径；原始支付证据缺失、现金净额与明细不符等情况阻断打印。旧版现金退款的返券仍异步结清，缺少可靠结清钩子的路径保持关闭。
- 商家增加模拟详情，修复权限拒绝后仍请求列表及切换门店遗留设备选择。浏览器合同夹具与真实隔离 H2 后端验收分开留证。
- 商家按具体事实编号及版本选择当前业务小票，支持同一订单多次部分退款；原订单号、子订单号与售后号均可定位对应退款事实。充值专岗无需额外订单权限；切店及快速切回会丢弃旧请求结果。操作失败不会被后续轮询清除。

## 来源与权限合同

| 场景 | 来源 | 必须冻结的原始事实 | 附加读取权限 |
|---|---|---|---|
| ORDER_PAID | SUB_ORDER | 原订单支付及商品快照 | biz:order:view，继续排除纯配送权限 |
| REFUND_RECEIPT | REFUND | v2退款义务结清、原支付及本次退款计划；其他组合需符合完整证据合同 | biz:aftersale |
| RECHARGE_RECEIPT | RECHARGE | 实际本金、赠送额及本笔完成后余额，新增独立SP事件编号 | pay:sv:recharge:read 且 pay:sv:account:read |

以上还需各动作本身的打印权限和严格租户、主体、门店范围。专属读取范围冻结到文档，历史模拟、导出及下载再次校验。新增充值事件沿现有MAIN所有者范围发布，既有SR收款、SC入账事件编号及v1摘要保持不变。

API继续使用现有场景、summary、请求、模拟、PDF及四个运营接口。兼容深链 `pages/print-center/index?orderNo=业务编号`：业务编号可为订单/子订单/售后号或充值号，后端按事实编号、版本、场景及范围校验。充值仅接受其自身充值号，不开放订单别名。V587新增事实附属表，V588追加已就绪且受shop.print.enabled门控的运营菜单，未修改既有迁移或自定义角色。

## 收银依赖

CASHIER_RECEIPT 继续关闭。现有线下库存出库不收款；餐饮线上 PREPAY 不具备 POS 原始实收、找零、支付明细及操作员结算事实。必须先由真实收银业务所有者提供 POS_SETTLEMENT 合同，禁止用库存、当前余额或演示数据补造。

## 验证顺序及结果

按用户要求，先完成业务及测试代码，再统一编译，最后集中测试；验收发现问题后完成修复和用例，再重新编译、回归。

| 项目 | 当前结果 |
|---|---|
| 后端统一编译 | PASS，包含生产及测试源码 |
| 运营与商家端类型检查、构建 | PASS，运营生产Webpack构建及产物断言；商家H5构建 |
| 新来源及打印域、相关所有者回归 | PASS，29类154项：153通过，零失败/错误，1项按设计跳过 |
| 独立浏览器服务测试 | PASS，1项；仅本地H2、模拟及PDF worker，正常关闭 |
| 运营与商家端全量自动化测试 | PASS，运营86文件/881项，商家65文件/358项 |
| 新迁移原生MySQL | PASS，9.7.2临时无网络容器，4组断言；含菜单就绪、幂等及自定义角色不变 |
| 浏览器合同夹具流程 | PASS，设备/模板/路由/异常/权限；失败提示经历16秒真实轮询仍保留 |
| 真实隔离后端与浏览器 | PASS，订单事实→模拟→PDF打开，真实职员拒绝及跨商家HTTP隔离；运营四接口10项HTTP断言 |
| 生成物、权限、API 和功能开关检查 | PASS，25生成器及相关结构守卫，见机器结果 |
| 独立静态审查 | PASS，来源、运营、商家三份报告，无未决重大问题 |
| 飞鹅、Windows、58/80mm 实机及私有 COS | 未验收，资源未准备 |

所有模拟或 PDF 成功只证明软件链路，不作为真实出纸或生产启用证据。本批不部署生产、不自动补打历史订单、不解除 UNKNOWN 设备门控。

本批运行了相关后端集中回归及两端全量测试，未重新运行全仓6213项。上批全仓结果仅作为基线。退款/充值真实所有者→来源→模拟/PDF由后端集成测试验证；多次部分退款与钱包专岗入口由组件回归验证；真实浏览器验证订单场景，未将这些层次混称实机端到端。

## 证据与重跑

[机器结果与日志SHA256](operations-and-sources-results.json)、[浏览器范围说明及样票](operations-and-sources-evidence/browser-results.md)、[运营真实HTTP](operations-and-sources-evidence/ops-real-http.json)、[来源审查](operations-and-sources-evidence/source-review.md)、[运营审查](operations-and-sources-evidence/operations-review.md)、[商家审查](operations-and-sources-evidence/merchant-review.md)。审查报告保留发现和修复过程，最终结论位于其末尾或“最终结论”段。

运营构建在隔离worktree使用现有外部依赖软链，Turbopack拒绝该路径，改用本地Next文档支持的 `next build --webpack`，生产环境参数不变，产物断言通过。商家依赖使用现有 `node scripts/link-ui.mjs` 恢复本地链接。未安装新依赖、修改生产CORS或扩大known基线。

后端先以JDK21执行 `test-compile -DskipTests`，集中 `-Dtest` 包含 `*Print*Test,StoredValueReceiptContractTest,AgentDurabilityTest,CompactReceiptAcceptanceTest,ReceiptSoftwareAcceptanceTest,BasicRefundClosureBoundaryFlowTest,BasicZeroRefundFlowTest,RefundClosureObligationFlowTest,ConsumerStoredValueFlowTest,EntitlementEventFlowTest,ArchitectureTest`。沿仓库既有reactor纳入shop-app及依赖，未嵌入餐饮profile。运营执行typecheck、生产构建/产物断言、全量test；商家执行type-check、`VITE_USE_MOCK=0 VITE_API_BASE=''` H5构建及全量test。

浏览器服务测试仅在 `PRINT_BROWSER_ACCEPTANCE=1` 时启用，私有ready.json提供本地URL、测试账号和业务编号。用 `BACKEND_URL=本地URL node scripts/print-browser-acceptance.mjs` 启动代理；凭据只保存在0600临时文件。默认无BACKEND_URL是明确标记的合同夹具，`FIXTURE_FAIL_COMMANDS=1`仅控制该夹具的失败响应。完成后写入私有目录stop文件关闭H2，停止Node服务。此夹具不是生产API。
