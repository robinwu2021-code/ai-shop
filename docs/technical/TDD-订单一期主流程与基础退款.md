# TDD-订单一期主流程与基础退款

> 执行者使用 `superpowers:executing-plans` 逐任务实施；当前以“基础功能与 C/B 对接详细执行方案”B01–B07 为执行清单；后文 §2 保留原任务背景，步骤用复选框追踪。

状态：**一期基础开发与本地定向验证完成 · 真实渠道/设备验收待执行**。创建/更新：2026-10-15。

**目标：** C 端下单和支付、B 端备货发货、物流跟踪和收货、运营异常处理形成可验收的完整链路；一期不依赖积分，退款只完成基础流程。

**架构：** 复用 `ai.neargo.shop.trade.order` 已有 2.0 构件，补齐生产 Store、事务边界和旧接口兼容接线。支付、库存、物流沿用现有 SPI；只为主流程的实际缺口增加最小合同，不要求先完成全部领域拆分或复杂结算重构。

**技术栈：** Java 21、Spring Boot、MyBatis Plus、Flyway、MariaDB；C/B 为 Vue + uni-app，运营端为 Next.js + React；JUnit 与 Vitest。

**需求/设计依据：** 用户 2026-10-15 的范围调整；[C端功能清单](../requirements/C端功能清单.md)、[B端功能清单](../requirements/B端功能清单.md)、[平台端功能清单](../requirements/平台端功能清单.md)、[支付域 PRD §S2/§S5](../requirements/PRD-支付域.md)、[物流功能清单](../requirements/物流-功能清单.md)、[订单状态与履约定义](./reference/订单状态与履约-定义整理.md)。原完整方案见 [旧开发计划](./TDD-订单重构修复与三端接线.md)，一期发生范围冲突时以本文为准；未纳入一期的需求保持延期，不能标成已完成。

## 当前执行调整（2026-10-15，以本节优先）

用户要求：停止并发测试，先完成基础功能并对接 C/B。既有实现与证据保留；暂停并发压测、连接池并发问题专项、并发消融以及全量旧测试夹具的批量整理，不再将这些工作作为 C/B 对接前置。本次全量后端测试已结束且未通过，不能据专项通过宣称整体通过；后续只运行直接覆盖本期基础行为的功能用例。

当前事实：现金下单/支付、备货发货、物流与收货、基础退款已有后端实现和专项复核；真实 C/B HTTP 下单至收货、基础退款接口链路各有本地 MariaDB 通过记录。C/B 页面及共享 API 类型已完成基础对接；运营现有售后抽屉已补资金/资源进度及原执行恢复入口。一期不接积分，新增订单代码继续位于 trade.order；shop.order 迁移及旧完整重构计划保持延期。

| 顺序 | 接下来任务 | 完成标准 |
|---|---|---|
| 1 | 基础后端收口与合同冻结：核对 C/B 实际调用、主单/子单/包裹号、状态、动作、错误及支付/退款进度；仅修阻断基础功能的问题 | 单次下单付款→备货发货→物流展示→确认收货；未发全额退、退货收妥后全额退均有真实接口结果；受理不显示到账 |
| 2 | 共享合同：补 pointsEnabled、paymentResolution、refundExecution 和实际物流字段；返回件确认增加 restockApproved，保留旧字段与路由 | C/B 类型与后端 JSON 一致；正常一期请求不含积分或部分退款指令 |
| 3 | C 端接线：确认单/收银台/订单列表详情/物流/收货/售后 | 禁用积分试算和承诺；支付按主单、履约按子单；可申请撤销全额退款、寄回、查看处理中及失败；签收与收货完成分开 |
| 4 | B 端接线：订单/备货/发货/物流/售后审核及退货收妥 | 门店子单正确，发货受付款守卫约束；整单全额现金退款，收妥时明确是否可回库；审核通过与退款到账分开 |
| 5 | 基础联调与交付：按真实 C/B 接口走主流程和两个退款支线，核对权限与状态；仅补必要运营展示 | 相关页面测试、类型检查、小程序构建和基础接口用例通过；列明未做的并发、全量历史回归、真实渠道与上线验证 |

后续验收不新增并发场景，不开展连接池专项，也不以调大测试池、改断言基线或跳过基础失败来制造通过。已经发现的并发问题保留为后续事项。已分账但通道无法确认回退的退款继续显示人工处理；本期不扩建分账回退能力。

## 基础功能与 C/B 对接详细执行方案

> 执行方式：沿用本任务逐项实施；先完成后端基础出口，再改共享合同和 C/B。以下 B01–B07 是当前执行清单，优先于后文旧任务中的并发、消融和全量回归步骤。

**状态：** B01–B07 基础开发及本地交付验证完成；整体验收复核发现的三项衔接问题已修复并通过定向回归。真实支付/退款渠道与设备验收另行执行。

### B01 基础后端收口与接口合同冻结

**范围：** 现有 `MpTradeController`、`BizAfterSaleController`、商家订单接口及 `trade.order` 下付款、发货、收货、退款服务。先核对现有行为，发现基础阻断才修改相应实现；不预设新增 Controller 或整体提取旧服务。

- [x] 列出页面→API 方法→HTTP→后端入口的对照表；每条写明编号类型、请求字段、返回状态和可执行动作。
- [x] 固定三类编号：支付用 `payOrderNo`；列表、备货、发货、收货和申请售后用门店 `subOrderNo`；退货/审核等后续动作使用 `afterSaleNo`，包裹引用使用 `shipmentNo`。旧 JSON 的 `orderNo` 继续沿用原端点语义，不全局改名。
- [x] 核对付款发起与结果查询：`/mp/order/{orderNo}/pay`、`pay-result`；调用通道成功不能直接当成后端已付款，结果未确认显示处理中。
- [x] 核对备货/发货仅允许已付且无阻断售后；整张子单发货后能返回实际包裹、承运商、运单号。
- [x] 核对普通订单详情和物流摘要读库/缓存；没有轨迹、订阅失败和异常均显示实际状态，不能补造轨迹或主动消耗快递100查询额度。
- [x] 核对本人确认收货与既有自动收货：签收与完成分开，自动收货沿用签收7天/发货15天先到者，不新增定时器。
- [x] 核对未发全额退款、退货收妥后全额退款：批准后显示执行进度；现金成功后再展示退款到账；资源恢复状态独立展示。
- [x] 只修上述基础链路的阻断问题，更新必要错误码/字段说明。后端基础出口通过后立即进入 B02，不等待历史测试批量整理或并发专项。

**基础验证：** 定向运行 `PhaseOneOrderEndToEndFlowTest`、`BasicReturnRefundFlowTest`、`StrictRefundCallbackFlowTest`；这些选择不包含并发类。若新增阻断修复，只补对应单次调用功能断言。

**交付：** 页面接口对照表、请求/响应样例、基础未完成项清单；明确已由现有实现满足的项目，避免重复开发。

### B02 同步共享类型和客户端适配层

**文件：** `packages/shared/src/types/trade.ts`；C/B `src/api/contract.ts`、`http.ts`、`requests.ts`，以及对应订单/售后 mock。物流类型若定义于共享履约类型文件，按实际来源补齐，避免重复定义。

- [x] 给 `CheckoutCapability` 增加可选 `pointsEnabled`；页面仅在明确允许时才可视为开启，一期统一关闭，缺字段也关闭。
- [x] 对照实际后端 DTO 补 `Order.paymentResolution` 和 `AfterSale.refundExecution`，旧字段保留，不把父单金额复制为子单实付。
- [x] `refundExecution` 对齐实际字段：`executionNo/paymentNo/status/phase/errorCode/resourceStatus/resourceErrorCode/succeededAt/nextAttemptAt`。允许旧记录缺失，按原售后状态展示，不凭缺字段宣称成功。
- [x] 保留 `mApproveAfterSale` 原兼容签名；一期页面只提交现金全额决策，正常审核不传自定义 `refundMinor`，零现金子单也省略该字段，由后端核定。
- [x] 将 B 端 `mConfirmReturn(afterSaleNo)` 扩为兼容的 `mConfirmReturn(afterSaleNo, restockApproved?: boolean): Promise<AfterSale>`；HTTP `POST /biz/after-sale/{afterSaleNo}/receive` 发送 `{restockApproved: restockApproved === true}`，缺省为 false。
- [x] mock 同步“审核通过→处理中→明确成功”的区别，不在 approve/receive 返回时一律伪装 REFUNDED；保留可控失败、未知和资源待恢复样例。
- [x] 先写请求体和返回值适配测试，再修改适配层，确保 C/B 类型检查通过；生成 OpenAPI 时跟随合同修改，不人工改生成字段。

**建议新增测试：** C/B `tests/order-phase1-api.test.ts`，覆盖缺少新字段兼容、确认退货 true/false、全额审核省略金额、售后受理仍处理中。

**交付：** 共享类型和真实 HTTP 适配层一致，页面后续直接消费，不各自推断服务端状态。

### B03 C 端下单、支付和订单主流程

**文件：** `c-app/src/pages/order-confirm/index.vue`、`pay/index.vue`、`orders/index.vue`、`order/index.vue`，以及 C 端现有支付结果处理代码。

- [x] 确认页移除一期抵扣/预计赠分交互，不发积分试算；预览和创建均传 `usePoints=0`，保留商品实价、优惠、运费、地址及门店选择。
- [x] 收银台按父支付号发起/查询；支付取消和失败允许按现有规则重新操作，处理中不显示“支付成功”，支付异常人工处理状态禁止再次引导付款。
- [x] 订单列表继续按门店子单展示；从任一待付子单进入同一笔支付，详情保留门店实付及关联支付号。
- [x] 详情按实际状态显示取消、查看物流、确认收货和申请售后；未支付取消不显示退款，已付取消必须进入售后。
- [x] 普通详情物流摘要只读取后端事实。确认收货成功后重新获取订单、有效包裹和可执行动作，不能只在页面本地改“已完成”。
- [x] 补页面单次调用测试：能力缺失无积分调用；两店列表两行、支付同一父单；付款待确认；签收未确认仍可正确收货；取消未付单后状态刷新。

**验证：** 扩展现有 `order-confirm-page/pay-page/one-tap-pay/orders-page/order-page` 测试；实际文件使用已有 `.test.ts`，不重复新建同类测试。

**交付：** C 端可以真实下单付款、查看履约、取消未付订单和确认收货。

### B04 C 端基础售后和物流页

**文件：** `c-app/src/pages/after-sale/index.vue`、订单详情的售后入口、现有 `c-app/src/pages/track/index.vue`；新增 `c-app/tests/after-sale-phase1.test.ts` 和必要物流页面测试。

