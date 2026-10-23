# 打印中心 API 设计

> 状态：目标接口契约设计稿，待评审 · 2026-10-23。路径、DTO和权限均为候选新增，未注册Controller或改变线上权限。
> 配套：[数据库设计](./打印中心-数据库设计.md) · [字段字典](./打印中心-数据库字段字典.md) · [DDL](./schema/print-center-schema.sql)。

> 场景模板合同：[20个场景及小票骨架](./打印中心-场景与小票模板设计.md)；结构化[目录](./schema/print-scene-catalog.json)、[模板定义](./schema/print-template-definitions.json)、[脱敏样例](./schema/print-scene-examples.json)。

> 总方案：[独立模块、订单接线、模拟与PDF](./打印中心-完整方案与模块封装.md)。本版83项候选接口，包含新增S01–S03和E01–E04。

> Java归属与声明：[类结构设计](./打印中心-Java类结构设计.md)、[83端点类方法映射](./打印中心-Java类与方法清单.md)。

## 1. 接口分层与统一约定

| 入口 | 调用方 | 认证/边界 |
|---|---|---|
| /biz/print/* | B端App、商家Web、POS | 商家认证＋独立权限＋门店对象范围 |
| /ops/print/* | 平台运维 | 运营权限＋明确目标租户/主体＋诊断审计 |
| /device/print/v1/* | 本地常驻代理 | 独立代理凭据，仅绑定门店和设备，不使用商家Token |
| /callbacks/print/{adapterCode}/{accountRef} | 云打印平台 | 厂商验签、防重放；账号映射可信主体 |
| Java SPI | 订单、行业、Job、通知 | 可靠业务事实和内部领域合同，不公开任意payload提交接口 |

### 1.1 请求与响应

- 商家、平台JSON沿用 `{code,msg,data}`；下文示例省略外层，只展示data。设备口也采用该封装。厂商回调例外，按供应商要求原样返回ACK，实施时须显式从ApiResponseWrapper排除，不能误加通用信封。
- 普通列表为 `PageData<T>{records,total,page,size}`，page从1、size默认20最大100。任务/回执按createdAt DESC、业务号DESC稳定排序；长轮询任务领取与事件增量流不使用普通页码。
- 业务号字符串；revision为非负整数；所有时间使用RFC3339，from/to按`[from,to)`；业务营业日按门店时区解释。
- 商家tenantNo、entityNo、actorNo来自可信身份，不接受请求体覆盖。storeNo是必需业务范围并须校验授权。任意对象号都不能跳过对象归属验证。
- 业务写命令必须带 `Idempotency-Key`，长度16..64，建议UUID。作用域为tenant＋entity＋actorType＋actorNo，跨接口也不能复用同键。同键同规范载荷返回原operationNo和结果；同键不同载荷拒绝。hash覆盖命令类型、路径目标、业务请求体，不包含trace头或客户端时间。
- 修改、取消、补打等携带expectedRevision；状态已改变返回REVISION_CONFLICT及当前revision，不自动覆盖。读接口返回allowedActions及blockers只是UI提示，服务端写时重新校验。
- 写命令返回 `PrintOperationVO`；纯本地事务可同步SUCCEEDED，跨外部系统先PENDING。可用HTTP 202标识已受理，但客户端必须读取data.status，不能依靠HTTP成功判定打印完成。失败沿用项目统一错误映射；业务码提案见第8节，最终数字统一注册。
- 输入reason长度1..255，列表最多50个任务；copies 1..10；原始打印数据默认上限256KiB，超限拒绝或由受控对象存储引用；单次来源最多500个业务行，超出需来源端拆分，不能静默截断。
- POST /preview、/dry-run是只读计算，不要求业务幂等键，不产生打印。所有真正测试打印都会建任务并有副作用。

### 1.2 统一DTO

```json
{
  "operationNo":"PO001","type":"REPRINT","status":"SUCCEEDED",
  "targetType":"BATCH","targetNo":"PB002",
  "result":{"batchNo":"PB002","jobNos":["PJ002"]},
  "error":null,"createdAt":"2026-10-23T02:00:00Z","finishedAt":"2026-10-23T02:00:00Z"
}
```

operation.status=PENDING/UNKNOWN/SUCCEEDED/FAILED；SUCCEEDED表示命令副作用已落库，如“成功创建补打任务”，不等于该票已打印。异步操作可通过operationNo查询；超时后查询原号或用原幂等键重发。

`PrintJobVO`的必需字段：jobNo,requestNo,batchNo,storeNo,scene,documentKey,copyIndex,required,printerNo,jobKind,status,revision,priority,retryCount,maxRetryCount,resultEvidence,confirmedAt,parentJobNo,rootJobNo,resolutionStatus,replacementJobNo,cancelRequestedAt,lastError,allowedActions,blockers,createdAt,updatedAt,asOf。来源orderNo/subOrderNo由request投影。attempt和receipt在详情中分页获取，列表不携带完整票据和顾客信息。

`PrintBatchVO`包含expectedCount、requiredCount、confirmedCount、failedCount、unknownCount、cancelledCount、resolvedCount、status、resolvedStatus、revision及originalComplete/replacementResolved；requiredCount可由不可变任务集合计算，其他聚合按数据库设计维护。计数均以任务为单位，confirmedCount与resolvedCount不重复。

候选权限：`biz:print:simulate`、`biz:print:export`、`biz:print:read`、`biz:print:device`、`biz:print:config`、`biz:print:submit`、`biz:print:reprint`、`biz:print:extra`、`biz:print:resolve`。风险补打和人工确认要求resolve，不能只靠普通订单查看权限。实施时登记BizPerms、功能点和端点矩阵，不直接使用文档字符串绕开现有授权体系。

## 2. 打印机与代理管理

### 2.1 打印机API

| ID | 方法与路径 | 请求 | 返回/权限 |
|---|---|---|---|
| P01 | GET /biz/print/adapters | connectionType? | AdapterVO[]；read |
| P02 | GET /biz/print/printers | storeNo,status?,healthStatus?,connectionType?,page,size | PageData<PrinterVO>；read |
| P03 | POST /biz/print/printers | CreatePrinterReq | Operation→printerNo；device |
| P04 | GET /biz/print/printers/{printerNo} | — | PrinterDetailVO含runtime、队列计数；read |
| P05 | PUT /biz/print/printers/{printerNo} | expectedRevision,name,paperWidthMm | Operation；device |
| P06 | POST /biz/print/printers/{printerNo}/connection-changes | expectedRevision,connection,reason | Operation；device＋resolve |
| P07 | POST /biz/print/printers/{printerNo}/status-changes | expectedRevision,status,reason | Operation；device |
| P08 | POST /biz/print/printers/{printerNo}/credential-rotations | expectedRevision,credentials | Operation；device＋resolve |
| P09 | POST /biz/print/printers/{printerNo}/probes | expectedRevision | Operation→ProbeResult；device |
| P10 | POST /biz/print/printers/{printerNo}/test-jobs | expectedRevision,testPattern | Operation→request/batch/job；device |

CreatePrinterReq：

```json
{
  "storeNo":"S001","name":"前台80mm","paperWidthMm":80,
  "connection":{"type":"CLOUD","adapterCode":"FEIE","vendorAccountRef":"VA001","deviceSn":"SN001","credentials":{"secret":"<仅写入>"}}
}
```

LOCAL替换connection为 `{type:"LOCAL",adapterCode:"LOCAL_IPP",agentNo:"PA001",config:{queueName:"cashier"},physicalRegistrationRef:"登记证据号"}`。actual physicalKey由服务端生成/验证，不让客户端随意重填以绕过重复设备限制。适配器schema决定credentials是否必需，响应只返回credentialConfigured和keyVersion。

PrinterVO：printerNo,storeNo,name,connectionType,adapterCode,paperWidthMm,status,health{status,reason,observedAt},capabilities,revision。详情可返回脱敏deviceSn、agentNo、connectionRevision、pendingCount、activeJobNo、blockedReason，永不返回凭据。

status=ACTIVE/PAUSED/RETIRED；暂停仅阻止新投递，不默认清空队列；退休有活动路由引用或未决任务时拒绝并返回引用清单。连接变更默认要求无未决任务；若厂商有注册/绑定动作，通过operation进行外部编排，未确认前不切换当前连接。Probe只检测连接和健康；testPattern由固定白名单生成，不能借测试页注入任意控制指令。

### 2.2 代理API

| ID | 方法与路径 | 请求 | 返回/权限 |
|---|---|---|---|
| A01 | GET /biz/print/agents | storeNo,status?,page,size | PageData<AgentVO>；device |
| A02 | POST /biz/print/agents | storeNo,name,platform | Operation→agentNo、配对材料；device |
| A03 | GET /biz/print/agents/{agentNo} | — | AgentDetailVO；device |
| A04 | POST /biz/print/agents/{agentNo}/enrollments | expectedRevision,clientPublicKey | Operation→加密配对码；device |
| A05 | POST /biz/print/agents/{agentNo}/revocations | expectedRevision,reason | Operation；device＋resolve |
| A06 | POST /biz/print/agents/{agentNo}/credential-rotations | expectedRevision,clientPublicKey,reason | Operation→加密新凭据；device＋resolve |

A02只创建PAIRING记录；A04生成10分钟单次配对码，返回给管理端的配对材料用clientPublicKey封装。A04同幂等重试返回相同加密结果；明文不存operation。已过期则用新幂等键生成新配对码、撤销旧码。A05阻止新领取并使会话失效，所有原未决attempt继续可核查，不能被当成未执行后自动迁移。

## 3. 策略、模板、路由API

| ID | 方法与路径 | 请求 | 返回/权限 |
|---|---|---|---|
| C01 | GET /biz/print/scenes | storeNo | SceneVO[]含门店能力、sourceTypes、manualMode、payloadSchemaVersion、可用变体和触发类型；read |
| C02 | GET /biz/print/scene-policies | storeNo | ScenePolicyVO[]；config |
| C03 | PUT /biz/print/scene-policies/{scene} | storeNo,expectedRevision,policy | Operation→policyVersionNo；config |
| C04 | GET /biz/print/scene-policies/{scene}/versions | storeNo,page,size | PageData<PolicyVersionVO>；config |
| C05 | GET /biz/print/templates | storeNo,scene?,variantCode?,page,size | PageData<TemplateVO>含可用继承模板；config |
| C06 | POST /biz/print/templates | storeNo,scene,variantCode,name | Operation→templateNo；config |
| C07 | POST /biz/print/templates/{templateNo}/versions | expectedRevision,format,schemaVersion,payloadSchemaVersion,paperWidthMm,content,compatibleCapabilities | Operation→templateVersionNo，DRAFT；config |
| C08 | PUT /biz/print/template-versions/{versionNo} | expectedRevision,payloadSchemaVersion,paperWidthMm,content,compatibleCapabilities | Operation；仅DRAFT可改；config |
| C09 | POST /biz/print/template-versions/{versionNo}/preview | sampleSourceRef,paperWidthMm | PreviewVO含warnings；config |
| C10 | POST /biz/print/template-versions/{versionNo}/publish | expectedRevision,expectedTemplateRevision | Operation；校验后发布并切头指针；config |
| C11 | GET /biz/print/templates/{templateNo}/versions | page,size | PageData<TemplateVersionVO>；config |
| C12 | GET /biz/print/routes | storeNo,scene?,page,size | PageData<RouteVO>；config |
| C13 | POST /biz/print/routes | storeNo,scene | Operation→routeNo；config |
| C14 | POST /biz/print/routes/{routeNo}/versions | expectedRevision,rules | Operation→routeVersionNo；config |
| C15 | POST /biz/print/route-versions/{versionNo}/dry-run | sourceRef | RouteDryRunVO；config |
| C16 | POST /biz/print/route-versions/{versionNo}/publish | expectedRevision,expectedRouteRevision | Operation；发布并切头指针；config |
| C17 | GET /biz/print/routes/{routeNo}/versions | page,size | PageData<RouteVersionVO>；config |
| C18 | POST /biz/print/template-resolutions/preview | storeNo,scene,sourceRef,printerNo,templateSelection? | TemplateResolutionPreviewVO；config；不创建任务 |
| C19 | GET /biz/print/scenes/{scene}/fields | storeNo,payloadSchemaVersion | SceneFieldCatalogVO含必填、类型、允许行字段、不可删标识；config |
| C20 | GET /biz/print/scenes/{scene}/examples | storeNo,variantCode | 脱敏固定样例；config |

policy包含enabled、critical、triggerTypes、fallbackPrinterNo、submitTimeoutSeconds、resultTimeoutSeconds、maxRetryCount、backlogAlertSeconds、staleAfterSeconds、notificationPolicy、outputOptions。边界：超时5..3600秒，retry 0..10，stale 60..86400秒；具体场景可收紧。新版本立即生效、不允许回填过去effectiveFrom；关闭策略只影响新触发，已生成任务按冻结策略继续，批量取消另走明确命令。

notificationPolicy格式：`{onBatchResolved:true,onIncident:true,recipientRoleKeys:["STORE_MANAGER"],channels:["IN_APP","PUSH"],escalateAfterSeconds:300}`。接收者由服务端按门店角色解析，不接受任意外部手机号。门店订阅能力不足的渠道返回配置错误，不静默忽略。

PreviewVO为 `{scene,variantCode,paperWidthMm,payloadSchemaVersion,templateVersionNo,document,documentHash,rendererVersion,warnings}`；sampleSourceRef可用服务端内置脱敏样例，或当前主体授权的来源引用，不允许提交任意跨店订单数据。模板详情和预览中的个人信息按字段权限裁剪。

RouteDryRunVO逐document返回命中ruleKey、目标printerNo、copies、required、templateSelection、resolvedTemplateVersionNo、templateResolution、fallbackUsed、unmatchedLines、warnings。发布关键场景时缺默认路由或设备能力不符应阻断，不允许“保存成功但永远匹配不到”。规则格式与首中语义见数据库设计。

模板头新增variantCode（STANDARD_58/STANDARD_80），版本新增paperWidthMm、payloadSchemaVersion。C06不接受覆盖主体scopeKey，服务端根据storeNo建立门店覆盖；平台模板由受控种子/平台发布流程管理，不通过商家端写入。C07/C08不能变更模板所属scene/variantCode，纸宽须一致。C10发布检查该场景全部必填显示字段、不可删标题及systemMarks；存在数据但未展示不能通过。system.warnings及原票状态由服务端注入不可删systemMarks，KITCHEN_VOID/URGE原票UNKNOWN必须显示原票待核实警示。PRINTER_TEST样例的paperWidthMm由选定变体覆盖为实际纸宽，其他业务金额/数量样例不可随意篡改。

C18可带授权sourceRef或固定样例（此时sourceRef为空并带sample=true）；templateSelection省略时取该来源的实际路由目标选择策略，printerNo必须是该文档的合法路由目标，否则拒绝。显式传入templateSelection只用于配置演练，同样验证场景/权限/纸宽。返回 `{scene,variantCode,payloadSchemaVersion,mode,selected:{templateNo,templateVersionNo,builtinKey},attempts:[{scope,status,reason}],fallbackUsed,documentPreview,blockers,asOf}`，来源含多个document时按documentKey分别返回results，不能只预览第一份。

C19返回目录字段、输入requiredFields/票面mandatoryPrintPaths、Money/Decimal/时间格式、行字段白名单、系统警示规则和可编辑区块，不返回未授权顾客字段；C20仅提供虚构样例，不能输出真实订单。路由target结构使用templateSelection替代旧templateVersionNo：SCENE_DEFAULT动态取生成时当前模板，PINNED_VERSION明确锁版本；纸宽按目标设备与variantCode双重校验。

模板和路由发布失败保持旧头指针不变。路由版本草稿需修改时创建新草稿，不覆盖旧发布内容；本版不提供PUT路由版本接口。

## 4. 订单、打印需求和任务API

### 4.1 查询与主动打印

| ID | 方法与路径 | 请求 | 返回/权限 |
|---|---|---|---|
| J01 | GET /biz/print/requests | storeNo,orderNo?,subOrderNo?,scene?,status?,from?,to?,page,size | PageData<PrintRequestVO>；read |
| J02 | GET /biz/print/requests/{requestNo} | — | RequestDetailVO含批次及阻断；read |
| J03 | POST /biz/print/requests | storeNo,sourceRef,scene,expectedSourceRevision,printMode,outputOptions? | Operation→requestNo,batchNo；submit |
| J04 | POST /biz/print/requests/{requestNo}/generation-retries | expectedRevision,reason | Operation；仅PENDING/BLOCKED；resolve |
| J05 | GET /biz/print/batches/{batchNo} | — | PrintBatchVO；read |
| J06 | GET /biz/print/jobs | storeNo,requestNo?,batchNo?,orderNo?,scene?,status?,jobKind?,printerNo?,from?,to?,page,size | PageData<PrintJobVO>；read |
| J07 | GET /biz/print/jobs/{jobNo} | — | JobDetailVO；read |
| J08 | GET /biz/print/jobs/{jobNo}/attempts | page,size | PageData<PrintAttemptVO>；read |
| J09 | GET /biz/print/jobs/{jobNo}/receipts | page,size | PageData<PrintReceiptVO>脱敏；resolve |
| J10 | GET /biz/print/jobs/{jobNo}/document | — | DocumentPreviewVO含标识；read＋字段权限 |
| J11 | GET /biz/print/orders/{orderNo}/summary | storeNo | OrderPrintSummaryVO；read＋订单权限 |
| J12 | GET /biz/print/operations/{operationNo} | — | PrintOperationVO；原操作者或resolve且同店 |

sourceRef格式 `{type:"SUB_ORDER",no:"SO001",factNo:"OF001",revision:"7"}`；多店订单必须按受权子单/门店展开，不允许把其他店明细带入票据。printMode=CURRENT_VERSION仅允许目录manualMode=CURRENT_VERSION的PRE_BILL，固定未付款声明并显示人工打印及版本；其他可人工场景必须用EXISTING_FACT，校验真实既有事实并沿用自动事实的triggerType/triggerInstance/bizRevision及trigger_key，已存在需求返回原需求不重复建批次；历史重打走reprints使用原快照。PRINTER_TEST仅走P10，拒绝通过J03制造测试或生产业务事实。J03不允许外部直接提交payload、renderer、templateVersionNo或任意模板指令，份数由发布路由决定。

J04仅恢复缺失的初打生成流程，已有INITIAL批次直接返回原批次；不能用生成重试绕过补打权限。OrderPrintSummaryVO返回按场景的应打印数、原票完成数、补打解决数、待核实数、异常数、最新批次、asOf，以及 `generationState=NO_PRINT_REQUIRED/PENDING/BLOCKED/GENERATED`，零任务不能被误显示成全部完成。

### 4.2 补打、加印与人工处理

| ID | 方法与路径 | 请求 | 返回/权限 |
|---|---|---|---|
| J13 | POST /biz/print/jobs/{jobNo}/reprints | ReprintReq | Operation→新批次/任务；reprint |
| J14 | POST /biz/print/reprints | storeNo,jobs[{jobNo,expectedRevision}],reason,targetPrinterNo?,riskAcknowledged | 同门店按request分批且整体原子创建，最多50票；reprint |
| J15 | POST /biz/print/jobs/{jobNo}/extra-copies | expectedRevision,copies,reason,targetPrinterNo? | Operation→EXTRA任务；extra |
| J16 | POST /biz/print/jobs/{jobNo}/retries | expectedRevision,reason | Operation；明确未受理且可重试原任务；resolve |
| J17 | POST /biz/print/jobs/{jobNo}/cancellations | expectedRevision,reason | Operation；submit，外部取消另需resolve |
| J18 | POST /biz/print/jobs/{jobNo}/result-queries | expectedRevision | Operation→查询已安排；resolve |
| J19 | POST /biz/print/jobs/{jobNo}/manual-confirmations | ManualConfirmationReq | Operation；resolve |
| J20 | POST /biz/print/jobs/{jobNo}/stale-releases | expectedRevision,reason,expectedSourceRevision | Operation；resolve |
| J21 | POST /biz/print/printers/{printerNo}/uncertainty-resolutions | DeviceResolutionReq | Operation；device＋resolve |

ReprintReq：

```json
{"expectedRevision":4,"reason":"原票缺纸后损坏","targetPrinterNo":"P002","riskAcknowledged":false}
```

- 原票FAILED或确认损坏时生成REPRINT，新任务保留原文档，仅添加受控补打标识。原票COMPLETED后仍可因损坏补打，须原因，不计为新增业务需求。
- UNKNOWN需要riskAcknowledged=true及resolve权限，并返回duplicateRisk；只确认这次补打风险，不能顺带清除原设备未决闸门。
- 同一原票已有未决REPRINT时返回REPRINT_IN_PROGRESS和existingJobNo。同键重发总是返回原任务，即使后来已经打印完成。
- targetPrinterNo为空用原设备；指定其他设备必须同店、能力兼容，并保存新目标路由快照，保留原模板和内容。票面纸宽不兼容时拒绝，不能隐式换模板导致内容变化。
- J15要求原票COMPLETED，生成EXTRA且不解决原票异常。copies 1..10，每份生成独立任务，记录copyIndex和加印序号。
- J16仅允许原任务RETRY_WAIT且有明确未受理证据，重试仍遵守maxRetryCount。FAILED为终态，应补打，不能重置历史结果。
- J17未提交时直接CANCELLED；已提交时记cancelRequestedAt并查询/调用取消。operation SUCCEEDED须已经确认取消；不支持或无法确认则UNKNOWN/FAILED，job不能假装CANCELLED。业务退菜/撤单由内部合同决定是否需要VOID票，不由前端自行选择。

ManualConfirmationReq：`{expectedRevision,outcome:"PRINTED"|"NOT_ACCEPTED",reason,evidenceRef,queueTerminatedConfirmed}`。PRINTED用于人工见到完整票据；NOT_ACCEPTED必须证明没有部分出纸且不会晚执行，调用原任务安全重试规则。部分打印使用补打，不能选择NOT_ACCEPTED。人工证据只能是本主体受控附件/现场核验引用，不能上传任意URL。

DeviceResolutionReq：`{expectedRevision,activeAttemptNo,action:"CONFIRM_QUEUE_TERMINATED",reason,evidenceRef,oldExecutorStopped:true}`。仅针对当前runtime未决attempt；核实旧代理已停、原队列已终止后释放设备闸门，并把原任务保留UNKNOWN/人工处置证据。释放后允许其他票运行，不自动把原票重试或改成功；需要重试仍必须证明原票未输出。不能拿“打印机现在在线”作为清除闸门的依据。

J14返回result.batchNos和按sourceJobNo映射的新jobNos；单票J13保留单个batchNo。各分组batch.request_no只关联自己的需求。

补打返回示例：

```json
{
  "operationNo":"PO002","type":"REPRINT","status":"SUCCEEDED",
  "targetType":"BATCH","targetNo":"PB002",
  "result":{"batchNo":"PB002","jobNos":["PJ002"],"sourceJobNos":["PJ001"],"duplicateRisk":true}
}
```

## 5. 本地代理协议

固定版本v1，所有数据校验代理绑定的租户、主体、门店、设备。TLS必需，令牌只用于本代理。配对外的接口使用 `Authorization: Bearer <agentCredential>`；会话请求带sessionEpoch。所有代理写请求含requestId或eventNo，必须可幂等恢复。

| ID | 方法与路径 | 请求 | 返回 |
|---|---|---|---|
| D01 | POST /device/print/v1/enrollments | agentNo,enrollmentCode,clientPublicKey,requestId,platform,softwareVersion | agentNo、sealedCredential、credentialVersion；单次消费配对码 |
| D02 | POST /device/print/v1/sessions | requestId,softwareVersion,previousSessionEpoch? | sessionEpoch、heartbeatIntervalSeconds=15、serverTime；认证后生成新会话 |
| D03 | POST /device/print/v1/heartbeats | requestId,sessionEpoch,printers[],localPendingCount | accepted、serverTime、revoked、commands[] |
| D04 | POST /device/print/v1/claims | requestId,sessionEpoch,printerNos,maxJobs=1,waitSeconds=0..25 | claimNo、dispatches[]；单机最多一张 |
| D05 | POST /device/print/v1/dispatches/{dispatchKey}/acceptances | requestId,sessionEpoch,fenceEpoch,leaseToken,documentHash | accepted、leaseUntil、leaseToken；仅表示本地已持久化 |
| D06 | POST /device/print/v1/dispatches/{dispatchKey}/lease-renewals | requestId,sessionEpoch,fenceEpoch,leaseToken | DeviceRenewLeaseVO含leaseUntil、leaseToken，或LEASE_LOST |
| D07 | POST /device/print/v1/events | sessionEpoch,events[] | 每项eventNo、ACK/DUPLICATE/CONFLICT/REJECTED |
| D08 | GET /device/print/v1/dispatches/{dispatchKey} | — | 该代理历史dispatch状态、是否仍可执行、取消意图 |
| D09 | POST /device/print/v1/recoveries | requestId,sessionEpoch,unresolvedDispatches[] | 每项CONTINUE/QUERY_ONLY/STOP_AND_REPORT，不能直接指令重打 |
| D10 | POST /device/print/v1/commands/{commandNo}/results | requestId,sessionEpoch,printerNo,commandType,dispatchKey?,attemptNo?,connectionRevision,fenceEpoch?,status,result | 取消/探测等控制命令终局结果，形成receipt或operation结果 |

D01配对码验证成功后持久化访问凭据哈希及用clientPublicKey封装的密文结果。响应丢失时相同requestId＋相同公钥可获取相同sealedCredential；不同公钥冲突拒绝。原始令牌仅代理解密可得；密文结果保留到确认或10分钟，过期需商家重新配对，不自动延长配对码。D02同requestId返回同sessionEpoch，重启使用新requestId会使旧session失去执行权。

claims响应示例：

```json
{
  "claimNo":"PC001","dispatches":[{
    "dispatchKey":"PD001","jobNo":"PJ001","attemptNo":"PT001","printerNo":"P001",
    "sessionEpoch":3,"fenceEpoch":18,"connectionRevision":2,
    "leaseToken":"<opaque>","leaseUntil":"2026-10-23T02:01:00Z",
    "executeBefore":"2026-10-23T02:01:00Z",
    "document":{"format":"PRINT_DOC_V1","rendererVersion":"1","content":{},"hash":"<sha256>"},
    "layout":{"profileVersion":"ROLL80_V1","fontBundleVersion":"SC_1","fontHash":"<sha256>","pageWidthMm":80,"pages":[],"contentHash":"<layout-sha256>"},
    "connection":{"adapterCode":"LOCAL_SYSTEM_QUEUE","queueName":"cashier"},
    "cancelRequested":false
  }]
}
```

- D04同requestId重试返回原claim，不额外领任务；claim幂等记录可用prn_operation（actorType=AGENT）及attempt恢复。过期claim仍返回原身份和canExecute=false，必须新claim请求才能领取其他任务。
- D04下发完整冻结PrintLayout及hash，示例pages省略内容不代表允许空页面；代理校验布局/字体hash，不自行选模板或重新分页。D05/D06另带leaseToken，runtime只保存其哈希。令牌由服务端签名并绑定dispatchKey、sessionEpoch、fenceEpoch和lease代次，同claim重试可确定性重建原令牌；续租返回新leaseUntil和新令牌，旧令牌按上一已确认代次幂等续租不能生成第二份执行授权。lease默认60秒，每15秒续租；执行前重新检查session、lease及cancelRequested。本地发包后失去lease只能停止后续发包，不能声称撤回设备中已接收的数据。
- D03 printers每项包含printerNo、connectionRevision、observedAt、health及capabilities摘要；服务端不允许通过心跳擅自换绑设备或提高证据等级。commands由持久化operation驱动，类型限PROBE/CANCEL/STOP，commandNo=operationNo，非任意shell指令。DeviceCommand为 `{commandNo,type,printerNo,dispatchKey,attemptNo,connectionRevision,sessionEpoch,fenceEpoch,expiresAt,payload}`；CANCEL/STOP必须绑定具体dispatch和attempt，PROBE可不带任务身份。命令完整目标在operation.command_payload持久化；代理精确校验，过期或不匹配返回STALE_TARGET，禁止取消“当前队列任务”替代原目标。STOP只阻止该dispatch后续发包，不代表设备队列已取消。
- D07 events最多100条，每项 `{eventNo,dispatchKey,attemptNo,fenceEpoch,sequence,status,evidenceLevel,providerJobId?,occurredAt,details}`。status是RECEIVED/SUBMITTING/ACCEPTED/PRINTING/CONFIRMED/NOT_ACCEPTED/UNKNOWN/FAILED/CANCELLED之一。NOT_ACCEPTED必须带明确未入设备队列的证据；FAILED/CANCELLED须含queueTerminated、partialOutput、evidenceRef，队列未证实终止时不得释放设备闸门。
- D07逐项ACK只代表证据已持久化，任务最终状态通过J07/D08读取。旧session证据可留存并裁决，不能作为旧session继续执行的授权。相同eventNo不同hash返回CONFLICT并告警。
- D09每次最多100条；local状态SUBMITTING且无法查询时返回QUERY_ONLY并保留UNKNOWN，不返回重新提交。代理收到STOP_AND_REPORT必须停止未开始的发包、报告仍在设备队列的任务，不能直接删除本地记录。
- D10请求还须回传原命令的dispatchKey/attemptNo、connectionRevision、sessionEpoch、fenceEpoch；commandType和printerNo须与原命令一致。仅允许终局结果APPLIED/FAILED/UNKNOWN/STALE_TARGET，不通过同一结果号多次上报进展。同requestId重复结果幂等，不同载荷冲突。APPLIED仅表示控制命令已执行；取消命令仍必须提供队列终止和出纸情况证据，由领域状态机裁决。
- D05接收确认不是打印完成；D10探测成功也不是任何业务票完成。无可靠完成证据时只能回报ACCEPTED/UNKNOWN，后续人工核实。

## 6. 云端回调与内部合同

### 6.1 回调

`POST /callbacks/print/{adapterCode}/{accountRef}`。

厂商验签方式、ACK格式、时间窗和重放标识由适配器定义；仅支持已注册账号。来源主体映射由accountRef及服务端凭据确定。先验签，再持久化prn_receipt，事务提交后ACK；业务更新可异步消费。收件箱不可用时返回厂商可重试错误，不回成功吞掉事件。无稳定事件号时用规范业务字段摘要去重，不能将每次投递时间当新事件。

回调可能早于提交响应：先存UNMATCHED，按provider账号作用域＋外部任务号关联attempt；不能仅凭外部任务号全库搜索。无法确定可信主体的原始事件进隔离日志，不任意挂到某门店。

厂商无回调时由Job主动query。查询false/未打印仍可表示在队列等待，适配器必须映射成ACCEPTED而非NOT_ACCEPTED。

### 6.2 内部Java合同（非公网API）

```java
PrintSourceFactsPort.scan(PrintFactCursor cursor, PrintFactRange range, int limit);
PrintSourceFactsPort.snapshot(PrintSourceRef source); // 固定事实号和版本，不返回当前行冒充历史
PrintSourceFactsPort.executionGuard(PrintSourceRef source); // 取消/更新/有效性裁决
PrintRequestService.onFact(PrintFact fact, OutboxEnvelope envelope);
PrintRequestService.cancelBySource(PrintCancellationFact fact); // 留版本与撤销事实
PrintRequestService.reconcile(PrintFactRange range);
PrintAdapter.capabilities();
PrintAdapter.submit(PrintDispatch dispatch); // ACCEPTED / NOT_ACCEPTED / UNKNOWN
PrintAdapter.query(PrintAttemptRef reference);
PrintAdapter.cancel(PrintAttemptRef reference);
PrintReceiptService.ingest(VerifiedPrintReceipt receipt);
```

以上为方法职责合同，不是已经存在的Java类型。fact需携带tenant/entity/store、sourceFactNo、bizNo、bizRevision、triggerType、occurredAt、stableLineRefs和snapshotRef。PrintSourceFactsPort由订单/行业事实所有者实现，打印中心不能直接改其业务表。

通知事件：PrintTaskCompleted、PrintBatchResolved、PrintIncidentOpened、PrintIncidentResolved，至少含事件号、tenant/entity/store、目标号、stateRevision、evidenceLevel、occurredAt及去重键。事件默认只带引用和脱敏摘要；通知内容按接收权限获取。成功通知文案不得把人工确认伪称设备自动回执。

## 7. 监控、事件更新与平台API

| ID | 方法与路径 | 请求 | 返回/权限 |
|---|---|---|---|
| M01 | GET /biz/print/health | storeNo | 在线设备数、积压、UNKNOWN、BLOCKED、最老任务及asOf；read |
| M02 | GET /biz/print/incidents | storeNo,status?,severity?,targetNo?,page,size | PageData<IncidentVO>；read |
| M03 | POST /biz/print/incidents/{incidentNo}/acknowledgements | expectedRevision,reason | Operation；resolve |
| M04 | GET /biz/print/action-logs | storeNo,targetType,targetNo,page,size | PageData<ActionLogVO>；resolve |
| M05 | GET /biz/print/changes | storeNo,afterCursor?,limit=1..100 | ChangePageVO；read |
| M06 | POST /ops/print/reconciliations | tenantNo,entityNo,storeNo,sourceKind,from,to,dryRun,reason | Operation→扫描统计；ops:print:reconcile |
| M07 | GET /ops/print/operations/{operationNo} | 目标范围上下文 | PrintOperationVO；ops:print:read |
| M08 | GET /ops/print/health | tenantNo,entityNo?,storeNo? | FleetHealthVO，不含顾客正文；ops:print:read |
| M09 | GET /ops/print/incidents | tenantNo,entityNo?,storeNo?,status?,page,size | PageData<IncidentVO>；ops:print:read |

M05采用prn_store_stream串行分配的prn_action_log.store_sequence构造不透明签名游标（绑定tenant/entity/store和过滤条件），返回 `{items:[{changeNo,targetType,targetNo,revision,action,occurredAt}],nextCursor,hasMore,asOf}`。状态变化事务先锁门店流头，按提交有序分配sequence并写action_log，才能覆盖所有UI刷新事件；禁止用自增id直接推进游标，以免并发事务提交乱序漏事件。前端收到后按需拉详情，不在增量事件中传整张票。首次无游标返回当前水位，客户端先拿游标、再加载快照、再消费后续变化，期间变化通过revision去重。

默认前台3秒轮询changes、后台降频，断线按游标续拉。游标超保留边界返回CURSOR_EXPIRED并要求重新取水位与快照。未来SSE可复用同一增量合同，本版不另建WebSocket基础设施。

M03只确认待办，不提供随意“标记已解决”接口；解决由任务/设备证据或经过审计的人工处理自动派生。M06默认dryRun=true，单次范围最多7天；dryRun=false仅补建缺失需求及安全恢复，不自动重发UNKNOWN。扫描完成不等于打印完成，结果给出scannedFacts/missingRequests/blockedRequests/createdRequests/conflicts。超过幂等保留边界的范围拒绝自动修复，先人工核对归档。

## 7.1 模拟打印API（独立于生产任务）

| ID | 方法与路径 | 请求 | 返回/权限 |
|---|---|---|---|
| S01 | POST /biz/print/simulations | SimulationReq | Operation→simulationNo；simulate，真实来源另需源读取权限 |
| S02 | GET /biz/print/simulations | storeNo,scene?,status?,page,size | PageData<SimulationVO>；simulate |
| S03 | GET /biz/print/simulations/{simulationNo} | — | SimulationDetailVO含冻结布局、预期/实际结果及有界轨迹；simulate |

SimulationReq：`{storeNo,scene,source:{type:"SAMPLE"|"JOB"|"FACT",no?,factRef?,expectedRevision?},variantCode,fixtureCode,outputOptions?,templateSelection?}`。SAMPLE不带业务号；JOB必须带jobNo及expectedRevision并保持原scene/变体；FACT必须有可信PrintFactRef且同scene。示例：

```json
{"storeNo":"S001","scene":"ORDER_PAID","source":{"type":"SAMPLE"},"variantCode":"STANDARD_80","fixtureCode":"SUCCESS","outputOptions":{"pdf":{"enabled":true,"profile":"ORIGINAL_ROLL"}}}
```

模板选项仅用于SAMPLE配置预演：需要config权限且允许受权草稿版本（仅受config权限的SAMPLE_PREVIEW purpose，PINNED_VERSION生产规则仍仅允许已发布）；JOB不接受templateSelection，FACT仅允许正式已发布模板选择。默认模板与实际来源权限照旧校验，不能借模拟查询别店事实、密钥或配置。返回operation SUCCEEDED仅代表模拟记录/冻结文档已可靠创建，simulation.status=PENDING/RUNNING/PASSED/FAILED另查S03；PASSED为fixture断言通过，actualResults可能包含UNKNOWN，不能命名printed=true。

SimulationVO至少含simulationNo,storeNo,scene,sourceType,fixtureCode,fixtureVersion,status,revision,expectedSummary,actualSummary,exportNos,createdAt,finishedAt。事件轨迹只在detail中返回脱敏数据。最多10份文档/500事件/3MiB/30秒，fixture只允许总方案列出的9种。不会生成生产request/batch/job，不回传生产receipt，不通知顾客，不更新订单summary的完成计数。

## 7.2 PDF生成与导出API

| ID | 方法与路径 | 请求 | 返回/权限 |
|---|---|---|---|
| E01 | POST /biz/print/exports | ExportReq | Operation→exportNo；export＋源对象读取权限 |
| E02 | GET /biz/print/exports | storeNo,sourceType?,sourceNo?,status?,page,size | PageData<ExportVO>；export，逐条源权限裁剪 |
| E03 | GET /biz/print/exports/{exportNo} | — | ExportVO；export＋当前源读取权限 |
| E04 | POST /biz/print/exports/{exportNo}/download-grants | — | DownloadGrant含60秒url、expiresAt、fileName、contentType；每次重新鉴权，不要求业务幂等键 |

ExportReq：`{storeNo,source:{type:"JOB"|"BATCH"|"SIMULATION",no,expectedRevision},profile:"ORIGINAL_ROLL"|"A4_SHEET"}`。源为SIMULATION另需simulate权限，文件固定SIMULATION_EXPORT且每页带模拟警示；JOB/BATCH固定LIVE_EXPORT但带导出副本说明，不能自报artifactKind移除水印。来源BATCH生成时冻结完整jobNo集合并按documentKey/targetKey/copyIndex排序，不在下载时按最新任务重算。

ExportVO为 `{exportNo,sourceType,sourceNo,sourceRevision,artifactKind,status,profile,pageCount,fileSize,contentHash,rendererVersion,fontBundleVersion,expiresAt,lastError,revision,createdAt,readyAt}`。无objectKey和永久URL。status=PENDING/GENERATING/READY/FAILED/EXPIRED，与PrintJobVO.status独立。50票/200页/20MiB上限，超限拒绝且不截断，READY前下载返回PRINT_EXPORT_NOT_READY，过期返回PRINT_EXPORT_EXPIRED。不存在/跨店对象不暴露元数据。

文件签名授权是一项短期读操作，E04每次验证当前商家/门店/源读取/export权限，授权不继承原创建者的历史权限；还须满足export.requiredDataScopes（冻结文档敏感字段权限并集），不足时拒绝，不把旧完整票据无条件给只读订单账号。JOB模拟同样检查原文档字段权限；响应Cache-Control=no-store。源被脱敏/撤权/删除时不得重新生成完整敏感PDF。对象始终私有，PDF默认保留7天、元数据180天，文件名仅含安全业务编号与日期、不含姓名/电话。下载不新建或推进打印任务。

### 7.3 伴随打印输出PDF

J03与门店scenePolicy可传/配置`outputOptions={pdf:{enabled:false,profile:"ORIGINAL_ROLL"}}`，默认关闭。门店自动来源使用冻结策略；人工命令显式开启需export权限，自动策略开启由config＋export权限发布，后台以该策略受控系统身份生成，不能因事件中自报outputOptions开启文件输出。

批次/模拟文档冻结事务内先登记export=PENDING再异步渲染。PrintSubmission.result可包含exportNos；订单J11摘要增加latestExportNo/latestExportStatus/exportCount，不将PDF或模拟计入confirmedCount。PDF失败不回滚订单或重新打印，打印失败也不删除可用PDF。job预览与导出保持同一模板/业务/布局快照，A4只承载原毫米尺寸，不重算金额。

## 8. 业务错误合同

候选符号须统一注册ErrorCode及多语言，不能直接复用未核对的数字。

| 符号 | 含义及客户端处理 |
|---|---|
| PRINT_SCOPE_FORBIDDEN | 无对象或门店权限，不显示目标细节 |
| PRINT_IDEMPOTENCY_CONFLICT | 同键不同命令，停止重试并提示 |
| PRINT_REVISION_CONFLICT | 数据已变化，刷新后重新判断 |
| PRINT_DEVICE_DUPLICATE | 同物理设备已登记，返回受权范围内现有设备引用 |
| PRINT_DEVICE_UNRESOLVED | 原设备有未决任务，先核实而非强行换绑 |
| PRINT_CAPABILITY_UNSUPPORTED | 查询/取消/纸宽/模板等设备不支持 |
| PRINT_SCENE_SOURCE_MISMATCH | 场景和来源事实不匹配，拒绝伪造业务凭证 |
| PRINT_TEMPLATE_SCENE_MISMATCH | 模板版本不属于场景或来源schema，拒绝发布/调用 |
| PRINT_TEMPLATE_VARIANT_MISMATCH | 纸宽或变体不匹配，不自动跨宽替代 |
| PRINT_TEMPLATE_REQUIRED_FIELD_MISSING | 模板遗漏强制字段/标识，禁止发布或降级出错票 |
| PRINT_TEMPLATE_PINNED_UNAVAILABLE | 固定版本不可用，需显式修配置 |
| PRINT_SIMULATION_LIMIT | 模拟输入/轨迹/运行预算超限，生产任务不受影响 |
| PRINT_SIMULATION_SCOPE | 模拟来源或模板无权限，拒绝读取 |
| PRINT_EXPORT_NOT_READY | 文件尚未生成，查询原exportNo |
| PRINT_EXPORT_EXPIRED | 文件已过期，按受权冻结来源重新申请 |
| PRINT_EXPORT_LIMIT | 超过50票/200页/20MiB，不生成截断文件 |
| PRINT_PDF_RENDER_FAILED | 字体/布局/生成失败，只影响导出状态 |
| PRINT_NO_ROUTE | 需求已保留但无路由，修配置后恢复生成 |
| PRINT_SOURCE_SNAPSHOT_UNAVAILABLE | 无对应业务版本快照，不能伪造当前内容 |
| PRINT_SOURCE_CANCELLED | 来源已撤销，拒绝旧版本出单 |
| PRINT_JOB_STATE_CONFLICT | 当前状态不允许所请求操作 |
| PRINT_RESULT_UNCERTAIN | 可能已打印，需要查结果或风险处置 |
| PRINT_REPRINT_IN_PROGRESS | 原票已有未决补打，返回existingJobNo |
| PRINT_RETRY_EXHAUSTED | 原任务重试额度已用尽，转补打处理 |
| PRINT_AGENT_REVOKED / PRINT_SESSION_STALE | 代理撤销/旧会话，停止领取与执行 |
| PRINT_LEASE_LOST | 未执行任务停止发包，已提交任务继续报告证据 |
| PRINT_RECEIPT_CONFLICT | 同回执身份内容不同或证据矛盾，告警待核实 |
| PRINT_PAIRING_EXPIRED | 配对码过期，重新生成配对材料 |
| PRINT_CURSOR_EXPIRED | 增量游标过期，重建快照 |

错误数据示例：`{errorKey:"PRINT_REVISION_CONFLICT",currentRevision:8,retryable:false,conflicts:[{jobNo:"PJ001",status:"UNKNOWN"}],traceNo:"T001"}`。按现有ApiResult的data承载；禁止泄露密钥、原始厂商报文和顾客敏感字段。设备协议的LEASE_LOST必须是可机读业务码，不能让代理解析msg。

## 9. API与数据写入对齐

| 命令/链路 | 主要写入 | 原子要求 |
|---|---|---|
| 创建/修改设备 | operation、printer、credential_version、runtime、action_log | 本地配置同事务；外部注册异步 |
| 配对/轮换/撤销 | agent、operation、action_log | 验码/消费/凭据hash原子；只存加密响应材料 |
| 发布模板/路由/策略 | version、head、operation、action_log | 校验、版本冻结、CAS头指针同事务 |
| 订单触发/对账补需求 | request、incident、Outbox消费状态 | 需求可靠保存后才能确认消费/推进游标 |
| 初打/补打/加印 | request/batch/job、operation、action_log | 整批任务原子创建；补打锁root防重复 |
| 领取/重试 | runtime、job、attempt、operation、action_log | 调外设前提交；租约失效不清未决 |
| 回执处理 | receipt、attempt、job、runtime、batch、incident、action_log、Outbox | 证据裁决与状态/通知事件同事务 |
| 模拟执行 | simulation_run、operation、action_log | 不写生产job/receipt/完成事件；冻结输入和fixture |
| PDF导出 | export_task、operation、action_log | 上传前先记账，字节/哈希验证后READY |
| 人工确认/闸门处置 | receipt、job/attempt/runtime、operation、action_log、Outbox | 保留原证据，权限和CAS同事务 |

## 10. 接入阶段与验收

实施阶段统一以完整方案M1–M5及PRT-01..16为准：先独立核心、模拟/PDF和订单接线，再商家入口、云端/本地与巡检通知，最后逐个行业来源启用。未知结果和幂等规则一期即实施，不能以后补。

合同测试至少覆盖：每个写接口同键重发/同键异体；同店与跨店鉴权；expectedRevision冲突；UNKNOWN禁止普通重试；补打/加印票面与原快照；批量50票原子性；代理重复领取与旧会话；厂商提前/重复/矛盾回执；取消尚未确认时不返回已取消；批次补打解决而不改原票；人工完成与设备完成通知区分。

品牌及系统确定后补充适配器字段schema、验签样例、错误码映射及真实完成证据样本；这些属于具体适配器合同，不改变本稿通用任务结构。
