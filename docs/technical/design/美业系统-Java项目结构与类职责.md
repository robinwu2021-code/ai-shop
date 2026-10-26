# 美业 Java 项目结构与类职责

> 状态：目标目录与实际实施对照 · 2026-10-24 · Task59代码接线已实现，实际部署后置；全表不代表全部完成。
> 由 `python3 scripts/gen-beauty-java-project-design.py` 生成，勿手改。真源：[项目结构JSON](./schema/beauty-java-project-design.json)。
> 总体决策：[美业独立项目与包依赖方案](../TDD-美业系统-独立项目与包依赖方案.md)；接口：[分模块API](./美业系统-API清单.md)；数据库：[结构设计](./美业系统-数据库结构设计.md)。

## 一、定位

以`backend-plugins/shop-industry-beauty`为独立Maven reactor，五个jar、一个聚合POM；美业根包`ai.neargo.shop.beauty`。20业务模块按职责归包，不拆20个工程。名称仅是实施定位，不要求一次生成全部空类。

![美业模块依赖](../diagrams/beauty-java-module-dependencies.svg)

箭头表示使用方依赖提供方，虚线表示按部署选择的协议依赖。业务实现与用户Web同级依赖接口；独立业务server不引用户web。共享平台依赖在下表列出，未把共享实现藏进美业core。

## 二、Maven模块

| 构件 | 职责 | 必需直接依赖 | 按需依赖 |
|---|---|---|---|
| beauty-api | 业务接口、Req/VO、内部共同协议、美业自有跨域SPI | shop-base | — |
| beauty-core | 业务实现、实体、Mapper、规则、事件、恢复Job、内部入口 | beauty-api、shop-base、shop-store-mybatis | job-api（启用业务Job时） |
| beauty-web | C/B/平台Controller、Web鉴权接入、本地或远程Service选择 | beauty-api、shop-base、shop-base-auth | beauty-channel（standalone接入） |
| beauty-channel | 内部HTTP协议客户端、权威Port远程实现、第三方协议适配 | beauty-api、shop-base、svc-client | — |
| beauty-server | 美业独立内部业务启动、主数据源、内部安全与就绪检查 | beauty-core、beauty-channel | — |

## 三、完整包路径

| 模块 | Java包 | 内容 |
|---|---|---|
| beauty-api | `ai.neargo.shop.beauty.service` | 业务Service接口 |
| beauty-api | `ai.neargo.shop.beauty.dto` | Req、Query、VO，不暴露持久化实体 |
| beauty-api | `ai.neargo.shop.spi.beauty` | 仅美业自有跨域契约与事实；按真实接入创建 |
| beauty-core | `ai.neargo.shop.beauty.service.impl` | 同名ServiceImpl；短事务和业务协调 |
| beauty-core | `ai.neargo.shop.beauty.entity` | 25项bty候选表对应Entity |
| beauty-core | `ai.neargo.shop.beauty.mapper` | 唯一BeautyMappers及内嵌对象Mapper |
| beauty-core | `ai.neargo.shop.beauty.policy` | 匹配请求/业务规则，资源锁由共用所有者执行 |
| beauty-core | `ai.neargo.shop.beauty.statemachine` | 预约、任务、提交过程状态约束 |
| beauty-core | `ai.neargo.shop.beauty.assembler` | 日历/账单/打印内容等必要转换 |
| beauty-core | `ai.neargo.shop.beauty.event` | 真实事件消费、inbox去重、outbox发布接线 |
| beauty-core | `ai.neargo.shop.beauty.job` | 提交恢复、预约协调、快照清理等JobHandler |
| beauty-core | `ai.neargo.shop.beauty.config` | BeautyAutoConfiguration/Properties/PersistenceConfiguration |
| beauty-core | `ai.neargo.shop.beauty.api.internal` | 仅内部资源RPC入口，与用户Web隔离 |
| beauty-web | `ai.neargo.shop.beauty.api.mp` | 消费者本人/公开入口 |
| beauty-web | `ai.neargo.shop.beauty.api.biz` | 前台/技师/商家Web/老板共用商家资源入口 |
| beauty-web | `ai.neargo.shop.beauty.api.ops` | 受控治理诊断恢复入口 |
| beauty-web | `ai.neargo.shop.beauty.config` | BeautyWebAutoConfiguration及独立Web条件 |
| beauty-web | `ai.neargo.shop.beautyclient` | BeautyClientConfiguration；仅远程Service选择 |
| beauty-web | `ai.neargo.shop.beautyclient.impl` | RemoteAppointmentService等同接口薄适配 |
| beauty-channel | `ai.neargo.shop.beauty.client` | @HttpExchange接口、序列化、错误映射 |
| beauty-channel | `ai.neargo.shop.beauty.port` | 既有/已批准权威SPI远程实现 |
| beauty-channel | `ai.neargo.shop.beauty.adapter` | 按供应商接入的预约签名/外部号映射协议 |
| beauty-server | `ai.neargo.shop.beauty.server` | BeautyApplication |
| beauty-server | `ai.neargo.shop.beauty.server.config` | BeautyServerConfiguration/InternalSecurityConfiguration |

## 四、20业务模块主责

下列接口若属美业，完整名为`ai.neargo.shop.beauty.service.XxxService`，文件归beauty-api；同名`XxxServiceImpl`完整名为`ai.neargo.shop.beauty.service.impl.XxxServiceImpl`，归beauty-core。标注共用的能力不在美业生成同名接口/实体。

