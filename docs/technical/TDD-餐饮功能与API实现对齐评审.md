# 餐饮功能与API实现对齐评审

核对日期：2026-10-25。代码基线：`d5ea1b066`。功能基线：[已交付功能说明](../requirements/餐饮系统-已交付功能说明.md)，完整目标仍由[需求矩阵](../requirements/餐饮系统-需求矩阵.md)保留。

## 一 结论与阅读顺序

非支付业务已具备真实Controller、Service实现和共享能力调用，不能将该结论扩大为全部收银、终端页面或门店营业流程均已完成。此次确认一项P2代码合同问题：厨房任务列表接受终态筛选，但底层活动队列先排除了终态。另修正一项预约说明过度宣称，并明确身份、取消、退餐、实际耗用及连锁配置边界。

1. 本册先按功能查入口和实现，再看问题及后续动作。
2. [实际公开API对照](./design/餐饮系统-实际公开API对照.md)逐项给出HTTP、路径、参数声明、返回类型、权限声明和直接委托，附Controller及本地/远程实现源码链接。
3. [原API设计](./design/餐饮系统-API设计.md)保留完整目标和历史切片；其中PLANNED、早期部署基线及旧验收状态不能直接当本次可用接口合同。

本轮为静态源码审查及文档对齐，没有修改业务代码、执行数据库迁移、测试、部署或远端同步。已查阅的历史测试作为行为佐证，不记为本轮运行通过。没有证实新的P1问题，不等于全代码不存在其他缺陷。

## 二 覆盖与接口口径

| 范围 | 核对结果 |
|---|---|
| 餐饮公开Controller | 59个，位于food-web的biz、mp、ops包 |
| 公开映射声明 | 252条：B端226、C端23、平台端3 |
| 报表路径展开 | 一个query Handler的正则映射覆盖10种view；按明确可选view展开共261个功能路径，不另算10个Handler |
| 路径重复 | 252条声明未发现重复HTTP方法＋路径模板 |
| 权限声明 | B端和平台端各声明均具有方法级权限注解；实际有效权限还取决于认证、数据范围和Service校验，不能只凭注解宣称安全验收完成 |
| C端身份 | 按注册顾客、桌码会话或设备会话分别校验，不能因无B端权限注解视为匿名公开 |
| 内部合同 | 源码发现food-server中258条内部映射声明，仅作为独立部署适配线索，不计入公开API数量、不向前端直接开放 |
| 共享能力 | 税、规则、人工调整、基础商品、核心订单、支付、库存和打印账本仍由原模块持有；不计作餐饮新增公开入口 |

映射清单通过现有`discoverFoodSource`只读提取，并与food-web的252行Mapping声明数量核对；未运行会写入多份生成文档的完整生成器。Controller首跳全部可映射到Service/Port字段调用；深层调用链采用下面各功能的重点人工追踪，不声称穷举所有分支。

## 三 功能到API与实现对照

下表中 B 为`/biz/x/food`，C 为`/mp/x/food`，O 为`/ops/x/food`。花括号是实际路径参数；省略的同类CRUD、具体DTO及输入约束均在API对照册逐条列出。“已对齐”只针对所列受控后端范围。

