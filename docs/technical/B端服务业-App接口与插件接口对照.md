# B 端服务业：App 接口与插件接口对照

日期：2026-11-09 · 依据：[B端门店功能矩阵-开发计划](./B端门店功能矩阵-开发计划-2026-11-09.md) 第八节 B5（已定案：App 改调插件接口，后端不另做 `/biz/beauty/*`）

读者：Flutter（ai-shop-bapp）与后端。左列是 App 按 mock 写的 23 个服务业建议接口（`/biz/beauty/*`，对应 App 功能 15-01～15-15，见 [商户App功能矩阵与API交接文档](../交接-商户App功能矩阵与API.md) §15）；右列是美业插件（`/biz/x/beauty/*`）和宿主共用的人员、资源、排班、时段接口。

新模型门店的门禁已在开发计划 B3 放开。美业接口在服务层判权，逐条审计结果见开发计划第八节；权限码与[门店功能矩阵](./TDD-B端门店功能矩阵与方案准入.md)服务业 4 行一致。

## 一、模型差异

| App 概念 | 插件对象 | 说明 |
|---|---|---|
| 预约 Visit（含到店、服务中、待结账） | **预约 appointment** + **工单 work-order** | 预约只管「约了什么、什么时候、谁做」；到店后开工单，服务与结账挂在工单上 |
| 签到并指派技师 | **到店 arrivals**（预约到店）/ **散客 walk-ins** | 签到会开出工单；指派体现在预约或工单的 `assignments` |
| 服务中加项 | **服务提交 service-submissions** | 在工单上提交实际做了的服务 |
| 结账 | **工单账单 bill** → 收款域 | 储值抵扣与其他付款方式由收款与储值域完成，美业只给账单 |
| 技师 | **人员 personnel**（宿主共用） | 用人员编号，不用姓名 |
| 资源（床位、房间） | **资源 resources**（宿主共用） | |
| 班表 | **周排班 schedules / schedule-rules**（宿主共用） | |
| 停约 | **预约时段 appointment-slots**（宿主） | 逐个关时段，已有预约不自动取消 |

## 二、逐条对照

| App 功能 | App 建议接口 | 插件 / 宿主接口 | 缺口 / 要对齐的地方 |
|---|---|---|---|
| 15-01 按天看预约 | `GET /biz/beauty/bookings?date=` | `GET /biz/x/beauty/appointments`（按时间范围过滤） | App 的 `stage` 要由预约状态加工单状态合成 |
| 15-02 预约详情 | `GET …/bookings/{no}` | `GET /biz/x/beauty/appointments/{appointmentNo}`；到店后 `GET /biz/x/beauty/work-orders/{workOrderNo}` | 会员信息（到店次数、偏好、储值余额）不在预约里，见缺口 1 |
| 15-03 签到并指派技师 | `GET …/staff`、`POST …/check-in` | `POST /biz/x/beauty/availability/queries`（谁有空）；`POST /biz/x/beauty/arrivals`（签到并开工单） | 指派用人员编号；签到需要 `biz:booking:manage` 和 `biz:customer` |
| 15-04 取消 | `POST …/cancel` | `POST /biz/x/beauty/appointments/{no}/cancellations` | 带 `expectedRevision` 与原因 |
| 15-05 结账 | `POST …/checkout` | `GET /biz/x/beauty/work-orders/{no}/bill`；付款走收款与储值域 | **缺口 2**：「储值扣一部分、其余刷卡或现金」的编排入口，与餐饮结账同一问题 |
| 15-06 改约 | `POST …/reschedule` | `POST /biz/x/beauty/appointments/{no}/changes/previews` → `POST …/changes` | 先预览再确认；时段冲突由预览返回 |
| 15-07 服务中加项 | `POST …/items` | `POST /biz/x/beauty/work-orders/{no}/service-submissions` | 需要 `biz:order:view` 和 `trade:checkout:prepare` |
| 15-08 首页汇总 | `GET /biz/beauty/summary` | 无单一接口 | **缺口 3**：短期 App 组合预约列表与工单；长期视性能补汇总接口 |
| 15-09 时间线 | `GET /biz/beauty/timeline` | `GET /biz/x/beauty/appointments`（含 `assignments`）+ `GET /biz/schedules`（班表窗口） | App 按技师或资源分栏自行排布 |
| 15-10 代客预约 | `GET …/availability`、`GET …/customers`、`POST …/bookings` | `POST /biz/x/beauty/availability/queries`；会员查找走会员域 `/biz/members`；`POST /biz/x/beauty/appointments/previews` → `POST /biz/x/beauty/appointments`；新客 `POST /biz/x/beauty/guest-registrations` | 订金规则未定（缺口 4） |
| 15-11 服务项目 | `GET/POST /biz/beauty/catalog` | `GET /biz/x/beauty/service-definitions`、`POST …/service-definitions/versions`（发布新版本）；卡片 `GET /biz/x/beauty/services/{skuNo}` | 服务项目按版本发布，不能原地改；时长、缓冲、可做技师都在定义里 |
| 15-12 技师与资源、可否被预约 | `GET /biz/beauty/staff-resources`、`POST …/{name}/bookable` | `GET/POST /biz/personnel`、`POST /biz/personnel/{personnelNo}/status-changes`；`GET/POST /biz/resources` | 用人员编号代替姓名；只有店主（`biz:store:admin`）能改 |
| 15-13 停约 | `GET /biz/beauty/slots`、`POST /biz/appointment-slots/{slotNo}/close` | `GET /biz/stores/{storeNo}/appointment-slots`、`POST /biz/appointment-slots/{slotNo}/close` | 关闭接口不收请求体，App 的 `reason` 不会被保存 |
| 15-14 排班 | `GET/POST /biz/beauty/schedule`、`GET …/schedule/impact` | `GET /biz/schedules`、`POST /biz/schedule-rules` | **缺口 5**：「改班前看影响」没有接口（`schedule-rules` 的响应不含受影响预约） |
| 15-15 报表 | `GET /biz/beauty/report` | `GET /biz/x/beauty/reports/scheduling?from=&to=`（预约数、到店率、爽约、取消、按技师次数、热门项目前 5） | 营收与按技师金额待 D19；App 按门店时区算今天 / 近 7 天 / 本月的窗口 |