| 模块 | Service或共用接入 | 表/事实/边界 |
|---|---|---|
| A | StorePolicyService、CapabilityService | bty_store_policy/version；门店基本资料及准入由共用域提供 |
| B | ServiceDefinitionService、ServiceCatalogQueryService、AppointmentAvailabilityService | bty_service_definition/version/requirement；商品SKU价格目录复用 |
| C | 共用人员/账号Service或SPI | 不创建BtyPersonnel；人员、任职、岗位、资格、账号授权归共用域 |
| D | AppointmentWorkbenchQueryService、ResourceChangeService、GroupSchedulingService | 美业组合读模型/业务意图；排班、容量桶、hold、occupation归共用资源域 |
| E | GuestRegistrationService、CareProfileService | 临客仅用共用顾客登记身份；bty_care_profile/note/media保存护理扩展 |
| F | AppointmentService、AppointmentAvailabilityService | bty_appointment/item/assignment；资源仲裁通过共用合同 |
| G | ReceptionService | 到店/转工单/排队组合；共用等候号，无美业重复队列账 |
| H | WorkOrderService、ServiceTaskService、ServiceSubmissionService | bty_work_order/service_task/service_step/service_participation；来源编排与分配guard |
| I | WorkOrderService、ServiceSubmissionService | 账单投影和提交恢复，报价支付普通订单由Core/Pay执行 |
| J | 共用储值Service或SPI | 不创建美业充值/余额/消费流水表 |
| K | TreatmentPlanService | bty_treatment_plan/session；权益规则发行余次与使用账归Pay |
| L | 共用营销/会员/权益/分析Service或SPI | 无美业活动/券发行状态机副本 |
| M | 共用库存/订单Service或SPI | 耗材关联taskNo；不创建美业库存账 |
| N | ServiceCaseCoordinationService、ServiceTaskService | 任务撤销与证据归任务；争议/审批/退款走共用所有者，薄协调不新增同义案件聚合 |
| O | PerformanceService、CommissionPolicyService、CommissionService | bty_performance_allocation/share、commission_policy/entry |
| P | 共用班次/财务Service或SPI | 无美业钱箱/对账/结算账本 |
| Q | CustomerTimelineQueryService、AppointmentWorkbenchQueryService、ServiceReportQueryService | 派生读模型，投影不作为容量仲裁或支付事实 |
| R | ServiceExceptionService、FollowUpCoordinationService | 协调当前任务/护理内容，共用通知送达与回访任务管理 |
| S | ServicePrintDocumentService | 装配脱敏业务文档，打印任务/设备/补打账由共用域维护 |
| T | CapabilityService、ServiceTraceQueryService、ServiceOperationQueryService、ServiceRecoveryService | 业务受控查询/恢复；审计监控备份与外部通道复用 |

## 五、用户Controller归属

42个候选资源Controller承接95条美业候选HTTP接口；共用人员/资源HTTP与共用CONTRACT不转移到美业。Controller完整包名是类身份，Spring装配时还须验证跨行业Bean名不冲突。