| 功能及需求范围 | 实际入口举例 | 实现及共享调用 | 对齐结论与边界 |
|---|---|---|---|
| 营业策略和日历 A02/A05/A06 | B GET `/stores/{storeNo}/policy`，POST `/stores/{storeNo}/policies`，PUT `/stores/{storeNo}/calendar/{localDate}` | StoreOperationServiceImpl.getPolicy/createPolicy/saveCalendar | 已对齐；门店原营业时间仍是上界，OPEN例外不能扩大原营业时间；capabilities/approvalRef非空当前拒绝，不表示能力模板编辑完成 |
| 桌区和桌台 A04 | B GET/POST `/stores/{storeNo}/table-areas`、`/tables`；PUT `/table-areas/{areaNo}`、`/tables/{tableNo}` | TableServiceImpl，原版本及占用约束 | 已对齐；有占用或预约时，归档、缩容等受限 |
| 桌码及就餐 C05/C08 | B POST `/tables/{tableNo}:rotate-token`、`:revoke-token`；C POST `/sessions`、`/sessions/{sessionNo}:end` | DiningSessionServiceImpl，真实会话与桌码生命周期 | 已对齐；旧码失效、会话结束不等于交易已取消或已付 |
| 开台转并拼桌 C05/C06 | B POST `/stores/{storeNo}/checks`、`/checks/{checkNo}:transfer`、`:combine-tables`、`:clear`、`/tables/{tableNo}:share` | CheckServiceImpl，桌台锁、占用及台账 | 已对齐；物理关系变化保留原订单事实 |
| 带单拼拆及责任移动 C06/C07 | B POST `/business-operations/checkCollaboration/moveBatches`、`/moveLines`、`/freeze`、`/release` | CheckCollaborationServiceImpl，原行归属及原应收读取 | 已对齐；跨check业务责任移动不重新生成已成交订单、不移动已付资金 |
| 桌位预约 C01/C04 | B GET/POST `/stores/{storeNo}/reservations`；POST `/reservations/{reservationNo}:change`、`:cancel`、`:no-show`、`:arrive` | ReservationServiceImpl.create/change/allocate/arrive | 已对齐本地桌位容量、占用及重叠检查；没有在纯桌位预约时自动占用接单slot，见问题A02 |
| 预点及定时点餐 C01/D09 | B POST `/business-operations/reservationPreorder/{save,get,prepareCart,convert,cancel}`；`/business-operations/serviceSlot/{create,get,reserve,transition,dispatchDue}` | ReservationPreorderServiceImpl、ServiceSlotServiceImpl → DiningOrderService.staffPlace | 已对齐本批非资金范围；预点保存不提前建交易；到店准备购物车与定时点餐的餐段、截止和产能链路分别校验 |
| 排队与通知 C03 | B GET/POST `/queue-tickets`，POST `/queue-tickets:call-next`及ticket的call/seat/recover-seating等；POST `/business-operations/queueNotification/list`、`/recover` | QueueServiceImpl → 可恢复开台；QueueNotificationServiceImpl → provider查询及发送 | 已对齐后端意图、状态及恢复；供应商DELIVERED与本地意图分开，实际送达待联验 |
| 菜品 B01/B03 | B GET/PUT `/stores/{storeNo}/dishes/{goodsNo}`及列表 | DishServiceImpl，原商品/SKU与图片权属事实 | 已对齐；餐饮草稿不修改原商品价格或实物库存 |
| 餐牌和时段 B02/B07 | B GET/POST `/stores/{storeNo}/menus`，PUT `/menus/{menuNo}`、`/definition`；GET B `/stores/{storeNo}/catalog`及C `/catalog` | MenuServiceImpl、CatalogServiceImpl → 已发布快照、原商品及营业日历 | 已对齐门店范围配置；本地渠道价格由餐牌contexts/skuPrices进入真实报价，不表示第三方同步或总部下发已完成 |
| 口味及收费配料 B03/B04/B06 | B POST `/stores/{storeNo}/option-groups`、`/modifiers`；PUT `/option-groups/{groupNo}/items`、`/modifiers/{modifierNo}` | OptionGroupServiceImpl、ModifierServiceImpl → 发布配置和有效选择 | 已对齐；草稿须发布并进入适用餐牌，不能只完成CRUD就售卖 |
| 套餐 B05 | B POST `/stores/{storeNo}/bundles`，PUT `/bundles/{skuNo}/definition` | BundleServiceImpl；DiningPriceSourcePolicy → 冻结组件与价格分配 | 已对齐固定/自选定义及本批真实后付成交；不推定完整预付资金闭环 |
| 配方与发布 B06/E09 | B POST `/stores/{storeNo}/recipes`，PUT `/recipes/{recipeNo}/definition`；相应配置`:publish`及`/config-revisions/{revisionNo}:republish` | RecipeServiceImpl、ConfigRevisionServiceImpl → 原物料基础单位及不可变版本 | 已对齐；发布不产生库存移动，历史重发布生成新版本，不覆盖旧版本 |
| 沽清及配额 B08 | B GET/POST `/stores/{storeNo}/sales-restrictions` | SalesRestrictionServiceImpl.manage/evaluate；报价及下单重校验 | 已对齐；销售限制和实物库存分开，配额恢复不回写物理库存 |
| 身份与点餐设备 D01/D02 | B POST `/dining-devices`、`/parties`及查询/撤权；C POST `/stores/{storeNo}/dining-device-sessions` | DiningIdentityServiceImpl，设备凭据hash、版本与会话校验 | 已对齐；匿名来源不是注册账号，也不具有资产授权 |
| 购物车 D03/D05 | B/C GET `/carts/{cartNo}`；POST `/stores/{storeNo}/carts`；PUT `/carts/{cartNo}/lines/{sourceLineRef}`及`:archive`；B `/checks/{checkNo}/cart-sessions` | CheckCartServiceImpl → 草稿、会话权限、OPEN及version校验 | 已对齐；B/C的授权和可执行动作不同，不假定两端对称 |
| 报价 D04/B07/B09 | B/C POST `/stores/{storeNo}/quotes`，GET `/quotes/{quoteNo}`，POST `/quotes/{quoteNo}:confirm` | DiningPricingServiceImpl → PricingCheckoutPort → 共享PricingCheckoutServiceImpl；核心按来源合同回读DiningPricingFactsServiceImpl | 已对齐；复杂目录条目要求报价不代表被永久禁售，真实下单仍须冻结选择、数量、税和库存计划 |
| 正式订单及加菜 D05/D07/D08 | B/C POST `/stores/{storeNo}/batches`，GET `/batches/{batchNo}`；B GET `/stores/{storeNo}/batches` | DiningOrderServiceImpl → IndustryOrderPort → 核心订单及独立库存；DiningBatchQueryServiceImpl | 已对齐非支付范围；员工POS/WAITER仅POSTPAY，匿名/设备POSTPAY，注册顾客既有受控PREPAY保留；不是所有身份和支付模式自由组合 |
| 员工处理中取消 G01 | B POST `/batches/{batchNo}:cancel`；草稿archive另走cart接口 | DiningOrderServiceImpl.staffCancel | 限POS/WAITER、POSTPAY、未绑定原单的PREPARED/PLACING/UNKNOWN；MP没有对应batch cancel入口，正式受理后走退餐 |
| 非资金退餐 G02/G04/G08 | B POST `/batches/{batchNo}/business-returns`及查询/cancel/approve/link-replacement；GET/POST `/returns/{returnNo}/kitchen` | BusinessReturnServiceImpl → BusinessReturnApprovalServiceImpl → IndustryOrderAmendmentPort；KitchenReturnServiceImpl | 已对齐原行退量、厨房处置和应收确认；换菜经原退量＋新购物车下单＋关联替换，非原正式行任意改价；不执行新增资金退款 |
| 历史顾客售后 G03/G05 | C POST `/stores/{storeNo}/returns`、GET `/returns/{returnNo}`、POST `:cancel`；B仅查询原记录 | DiningReturnServiceImpl → IndustryAfterSalePort | 既有受控路径保留，仅注册buyer，不接受session/token；与匿名点餐及B端非资金退餐分开 |
| 业务AA C07/F05 | B POST `/checks/{checkNo}/business-share-plans`及get/confirm/cancel | BusinessShareServiceImpl → 原有效应收及分配版本 | 已对齐份额和责任，不执行AA收款；没有C端AA路由 |
| 厨房制作 E01～E06/E09 | B GET `/stores/{storeNo}/kitchen/tasks`；POST `/tasks/{taskNo}:start/:ready/:serve/:hold/:fire/:urge/:void/:loss/:remake`及events；course接口 | KitchenTaskServiceImpl.accepted/queue/act/remake；KitchenQuantityPolicy | 已对齐受理、工位、部分数量、道次、装配与重做；终态筛选存在A01代码问题 |
| 打包自提外送 E06～E08 | B POST `/batches/{batchNo}:confirm-packing`、`:handover`、`:verify-pickup`及凭证查询；B/C cart delivery读写 | KitchenTaskServiceImpl.handover → IndustryFulfillmentPort；DiningPickupVerificationServiceImpl、DiningDeliveryServiceImpl | 已对齐本批部分交付、自提核销、自营外送快照及交接；不代表骑手厂商或Keeta已接入 |
| 实际耗用 E09/J11 | B GET `/stores/{storeNo}/production-consumptions`；POST `/production-events/{eventNo}:consume/:reverse`、`/production-tasks/{taskNo}:adjust` | ProductionConsumptionServiceImpl → InventoryProductionPort → 独立库存owner | 已对齐领耗、补料/报损及冲销；adjust输入任务/组件数量后按冻结配方展开材料，非任意材料实耗列表；缺历史成本保持未知 |
| 打印设备 H01～H05 | B `/print-configuration`、`/print-jobs`及job的retry/reprint；`/print-devices`及revoke/heartbeat/claim/acknowledge | FoodPrintServiceImpl → 共享PrintPort/PrintServiceImpl | 已对齐后端冻结、投递和回执；UNKNOWN不可自动retry，人工reprint须原因；SDK及物理出纸待验收 |
| KDS和工位 H06/E10 | B `/kitchen-subscriptions`及revoke，GET `/kitchen-events`、`/kitchen-stream`；station runtime接口 | FoodKdsServiceImpl、FoodKdsStreamServiceImpl、FoodStationRuntimeServiceImpl | 已对齐授权、设备/工位范围及断线回放；终端体验和语音设备效果另验 |
| 营业日及非资金结转 I04/I06/I08/I09 | B POST `/business-days/{date}:open`，GET `/reports/business-day`，POST `/closes:preview/:sign/:adjust`及异常propose-carry/approve-carry | BusinessCloseServiceImpl → 原事实、水位和签署快照 | 已对齐受控范围；签署到CLOSING，不等于全店资金CLOSED；异常结转当前限SYNC且提议与审批分人 |
| 营业责任及异常恢复 I07/K08 | B POST `/business-operations/businessOperations/{evidence,propose,handover,accept,cancel,openCase,cases,recoverCase,resolveCase}` | BusinessOperationsServiceImpl → 原check/batch/reservation/queue/preorder来源 | 已对齐非资金责任交接和授权恢复；不是钱箱现金交班 |
| 经营报表 J组首批 | B GET `/operating-reports/metrics`、`/realtime`、`/inventory/realtime`及query的10种view；POST/GET exports及download | DiningReportServiceImpl → 共享ReportQuery/Realtime/Export服务及独立统计库 | 代码对齐，生产未启用；缺源指标UNKNOWN/PARTIAL；不是全部报表目标业务验收通过 |
| 平台支持 K06/K07 | O GET `/support/readiness`、`/cases`、`/source` | FoodSupportServiceImpl → schema/provider诊断、原异常和证据只读 | 已对齐；验证OPERATOR及商家数据域，无营业恢复写入口；readiness不是门店可营业证书 |