- [x] 仅提供子单整单全额现金退款；不显示商品勾选、数量/金额编辑、积分补偿或新协商入口。历史记录仍可查询。
- [x] 申请、详情、撤销和填写寄回单号沿用原路由，使用售后号寻址；可撤销/可介入等能力尊重后端返回与现有规则。
- [x] 分别展示待审核、待寄回、待商家收妥、退款处理中、退款失败/未知、现金已退，以及库存/优惠待恢复；退款失败不引导买家重复申请一笔退款。
- [x] 零现金子单不能被误当成未付款；历史积分订单显示人工核对提示，不把历史积分快照清零。
- [x] 物流页显示承运商、运单、节点、更新时间和异常；加载失败有重试入口，无轨迹与接口失败分开；不在前端虚构揽收、签收或订单完成。
- [x] 验证未发申请/撤销、退货填单、退款状态刷新、无轨迹、加载失败；不新增并发或压测用例。

**交付：** 买家能够完成基础退款所需操作并看到真实进度。

### B05 B 端订单、备货发货和物流

**文件：** `b-app/src/pages/orders/index.vue`、`order/index.vue`、已有发货表单/物流展示组件；新增 `b-app/tests/order-phase1-flow.test.ts`。

- [x] 列表和详情按当前商家/门店子单展示金额、商品、付款、履约与售后状态，不显示其他门店商品和父单合计作为本店收入。
- [x] 已付款且允许履约时显示备货/发货动作；保留后端权限和售后守卫，不用页面开关替代服务端校验。
- [x] 一期整张子单发货，使用现有承运商编码和运单校验；成功后重新获取订单与包裹，失败保留表单内容及真实原因。
- [x] 展示实际运单、轨迹摘要、物流异常及必要的上传/订阅状态；物流签收显示为交付事实，不当作买家已确认收货。
- [x] 验证备货、整单发货、运单错误、无权限、售后阻断、物流状态刷新；只做单次操作功能验证。

**交付：** 商家能把 C 端真实付款的订单完成备货和发货，C/B 展示同一包裹。

### B06 B 端退款审核和退货收妥

**文件：** `b-app/src/pages/after-sale/index.vue`、B 端售后 API 和 mock；扩展 `b-app/tests/after-sale-approve.test.ts`，新增 `b-app/tests/after-sale-return.test.ts`。

- [x] 审核页显示当前门店子单、申请原因、实际全额退款金额、履约阶段、原申请和当前退款进度。
- [x] 同意/拒绝沿用原接口；移除一期自定义少退和积分退款选择。审核通过后根据返回值显示等待寄回或退款处理中，不能直接弹“已退款到账”。
- [x] 对 RETURNING 显示“确认收到退货”，由商家明确选择是否可回库，默认否；发起 receive 时传入 `restockApproved`，不能根据运单存在或签收自动确认收到退货。
- [x] 已发仅退款不自动回库；确认收到退货也不自动等于可再次销售。
- [x] 分开显示资金执行和资源恢复状态；`REVERSE_UNCONFIRMED` 等无法自动完成的情况显示人工处理，不给 B 端“强制成功”按钮。
- [x] 验证审核后处理中、拒绝说明、退货收妥 true/false、零现金审核省略自定义金额、历史积分提示和退款失败展示。

**交付：** 商家可完成审核、拒绝、退货收妥和可回库决定，C/B 对同一售后进度一致。

### B07 基础联调、定向验证与交付

按下面顺序逐条走通；每条记录支付号、子单号、包裹号/售后号、实际请求响应和两端结果。

| 场景 | 验收结果 |
|---|---|
| 一店快递主流程 | C 创建并真实付款→B 备货发货→C/B 同一运单→物流签收→C 确认收货→双方完成 |
| 两店合并付款 | 一笔付款两张门店单；各店履约独立；不可查看/处理其他门店订单 |
| 未支付取消 | C 取消后订单关闭，原库存/优惠占用正确释放，无退款指令 |
| 未发货全额退 | C 申请→B 同意→处理中→可信资金成功→全额现金退款；原库存一次恢复 |
| 退货后全额退 | C 申请→B 审核→C 填寄回→B 收妥→资金成功；回库仅在明确同意时执行 |
| 拒绝与撤销 | 原因和状态两端同步；未发生现金退款 |
| 结果未确认/失败 | 不显示到账，不盲目重拍退款；保留原执行与人工处理出口 |
| 积分关闭 | 全程无积分试算/抵扣/赠分承诺；历史积分快照只读与人工处理 |

- [x] 执行 B01 的基础后端选择和本次修改对应的 C/B 页面/API 测试；不选择带并发场景的整组后端类。
- [x] 执行 C/B `type-check` 与 `build:mp-weixin`；修复本次对接引入的错误，历史无关失败单独列明，不修改 known-* 或断言阈值。
- [x] 如基础失败需要运营处理，只补已有售后页的退款执行/资源错误展示与受权限保护的原执行恢复入口；不扩建运营订单工作台。
- [x] 按实际修改重生成 OpenAPI、API详情、必要字段/权限文档；对照当前 diff 审查权限、金额、编号和成功语义。
- [x] 每个可独立验收的批次精确提交；最终列出已完成基础功能、未完成事项及真实渠道联调边界，不将本地测试通道通过写成上线通过。