| beauty-web内完整类名 | 调用的Service | API编号 |
|---|---|---|
| `ai.neargo.shop.beauty.api.biz.BizAppointmentController` | AppointmentService | BAPI-038、BAPI-039、BAPI-040、BAPI-041、BAPI-042、BAPI-043、BAPI-044、BAPI-045、BAPI-052 |
| `ai.neargo.shop.beauty.api.biz.BizAppointmentWorkbenchController` | AppointmentWorkbenchQueryService | BAPI-025、BAPI-026、BAPI-117、BAPI-124 |
| `ai.neargo.shop.beauty.api.biz.BizAssetDisputeController` | ServiceCaseCoordinationService | BAPI-103 |
| `ai.neargo.shop.beauty.api.biz.BizAvailabilityController` | AppointmentAvailabilityService | BAPI-010 |
| `ai.neargo.shop.beauty.api.biz.BizBusinessTraceController` | WorkOrderBillService | BAPI-137 |
| `ai.neargo.shop.beauty.api.biz.BizCapabilityApplicationController` | CapabilityService | BAPI-004 |
| `ai.neargo.shop.beauty.api.biz.BizCapabilityController` | CapabilityService | BAPI-135 |
| `ai.neargo.shop.beauty.api.biz.BizCareProfileController` | CareProfileService | BAPI-034、BAPI-035、BAPI-036、BAPI-037 |
| `ai.neargo.shop.beauty.api.biz.BizCommissionEntryController` | CommissionService | BAPI-108 |
| `ai.neargo.shop.beauty.api.biz.BizCommissionPolicyController` | CommissionPolicyService | BAPI-106、BAPI-107 |
| `ai.neargo.shop.beauty.api.biz.BizCommissionSettlementRecordController` | CommissionService | BAPI-110 |
| `ai.neargo.shop.beauty.api.biz.BizCustomerTimelineController` | CustomerTimelineQueryService | BAPI-116 |
| `ai.neargo.shop.beauty.api.biz.BizExceptionWorkitemController` | ServiceExceptionService | BAPI-125 |
| `ai.neargo.shop.beauty.api.biz.BizFollowUpTaskController` | FollowUpCoordinationService | BAPI-126、BAPI-127、BAPI-128 |
| `ai.neargo.shop.beauty.api.biz.BizGroupSchedulingController` | GroupSchedulingService | BAPI-029 |
| `ai.neargo.shop.beauty.api.biz.BizGuestRegistrationController` | GuestRegistrationService | BAPI-031、BAPI-032 |
| `ai.neargo.shop.beauty.api.biz.BizPerformanceAdjustmentCaseController` | PerformanceService | BAPI-109 |
| `ai.neargo.shop.beauty.api.biz.BizReceptionController` | ReceptionService | BAPI-047、BAPI-048、BAPI-053 |
| `ai.neargo.shop.beauty.api.biz.BizReportExportController` | ServiceReportQueryService | BAPI-121、BAPI-122 |
| `ai.neargo.shop.beauty.api.biz.BizResourceChangeController` | ResourceChangeService | BAPI-027、BAPI-028 |
| `ai.neargo.shop.beauty.api.biz.BizServiceController` | ServiceCatalogQueryService | BAPI-009 |
| `ai.neargo.shop.beauty.api.biz.BizServiceDefinitionController` | ServiceDefinitionService | BAPI-007、BAPI-008 |
| `ai.neargo.shop.beauty.api.biz.BizServiceOperationController` | ServiceOperationQueryService | BAPI-160 |
| `ai.neargo.shop.beauty.api.biz.BizServicePrintDocumentController` | ServicePrintDocumentService | BAPI-130 |
| `ai.neargo.shop.beauty.api.biz.BizServiceRemedyCaseController` | ServiceCaseCoordinationService | BAPI-102 |
| `ai.neargo.shop.beauty.api.biz.BizServiceReportController` | ServiceReportQueryService | BAPI-119、BAPI-120 |
| `ai.neargo.shop.beauty.api.biz.BizServiceSubmissionController` | ServiceSubmissionService | BAPI-072、BAPI-073 |
| `ai.neargo.shop.beauty.api.biz.BizServiceTaskController` | ServiceTaskService | BAPI-057、BAPI-058、BAPI-059、BAPI-060、BAPI-061、BAPI-062、BAPI-063、BAPI-064、BAPI-159、BAPI-100、BAPI-101、BAPI-105 |
| `ai.neargo.shop.beauty.api.biz.BizStorePolicyController` | StorePolicyService | BAPI-001、BAPI-002 |
| `ai.neargo.shop.beauty.api.biz.BizTreatmentPlanController` | TreatmentPlanService | BAPI-086、BAPI-087 |
| `ai.neargo.shop.beauty.api.biz.BizWaitingTicketController` | ReceptionService | BAPI-049、BAPI-050、BAPI-051 |
| `ai.neargo.shop.beauty.api.biz.BizWorkOrderController` | WorkOrderService | BAPI-054、BAPI-055、BAPI-056、BAPI-065、BAPI-066、BAPI-067、BAPI-071 |
| `ai.neargo.shop.beauty.api.mp.MpAppointmentController` | AppointmentService | BAPI-147、BAPI-148、BAPI-149、BAPI-150、BAPI-151、BAPI-152、BAPI-153 |
| `ai.neargo.shop.beauty.api.mp.MpAvailabilityController` | AppointmentAvailabilityService | BAPI-146 |
| `ai.neargo.shop.beauty.api.mp.MpCustomerTimelineController` | CustomerTimelineQueryService | BAPI-158 |
| `ai.neargo.shop.beauty.api.mp.MpReceptionController` | ReceptionService | BAPI-154 |
| `ai.neargo.shop.beauty.api.mp.MpServiceController` | ServiceCatalogQueryService | BAPI-145 |
| `ai.neargo.shop.beauty.api.mp.MpServiceDisputeController` | ServiceCaseCoordinationService | BAPI-157 |
| `ai.neargo.shop.beauty.api.mp.MpStoreController` | ServiceCatalogQueryService | BAPI-144 |
| `ai.neargo.shop.beauty.api.mp.MpTreatmentPlanController` | TreatmentPlanService | BAPI-156 |
| `ai.neargo.shop.beauty.api.mp.MpWorkOrderController` | WorkOrderService | BAPI-155 |
| `ai.neargo.shop.beauty.api.ops.OpsServiceRecoveryController` | ServiceRecoveryService | BAPI-138、BAPI-139 |

## 六、全部API/合同的所有权对照