## 四 输入输出与共享入口

每个公开Handler的实际输入绑定、类型、注解约束和返回类型已列入[API对照册](./design/餐饮系统-实际公开API对照.md)。DTO完整字段继续引用原Java定义和[原API设计当前源码清单](./design/餐饮系统-API设计.md)，本轮不另造一套与Java脱节的请求模型。

| 接入约定 | 核对结论 |
|---|---|
| 商家/门店/操作者 | 从原认证及BizContext、StoreDirectory取得可信范围；路径storeNo与当前门店校验，不用Body伪造上下文 |
| 幂等 | 写操作沿各自声明的Idempotency-Key/原生命周期版本；不是所有写接口都具有相同Header，按具体Handler使用 |
| 版本与金额 | 报价、草稿、退量、AA及库存动作分别保留版本/原来源；不允许终端自行覆盖服务端成交金额 |
| 数量及单位 | 使用精确物理数量和受控单位；不得静默截断或推断任意单位换算 |
| 状态与返回 | 服务端UNKNOWN/未付/未结清保持原义；成功创建意图、份额或打印任务不能解释为实际收款/交付/出纸 |
| 多语言与显示 | API存在不表示三语/RTL终端已完成；菜名、票面和设备显示须页面及真机验收 |

共享管理入口保留原归属：