## 三、缺口清单（后端待办）

| # | 缺口 | 建议 |
|---|---|---|
| 1 | 预约详情里的会员摘要（到店次数、偏好、储值余额） | App 用会员域 `/biz/members/{memberNo}` 与储值接口补读；或美业详情接口增加会员摘要 |
| 2 | 结账编排（储值抵扣 + 其他付款） | 已定（执行方案 D5）：各付款方式带工单号 `bizNo`，是否收齐读 `GET /biz/stores/{storeNo}/receivables/{bizNo}`（[TDD-业务单收款汇总](./TDD-业务单收款汇总.md)） |
| 3 | 首页汇总 | 短期 App 组合读 |
| 4 | 订金规则 | 已定（执行方案 D6）：订金用外部记账记在预约号下；结账读汇总时带 `relatedBizNo=预约号` 抵扣（[TDD-美业订金线下记账](./TDD-美业订金线下记账.md)） |
| 5 | 改班影响预览 | `POST /biz/schedule-rules` 只返回 `ruleNo, resourceNo, resourceRevision, status`，不含受影响预约；需补影响预览接口 |
| 6 | 服务业报表 | 首期计数已实现（[TDD-服务业报表](./TDD-服务业报表.md)）；营收口径见 D19 |

## 四、共通约定

与餐饮对照表相同：金额 `{currency, minor}`；枚举用大写机器码；写操作带 `Idempotency-Key` 和版本号；路径里的 `storeNo` 必须等于 `X-Store-No`。App 改完后从 `pendingContracts` 删除 `^/biz/beauty/` 与 `^/biz/appointment-slots/`。


## 2026-11-10 App继续实现的兼容增量（未部署）

- GET工单详情的workOrder/task增加nullable `revision`，真实值为各自实体version，独立于scopeRevision/bindingRevision；旧构造及历史回执不伪造version。
- 本域未冻结OPEN/IN_SERVICE且无活动收费提交时给SUBMIT_SERVICES，逐任务符合首次绑定规则时给SUBMIT_SOURCE。权限仍由原ServiceSubmissionAccess决定，canStart保持false。
- App从traces读取实际收费submission；根工单submissionNo是OPEN接待草稿。MANUAL_PREPARE前验证顾客selector；原号恢复端点为`/service-submissions/{no}/recovery-requests`。
- 宿主GET `/biz/service-orders/counter-quotes/{operationNo}/state`返回原confirmation、result和canAccept，避免退出后重建接受授权或原订单；PLACING只读恢复。
- 未接受过期报价由所有者在原锁转REQUOTE_REQUIRED，美业恢复见明确终态才结束本地提交；未知开单不能因超时释放。

代码/测试与原生V10迁移阻塞详见[TDD App续接](./TDD-美业系统-后端开发计划.md#app服务提交合同补齐2026-11-10)。当前仅本机补丁，须两仓同步集成部署后联验。


## V10解除阻塞后的服务准备（2026-11-10，未部署）

- V10精确DDL执行适配保留冻结SQL/checksum，V11识别与独立物理检查补齐；所选本机原生数据库场景71项通过。
- 工单task新增nullable `preparationOperationNo`，对应已有START提交原号，只回读安全引用，不返回保存的人员凭证。
- 人工来源已观察就绪、单个COUNT待做任务可给PREPARE_TASK；真实SELF/任职/指派仍由准备服务验证。App不把订单/预约管理权限当作技师权限。
- POST `/work-orders/{no}/tasks/{taskNo}/preparations`仍是数量和耗材准备；原操作回读/恢复沿既有operations/{no}及其recoveries，无新真实开工/完成入口。canStart=false。
- 未知响应、空操作号或错误任务回执保留原幂等键，确认有效原号后才改用服务端原操作恢复；旧operations恢复合同不被强制当准备合同。