| 编号 | 路由或合同 | 源模块 | 所有者 |
|---|---|---|---|
| BAPI-001 | `GET /biz/x/beauty/store-policies` | A | beauty-web |
| BAPI-002 | `POST /biz/x/beauty/store-policies/versions` | A | beauty-web |
| BAPI-003 | `CONTRACT merchant.store-context` | A | 共用合同所有者 |
| BAPI-004 | `POST /biz/x/beauty/capability-applications` | A | beauty-web |
| BAPI-005 | `CONTRACT product.catalog-and-store-offer` | B | 共用合同所有者 |
| BAPI-006 | `CONTRACT catalog.sales-calendar` | B | 共用合同所有者 |
| BAPI-007 | `GET /biz/x/beauty/service-definitions` | B | beauty-web |
| BAPI-008 | `POST /biz/x/beauty/service-definitions/versions` | B | beauty-web |
| BAPI-009 | `GET /biz/x/beauty/services/{skuNo}` | B | beauty-web |
| BAPI-010 | `POST /biz/x/beauty/availability/queries` | B | beauty-web |
| BAPI-011 | `CONTRACT quote.configured-options` | B | 共用合同所有者 |
| BAPI-012 | `GET /biz/personnel` | C | 共用人员/资源所有者 |
| BAPI-013 | `POST /biz/personnel` | C | 共用人员/资源所有者 |
| BAPI-014 | `POST /biz/personnel/{personnelNo}/status-changes` | C | 共用人员/资源所有者 |
| BAPI-015 | `POST /biz/personnel/{personnelNo}/account-links` | C | 共用人员/资源所有者 |
| BAPI-016 | `POST /biz/personnel/{personnelNo}/assignments` | C | 共用人员/资源所有者 |
| BAPI-017 | `CONTRACT merchant.roles-and-permissions` | C | 共用合同所有者 |
| BAPI-018 | `POST /biz/personnel-cooperations` | C | 共用人员/资源所有者 |
| BAPI-019 | `CONTRACT identity.session-revoke` | C | 共用合同所有者 |
| BAPI-020 | `GET /biz/resources` | D | 共用人员/资源所有者 |
| BAPI-021 | `POST /biz/resources` | D | 共用人员/资源所有者 |
| BAPI-022 | `GET /biz/schedules` | D | 共用人员/资源所有者 |
| BAPI-023 | `POST /biz/schedule-changes/previews` | D | 共用人员/资源所有者 |
| BAPI-024 | `POST /biz/schedule-changes` | D | 共用人员/资源所有者 |
| BAPI-025 | `GET /biz/x/beauty/workbench/calendar` | D | beauty-web |
| BAPI-026 | `GET /biz/x/beauty/workbench/calendar/pages` | D | beauty-web |
| BAPI-027 | `POST /biz/x/beauty/resource-changes/previews` | D | beauty-web |
| BAPI-028 | `POST /biz/x/beauty/resource-changes` | D | beauty-web |
| BAPI-029 | `POST /biz/x/beauty/group-scheduling/previews` | D | beauty-web |
| BAPI-030 | `CONTRACT member.identification` | E | 共用合同所有者 |
| BAPI-031 | `POST /biz/x/beauty/guest-registrations` | E | beauty-web |
| BAPI-032 | `POST /biz/x/beauty/guest-registrations/{guestNo}/claims` | E | beauty-web |
| BAPI-033 | `CONTRACT member.profile-and-history` | E | 共用合同所有者 |
| BAPI-034 | `GET /biz/x/beauty/care-profiles/{customerNo}` | E | beauty-web |
| BAPI-035 | `POST /biz/x/beauty/care-profiles/{customerNo}/notes` | E | beauty-web |
| BAPI-036 | `POST /biz/x/beauty/care-profiles/{customerNo}/media-authorizations` | E | beauty-web |
| BAPI-037 | `POST /biz/x/beauty/care-profiles/{customerNo}/privacy-requests` | E | beauty-web |
| BAPI-038 | `POST /biz/x/beauty/appointments/previews` | F | beauty-web |
| BAPI-039 | `POST /biz/x/beauty/appointments` | F | beauty-web |
| BAPI-040 | `GET /biz/x/beauty/appointments` | F | beauty-web |
| BAPI-041 | `GET /biz/x/beauty/appointments/{appointmentNo}` | F | beauty-web |
| BAPI-042 | `POST /biz/x/beauty/appointments/{appointmentNo}/changes/previews` | F | beauty-web |
| BAPI-043 | `POST /biz/x/beauty/appointments/{appointmentNo}/changes` | F | beauty-web |
| BAPI-044 | `POST /biz/x/beauty/appointments/{appointmentNo}/cancellations` | F | beauty-web |
| BAPI-045 | `POST /biz/x/beauty/appointments/{appointmentNo}/no-shows` | F | beauty-web |
| BAPI-046 | `CONTRACT booking.deposit-and-penalty-policy` | F | 共用合同所有者 |
| BAPI-047 | `POST /biz/x/beauty/arrivals` | G | beauty-web |
| BAPI-048 | `POST /biz/x/beauty/walk-ins` | G | beauty-web |
| BAPI-049 | `GET /biz/x/beauty/waiting-tickets` | G | beauty-web |
| BAPI-050 | `POST /biz/x/beauty/waiting-tickets` | G | beauty-web |
| BAPI-051 | `POST /biz/x/beauty/waiting-tickets/{ticketNo}/actions` | G | beauty-web |
| BAPI-052 | `POST /biz/x/beauty/appointments/{appointmentNo}/work-orders` | G | beauty-web |
| BAPI-053 | `POST /biz/x/beauty/reception-exceptions` | G | beauty-web |
| BAPI-054 | `POST /biz/x/beauty/work-orders` | H | beauty-web |
| BAPI-055 | `GET /biz/x/beauty/work-orders/{workOrderNo}` | H | beauty-web |
| BAPI-056 | `POST /biz/x/beauty/work-orders/{workOrderNo}/service-submissions` | H | beauty-web |
| BAPI-057 | `GET /biz/x/beauty/tasks` | H | beauty-web |
| BAPI-058 | `POST /biz/x/beauty/tasks/{taskNo}/acceptances` | H | beauty-web |
| BAPI-059 | `POST /biz/x/beauty/tasks/{taskNo}/starts` | H | beauty-web |
| BAPI-060 | `POST /biz/x/beauty/tasks/{taskNo}/steps/{stepNo}/completions` | H | beauty-web |
| BAPI-061 | `POST /biz/x/beauty/tasks/{taskNo}/handovers/previews` | H | beauty-web |
| BAPI-062 | `POST /biz/x/beauty/tasks/{taskNo}/handovers` | H | beauty-web |
| BAPI-063 | `POST /biz/x/beauty/tasks/{taskNo}/interruptions` | H | beauty-web |
| BAPI-064 | `POST /biz/x/beauty/tasks/{taskNo}/completions` | H | beauty-web |
| BAPI-065 | `POST /biz/x/beauty/work-orders/{workOrderNo}/closure-previews` | H | beauty-web |
| BAPI-066 | `POST /biz/x/beauty/work-orders/{workOrderNo}/closures` | H | beauty-web |
| BAPI-067 | `POST /biz/x/beauty/work-orders/{workOrderNo}/stage-plans` | H | beauty-web |
| BAPI-068 | `CONTRACT trade.quote-and-create` | I | 共用合同所有者 |
| BAPI-069 | `CONTRACT payment.initiate-and-query` | I | 共用合同所有者 |
| BAPI-070 | `CONTRACT payment.offline-receipt` | I | 共用合同所有者 |
| BAPI-071 | `GET /biz/x/beauty/work-orders/{workOrderNo}/bill` | I | beauty-web |
| BAPI-072 | `GET /biz/x/beauty/service-submissions/{submissionNo}` | I | beauty-web |
| BAPI-073 | `POST /biz/x/beauty/service-submissions/{submissionNo}/recovery-requests` | I | beauty-web |
| BAPI-074 | `CONTRACT cashier.advanced-contracts` | I | 共用合同所有者 |
| BAPI-075 | `CONTRACT stored-value.plans` | J | 共用合同所有者 |
| BAPI-076 | `CONTRACT stored-value.recharges` | J | 共用合同所有者 |
| BAPI-077 | `CONTRACT stored-value.accounts` | J | 共用合同所有者 |
| BAPI-078 | `CONTRACT stored-value.consumptions` | J | 共用合同所有者 |
| BAPI-079 | `CONTRACT stored-value.history` | J | 共用合同所有者 |
| BAPI-080 | `CONTRACT stored-value.recovery` | J | 共用合同所有者 |
| BAPI-081 | `CONTRACT entitlement.rules` | K | 共用合同所有者 |
| BAPI-082 | `CONTRACT product.treatment-card-offers` | K | 共用合同所有者 |
| BAPI-083 | `CONTRACT entitlement.sale-and-issuance` | K | 共用合同所有者 |
| BAPI-084 | `CONTRACT entitlement.eligible-assets` | K | 共用合同所有者 |
| BAPI-085 | `CONTRACT entitlement.service-consumption` | K | 共用合同所有者 |
| BAPI-086 | `GET /biz/x/beauty/treatment-plans` | K | beauty-web |
| BAPI-087 | `POST /biz/x/beauty/treatment-plans` | K | beauty-web |
| BAPI-088 | `CONTRACT entitlement.policy-cases` | K | 共用合同所有者 |
| BAPI-089 | `CONTRACT promotion.eligible-and-quote` | L | 共用合同所有者 |
| BAPI-090 | `CONTRACT promotion.campaign-versions` | L | 共用合同所有者 |
| BAPI-091 | `CONTRACT member.tags-and-audiences` | L | 共用合同所有者 |
| BAPI-092 | `CONTRACT entitlement.grant` | L | 共用合同所有者 |
| BAPI-093 | `CONTRACT analytics.campaign-conversion` | L | 共用合同所有者 |
| BAPI-094 | `CONTRACT trade.goods-inventory-delivery` | M | 共用合同所有者 |
| BAPI-095 | `CONTRACT inventory.stock-operations` | M | 共用合同所有者 |
| BAPI-096 | `CONTRACT inventory.service-material-use` | M | 共用合同所有者 |
| BAPI-097 | `CONTRACT inventory.return-and-scrap` | M | 共用合同所有者 |
| BAPI-098 | `CONTRACT inventory.bom-and-cost` | M | 共用合同所有者 |
| BAPI-099 | `CONTRACT aftersale.standard` | N | 共用合同所有者 |
| BAPI-100 | `POST /biz/x/beauty/tasks/{taskNo}/cancellations` | N | beauty-web |
| BAPI-101 | `POST /biz/x/beauty/tasks/{taskNo}/service-evidence` | N | beauty-web |
| BAPI-102 | `POST /biz/x/beauty/service-remedy-cases` | N | beauty-web |
| BAPI-103 | `POST /biz/x/beauty/asset-disputes` | N | beauty-web |
| BAPI-104 | `CONTRACT asset.reversal-contract` | N | 共用合同所有者 |
| BAPI-105 | `GET /biz/x/beauty/tasks/{taskNo}/participations` | O | beauty-web |
| BAPI-106 | `GET /biz/x/beauty/commission-policies` | O | beauty-web |
| BAPI-107 | `POST /biz/x/beauty/commission-policy-versions` | O | beauty-web |
| BAPI-108 | `GET /biz/x/beauty/commission-entries` | O | beauty-web |
| BAPI-109 | `POST /biz/x/beauty/performance-adjustment-cases` | O | beauty-web |
| BAPI-110 | `POST /biz/x/beauty/commission-settlement-records` | O | beauty-web |
| BAPI-111 | `CONTRACT finance.receipt-history` | P | 共用合同所有者 |
| BAPI-112 | `CONTRACT cashier.shifts` | P | 共用合同所有者 |
| BAPI-113 | `CONTRACT finance.business-day-close` | P | 共用合同所有者 |
| BAPI-114 | `CONTRACT finance.channel-reconciliation` | P | 共用合同所有者 |
| BAPI-115 | `CONTRACT finance.settlements-and-cases` | P | 共用合同所有者 |
| BAPI-116 | `GET /biz/x/beauty/customer-timelines/{customerNo}` | Q | beauty-web |
| BAPI-117 | `GET /biz/x/beauty/workbench/summary` | Q | beauty-web |
| BAPI-118 | `CONTRACT analytics.money-separate-measures` | Q | 共用合同所有者 |
| BAPI-119 | `GET /biz/x/beauty/reports/scheduling` | Q | beauty-web |
| BAPI-120 | `GET /biz/x/beauty/reports/services` | Q | beauty-web |
| BAPI-121 | `POST /biz/x/beauty/report-exports` | Q | beauty-web |
| BAPI-122 | `GET /biz/x/beauty/report-exports/{exportNo}` | Q | beauty-web |
| BAPI-123 | `CONTRACT notification.delivery-and-preferences` | R | 共用合同所有者 |
| BAPI-124 | `GET /biz/x/beauty/workbench/exceptions` | R | beauty-web |
| BAPI-125 | `POST /biz/x/beauty/exception-workitems/{workitemNo}/resolutions` | R | beauty-web |
| BAPI-126 | `GET /biz/x/beauty/follow-up-tasks` | R | beauty-web |
| BAPI-127 | `POST /biz/x/beauty/follow-up-tasks` | R | beauty-web |
| BAPI-128 | `POST /biz/x/beauty/follow-up-tasks/{followUpNo}/entries` | R | beauty-web |
| BAPI-129 | `CONTRACT notification.marketing-consent` | R | 共用合同所有者 |
| BAPI-130 | `POST /biz/x/beauty/print-documents` | S | beauty-web |
| BAPI-131 | `CONTRACT device.print-routes-and-templates` | S | 共用合同所有者 |
| BAPI-132 | `CONTRACT printing.jobs` | S | 共用合同所有者 |
| BAPI-133 | `CONTRACT device.terminals` | S | 共用合同所有者 |
| BAPI-134 | `CONTRACT device.edge-service` | S | 共用合同所有者 |
| BAPI-135 | `GET /biz/x/beauty/capability-status` | T | beauty-web |
| BAPI-136 | `CONTRACT audit.scoped-events` | T | 共用合同所有者 |
| BAPI-137 | `GET /biz/x/beauty/traces/{businessNo}` | T | beauty-web |
| BAPI-138 | `GET /ops/x/beauty/recovery-workitems` | T | beauty-web |
| BAPI-139 | `POST /ops/x/beauty/recovery-requests` | T | beauty-web |
| BAPI-140 | `CONTRACT observability.health-and-alerts` | T | 共用合同所有者 |
| BAPI-141 | `CONTRACT governance.privacy-config-backup` | T | 共用合同所有者 |
| BAPI-142 | `CONTRACT integration.external-booking` | T | 共用合同所有者 |
| BAPI-143 | `CONTRACT offline.claim-and-sync` | T | 共用合同所有者 |
| BAPI-144 | `GET /mp/x/beauty/stores/{storeNo}` | A | beauty-web |
| BAPI-145 | `GET /mp/x/beauty/services/{skuNo}` | B | beauty-web |
| BAPI-146 | `POST /mp/x/beauty/availability/queries` | B | beauty-web |
| BAPI-147 | `POST /mp/x/beauty/appointments/previews` | F | beauty-web |
| BAPI-148 | `POST /mp/x/beauty/appointments` | F | beauty-web |
| BAPI-149 | `GET /mp/x/beauty/appointments` | F | beauty-web |
| BAPI-150 | `GET /mp/x/beauty/appointments/{appointmentNo}` | F | beauty-web |
| BAPI-151 | `POST /mp/x/beauty/appointments/{appointmentNo}/changes/previews` | F | beauty-web |
| BAPI-152 | `POST /mp/x/beauty/appointments/{appointmentNo}/changes` | F | beauty-web |
| BAPI-153 | `POST /mp/x/beauty/appointments/{appointmentNo}/cancellations` | F | beauty-web |
| BAPI-154 | `POST /mp/x/beauty/arrivals` | G | beauty-web |
| BAPI-155 | `GET /mp/x/beauty/work-orders/{workOrderNo}` | H | beauty-web |
| BAPI-156 | `GET /mp/x/beauty/treatment-plans` | K | beauty-web |
| BAPI-157 | `POST /mp/x/beauty/service-disputes` | N | beauty-web |
| BAPI-158 | `GET /mp/x/beauty/timeline` | Q | beauty-web |
| BAPI-159 | `GET /biz/x/beauty/tasks/{taskNo}` | H | beauty-web |
| BAPI-160 | `GET /biz/x/beauty/operations/{operationNo}` | T | beauty-web |