- 税率及价内/价外模式：`GET/PUT /biz/pricing-tax-settings`、`POST /biz/pricing-tax-settings/reset` → PricingTaxSettingService。
- 商品优惠参与及税分类：`GET/PUT /biz/goods/{goodsNo}/skus/{skuNo}/pricing-policy` → GoodsPricingPolicyService。
- 门店规则配置：`/biz/pricing-configs` 下initialize、draft、preview、publish、rollback等 → StorePricingConfigService；真实发布仍需原来源及能力证据，配置端点不代表所有行业已开放资产执行。
- 人工调整：`/biz/pricing-manual-policies/{currency}/{reasonCode}/{kind}`、`/biz/pricing-manual-adjustments`及approve/quote → PricingManualAdjustmentService；FOOD报价接口不独立授予人工调价权限。
- 费用规则及有效应收调整：`/biz/pricing/fee-rules/{ruleNo}`、`/biz/pricing/fee-scopes/{scopeNo}/adjustments`及受控apply，继续归原费用与订单owner。

## 五 已确认问题及说明修订

### A01 P2 厨房终态筛选与活动队列不一致

**类型：代码合同问题；本轮验收已在本地修复，尚未提交发布。**

触发：`GET /biz/x/food/stores/{storeNo}/kitchen/tasks?stationNo=HOT&status=SERVED`，存在已上菜历史任务仍返回空；VOIDED同样如此。