**验证命令（在一期隔离树执行）：**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -o -B -f backend/pom.xml -pl shop-app -am -Dtest=PhaseOneOrderEndToEndFlowTest,BasicReturnRefundFlowTest,StrictRefundCallbackFlowTest,BuyerPaymentListResolutionFlowTest,ShippingRefundSnapshotFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix c-app run test -- tests/order-confirm-page.test.ts tests/pay-page.test.ts tests/one-tap-pay.test.ts tests/orders-page.test.ts tests/order-page.test.ts tests/order-phase1-api.test.ts tests/after-sale-phase1.test.ts tests/track-phase1.test.ts -t '^(?!.*(迟到|竞态|并发|旧详情请求)).*$'
npm --prefix b-app run test -- tests/order-phase1-api.test.ts tests/order-phase1-flow.test.ts tests/after-sale-approve.test.ts tests/after-sale-return.test.ts tests/after-sale-page-phase1.test.ts tests/after-sale-phase1-mock.test.ts
npm --prefix c-app run type-check
npm --prefix b-app run type-check
VITE_USE_MOCK=0 npm --prefix c-app run build:mp-weixin
VITE_USE_MOCK=0 npm --prefix b-app run build:mp-weixin
```

上述命令已在一期隔离树执行；最终后端 14/14、C 72、B 26 定向通过，3 个既有 C 竞态用例按范围排除。运营相关 21/21，三端类型检查及 C/B 小程序构建通过；未运行旧全量或并发测试。

**执行顺序与停止条件：** B01→B02→B03→B04→B05→B06→B07。每批先列出缺口与功能断言，再最小实现、定向验证、检查实际 diff 并提交。仅当前基础流程阻断才暂停进入下一批；已延期的并发、连接池、历史全量用例和包迁移不得重新变为前置任务。

## §0 范围与需求对账

### 0.1 一期做什么

主线：**确认商品/地址/运费 → 创建订单 → 支付确认 → 商家备货 → 发货/登记运单 → 揽收/运输/派送/签收 → 买家确认或自动收货 → 完成**。

![一期实施顺序与退款支线](./diagrams/order-phase1-flow.svg)

订单主线先到收货完成，再验基础退款支线；未发货退款和退货后退款都以现金成功回执收尾。物流签收只提供交付事实，订单完成仍由人工或自动收货规则判断。

优先验收普通实物快递 `EXPRESS`，先支持每个门店子单整单发货。多门店一次支付仍必须正确，订单展示和履约按门店子单独立处理。已存在的自提、自送、核销、预约、拼团等保留兼容并做受影响回归，一期不扩建这些模式。

退款范围暂按以下最小产品口径规划，属于本计划的明确假设，可在实施前调整：

- 门店子单一次全额现金退款，金额以该子单实际支付快照为上限；多店支付只退申请的子单，不能退掉兄弟单。
- 未发货：买家申请 → 商家或获授权运营审核 → 原路退款 → 成功确认 → 订单收尾。
- 已发货：申请 → 审核；需要退货的，填寄回单号 → 商家确认收到退货 → 原路退款 → 成功确认 → 收尾。已有免退货全额退款可保留，经审核执行。
- 拒绝/撤销申请、退款处理中、明确失败、结果未知可见；受理、审核通过和物流签收都不能冒充现金到账。
- 未支付取消属于主流程，释放原占用，不发起退款。已支付禁止通过未支付取消入口绕过退款。

一期暂不新增部分商品退款、部分金额/多次退款、协商少退、损耗留存、退款换积分、自动垫付或供应商追偿；不增加退货物流自动审核。原有复杂售后存量需要保留查询/出口，不能因新入口收窄直接删掉。

### 0.2 积分边界

一期新订单不抵扣、不赠送、不转正、不返还或追回积分、不做积分补偿；价格公式只包含现有商品实价、现有优惠和运费。C/B/运营订单入口不展示“可用积分/预计赠分”的新承诺，不请求积分试算；后端仍要拦截 `usePoints > 0`，不能只靠 C 端开关。

积分域及历史记录保留；历史带积分订单只读展示原快照，需处理积分的历史售后进入可见的人工核对流程，不能清零、伪造积分处理成功或把积分金额当现金多退。对一期无积分订单，详情/回调/收货/关单/退款均不得因积分服务异常失败。

### 0.3 验收映射

| AC | Given / When / Then | 任务 |
|---|---|---|
| P01 | 积分接口全部故障时，无积分订单仍可下单、支付、发货、收货、全额退款；正积分请求在资源占用前被拒绝 | N01、N02、N03、N07 |
| P02 | 一次购买两个门店 1000/2000 分商品，生成一笔 3000 分支付、两张门店订单；C/B/运营金额与权限一致 | N02、N03、N08 |
| P03 | 真实下单接口遇重复/并发/跨买家同幂等键，不能重复建单/占库存/返回别人订单；失败后资源可恢复 | N02 |
| P04 | 只有可信支付成功才进入待履约；回调重复一次扣库存/建结算单，回调丢失可以原交易号回查修复 | N03 |
| P05 | 未支付取消/到期关单一次释放资源；支付与关单竞态不能产生“收款成功但订单永久无出口” | N03 |
| P06 | 商家备货和整单发货受门店、支付、售后守卫约束；重复发货只建一张有效发货单，失败整体回滚 | N04 |
| P07 | 发货后登记/订阅/上传微信可恢复；可信物流状态推进，重复/乱序/作废运单事件不误改当前包裹 | N05 |
| P08 | 三端能看同一运单及轨迹/异常；普通详情只读库，快递100默认不主动查询；故障不伪造签收 | N05、N08 |
| P09 | 签收与订单完成分开；买家或自动收货一次完成，失败回滚，一单失败不阻断其他单 | N06 |
| P10 | 全额退款审核和成功分开；受理/超时不记实退，重复回执不双退，退款失败保持可恢复 | N07 |
| P11 | 未发退款正确恢复库存；已发货只在真实退回并确认可回库后恢复；不退款兄弟单、不重复退券/库存 | N07 |
| P12 | C/B/运营对同一订单的状态、可执行动作、支付/退款/物流事实一致；旧 HTTP/JSON 兼容 | N00–N08 |

## §1 当前完成状态与复用方式

### 1.1 暂停检查点

旧计划暂停于隔离工作树 `/Users/robin/.codex/worktrees/order-refactor-plan/ai-shop-codex`，分支 `codex/order-refactor-plan`；已提交检查点 `fdb3926e84a1fe11007bccde2f9c9a0dcb3e496d`。

| 旧批次/构件 | 目前事实 | 一期安排 |
|---|---|---|
| B00、M01 | 基线/归属清单及 9 个 Mapper 提取已提交 | 复用；不重建订单 2.0 |
| F01 | 退款回调方向及认领幂等修复已提交 | N07 回归并复用 |
| F02/F03 | 每单自动收货事务、备货 CAS 修复已提交 | N04/N06 接线时保留 |
| F04/F05 | 整笔取消影响集合授权、C 支付聚合与有界回查已提交 | N02/N03/N08 复用 |
| F06 | 不可变退款基数写入已提交，尚未切换退款读策略 | 保留已有数据合同，不要求一期实现全部切分策略 |
| F07 | 累计退款切分、结算位置等仍未提交、未完成 | 原地保留；不继续执行，不作为一期默认依赖 |
| 下单/发货/收货 Handler | 已有构件和内核测试；`OrderCommandService` 仍只有接口，若干 Store 仅有接口/测试替身 | 补生产适配和真实入口验证，不能以单测替身通过宣告链路完成 |
| 查询 | `OrderQueryService` 现为支付主单视图；旧服务仍负责大量生产读写 | 门店子单业务视图与支付聚合视图区分，旧 JSON 用兼容适配保留 |
| 物流 | 已有 logistics-domain/channel、登记/推送/补偿及三端端点 | 对账并补断点；不另建轨迹系统 |

暂停前最后扩展测试为 pay 14 + shop-app 182，共 196 条通过，日志见 `.superpowers/sdd/TDD-订单重构修复与三端接线/F07-final-expanded.log`。这是含未提交 F07 的选择性测试证据，不是干净检查点或一期验收结果，不包含本次重新整理计划后的新实现。

### 1.2 全局约束

1. 2026-10-15 用户授权一期开发，先完成后端，再对接 C/B。旧 26 批计划暂停，F07 不再追加修复或提交为“已完成”。
2. 一期订单新增规则落 `ai.neargo.shop.trade.order`。后续迁到 `ai.neargo.shop.order` 的目标不变，包迁移、全域所有权收口延期，不作为主流程前置。
3. 保留现有 HTTP 路由、权限/数据域和 JSON；需要增量字段时同步三端合同。支付聚合号 `payOrderNo`、门店业务号 `subOrderNo`、包裹号 `shipmentNo` 不混用。
4. 状态变更复用 `StateTransitionGate/TradeStatusWriter`；旧 `OrderTransitions` 委托到同一写门，不保留两个生产写器。事务原子性只承诺同数据源；外部发送不持有订单数据库锁。
5. 金额为整数分，取服务端价格/实际支付快照。全额现金上限、原支付号/通道/币种、已支付/未支付界限均由服务端校验。
6. 只新增必要 Flyway 迁移；已应用版本冻结。隔离树与最新共享分支已有 V407 编号冲突记录，实施前先核对已应用记录，再分配未应用迁移号，禁止顺手修改生产 checksum。
7. 物流沿用需求约束：**快递100 额度不够，只订阅不主动查** · **状态尽量找微信要，策略可配置** · **定时作业能不用就不用**。普通详情只读缓存/库；微信允许的物流页校正沿用 10 分钟缓存。
8. 自动收货沿用签收后 7 天 / 发货后 15 天先到者，不重新制定时限；已有服务类/自提完成路径按原规则回归。
9. 不引入 Redis、新支付通道、新快递渠道、新积分表族或新的通用工作流。必要异步恢复复用现有 Outbox、稳定业务号、支付回查与作业设施。
10. 实施者按任务先写有业务断言的失败测试，最小修复后跑相关回归并做一次消融；每任务只提交明确文件。不能改 `known-*` 止血线掩盖失败。

### 1.3 Review 重点

| 容易遗漏的输入 | 预期 | 验证归属 |
|---|---|---|
| 旧客户端仍发正积分、或积分服务不可用 | 拒绝正积分；现金路径不调用积分 | N01 `positivePointsRejectedBeforeReservation`、N08 |
| 不同买家同 key、相同 key 不同购物内容 | 不泄露旧响应，不返回不匹配订单，不新增重复占用 | N02 `foreignBuyerCannotReplay`/`sameKeyDifferentPayloadRejected` |
| 支付成功回调晚于关单，或回查返回其他金额/交易 | 可恢复冲突可见；不将已取消订单直接复活，不认领不匹配收款 | N03 `latePaymentHasRecoverableResolution`/`mismatchedPaymentNotApplied` |
| 无揽收事件直接签收、换单号后旧单迟到 | 可信签收可收敛，旧单不推进新单/订单 | N05 `signedWithoutPickupConverges`/`oldWaybillCannotAdvanceReplacement` |
| 退款网关已经成功但本地提交失败/ACK 丢失 | 原执行号回查恢复，现金/库存仅一次 | N07 `externalSuccessLocalFailureRecoversSameExecution` |

## §2 一期开发计划

依赖主线：**N00 → N01 → N02 → N03 → N04 → N05 → N06 → N07 → N08**。2026-10-15 用户调整实施顺序：N01–N07 先完成后端，相关页面与 API 类型在后端合同验收后集中于 N08 对接 C/B；运营已有接口保留兼容。里程碑 A=N03 下单支付；B=N06 发货物流收货；C=N08 基础退款与三端完整验收。每个任务独立测试/复核/提交；未通过出口不标完成。

路径缩写：`T=backend/shop-core/src/main/java/ai/neargo/shop/trade/`，`O=T/order/`，`S=backend/shop-base/src/main/java/ai/neargo/shop/spi/`，`P=backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/`，`IT=backend/shop-app/src/test/java/ai/neargo/shop/scenario/`。下列“新增”是拟创建，实施时先验证不存在并复用等价构件。

### Task 0: N00 固定一期实施基线和接线清单

**文件：** 修改本文 §1/§6、`docs/technical/design/order-domain-migration-map.json` 的现状标记；执行记录放 `.superpowers/sdd/TDD-订单一期主流程与基础退款/`，不调整已有归属目标。必要迁移编号核查位置 `backend/shop-app/src/main/resources/db/migration/`。

**接口/产出：** 固定 `phase1-base.sha`，产出 C/B/运营 Controller → Service → Handler → Store → Mapper/外部 Port 清单，标明生产/替身/未装配。记录暂停 F07 文件清单和恢复路径。

- [ ] 将旧工作树及未提交内容作为暂停快照保留；从已验证检查点新建隔离一期分支，并逐项对照最新主线订单相关增量。不得直接在含 F07 的脏树上继续编码、整树复制共享登录/积分改动或清理旧文件。
- [ ] 核对 Flyway 已应用历史及 V407/V408/V409 占用，区分本地未应用改名和生产冻结版本；只解决一期实际使用迁移的装配冲突，不执行生产迁移。
- [ ] 运行原 `ConsumerOrderFlowTest,StoreSplitOrderFlowTest,BizOrderFulfillFlowTest,LogisticsRegistrationFlowTest,OrderAutoReceiptFlowTest,M5AfterSaleFlowTest,ArchitectureTest`，记录每个真实入口当前能力与失败，固定测试选择和 SHA。
- [ ] 将遗漏 Store、主单/子单合同、积分调用和支付/物流外部依赖写回本文；只提交基线文档，不制造“基线必须红”的业务测试。

### Task 1: N01 订单一期积分隔离

**文件：** 修改 `T/service/impl/{OrderServiceImpl,AfterSaleServiceImpl}.java`、`T/dto/CheckoutCapabilityVO.java`；新增 `O/policy/PhaseOnePointsPolicy.java`。增量错误码 `ORDER_POINTS_MANUAL_REQUIRED=20014` 与三语消息提示历史义务人工核对；cash 的 pay 结算不调用积分转正（`P/impl/SettleServiceImpl.java` 根据原积分成本/补差快照跳过零积分调用，历史非零财务回执保留既有处理）。修改 `c-app/src/pages/order-confirm/index.vue`、三端 API 合同及订单/售后展示；复用现有 C 端 `FEATURES.points`，不改积分域实现。

**接口：** 新增 `void PhaseOnePointsPolicy.validate(Long usePoints)`：正值拒绝，null/0 合法，负值按输入错误拒绝；`boolean enabled()` 一期固定 false。结算能力增量字段 `pointsEnabled=false`；C 端缺字段也按关闭处理。现金订单的调用编排依据此政策跳过扣分/赠分/返分和规则查询，历史积分义务不能套用“无积分已完成”。

- [ ] 新增 `IT/OrderWithoutPointsFlowTest.java`：`positivePointsRejectedBeforeReservation` 断言资源占用 0 次；`cashLifecycleNeverCallsPoints` 让 PointsPort/PointsRulePort 被调用就失败，覆盖详情、付款、收货、关闭及基础退款。
- [ ] 扩展 `c-app/tests/order-confirm-page.test.ts`：能力关闭或缺字段时没有积分试算请求、抵扣/赠分承诺，提交 `usePoints=0`；旧客户端正值请求后端拒绝。运行新测试确认当前赠分等调用使断言失败。
- [ ] 以策略统一隔离调用；如存在收货/事件消费者继续发积分，纳入同一检查。不把组件余额置零伪装完成，不改历史金额。
- [ ] 运行上述测试及 `OrderPaidFourChannelsFlowTest`、C 页面测试/类型检查，证明支付不依赖积分；保存真实输出、消融策略守卫、复核并精确提交。

### Task 2: N02 完整现金下单与门店查询接线

**实施裁定（2026-10-15）：** 一期以真实现金下单和恢复能力为出口。现有生产定价、优惠、运费、门店/地址、预约和拼团规则保持同一路径；已新增稳定创建意图、严格库存协议及真实门店查询 Store。纯 `PreparedOrder` / 创建 Store 的整体提取移入后续结构重构，不用尚未承载完整商价规则的原价草稿替换生产下单。下列提取文件/接口保留为后续方向，不计入一期必做；本期仍必须通过原真实接口的价格、选择、幂等和恢复验收。代价是旧定价服务仍较大，后续提取需要独立回归。

**文件：** 修改 `O/command/CreateOrderCommand.java`、`O/service/create/{CreateOrderHandler,PaymentDraftFactory,OrderDraftFactory,OrderPersister,OrderWriteStore}.java`、`T/service/impl/OrderServiceImpl.java`。新增 `O/command/OrderActor.java`、`O/service/create/{CheckoutPreparationService,PreparedOrder,MybatisOrderWriteStore,OrderCreateTransaction}.java` 及准备服务实现；新增 `O/service/{StoreOrderQueryService,impl/StoreOrderQueryServiceImpl}.java`、`O/service/query/{StoreOrderReadStore,MybatisStoreOrderReadStore,StoreOrderAssembler}.java`、`O/contract/{StoreOrderView,StoreOrderSummary,StoreOrderQuery}.java`。复用 `backend/shop-store-mybatis/src/main/java/ai/neargo/shop/idem/IdempotencyService.java`，只补真实订单缺口。

**接口：** `PreparedOrder prepare(CreateOrderCommand command,OrderActor actor)`；`String create(PreparedOrder prepared,OrderActor actor,String idemKey)` 返回支付号；`StoreOrderView detail(String subOrderNo,String buyerNo)`、`PageData<StoreOrderSummary> list(String buyerNo,StoreOrderQuery query,long page,long size)`。OrderActor 的角色/属主/merchant/store 集合由认证或代客授权构造；B/运营继续用各自数据域，不能用 buyerNo 代替店权限。

拟新增交接类型：`record OrderActor(Actor actorKind,String operatorNo,String buyerNo,String merchantNo,Set<String> storeNos,Integer payMinutes)`，`record PreparedOrder(PaymentDraft draft,String inputHash)`。`StoreOrderView/StoreOrderSummary` 必须包含 `subOrderNo/payOrderNo/storeNo/status/payAmountMinor/refundedMinor/version`；View 另含实际订单行和有效包裹。旧查询条件搬入 `StoreOrderQuery` 时保留现有筛选语义，不让各端自行推断金额属于主单还是子单。

新命令保留旧 `OrderService.CreateOrderCommand` 所有字段：商品/SKU/数量、履约、自提点、地址、券、备注、预约、支付模式/场景、拼团、活动、门店和多地址选择，积分只允许 0。草稿保存实际行价/优惠/运费及子单实付快照；`PreparedOrder` 还保存输入 hash，不在生产中用原价 CartLine 简化旧规则。新查询按门店子单分页；`OrderQueryService/UnifiedOrderVO` 继续承担付款聚合，旧 JSON 在兼容入口唯一转换。

- [ ] 新增 `IT/OrderCreateCashWiringFlowTest.java`：`twoStoresOnePayment` 核对 1000+2000=3000；`sameKeyRaceCreatesOneReservation` 并发只产生一组订单/库存占用；`foreignBuyerCannotReplay`、`sameKeyDifferentPayloadRejected`、`saveFailureReleasesOrRecordsRecovery`、`existingChoicesAndFreightArePreserved` 从真实 POST 验证。
- [ ] 新增 `IT/StoreOrderQueryWiringFlowTest.java`：一店一行、异店/异买家不可见、付款视图仍聚合、GET 不落库。确认生产缺接线/属主重放缺口使新增测试失败。
- [ ] 提取并复用旧定价/落店逻辑，装配真实 Store/公共事务代理，改旧 create/createFor 委托；保留原端点幂等 scope/旧响应缓存。相同业务键需数据库唯一认领，不能先查再写。
- [ ] 资源占用前固定稳定支付号；失败后核对本地完整订单与外域占用事实。复用已有稳定业务键/Outbox 恢复；若缺持久化恢复记录，在本任务先增量设计最小创建记录（buyer/endpoint/key/hash/payNo/status/resource refs），再实现，不能把跨库占用当成本地回滚成功。未知结果按原号回查，不另建单。
- [ ] 运行新测试和 `StoreSplitOrderFlowTest,FreightOrderFlowTest,CouponDoubleUseTest,AppointmentSlotFlowTest,GroupOrderFlowTest`；C 确认页/订单列表与 B/运营订单详情接真实 API 验证字段/权限，消融一项幂等/选择字段、复核并提交。此任务不要求全域搬迁。

### Task 3: N03 支付、取消和自动关单闭环

**文件：** 新增 `O/service/lifecycle/{PaymentLifecycleService,OrderCancellationService}.java` 及同包 `impl` 实现；修改 `T/service/impl/OrderServiceImpl.java`、`T/api/callback/{PayCallbackController,ChannelPayCallbackController}.java`、`T/job/OrderAutoCloseJob.java`、`backend/shop-app/src/main/java/ai/neargo/shop/paybridge/OrderPaidReconciler.java`。保留 `c-app/src/pages/pay/index.vue` 和已有支付测试中的 F05 行为；必要资金上下文修改走 `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/port/SettlePortImpl.java`，不直接调用 pay Mapper。

**接口：** `void markPaid(String payNo,String channel,String tradeNo)` 消费支付域已确认的收款；`void cancel(String payOrSubNo,String reason,OrderActor actor)` 消费完整影响集合授权；现有 `/mp/order/{orderNo}/pay-result` 保留。一笔支付合并展示/发起/回查，多店子单各自履约。零现金只按服务端实际结清处理；线下待确认不能显示已支付。

- [x] 新增 `IT/OrderPaymentLifecycleWiringFlowTest.java`：重复可信回调一次扣库存/建结算单；回调丢失回查补齐；`mismatchedPaymentNotApplied` 拒绝金额/币种/交易归属不符；`latePaymentHasRecoverableResolution` 保留真实收款并形成可恢复冲突，不盲目复活 CANCELLED。
- [x] 扩展关单/取消场景：支付与关单并发有一致胜者，取消/关单不重复释放；异店运营不能取消整笔支付；已付取消必须走售后。先跑新增断言定位当前缺口。
- [x] 将付款/取消/关单规则接同一写门；支付账确认在订单投影之前，库间中断由原流水回查恢复。保持实际付款账户/场景来源，不由调用端 header 伪造金融身份。
- [ ] 运行新测试及 `OrderPaidFourChannelsFlowTest,OrderCloseRuleFlowTest,MultiStoreOrderScopeTest`、C `pay-page.test.ts/one-tap-pay.test.ts`；真接口验证付款失败/用户取消/成功延迟回查。复核提交后交付里程碑 A。

**后端复核：** 原收款匹配修复复核 ADDRESSED；收银台旧读并发守卫补充评审 Approved，无阻断项。支付相关 H2 52 条、MariaDB 14 条，以及新增实际付款后旧收银台请求 1 条分别通过；真实失败断言与持久冲突消融均已记录。C 付款页面留 N08，完整后端回归和跨端验收仍待统一完成。

### Task 4: N04 备货、整单发货和有效发货单

**文件：** 修改 `O/service/OrderCommandService.java`、`O/command/ShipCommand.java`、`O/service/ship/ShipHandler.java`、`T/service/impl/MerchantOrderServiceImpl.java`、`T/api/biz/BizOrderController.java`。新增 `O/service/impl/OrderCommandServiceImpl.java`、`O/service/ship/{MybatisShipmentReadStore,MybatisShipmentWriteStore}.java`；修改 `b-app/src/pages/order/index.vue`、`b-app/src/api/{contract,requests}.ts`。

**接口：** `ShipResult ship(String subOrderNo,ShipCommand command,OrderActor actor)`。ShipCommand 的内部 itemIds 改为 `List<Long>` 真实行 ID，Handler 不再把它当 SKU；旧 `{expressNo,expressCompany}` 请求统一映射为整单剩余行。一期 UI 不开放选件/行内分批；整单发货一次绑定全部可发货行。门面 create/markPaid/cancel 委托 N02/N03，不重新实现业务。

- [ ] 新增 `IT/OrderShipWiringFlowTest.java`：真实 B ship 路由创建 ord_shipment/绑定行；未付、异店、进行中退款不可发；同号重复一次；同 SKU 多行均正确绑定；`saveFailureRollsBackAllFacts` 无半发货单/孤立行。
- [ ] 补 B 页面 `b-app/tests/order-shipping.test.ts`：只能在允许状态操作；快递公司/运单必填、提交中禁重复、成功后刷新真实订单。先确认新增后端断言在现路径失败。
- [ ] 锁所属子单后重读支付/售后事实并校验，保存 Shipment、行、状态和流水在同事务；保留 F03 备货 CAS。重复提交必须同时匹配订单与相同请求内容，不能凭单号返回不相符发货结果。
- [ ] 发货登记和微信上传使用稳定 shipmentNo 触发现有可靠事件/Outbox；本地已发货、外部订阅失败可见且可恢复，不能回滚后再建第二张。既有改单号入口需作废旧单并生成有效新单、保留审计。
- [ ] 运行新测试及 `MerchantOrderPrepareTest,BizOrderFulfillFlowTest,ShipNotifyFlowTest,WxShippingUploadFlowTest`，B 真接口发货/C 查看；消融绑定/事务守卫、复核并提交。

### Task 5: N05 物流登记、推送、换号和三端查看

**文件：** 修改 `backend/logistics/logistics-domain/src/main/java/ai/neargo/shop/logistics/{registration/ShipmentRegistrar.java,registration/SubscribeExecutor.java,push/PushIngestion.java,domain/WaybillProgress.java,port/LogisticsPortImpl.java}`、`T/service/impl/{OrderServiceImpl,MerchantOrderServiceImpl}.java`；必要订单接收适配新增 `O/service/ship/ShipmentSnapshotConverger.java`。复用 `backend/shop-app/src/main/java/ai/neargo/shop/logisticsbridge/LogisticsCompensationJob.java`、`backend/shop-core/src/main/java/ai/neargo/shop/fulfillment/service/impl/LogisticsServiceImpl.java` 的既有运营能力，C/B 订单页及运营现有物流页面补消费。

**接口：** 登记沿用 `SUB_ORDER_SHIPPED` Outbox 事件 → ShipmentRegistrar → SubscribeExecutor；补齐事件中的有效订单发货单号/版本。查询沿用 `Optional<TrackView> LogisticsPort.track(TrackQuery query)` 和 `Map<String,Long> signedAtOf(Collection<String> subOrderNos)`；订阅快照沿用 `S/logistics/ShipmentSourcePort.java`。内部收敛 `void converge(String shipmentNo,long version,String waybillNo,String status,Long signedAt)`，只接受当前有效发货版本的可信快照。order 读物流 Port 不直读 lgs Mapper；`ord_shipment.shipmentNo` 与 `lgs_waybill.shipmentNo` 当前不是同一编号体系，本任务固定来源映射，不直接按名字等同，两个模型不能各自订阅一遍。

- [ ] 新增 `IT/OrderShipmentLogisticsFlowTest.java`：发货一次登记/订阅；验签失败无推进；`signedWithoutPickupConverges` 可信直接签收可收敛；重复/乱序不回退；`oldWaybillCannotAdvanceReplacement`；订阅失败按原 shipmentNo 重放成功。
- [ ] 扩展 `LogisticsRegistrationFlowTest,WaybillNotifyFlowTest,OpsLogisticsFlowTest`：普通三端详情无外部查询，微信校正受 10 分钟缓存约束，快递100主动查询 0 次；同一有效运单三端摘要/节点/异常一致。先跑新增反例确认断点。
- [ ] 接通登记→订阅→推送入库→订单包裹事实→三端查询；复用手机号保护、渠道验签和去重。签收只能更新签收事实/提示收货，不直接冒充买家确认完成。
- [ ] 微信发货上传/token 前置未满足可见且可重试；运营可以按原号重放订阅/上传失败，换号留痕并隔离旧回执。异常/无轨迹显示真实原因，不写虚构轨迹。
- [ ] 跑新场景和 `WaybillProgressTest,SubscribeExecutorTest,WxBindTest`（域模块），三端类型/页面检查；准备真实渠道配置与 HTTPS 回调联调清单。记录测试替身、沙箱和真实渠道分别验到哪里，复核提交。

### Task 6: N06 确认收货、自动收货和完成收敛

**文件：** 修改 `O/service/receipt/{ReceiptHandler,OrderCompletion,OrderReceiptCompleter}.java` 及 `impl/OrderReceiptCompleterImpl.java`、`T/service/impl/OrderServiceImpl.java`、`T/job/OrderAutoReceiptJob.java`、命令门面；新增 `O/service/receipt/{MybatisReceiptReadStore,MybatisReceiptWriteStore}.java`。C `c-app/src/pages/order/index.vue` 接真实收货结果。

**接口：** `void confirmReceipt(String subOrderNo,String shipmentNo,OrderActor actor)`；旧整单收货接口映射到该子单当前有效发货单。自动收货沿用 `boolean completeAuto(String subOrderNo,long now,int shippedDays,int signedDays)` 的每单公共事务代理，15/7 天取先到，售后等已有守卫保留。

- [x] 新增 `IT/OrderReceiptWiringFlowTest.java`：签收不自动变完成；本人确认一次、异买家拒绝；并发人工/自动收货只一条完成流水；流水写失败订单/包裹一起回滚；缺历史 Shipment 的旧单走明确兼容事实。
- [x] 在 `OrderAutoReceiptTransactionFlowTest/OrderAutoReceiptFlowTest` 保留一单失败不中断批次、当前事实重验及时间边界；历史多包裹最后两个并发收货也必须收敛，不因此新开部分发货产品入口。
- [x] 包裹更新按所属子单串行，再当前读重算完成；完成、退款终态不被迟到物流事件改回履约中；复用状态写门/通知，积分调用仍为 0。
- [ ] 跑新场景与 `OrderAutoReceiptFlowTest,BizOrderFulfillFlowTest`、C 收货页面测试；真接口完成普通快递订单。复核提交后交付里程碑 B。

**后端复核：** N06 专项评审 Approved，无阻断项；收货相关 H2 77 条、真实 MariaDB 收货 16 条通过，实际写门消融先失败、恢复后重新编译通过。人工与自动收货均按当前子单事实串行，改单号保留原包裹期限。源码暂与 N05/N07 共享 schema 待统一提交；C 收货页面及跨物流完整链路仍留 N08，里程碑 B 尚不据本专项单独宣告完成。

### Task 7: N07 基础全额退款和必要恢复

**文件：** 修改 `T/service/impl/AfterSaleServiceImpl.java`、`O/refund/service/{AfterSaleRefundExecutor,RefundLedger}.java`、`T/api/{mp/MpTradeController,biz/BizAfterSaleController,ops/OpsAfterSaleController}.java`、`T/job/RefundRetryJob.java`；扩展 `S/settle/SettlePort.java`、`backend/shop-app/src/main/java/ai/neargo/shop/paybridge/port/SettlePortImpl.java` 和 `P/service/impl/PaymentLedgerServiceImpl.java` 的最小退款结果能力。新增 `O/refund/service/{BasicRefundExecutionService,impl/BasicRefundExecutionServiceImpl}.java`、`S/settle/{RefundRequest,RefundDispatchResult}.java`。三端售后页和合同同步；必要执行数据用新 Flyway 迁移及测试 schema 对账。

**接口：** `String BasicRefundExecutionService.prepare(String afterSaleNo,OrderActor actor)` 在批准/退货收妥后冻结全额计划；`void dispatch(String executionNo)`；`void applyResult(RefundDispatchResult result)`；`void recover(String executionNo)`。`record RefundRequest(String executionNo,String afterSaleNo,String subOrderNo,String originPaymentNo,long amountMinor,String currency,String reason)`；`record RefundDispatchResult(String executionNo,String paymentNo,DispatchStatus status,String errorCode,boolean retryable,Long succeededAt)`，`DispatchStatus=ACCEPTED/SUCCEEDED/FAILED/UNKNOWN` 在 RefundDispatchResult 内定义。`RefundDispatchResult SettlePort.requestRefund(RefundRequest request)`/`RefundDispatchResult queryRefundExecution(String executionNo)` 返回明确结果；资金请求编号/实际通道由 pay 持有。

最低数据合同：优先复用可满足唯一性/冻结/恢复的现有执行与资金流水；不足则新增 `ord_refund_execution`，executionNo/afterSaleNo 唯一，保存 subOrderNo/originPaymentNo/amountMinor/currency/冻结行快照/status/paymentNo/version/nextAttemptAt/errorCode，状态 PREPARED/PROCESSING/UNKNOWN/FAILED/SUCCEEDED。发送认领采用条件更新或既有租约；不为一期引入协议留存、分级追偿或完整财务位置表。批准只冻结/预占，不增加实退；现金成功后才落实退账本和售后成功。

- [ ] 新增 `IT/BasicRefundFlowTest.java`：未发全退、退货收妥全退、拒绝/撤销；一次审核一条执行；两店付款只退申请子单；不是全额/超过实付/带积分补偿的新请求被拒绝。
- [ ] 新增 `IT/BasicRefundRecoveryFlowTest.java`：ACCEPTED/UNKNOWN 不增加实退；重复成功一次；`externalSuccessLocalFailureRecoversSameExecution` 重启后原退款号回查、本地补记一次；明确失败不退款成功；未知时禁止重新拍一笔或切线下。先确认现有“先记实退 + 返回流水号即成功”反例变红。
- [ ] 实现最小执行记录、提交后发送、成功回执/回查记实退。沿用 F01 的方向/幂等认领；现金成功不因库存/券恢复失败再次退款。缺原收款、不匹配币种、分账回退失败/未知进入可见异常，不返回退款成功。
- [ ] 对一期启用的每条支付路线验未分账和已分账退款：已分账只使用既有通道可确认的回退能力；若缺口阻断普通全额退款，只补该路线最小恢复协议。已提现/供应商追偿/垫付保持人工处理。不能绕过回退检查，也不能为了这个任务续做整套旧 F07–F12；对无法支持的场景明确入口与验收边界。
- [ ] 未发商品退款后按实际扣库存事实恢复一次；已发只在退回且审核可回库后恢复。库存目的域按稳定业务键幂等，不能只靠售后布尔值；券按既有整笔支付全部取消规则处理，单店退款不得误退兄弟单仍使用的券。若现金已到账但资源恢复失败，记录可恢复结果并在运营展示。
- [ ] C 能申请/撤销/看进度/填寄回单号，B 能审核/确认退回，运营能审核并查失败原因/原执行恢复；不得显示积分补偿或新部分退款操作。扩展 `b-app/tests/after-sale-approve.test.ts` 并新增 C 基础退款页面测试。
- [ ] 运行新场景及 `LineRefundFlowTest,M5AfterSaleFlowTest,AfterSaleTimeoutFlowTest,RefundLeavesLedgerTest`、真实 MariaDB 并发/事务验证和三端售后测试。原分账后回退/原路退款的沙箱或真实小额联调另记证据，未通过不得宣告上线；消融成功确认守卫、复核并提交。

### Task 8: N08 三端贯通验收与交付清单

**文件：** C `c-app/src/pages/{order-confirm,pay,orders,order,after-sale}/index.vue`；B `b-app/src/pages/{orders,order,after-sale}/index.vue`；运营 `ops-web/app/orders/page.tsx`、`ops-web/app/after-sales/page.tsx`、`ops-web/app/fulfillment/{page,express-tab}.tsx`；三端 API 合同/HTTP/mock；修改本文 §5/§6。仅在页面/菜单实际变化时运行 UI 清单生成器。

**接口：** 消费 N02 门店/支付视图、N03 支付结果、N04 Shipment、N05 物流和 N07 RefundDispatchResult；按钮依据服务端当前权限/状态合同，提交后服务端重验，不用前端状态放行写操作。

- [ ] 新增 `IT/PhaseOneOrderEndToEndFlowTest.java`：走真实 Controller 和生产 Store，串起下单→支付→备货→发货→可信物流→人工/自动收货；另一路串基础退款。再加入多店、取消/超时、支付/物流/退款异常路径，不替换业务 Service 以制造假贯通。
- [ ] 三端关闭 mock 联调：C 产生订单，B 备货/填真实运单，C/B/运营对照同一子单；物流故障和退款处理中清楚可见，运营按原执行重试。记录真实网关调用和已验证账户/渠道，不能把网关替身通过写成外部已通。
- [ ] 核对旧端点/JSON、数据域、历史订单、既有自提/自送/预约/拼团/零现金/线下支付受影响回归；积分服务断开时一期主线可运行。
- [ ] 按 §5 命令跑后端/三端相关测试与构建；UI/API/i18n/字段/生成文档按实际变动执行守卫；记录已知存量失败归属，不修无关任务或放宽棘轮。
- [ ] 复核未完成退款/订阅任务的原号续跑与回退兼容，写入上线/回退步骤及 AC 结果；一期全部出口通过才标已实现。包迁移和复杂售后另起后续计划，旧 F07 仍保持暂停。

## §3 取舍与延期

| 延期项 | 原因与恢复条件 |
|---|---|
| `shop.trade.order` → `shop.order`、旧 I05/M02 全面收口 | 用户当前优先业务流程；主线验收后按归属清单独立迁移，避免业务/目录同时大改 |
| 积分抵扣/赠送/返还/补偿及新积分域 | 积分尚未完成；待积分独立验收并明确订单接口再接入 |
| 旧 F07 累计切分/财务位置与 F08 协议用途模型 | 一期全额退款不要求多次累计/损耗留存；已提交 F06 保留，暂停 F07 不默认合入 |
| 部分退款、多次退款、协商/裁决少退、比例退权益 | 先验一次全额退款与退货基础流程，再单独评审 |
| 自动垫付、供应商追偿、提现后自动扣回 | 资金账户/权限/责任未确定；一期对不可自动处理的场景显示人工处理状态 |
| 行内分批发货、自由多包裹发货产品、新快递渠道 | 先完成整单快递；存量多包裹的正确收货仍需回归 |
| 全域动作描述符重构、新行业工作流 | 现有权限和状态守卫足以交付本期；只补实际动作缺口 |

基础退款中“持久化原请求、区分受理与成功、防重复现金”的最小安全能力仍要实现，不因范围缩小省掉。若同一普通路线缺回退能力，先报告准确业务边界并补最小实现，不能静默缩成“只能未支付取消”。

## §4 外部前置与待确认边界

- 一期默认微信小程序已支持的普通线上支付/退款、商家手填快递单号。其他现有支付路线按实际可用能力保留和回归，启用范围必须在 N00/N03 记录，不能假定账户已开通。
- 真实支付/退款需测试主体、原付款账户和回调；物流需可用订阅渠道与 HTTPS 回调/微信配置。代码和回放测试可先做，真实外部闭环未通需单独标记；不另立新渠道项目。
- 基础退款暂按“门店子单一次全额 + 必要退货确认”，一期不允许商家任意填写少退金额；后续若用户需要部分退款，再扩范围。
- 历史积分/复杂售后、已提现需人工核对；已有入口出口的兼容映射在 N00/N07 列清。人工处理不标自动退款成功，也不阻止无积分普通单正常完成。

## §5 验证计划与结果

当前结果：N00 基线后端 92 条通过（7 个类，0 失败/错误）。N01 后端积分隔离及 N02-A 幂等增量通过全量后端回归：3501 条、0 失败/错误、1 条既有条件跳过（RefundBasisMariaDbReplayTest 需显式独立 MariaDB URL）；日志 `.superpowers/sdd/TDD-订单一期主流程与基础退款/backend-full-verified.log`。N02 其余接线及 N03–N08 尚未完成，C/B 在后端之后对接。旧测试证据只记在 §1，不勾选一期 AC。

后端每任务命令模板（在一期隔离树执行，`<任务测试类>` 替换为任务列出的确切类名）：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -o -B -f backend/pom.xml -pl shop-app -am -Dtest=<任务测试类> -Dsurefire.failIfNoSpecifiedTests=false test
```