## 七、美业持久化候选类

只映射25项bty表，全部在beauty-core；实体继承现有BaseEntity。Mapper集合完整名为`ai.neargo.shop.beauty.mapper.BeautyMappers`，以下Mapper为其内嵌接口，不生成等义Repository。16项共用表结构全部排除。逻辑聚合/状态机与数据库Entity按职责组合，不再为每张表强制复制一个Domain对象。

| 表 | Entity完整类名 | 内嵌Mapper |
|---|---|---|
| bty_store_policy | `ai.neargo.shop.beauty.entity.BtyStorePolicy` | `BeautyMappers.StorePolicyMapper` |
| bty_store_policy_version | `ai.neargo.shop.beauty.entity.BtyStorePolicyVersion` | `BeautyMappers.StorePolicyVersionMapper` |
| bty_service_definition | `ai.neargo.shop.beauty.entity.BtyServiceDefinition` | `BeautyMappers.ServiceDefinitionMapper` |
| bty_service_definition_version | `ai.neargo.shop.beauty.entity.BtyServiceDefinitionVersion` | `BeautyMappers.ServiceDefinitionVersionMapper` |
| bty_service_requirement | `ai.neargo.shop.beauty.entity.BtyServiceRequirement` | `BeautyMappers.ServiceRequirementMapper` |
| bty_appointment | `ai.neargo.shop.beauty.entity.BtyAppointment` | `BeautyMappers.AppointmentMapper` |
| bty_appointment_item | `ai.neargo.shop.beauty.entity.BtyAppointmentItem` | `BeautyMappers.AppointmentItemMapper` |
| bty_appointment_assignment | `ai.neargo.shop.beauty.entity.BtyAppointmentAssignment` | `BeautyMappers.AppointmentAssignmentMapper` |
| bty_work_order | `ai.neargo.shop.beauty.entity.BtyWorkOrder` | `BeautyMappers.WorkOrderMapper` |
| bty_service_task | `ai.neargo.shop.beauty.entity.BtyServiceTask` | `BeautyMappers.ServiceTaskMapper` |
| bty_service_step | `ai.neargo.shop.beauty.entity.BtyServiceStep` | `BeautyMappers.ServiceStepMapper` |
| bty_service_participation | `ai.neargo.shop.beauty.entity.BtyServiceParticipation` | `BeautyMappers.ServiceParticipationMapper` |
| bty_service_submission | `ai.neargo.shop.beauty.entity.BtyServiceSubmission` | `BeautyMappers.ServiceSubmissionMapper` |
| bty_trade_binding | `ai.neargo.shop.beauty.entity.BtyTradeBinding` | `BeautyMappers.TradeBindingMapper` |
| bty_action_log | `ai.neargo.shop.beauty.entity.BtyActionLog` | `BeautyMappers.ActionLogMapper` |
| bty_care_profile | `ai.neargo.shop.beauty.entity.BtyCareProfile` | `BeautyMappers.CareProfileMapper` |
| bty_care_note | `ai.neargo.shop.beauty.entity.BtyCareNote` | `BeautyMappers.CareNoteMapper` |
| bty_care_media | `ai.neargo.shop.beauty.entity.BtyCareMedia` | `BeautyMappers.CareMediaMapper` |
| bty_treatment_plan | `ai.neargo.shop.beauty.entity.BtyTreatmentPlan` | `BeautyMappers.TreatmentPlanMapper` |
| bty_treatment_session | `ai.neargo.shop.beauty.entity.BtyTreatmentSession` | `BeautyMappers.TreatmentSessionMapper` |
| bty_performance_allocation | `ai.neargo.shop.beauty.entity.BtyPerformanceAllocation` | `BeautyMappers.PerformanceAllocationMapper` |
| bty_performance_share | `ai.neargo.shop.beauty.entity.BtyPerformanceShare` | `BeautyMappers.PerformanceShareMapper` |
| bty_commission_policy | `ai.neargo.shop.beauty.entity.BtyCommissionPolicy` | `BeautyMappers.CommissionPolicyMapper` |
| bty_commission_entry | `ai.neargo.shop.beauty.entity.BtyCommissionEntry` | `BeautyMappers.CommissionEntryMapper` |
| bty_source_allocation_guard | `ai.neargo.shop.beauty.entity.BtySourceAllocationGuard` | `BeautyMappers.SourceAllocationGuardMapper` |