证据：[BizKitchenTaskController](../../backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java) 第60行允许SERVED/VOIDED，第61行固定传`batchNo=null`；[KitchenTaskServiceImpl](../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/KitchenTaskServiceImpl.java) 第441行将其解释为活动队列，第468行排除关闭ticket，第483～486行排除SERVED/VOIDED任务。独立代理转发相同参数，不能消除此差异。

既有KitchenQueueMapperTest静态用例也区分了“batchNo为空只返回活动任务”和“指定batch可读历史”；尚无该Controller终态筛选的运行回归证据。本次未执行测试，不宣称已运行复现。

处理建议：当前端点若保持活动队列语义，显式拒绝这两种终态筛选；历史回看另提供按批次/日期及分页限制的合同。若业务要求当前入口兼容历史，则先补有界查询和明确状态下推，不能简单放开全表扫描。

### A02 P2 纯桌位预约的说明超出当前校验

**类型：功能说明问题，已在已交付功能说明中收敛。**

原说明把桌位预约和“按餐段与产能约束受理”写为同一路径。实际[ReservationServiceImpl](../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ReservationServiceImpl.java) 第175行create及第415行allocate只校验未来时间、桌台版本、容量、占用及重叠，不读取接单slot。闭店时段或独立接单slot已满时，仍可能创建没有桌位冲突的预约。

[ServiceSlotServiceImpl](../../backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/ServiceSlotServiceImpl.java) 第244行reserve另行校验OPEN、截止及容量。修订后明确：桌位资源预约与定时点餐产能是两套约束，不能从预约CONFIRMED推断已承诺接单产能。若产品要求预约即占产能，需要单独接通创建、变更、取消及到店时的产能生命周期。

### A03 端能力与流程颗粒度说明

**类型：说明补充，已修订，不作为新增代码缺陷。**

- 员工下单仅POSTPAY；匿名/设备下单仅POSTPAY；注册顾客基础PREPAY保留。不能把“支持先付和后付”理解为每个端都已支持全部组合。
- 草稿archive、员工未绑定批次取消和正式受理后退餐是三套操作；MP没有任意批次取消接口。
- C端历史售后要求注册buyer且拒绝session/token；B端business-return负责本批非资金退量、审批及厨房处置，不向匿名端自动开放。
- 换菜按原退量、新购物车报价下单、关联替换处理，没有通用“原正式行任意改价/增量”接口。
- actual consumption的adjust输入菜品/组件任务数量，再按冻结配方展开，不是任意物料SKU实耗录入；完整逐物料盘耗需另行交付。
- 当前餐牌配置为门店范围；总部批量下发、多门店override、菜品批量导入导出、能力模板编辑未据此认定完成。