域内物流测试按对应模块执行，例如 `-pl logistics/logistics-domain -am -Dtest=WaybillProgressTest,SubscribeExecutorTest,WxBindTest`；所有测试必须检查 Surefire 实际发现数/Failures/Errors，不能仅凭 BUILD SUCCESS。

```bash
npm --prefix c-app run test -- tests/order-confirm-page.test.ts tests/pay-page.test.ts tests/order-page.test.ts
npm --prefix c-app run type-check
npm --prefix b-app run test
npm --prefix b-app run type-check
npm --prefix ops-web run test
npm --prefix ops-web run typecheck
npm --prefix c-app run build:mp-weixin
npm --prefix b-app run build:mp-weixin
npm --prefix ops-web run build
python3 scripts/gen-ui-catalog.py --check
git diff --check
```

执行后逐行回填：AC → 实际测试方法 → 输出/日志 → 外部联调边界。每任务记录至少一个撤掉守卫/实现后真正失败的业务断言；资料整理和纯目录核查不制造消融。

## §6 执行状态与计划自检

| 任务 | 状态 | 交付出口 |
|---|---|---|
| N00 | 已完成 | 干净一期基线/接线清单/旧改动保留 |
| N01 | 后端已完成，C/B 待 N08 | 无积分依赖、端与服务端一致 |
| N02 | 后端功能已验证，结构提取延期 | 现金下单/查询生产接线和幂等 |
| N03 | 后端已验证及复核，C 待 N08 | 付款/取消/关单闭环，里程碑 A |
| N04 | 后端已验证，N05/N08 继续接线 | 商家备货/整单发货 |
| N05 | 实施中 | 物流订阅/可信推送/三端查看 |
| N06 | 后端专项已验证及复核，C 待 N08 | 收货与完成，里程碑 B |
| N07 | 实施中 | 基础退款成功/失败/未知可恢复 |
| N08 | 待实施 | 三端贯通与交付证据，里程碑 C |