## 八、生成物边界

本清单不生成Java源文件。API输入输出细节沿API清单和工作台契约；具体类与方法签名已整理到[Java类与方法清单](./美业系统-Java类与方法清单.md)及[Controller-Service-Mapper方法清单](./美业系统-Controller-Service-Mapper方法清单.md)，以方法设计JSON为真源；不使用BAPI序号作类名或方法名。共享权限、资金、资源、打印和任务等合同须先核验真实提供方，不能仅由类名推断其已实现。

## 九、Task59 实际增量类型

Task59 code/protocol scope implemented; two-JVM actual owner deployment DEFERRED; candidate inventory below is not completion evidence.

| Module / source | Actual class / responsibility |
|---|---|
| `backend-plugins/shop-industry-beauty/beauty-api/src/main/java/ai/neargo/shop/beauty/spi/BeautyReceptionInternalApi.java` | `ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/client/BeautyReceptionCalls.java` | `ai.neargo.shop.beauty.client.BeautyReceptionCalls`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/client/BeautyReceptionResponses.java` | `ai.neargo.shop.beauty.client.BeautyReceptionResponses`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/client/RemoteGuestRegistrationService.java` | `ai.neargo.shop.beauty.client.RemoteGuestRegistrationService`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/client/RemoteReceptionService.java` | `ai.neargo.shop.beauty.client.RemoteReceptionService`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/client/RemoteServiceSubmissionService.java` | `ai.neargo.shop.beauty.client.RemoteServiceSubmissionService`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/client/RemoteWorkOrderBillService.java` | `ai.neargo.shop.beauty.client.RemoteWorkOrderBillService`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/client/RemoteWorkOrderService.java` | `ai.neargo.shop.beauty.client.RemoteWorkOrderService`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/config/BeautyCheckoutClientConfiguration.java` | `ai.neargo.shop.beauty.config.BeautyCheckoutClientConfiguration`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteCheckoutPort.java` | `ai.neargo.shop.beauty.port.RemoteCheckoutPort`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-server/src/main/java/ai/neargo/shop/beauty/server/api/InternalBeautyGuestController.java` | `ai.neargo.shop.beauty.server.api.InternalBeautyGuestController`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-server/src/main/java/ai/neargo/shop/beauty/server/api/InternalBeautyOperationController.java` | `ai.neargo.shop.beauty.server.api.InternalBeautyOperationController`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-server/src/main/java/ai/neargo/shop/beauty/server/api/InternalBeautyReceptionController.java` | `ai.neargo.shop.beauty.server.api.InternalBeautyReceptionController`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-server/src/main/java/ai/neargo/shop/beauty/server/api/InternalBeautySubmissionController.java` | `ai.neargo.shop.beauty.server.api.InternalBeautySubmissionController`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-server/src/main/java/ai/neargo/shop/beauty/server/api/InternalBeautyTraceController.java` | `ai.neargo.shop.beauty.server.api.InternalBeautyTraceController`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-server/src/main/java/ai/neargo/shop/beauty/server/api/InternalBeautyWorkOrderController.java` | `ai.neargo.shop.beauty.server.api.InternalBeautyWorkOrderController`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-web/src/main/java/ai/neargo/shop/beauty/api/biz/BizArrivalChallengeController.java` | `ai.neargo.shop.beauty.api.biz.BizArrivalChallengeController`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-web/src/main/java/ai/neargo/shop/beauty/api/biz/BizBusinessTraceController.java` | `ai.neargo.shop.beauty.api.biz.BizBusinessTraceController`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-web/src/main/java/ai/neargo/shop/beauty/api/biz/BizGuestRegistrationController.java` | `ai.neargo.shop.beauty.api.biz.BizGuestRegistrationController`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-web/src/main/java/ai/neargo/shop/beauty/api/biz/BizReceptionController.java` | `ai.neargo.shop.beauty.api.biz.BizReceptionController`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-web/src/main/java/ai/neargo/shop/beauty/api/biz/BizServiceSubmissionController.java` | `ai.neargo.shop.beauty.api.biz.BizServiceSubmissionController`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-web/src/main/java/ai/neargo/shop/beauty/api/biz/BizWorkOrderController.java` | `ai.neargo.shop.beauty.api.biz.BizWorkOrderController`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-web/src/main/java/ai/neargo/shop/beauty/api/mp/MpReceptionController.java` | `ai.neargo.shop.beauty.api.mp.MpReceptionController`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend-plugins/shop-industry-beauty/beauty-web/src/main/java/ai/neargo/shop/beauty/api/mp/MpWorkOrderController.java` | `ai.neargo.shop.beauty.api.mp.MpWorkOrderController`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BoundDelegatedCalls.java` | `ai.neargo.shop.auth.BoundDelegatedCalls`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/security/InternalCall.java` | `ai.neargo.shop.spi.security.InternalCall`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/security/InternalReply.java` | `ai.neargo.shop.spi.security.InternalReply`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/security/InternalRequestBinding.java` | `ai.neargo.shop.spi.security.InternalRequestBinding`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalBindings.java` | `ai.neargo.shop.svc.InternalBindings`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalCallSerializer.java` | `ai.neargo.shop.svc.InternalCallSerializer`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalReplyDeserializer.java` | `ai.neargo.shop.svc.InternalReplyDeserializer`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |
| `backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalReplySerializer.java` | `ai.neargo.shop.svc.InternalReplySerializer`<br>Task59 public/typed/remote/private ingress; actual owners remain in existing domains |

HOST实际classpath排除beauty-core/server；SERVER排除shop-app/core/merchant/web；双JVM夹具已编码，运行待统一部署关口。

## 十、共用增量类型与实施状态

TASK68A_B1_CODE_COMPILED_STATIC_REVIEWED_B2_C_PENDING_UNIFIED_TEST_PENDING

主TDD§2.47：共用fence/mapper、纯SPI协议、owner规则及codec/envelope已编码编译和静态复审，待统一测试；真实报价/订单/现金准入消费者在B2接入。均为共用所有者增量，不增加美业构件/依赖或25私有表计数。

| Source | Planned class / responsibility |
|---|---|
| `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/CounterCampaignRules.java` | `ai.neargo.shop.marketing.campaign.CounterCampaignRules`<br>[编码编译/静态复审完成；统一测试未执行] marketing本域严格goods解析及owner规则；规范分组/选择/摘要统一调用纯SPI契约 |
| `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/CounterCampaignFence.java` | `ai.neargo.shop.marketing.campaign.CounterCampaignFence`<br>[编码编译/静态复审完成；统一测试未执行] shared marketing owner fence and capability |
| `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/mapper/CounterCampaignFenceMapper.java` | `ai.neargo.shop.marketing.campaign.mapper.CounterCampaignFenceMapper`<br>[编码编译/静态复审完成；统一测试未执行] exact-PK shared fence SQL |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CounterEconomicCodec.java` | `ai.neargo.shop.trade.order.service.create.CounterEconomicCodec`<br>[编码编译/静态复审完成；统一测试未执行] old/new economic quote/plan compatibility and consumed integrity |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CounterQuoteSnapshot.java` | `ai.neargo.shop.trade.order.service.create.CounterQuoteSnapshot`<br>[编码编译/静态复审完成；统一测试未执行] internal immutable economic/display quote envelope |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/marketing/CounterCampaignContract.java` | `ai.neargo.shop.spi.marketing.CounterCampaignContract`<br>[编码编译/静态复审完成；统一测试未执行] 共用纯协议：规范选择/分组、冻结seal形状和摘要；各业务域只依赖SPI，无查询或价格引擎 |