### A04 原生成设计保留早期状态

**类型：文档维护待办，不是运行代码缺陷。**

[原API设计](./design/餐饮系统-API设计.md)仍保留FOOD V15云端基线、早期CODED_PENDING_VERIFICATION及H5/PICKUP切片文字；部分是历史验收边界，不能当成当前所有接口限制。当前BIZ发布记录已为FOOD25、CORE581及inventory14，报表后续又完成集成发布。

本轮使用新对照册明确当前快照及真实入口，不手改生成文件或抹除历史切片。后续从`food-system-design.json`与`gen-food-system-design.mjs`、`food-source-inventory.mjs`的状态输出统一修订阅读标记，再生成原册；保留“源码存在、专项验证、生产启用、营业验收”四种不同状态。

## 六 当前不应宣称已完成的入口或场景

| 完整目标 | 当前对应证据和范围 | 剩余工作 |
|---|---|---|
| 收银员完整收银 | B端settlement仅GET读取；C端既有create/pay/finalize/cancel保留受控原单路径 | 员工支付授权、现金找零、混合/跨根/AA收款及相关恢复 |
| 券储值积分结账 | 共享模型及规则基础存在，当前PricingCheckoutServiceImpl baseline仍拒绝真实资产选择 | 真实资格、授权/核销、资金提交及退款；不以规则JSON代替owner回执 |
| 完整日结和现金班次 | 受控营业日预结/签署、SYNC结转和非资金责任交接 | 现金盘点及完整资金对账、结清与更正闭环 |
| 连锁餐牌下发和批量导入 | 门店范围的配置/发布存在 | 总部批量下发、多门店差异覆盖及菜品批量导入导出管理流程 |
| 完整经营报表 | 首批查询、构建、导出及失败处理代码存在 | 生产独立库/任务/权限启用，真实成本、费用、优惠税额、支付归属和历史库存证据 |
| 真实打印及通知 | 任务、桥接回执、通知provider合同和UNKNOWN恢复存在 | 指定厂家SDK、实机出纸、屏幕及供应商真实送达联验 |
| Keeta/离线/酒店寄存等 | 保留规划或局部基础引用 | 真实渠道、边缘写入同步、专属资金及专项完整流程，分别验收 |

## 七 接入与后续任务

1. 本次已完成：按源码对齐功能说明；形成公开API逐项快照及功能调用链；记录A01代码缺口及A04生成状态维护项。
2. A01厨房活动队列合同已在本轮验收本地修复；新增冲销方向与MVC参数错误映射问题也已先失败再修复，详见[验收执行报告](./TDD-餐饮功能验收与接口测试.md)。历史回看仍不是当前活动队列能力。
3. 同步维护原设计源及生成器的状态标签，保留稳定需求编号和旧验收记录；避免新前端再次依据旧计划路径或DTO接入。
4. 终端联调依本册选择各端真实合同：桌台/菜单 → 报价/下单 → 厨房/交付 → 非资金退餐 → 营业责任；完整收银资金及报表启用按各自专项推进。

本轮没有创建新的业务API、改动资金执行、启用报表或迁移生产数据库。


## 八 验收阶段补充

当前验收入口为[功能验收与接口测试](./TDD-餐饮功能验收与接口测试.md)及其166项矩阵。本评审原结论对应d5ea1b066，不能将其中“本轮未改业务代码”误读为后续验收也没有代码修复。

A01本地修复将SERVED/VOIDED筛选改为明确BAD_REQUEST。新增A05为ProductionConsumptionServiceImpl冲销ABSENT误转正向consume、正向CONFIRMED误结冲销；新增A06为共享GlobalExceptionHandler未识别MVC方法校验异常。A05/A06均已观察失败后修复并定向验证；未改变付款或生产报表开关。