计划自检：P01–P12 均有任务落点；Review 重点五项均指定反例测试；支付聚合与子单/Shipment 编号分开；新积分、复杂财务和迁包未作为主线前置；F07 保留未提交且未记完成。实现时用实际 diff 更新文件清单/偏差与测试结果，不能把本节规划自检写成代码通过。

N00 执行：隔离树 `/Users/robin/.codex/worktrees/order-phase1/ai-shop-codex`，分支 `codex/order-phase1`，BASE `fdb3926e84a1fe11007bccde2f9c9a0dcb3e496d`。原 F07 保留，未复制共享登录/积分代码。接线清单及暂停状态保存在本计划工作区；本地测试 Flyway 禁用，仅 H2 schema，未对生产数据库执行迁移。

N01 范围对账：初始5条反例、历史取消/到期两条反例和现金结算转正反例均已得到业务 RED；首轮现金结算夹具误写 NONE 而应 NO_INVOICE（发票守卫错误不计入 RED）。M5 旧积分订单/补偿的3条验收按一期范围改为拒绝和历史人工核对，现金售后37条保留。新 capability 字段与三语错误消息同步后端合同；C/B 按用户要求暂不修改。

N01 后端验收：`N01-verified.log` 11 类129/129，`N01-restored.log` 3 类54/54，均0失败/错误；移除正积分守卫后 `N01-ablation.log` 1/1失败，恢复文件与备份字节一致后重新编译54条通过。覆盖正/负积分、能力关闭、付款/取消/超时/历史人工处理、现金结算无转正、积分补偿拒绝，普通下单/支付/发货/物流/收货/现金售后回归保持通过。仅后端及文档改动，C/B 没有提前接线。

N02-A 当前实施边界：先修真实 `/mp/order` 的幂等合同；新增 `O/service/create/OrderCreateRequestHasher.java`，SHA-256 对完整现有 CreateOrderCommand 规范化排序后摘要。`IdempotencyService` 增加带 requestHash 的 execute 重载，旧调用保留；同 `(idemKey,endpoint)` 重放必须匹配 userNo，新记录还需匹配摘要，跨属主 NOT_FOUND、不匹配 CONFLICT，旧 null 摘要记录仅在属主相同情况下兼容重放。`sys_idempotent.request_hash VARCHAR(64) NULL` 仅增量，使用 V410（暂停 F07 的 V408/V409 版本预留不复用），旧唯一键不改变。该子批次不等于 N02 全部完成；完整草稿/资源恢复与查询 Store 仍按任务继续。

N01 审查修复：历史取消/退款的 REFUND/REVOKE 流水按原积分快照走只读查询，现金订单不请求积分；仅有非零赠分费用快照也进入人工核对。订单通过 CampaignPort.MerchantAmount.pointsRewardsEnabled=false 排除包含积分的组合活动（含现金与积分混合），不展示、不接受选择、不消耗其配额；保留独立积分活动与现金减免组合。两类新增四条真实入口反例均先 RED 后通过，独立活动与现金回归也通过。

N02-A 验证：相关30条通过，移除属主/请求摘要守卫后两条业务断言失败，恢复源码后全量重新编译。临时本地 MariaDB 12.2.2 使用独立 socket/port，343个原始应用迁移 SQL 按版本执行成功（含 V410），真实下单五条测试全部通过，覆盖同键并发、属主、内容变化、旧缓存及 Map 排序。不是生产迁移或 Flyway 历史/checksum验证。原 F07 继续暂停，C/B 尚未改动。

N02-B 增量创建恢复设计：新增 V411 `ord_create_intent`，在外域占库前以独立事务冻结 buyer/endpoint/key/hash/原支付号/库存真相源/原 SKU 门店数量；原支付号与 `(endpoint,key)` 唯一。主创建事务锁意图行后才占预约和库存，提交后按原支付号检查完整支付单，回滚后按原资源引用释放。状态为 PREPARED/COMMITTED/RECOVERING/RELEASED/RECOVERY_REQUIRED；释放故障、库存真相源变化保留待恢复，不把它视为成功或创建第二张单。复用现有自动关单扫描，每次上限100条、120秒租约，不增加调度器。

平台库存使用嵌套事务保存点，只有明确缺货才允许取消赠品重试，未知结果不改变数量重发；Port 返回缺货列表也必须拒单。独立库存按业主分组且一次库存事务全部预留，确认/释放覆盖同一支付号的全部业主。失败创建按冻结 SKU 的原业主登记取消，即使预留还未到达也有释放墓碑，拒绝迟到预留；已实扣而无订单的异常保留人工核对。正常清算/库存接口保持兼容。

N02-B 当前结果：初始5条资源反例 RED；首轮51条通过。新增多业主确认反例期望2笔 COMMITTED 实际1笔；清除旧缓存后的预约重放反例期望1次占位实际2次，均已复现并修正，扩展回归进行中。N02 草稿与查询 Store 接线尚未全部完成，不能以本子批次宣告后端完成。

N02-B 扩展102条通过；移除事务回滚后的补偿，新库存库残留预留测试再次失败。新增 V411 原 SQL 本地执行成功。MariaDB 12.2.2 开启 innodb_snapshot_isolation，旧 RR 快照不能锁独立事务新写入的意图，首轮真实库八条均失败（1020主因）；C 创建/代客创建的公共事务显式 READ_COMMITTED 后，八条全部通过，包括保存点赠品降级与并发重放。没有关闭服务器隔离选项或移除唯一键/行锁；[MariaDB 官方说明](https://mariadb.com/docs/server/ha-and-performance/standard-replication/enhancements-for-start-transaction-with-consistent-snapshot) 支持该根因判断。后端公共代理继续在同一创建事务中核对额度与留痕，不能为绕过旧快照而提前独立提交订单。

N02-B 提交前验证：受影响后端496条（0失败/错误，原有 MariaDB opt-in 测试跳过1条）；恢复记录版本反例先得到0版本失败，随后53条相关场景通过；最终真实 MariaDB 九条通过。状态更新记录更新时间与版本，仅允许非终态继续推进，迟到结果不覆盖已 COMMITTED/RELEASED 的记录。二十四个文档生成器全部新鲜，三端页面源码未修改。共享24条检查23条通过、1条失败：运营权限归类缺12条已有非订单路由映射，相关 Controller/规则源码本批均无变动，不改权限规则或放宽守卫以止血。N02 其余接线与 N03–N08 继续实施。

N02-C 查询接线：真实门店分页及详情改由注册的 StoreOrderQueryService/MybatisStoreOrderReadStore 读取，子单与支付单均显式核对买家，明细/日志/门店/有效包裹批量装配；兼容入口保留原 JSON。50 条相关回归与真实 MariaDB 四条通过。绕过请求数据域的内部读取仍受显式属主条件保护；移除该条件后反例失败，恢复原文件后重新编译并验证。两店 1000/2000 分汇总为一笔 3000 分支付，GET 不写入，外部视图不暴露物理行 ID。N02 完整定价草稿提取仍待实施，N04 发货独立子任务进行中；后端完成前不修改 C/B 页面。

N03-A 可信收款：新增真实付款场景六条均先失败，包含未知流水被认领、无流水/金额不同/币种不同/付款属主不同仍推进，以及 stub 将 6980 分写为零元。SettlePort 增加精确发起流水及成功收款事实读取，支付域按原商户单号当前锁认领；未知号不退化成订单号，不接受通道不同或重复交易事实变化。实际到账金额仍留在支付流水，订单生产入口由 PaymentLifecycleService 与原应付快照核对后才经写门推进。启用开发回调时从真实发起流水取金额与通道，不在支付域特殊放行 STUB；零现金仍需成功结清事实，线下确认保持原授权商家入口。首轮相关19条通过，N03-payment-green.log；取消后到账持久化出口、竞态与完整回归尚未完成。

N03-B 增量设计（实施前固定）：新增 V412 `ord_payment_resolution`，以原支付号/原收款流水号唯一登记到账冲突，冻结本订单买家及来源收款的订单/付款属主、实收金额/币种/通道/交易号、原因及处理状态。记录使用独立主库事务，订单投影回滚不丢冲突；无外键或额外订单资源占用。已取消/关闭后到账登记 LATE_PAYMENT，真实金额/币种/属主不符登记 RECEIPT_MISMATCH，初始 MANUAL_REQUIRED，不自动复活订单、重建支付、释放已实扣库存或假定退款成功。既有详情/付款结果以可空 paymentResolution 展示处理状态及原冲突引用；金额事实留在金融记录，不在商家子单详情额外泄漏跨店支付汇总，沿用原买家/门店访问授权，不新增匿名读取。后续 N07 仅在原收款退款确实成功后关闭记录；现阶段人工核对出口需保留真实金融证据。付款与关闭均采用当前锁/共同状态写门，支付域 close 不允许覆盖已成功的收款；重复回调/冲突记录不得重新写一次成功或处理记录。

N03-B 当前结果：V412 独立保存迟到/不匹配/重复收款的原支付号、原收款号、订单买家及来源属主、实际金额/币种/通道/交易号；唯一键保证重推不覆盖原处理记录。买家/B/运营的既有详情仅增加可空处理状态/原因/原引用，不额外公开跨店金额。真实迟到/金额反例先为记录0失败后通过；测试确认订单投影回滚仍保留记录、取消订单不复活。实际收款先落账，现金订单必须消费 SUCCESS；source Query/Settled 币种贯通，零元交易号由成功金融事实解析。H2 延迟关闭收款先复现旧 RR 死锁；金融关闭独立 RC 当前锁后不覆盖 SUCCESS。MariaDB 支付/到期竞争复现 ord_order1020，付款/取消/关单公共边界限定 RC，关闭数只计真实成功，服务器严格快照仍开启。

N04 实施偏差：使用独立 OrderShippingService/Impl 接真实 B ship 路由，避免将尚不完整的通用 create 原型冒充生产门面；旧 create/markPaid/cancel 不另实现一套。Long 行 ID、整单绑定、父单支付/店权限/售后检查、包裹与状态审计及 Outbox 同事务。换号作废旧包裹并创建新包裹、重绑真实行；sourceShipmentNo/不可变 generation 与后续 lgs 标识映射分开。共享写门处理普通发货/换号，实际认证员工账号写入审计。V413 以 STORED 可空有效运单列唯一约束同租户承运商/运单，作废释放约束，别的约束错误不误译为重复运单。真实跨单竞态先2成功失败；实际 MariaDB 同子单重试先1020失败，入口当前锁及限定 RC 后通过。发货消融真实行绑定后5条失败，恢复原字节后8条通过。

N03/N04 合并 H2 回归156条通过，覆盖原现金/线下/关单/门店范围、角色和审计、B/运营处理记录可见及 Architecture。真实 MariaDB 最终20条通过（收款关闭1、发货并发2、实际发货9、付款生命周期8）；早期70045仅因本地测试库未加载既有 TEST 通道补种，1901因夹具 CHECK 引用了自增主键。补种仅临时本地数据库，改夹具为目标子单第二行数量检查，并将每次运行运单样本加稳定随机后缀，没有清除有效运单或放宽唯一约束。N04 独立任务复核通过，一项重要夹具重复运行问题已修复并经范围复核确认；旧编译/动态代理提示列为非发货行为的小项。N05 仍需处理上传入队的 REQUIRED 事务故障及来源映射；N07 仍需修复售后申请在等待发货锁后使用旧阶段规划的反向竞态。N03 原流水回查仍继续接线，不能宣布全部后端或下单里程碑完成。

N02 一期范围裁定：按 Task 2 的实施裁定，真实商业下单、门店查询、幂等和资源恢复为本期出口；纯创建草稿与 Store 提取延期。旧状态记录中“草稿仍待实施”仅代表当时进度，不表示现阶段仍将其作为主流程前置。
N03 回调丢失修复：生产 PaymentReconReconciler 按原 outTradeNo 将通道实收金额/币种先记入金融账，再投影订单；找不到/未知查询不伪造收款。真实接口四条通过，包含 USD 与关单后迟到资金的可见人工核对。旧对账回归16条通过；新增长错误记录反例已复现，正在补齐字段长度保护。


## 2026-10-15 基础对接实施记录（B01–B07）

### 页面与接口合同

| 页面 / 方法 | HTTP（POST，查询另注） | 编号 / 请求 | 返回与动作 |
|---|---|---|---|
| C 确认单 `orderCapability/orderPreview/createOrder` | `/mp/order/capability`、`/mp/order/preview`、`/mp/order` | 原购物、地址、门店字段；`usePoints=0` | 服务端金额；能力缺失/关闭均无积分承诺 |
| C 订单列表/详情 | GET `/mp/order`、`/mp/order/{orderNo}` | 门店子单，支付聚合视图保留 `subOrders` | 各门店金额独立；普通详情不额外查轨迹 |
| C 收银台 `payOrder/payResult` | `/mp/order/{orderNo}/pay`；GET `pay-result` | 先按子单详情解析 `payOrderNo` | 待确认继续展示处理中；`paymentResolution` 禁止重复付款 |
| C `cancelOrder` | `/mp/order/{orderNo}/cancel` | 原订单号 | 未付关闭；已付退款走售后，既有社区集单特例保留 |
| C `confirmReceipt` | `/mp/order/{orderNo}/confirm-receipt` | 门店子单，确认全部有效包裹 | 返回订单并回读详情；物流签收仍是履约中 |
| C `applyAfterSale` | `/mp/order/{orderNo}/after-sale` | 门店子单、类型、原因、凭证；无金额编辑 | 返回售后；再读订单，已有执行只展示进度 |
| C `cancelAfterSale/fillReturnExpress` | `/mp/after-sale/{afterSaleNo}/cancel`、`ship` | 原售后号；寄回单号 | 售后返回后回读订单；执行中不提供重新申请 |
| C 看件 | GET `/mp/track` 或 `/mp/track/mine` | 原令牌；小程序保留本人鉴权 | 无轨迹、加载失败、失效/非本人分别展示；失败可重试 |
| B 订单/备货/发货 | GET `/biz/order/{subOrderNo}`；POST `prepare`、`ship` | 当前商家门店子单；`expressCompany/expressNo` | 权限、已付与活动售后共同守卫；成功回读实际订单 |
| B 物流 | GET `/biz/order/{subOrderNo}/trace` | 原门店子单 | 普通详情只用已有摘要；打开物流时读取实际轨迹 |
| B 同意/拒绝 | `/biz/after-sale/{afterSaleNo}/approve`、`reject` | `refundMode=CASH`，省略 `refundMinor`；拒绝必填 `remark` | 退货待寄回或原退款执行中，不直接显示到账 |
| B 收妥/在途仅退 | `/biz/after-sale/{afterSaleNo}/receive`、`confirm-refund` | 收妥显式 `restockApproved`，默认 false | 按原执行处理；收妥不等于可再次销售 |
| 运营恢复原执行 | `/ops/after-sales/{afterSaleNo}/recover` | 原售后号、空请求；`aftersale:ticket:handle` | 原执行/资源恢复结果；不传新金额或强制成功 |

C/B `refundExecution` 与后端 `AfterSaleVO.RefundExecutionProgress` 同形；运营沿用 `OpsAfterSaleVO`。`SUCCEEDED` 只代表现金结果，`resourceStatus=DONE` 才表示资源也完成。`REVERSE_UNCONFIRMED`、未知、失败均保留原执行与人工出口。历史积分只读，不承诺自动退还或清零。

### 本地验证与验收边界

- 后端真实 Controller/Store 的主流程、未发全额退、基础退货、严格资金回执：B01 三类 9/9 通过。`PhaseOneOrderEndToEndFlowTest` 断言 C/B 相同运单、ORD/LGS 实际包裹映射、可信签收后仍履约中、本人收货后双方完成；退款断言到账前不冒充成功、原退款及库存恢复仅一次。使用本地测试资金通道，不代表真实微信/支付宝退款验收。
- C 页面/API 定向功能 72 通过，主动排除 3 个已有竞态/迟到请求用例；B 页面/API/mock/规则定向 26 通过。包含两店列表、统一父支付号、付款待确认、人工核对禁重付、收货回读、售后撤销、全额退款、寄回、回库 true/false、拒绝理由、发货失败保留输入、资金/资源进度以及物流失败重试与异常状态。
- C/B 类型检查与关闭 mock 的小程序构建通过；运营定向合同/恢复及词条测试 21 通过，类型检查通过。最后修复版本 C 类型与 mock 关闭的小程序构建再次通过；B 最终源版本构建通过。
- 共享轨迹测试通过；全局三语一致性检查发现既有 B 端 `common.delete` 英文缺失（订单改动前已存在），未更改断言基线或扩展修复范围。B 端 OpenAPI 生成器沿用既有行尾空格格式。
- 尚未执行真实支付/退款渠道、微信真机及生产部署验收；外部渠道请求与实际订单号须在环境验收时逐单记录，不能用测试通道编号冒充。暂停并发/连接池专项、旧全量失败整理、积分接入和迁至 `shop.order`。


### 最终独立复核与修复（2026-10-15）

一次只读整支复核覆盖 `fdb3926e8..4b0e08936`，未运行并发测试；没有 Critical 或 Minor，提出三项 Important。修复后按用户范围复验，不增加第二次复核或旧全量回归。

| 复核发现 | 最终修复与证据 |
|---|---|
| C 在途仅退款 `WAIT_RECEIPT` 隐藏确认收货 | 删除客户端额外售后限制，保留快递/履约中/门店子单条件；组件真实失败后通过。收货后原售后仍 WAIT_RECEIPT，只提醒商家，不自动认定退款到账 |
| C 列表漏传 `paymentResolution` | 按本人和当前页父支付号批量读取；只输出状态/原因/原资金参考号。真实 C HTTP 缺字段失败后通过；他人订单/他人回执不泄露 |
| 两店合单一店退款后原微信上报快照停滞 | 消费持久化 `AFTER_SALE_REFUNDED`，从已退款子单找到原支付单并复用队列刷新。PENDING、已部分 SUCCESS、达到渠道修正限制三种单次场景均失败后通过；原行/支付号、当前包裹和硬限制保持 |

另在复现列表问题时发现历史 `store_no=NULL` 会触发空键查询并让整页失败，单独构造历史行证实 10500，再补空值投影；同组测试通过。第一次新测试编译字段名误写、一次运单预期未遵守既有大写规范，均只修正测试夹具，不计作业务通过证据。

最终后端五类 **14/14**（原基础 9 + 新列表 2 + 退款上报 3）通过，C **72**、B **26**、运营 **21** 定向功能通过；C/B/Ops 类型检查和 C/B 关闭 mock 的小程序构建通过。全局旧失败保持如实记录。代码未推送、合并或部署。

真实渠道补充边界：现有微信直连支持退款查询；微信服务商/支付宝等部分既有适配器仍沿用不可查询的默认实现。可信回调可驱动资金完成，但丢回调后的查询恢复不能据此宣称全渠道支持；这些渠道的未知结果保留原执行与人工核对。已分账回退仍需可信资金确认。

### 执行决策与延期代价

本次复核按当前 B01–B07 范围判定。并发、连接池、旧全量回归、迁包、旧定价提取、积分、历史无可靠库存权威、既有渠道查询缺口和 null 幂等哈希均保持既有明确边界；手动收货遵守现有后端语义。延期会限制全量回归与真实渠道认证，不能据本地定向通过承诺上线安全。

以下保存本计划账本中的全部决策，避免清理临时工作区后丢失取舍记录：

- Ruling: 用户最新要求后端完成后才对接 C/B，N01–N07 前端步骤推迟到 N08；运营保留已有合同兼容 — 原计划顺序由用户最新指令覆盖 — 风险：需要后端真实契约测试防止晚联调发现丢字段。
- Ruling: 原 F07 全部留在 order-refactor-plan 树；一期从完成的 F06 检查点起步。主目录 HEAD 仍 df2adc641，共享登录/积分未提交变动不复制 — 不复活暂停结算重构，不混入别人工作 — 风险：后续整合时需核对重叠 API 和迁移号。
- Ruling: task-start 仅识别数字任务标题，文档标题改 Task 0..8 并保留 N00..N08 业务编号；不改开发范围或顺序。
- Ruling: 扩展128条有3条旧积分订单/补偿验收失败，源于用户已延期的功能，改为同等入口的关闭与历史人工核对断言，不删除/跳过测试。独立积分域能力仍保留 — 新一期不允许旧开关重开积分 — 成本：未来积分接入时须重新启用并评审原积分生命周期验收。
- Ruling: N00 source inspection corrected prior architectural assumption: legacy production C list already pages ord_sub_order and batches parent reads; OrderQueryService main-view is unregistered prototype. N02 must preserve existing child-list routes, not introduce unnecessary replacement endpoint or regress to pay-level paging. Need isolate its remaining per-row reads/production store, while retaining full JSON.
- Ruling: switch subsequent disjoint backend work to subagent-driven-development; root owns N03 payment and existing OrderServiceImpl, fresh implementer owns N04 shipping/MerchantOrderServiceImpl and shipment folders, no overlapping file writes — current N02 cash creation contract is tested and is sufficient to begin independent shipment integration while pure quote extraction remains pending — cost: must coordinate shared Maven source/build snapshots and review the integrated contract. No C/B implementation before all backend exits. Old F07 remains untouched.
- Ruling: N04 uses dedicated typed OrderShippingService instead of generic unimplemented create facade; actual production checkout stays intact until N02 frozen draft extraction — no invented raw-price create integration — cost: later unified command facade needs separate proof.
- Ruling: public lifecycle transactions scoped RC after actual strict-Maria1020; financial close requires new current-read transaction to avoid inherited stale RR — no global option or CAS removal — cost: closure commits independently of a caller's outer transaction, justified by authoritative channel failure/success ordering.
- Ruling: N02 purePreparedOrder/createStore extraction deferred to structuralrefactor — user stopped old refactor and prioritizes actualcashmainflow; current productioncommercialrules retained and creation/query/recovery exitsverified, neverrawCartLine — cost: legacypricing servicelarge, futureextractionneedsindependentregression. PlanTask2 explicitly updated, no prototypecompletionclaim.
- Ruling: receivedreturnedgoods doesnot implyresaleable — additiveexplicitrestockApproved missing=false freezesexecutiondecision — cost: B/Ops mustexplicitlymarkstockreturn.
- Ruling: alreadySPLIT cashrefund with onlyStub reversalacceptance cannotclaimconfirmed — supportedordinarypaidUNSPLITrefundcomplete; split routevisibleREVERSE_UNCONFIRMEDmanualexit untilrealcapability — cost: splitrefundrequiresmanualfinancialverification.
- Ruling: legacywhole-orderreceiptconfirmsallcurrentlivepackages atomically underchildlock — preventsstrandinghistoricalmultiplepackages, explicitshipmentcommandremainsperpackage — cost: whole-button assertsbuyerreceivedallpackages, requiresUIwording.
- Ruling: B05/B06 gain real Vue component tests with existing root Vue plugin/happy-dom and global shared UI registration — API/utility tests alone cannot prove merchant page actions — cost: modest B test configuration added; no new packages/lock changes.
- Ruling: retain existing global B common.delete parity failure and OpenAPI-B generator whitespace rather than expand unrelated cleanup — cost: global parity/diff whitespace gate remains known debt; new order keys present in all languages.
- Final: Ruling: preserve deferred concurrency/pool/global legacy suite work — user explicitly excluded it — cost: no concurrency/full-regression readiness claim.
- Final: Ruling: preserve trade.order and large legacy pricing service — migration/extraction deferred by accepted plan — cost: future structural refactor still needed.
- Final: Ruling: preserve REVERSE_UNCONFIRMED manual recovery — channel acceptance cannot prove reversal — cost: split refunds may need financial intervention.
- Final: Ruling: retain preexisting non-direct channel refund-query capability debt — direct WX query implemented; authoritative TEST callback tested locally, other adapter defaults remain unavailable — cost: lost callback on those channels requires manual verification; no live certification.
- Final: Ruling: preserve null idempotency hashes on legacy rows — migration compatibility intentional — cost: old replay cannot gain new hash comparison until separately migrated.
- Final: Ruling: historical points / ambiguous original stock authority remain manual — cannot reconstruct absent reliable original facts — cost: historical refund may not auto-complete.
- Final: Ruling: B global locale parity and generated whitespace remain baseline debt — unrelated to basic order scope — cost: global parity/diff-whitespace gate not clean.
- Final: Ruling: manual receipt follows backend semantics across after-sale states — buyer receipt records physical fact, does not authorize cash refund — cost: a future product restriction must be designed/server-enforced separately.

本轮交付提交：`4b0e08936`（C/B 基础接线及必要运营展示），`089e5021c`（最终复核修复）。隔离树及 `codex/order-phase1` 分支保留供后续设备/渠道验收。


## 2026-10-15 服务器与小程序部署准备

用户已授权部署服务器并上传小程序进行测试。目标为现有 `soukmind-tx` / `https://www.hxmall.top`；小程序 `wx3201250c28393850` 使用既有 C/B 合包，B 无独立小程序 AppID。保留正常真实支付配置，不为联调开放假回调或修改用户积分。

部署前核对发现：服务器运行标记为 `b3ff522dd-phone-login`，已应用 `V407__mp_session_operator.sql`（checksum `-276806239`）；该登录补丁此前未进入一期隔离分支。此次仅从主工作区已提交 `f07e28caf` 保留后端登录会话、手机号账号归属及付款 OpenID 选择相关代码，不复制积分开发或回退一期订单接线。既有登录、密码与订单定向测试通过：core 9、app 22；不选择并发用例。

一期未上线的退款依据表迁移由 V407 改到 **V416**，SQL 内容保持不变；恢复线上 V407 的原始脚本，禁止 repair 或改写线上 checksum。原 V401–V406 在服务器尚未应用，但版本小于已应用 407，发布启动需要临时启用 Flyway out-of-order 来补齐；迁移完成后撤销这一临时启动覆盖。

数据库及配置备份：服务器 `/data/app/ai-shop/shop-app/backup-order-phase1-20261015-090743/`，主库和库存库 SQL gzip 完整性校验通过，权限限于部署账号。原数据在独立 `ai_shop_order_release_20261015_091042` / `ai_shop_inv_order_release_20261015_091042` 副本中演练了 13 条主库增量和库存 V9，MySQL 9.7.2 执行通过；此演练未改生产数据。

部署、健康检查、体验版上传及实际设备验证结果在操作完成后续记；上述准备不能写作已经上线或已完成真实支付验收。
