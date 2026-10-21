# 餐饮系统API设计

> 状态：待评审 · 2026-10-20 · V1含73张fnb_业务表；增量迁移及已交付API见M0验收，prn/rpt保持共享候选。
> 由 `node scripts/gen-food-system-design.mjs` 生成，勿手改；真源：[领域数据库API设计源](./schema/food-system-design.json)。生成依赖沿用 @viz-js/viz，可用 FOOD_ER_VIZ_MODULE 指定模块路径。
> 项目约束：[底座兼容对齐](./餐饮系统-项目底座兼容对齐.md)，账号、门店、营业时间、公共实体及协议冲突时优先按该对齐说明修订。
> 产品依据：[完整需求文档](../../requirements/餐饮系统-完整需求文档.md) · [109项需求矩阵](../../requirements/餐饮系统-需求矩阵.md)。

## 一、协议与能力边界

110个目标端点，统一JSON语义；路径为建议合同，未全部注册在当前Controller。本地适配器和远程适配器遵守相同DTO、权限和幂等。

- /biz为商家作业，/mp为顾客，/ops为平台，/internal仅服务身份。老板App/收银/厨房复用biz服务，按角色门店工位鉴权，不把平台管理员当商家收银员。
- Authorization为获准身份；X-Request-Id用于追踪。所有有副作用操作必需Idempotency-Key；作用域含entity/store/operation/key，规范payload哈希同键异参返回409。operator/entity从身份获取，不接受客户端冒充。
- 版本字段为CAS expected version；配置发布、结账冻结、日结签核还核验规则版/指纹/水位。path同名字段不得在body覆盖。GET只读，普通分页沿用PageData(records,total,page,size)，page默认1、size默认20最大100；报告专有metadata不改变统一外层。
- 输出沿用ApiResult<T>：{code:0,msg:"success",data:T}，Controller直接返回VO，由ApiResponseWrapper包装；异常沿用ErrorCode/BizException与现有全局处理器。追踪号使用既有链路，不新增必填requestId响应字段。业务处理中状态放data.status，HTTP状态/异步语义按宿主异常与包装链校验，不建立另一套HTTP-only错误规范。
- 普通分页沿用PageData(records,total,page,size)；DTO示意不得迫使现有前端改分页壳。int64金额与业务号遵循现有共享序列化，不能将全项目金额强制改字符串；新增decimal数量使用明确精度合同。UTC对外时刻与数据库LocalDateTime转换必须通过现有配置验证，date明确营业日或自然日基准。
- B端复用Realm.MERCHANT和BizContext；X-Store-No用于解析主体门店。路径/body显式门店必须与已解析的获准门店匹配，不能因过滤器回落默认店而把显式目标写到另一店。跨店报表逐店验证主体及权限集合。
- 参数表“是”为必须出现；条件字段的必填性由说明与守卫补充。写入枚举限定域值，服务端拒绝未知字段/未授权能力。通用配置data按路径对应数据库字段白名单解析，不能作为任意属性注入口。

## 二、分域接口目录

| 域 | 编号 | 方法与路径 | 功能 |
|---|---|---|---|
| A | FOOD-API-001 | GET `/biz/x/food/stores/{storeNo}/policy` | 查询营业策略 |
| A | FOOD-API-002 | POST `/biz/x/food/stores/{storeNo}/policies` | 发布营业策略 |
| A | FOOD-API-003 | PUT `/biz/x/food/stores/{storeNo}/calendar/{localDate}` | 餐饮接单日历例外 |
| B | FOOD-API-004 | POST `/biz/x/food/stores/{storeNo}/menus` | 创建menus |
| B | FOOD-API-005 | GET `/biz/x/food/stores/{storeNo}/menus/{configNo}` | 读取menus |
| B | FOOD-API-006 | POST `/biz/x/food/stores/{storeNo}/menus/{configNo}:publish` | 发布menus |
| B | FOOD-API-007 | POST `/biz/x/food/stores/{storeNo}/option-groups` | 创建option-groups |
| B | FOOD-API-008 | GET `/biz/x/food/stores/{storeNo}/option-groups/{configNo}` | 读取option-groups |
| B | FOOD-API-009 | POST `/biz/x/food/stores/{storeNo}/option-groups/{configNo}:publish` | 发布option-groups |
| B | FOOD-API-010 | POST `/biz/x/food/stores/{storeNo}/bundles` | 创建bundles |
| B | FOOD-API-011 | GET `/biz/x/food/stores/{storeNo}/bundles/{configNo}` | 读取bundles |
| B | FOOD-API-012 | POST `/biz/x/food/stores/{storeNo}/bundles/{configNo}:publish` | 发布bundles |
| B | FOOD-API-013 | POST `/biz/x/food/stores/{storeNo}/stations` | 创建stations |
| B | FOOD-API-014 | GET `/biz/x/food/stores/{storeNo}/stations/{configNo}` | 读取stations |
| B | FOOD-API-015 | POST `/biz/x/food/stores/{storeNo}/stations/{configNo}:publish` | 发布stations |
| B | FOOD-API-016 | PUT `/biz/x/food/stores/{storeNo}/dishes/{goodsNo}` | 菜品餐饮属性 |
| B | FOOD-API-017 | PUT `/biz/x/food/menus/{menuNo}/definition` | 菜单组成与时段配置 |
| B | FOOD-API-018 | PUT `/biz/x/food/bundles/{skuNo}/definition` | 套餐选择组配置 |
| B | FOOD-API-019 | PUT `/biz/x/food/option-groups/{groupNo}/items` | 配置选择项 |
| B | FOOD-API-020 | PUT `/biz/x/food/modifiers/{modifierNo}` | 配料组合配置 |
| B | FOOD-API-021 | PUT `/biz/x/food/stores/{storeNo}/sku-routes/{skuNo}` | 工位路由配置 |
| B | FOOD-API-022 | POST `/biz/x/food/stores/{storeNo}/sales-restrictions` | 沽清与恢复 |
| B | FOOD-API-023 | GET `/mp/x/food/catalog` | 顾客菜单视图 |
| C | FOOD-API-024 | POST `/biz/x/food/stores/{storeNo}/tables` | 建桌 |
| C | FOOD-API-025 | POST `/biz/x/food/tables/{tableNo}:rotate-token` | 桌码轮换 |
| C | FOOD-API-026 | POST `/mp/x/food/sessions` | 桌码换短会话 |
| C | FOOD-API-027 | POST `/biz/x/food/stores/{storeNo}/reservations` | 创建预约 |
| C | FOOD-API-028 | POST `/biz/x/food/reservations/{reservationNo}:cancel` | 预约cancel |
| C | FOOD-API-029 | POST `/biz/x/food/reservations/{reservationNo}:no-show` | 预约no-show |
| C | FOOD-API-030 | POST `/biz/x/food/reservations/{reservationNo}:arrive` | 预约arrive |
| C | FOOD-API-031 | POST `/biz/x/food/stores/{storeNo}/checks` | 开台 |
| C | FOOD-API-032 | GET `/biz/x/food/stores/{storeNo}/checks` | 台账桌台查询 |
| C | FOOD-API-033 | POST `/biz/x/food/checks/{checkNo}:transfer` | 台账transfer |
| C | FOOD-API-034 | POST `/biz/x/food/checks/{checkNo}:merge` | 台账merge |
| C | FOOD-API-035 | POST `/biz/x/food/checks/{checkNo}:split` | 台账split |
| C | FOOD-API-036 | POST `/biz/x/food/checks/{checkNo}:clear` | 台账clear |
| D | FOOD-API-037 | GET `/biz/x/food/carts/{cartNo}` | 购物车读取 |
| D | FOOD-API-038 | POST `/biz/x/food/stores/{storeNo}/carts` | 创建购物车 |
| D | FOOD-API-039 | PUT `/biz/x/food/carts/{cartNo}/lines/{sourceLineRef}` | 替换草稿行 |
| D | FOOD-API-040 | POST `/biz/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive` | 归档购物车草稿行 |
| D | FOOD-API-041 | POST `/biz/x/food/stores/{storeNo}/quotes` | 点单报价 |
| D | FOOD-API-042 | POST `/biz/x/food/stores/{storeNo}/batches` | 提交点餐批次 |
| D | FOOD-API-043 | GET `/biz/x/food/batches/{batchNo}` | 批次结果查询 |
| E | FOOD-API-044 | GET `/biz/x/food/stores/{storeNo}/kitchen/tasks` | 工位队列 |
| E | FOOD-API-045 | POST `/biz/x/food/tasks/{taskNo}:start` | 制作任务start |
| E | FOOD-API-046 | POST `/biz/x/food/tasks/{taskNo}:ready` | 制作任务ready |
| E | FOOD-API-047 | POST `/biz/x/food/tasks/{taskNo}:serve` | 制作任务serve |
| E | FOOD-API-048 | POST `/biz/x/food/tasks/{taskNo}:hold` | 制作任务hold |
| E | FOOD-API-049 | POST `/biz/x/food/tasks/{taskNo}:fire` | 制作任务fire |
| E | FOOD-API-050 | POST `/biz/x/food/tasks/{taskNo}:urge` | 制作任务urge |
| E | FOOD-API-051 | POST `/biz/x/food/tasks/{taskNo}:void` | 制作任务void |
| E | FOOD-API-052 | POST `/biz/x/food/tasks/{taskNo}:remake` | 制作任务remake |
| E | FOOD-API-053 | POST `/biz/x/food/checks/{checkNo}/courses` | 创建course |
| E | FOOD-API-054 | POST `/biz/x/food/courses/{courseNo}:fire` | 道次叫起 |
| E | FOOD-API-055 | POST `/biz/x/food/batches/{batchNo}:handover` | 交付及配送交接 |
| F | FOOD-API-056 | POST `/biz/x/food/stores/{storeNo}/settlements` | 冻结结账范围 |
| F | FOOD-API-057 | GET `/biz/x/food/settlements/{settlementNo}` | 读取预结账 |
| F | FOOD-API-058 | POST `/biz/x/food/settlements/{settlementNo}:pay` | 结账pay |
| F | FOOD-API-059 | POST `/biz/x/food/settlements/{settlementNo}:cancel` | 结账cancel |
| F | FOOD-API-060 | POST `/biz/x/food/settlements/{settlementNo}:finalize` | 结账finalize |
| F | FOOD-API-061 | POST `/biz/x/food/settlements/{settlementNo}/adjustments` | 收费优惠调整申请 |
| G | FOOD-API-062 | POST `/biz/x/food/stores/{storeNo}/returns` | 退餐申请 |
| G | FOOD-API-063 | POST `/biz/x/food/returns/{returnNo}:execute` | 退餐审批执行 |
| G | FOOD-API-064 | GET `/biz/x/food/returns/{returnNo}` | 退餐查询 |
| H | FOOD-API-065 | POST `/biz/print/jobs` | 提交打印 |
| H | FOOD-API-066 | GET `/biz/print/jobs/{jobNo}` | 打印查询 |
| H | FOOD-API-067 | POST `/biz/print/jobs/{jobNo}:reprint` | 人工补打 |
| I | FOOD-API-068 | POST `/biz/x/food/stores/{storeNo}/cash-shifts` | 开班 |
| I | FOOD-API-069 | POST `/biz/x/food/cash-shifts/{shiftNo}/entries` | 非销售现金操作 |
| I | FOOD-API-070 | POST `/biz/x/food/cash-shifts/{shiftNo}:count` | 实盘与交班 |
| I | FOOD-API-071 | POST `/biz/x/food/cash-shifts/{shiftNo}:handover` | 接班确认 |
| I | FOOD-API-072 | POST `/biz/x/food/stores/{storeNo}/closes` | 营业日预结 |
| I | FOOD-API-073 | GET `/biz/x/food/closes/{closeNo}` | 日结查询 |
| I | FOOD-API-074 | POST `/biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:carry` | 异常结转申请 |
| I | FOOD-API-075 | POST `/biz/x/food/closes/{closeNo}:sign` | 签核日结 |
| I | FOOD-API-076 | POST `/biz/x/food/closes/{closeNo}/adjustments` | 关账后更正 |
| J | FOOD-API-077 | GET `/biz/x/food/reports/{reportCode}` | 经营指标报表 |
| J | FOOD-API-078 | POST `/biz/x/food/report-exports` | 异步报表导出 |
| J | FOOD-API-079 | GET `/biz/x/food/report-exports/{exportNo}` | 导出结果 |
| J | FOOD-API-080 | POST `/ops/x/food/report-rebuilds` | 重算投影 |
| K | FOOD-API-081 | PUT `/ops/x/food/stores/{storeNo}/capabilities` | 平台能力配置 |
| K | FOOD-API-082 | PUT `/biz/x/food/integrations/{provider}/mappings` | 外部映射 |
| K | FOOD-API-083 | GET `/ops/x/food/integration-exceptions` | 接入异常查询 |
| K | FOOD-API-084 | POST `/internal/food/sync-batches` | 边缘增量认领 |
| L | FOOD-API-085 | POST `/biz/x/food/checks/{checkNo}/entitlements` | 自助权益入场 |
| L | FOOD-API-086 | POST `/biz/x/food/entitlements/{entitlementNo}:extend` | 续时及最后点单校验 |
| L | FOOD-API-087 | POST `/biz/x/food/stores/{storeNo}/banquet-plans` | 宴会计划 |
| L | FOOD-API-088 | POST `/biz/x/food/banquet-plans/{planNo}:confirm-attendance` | 宴会人数及加席 |
| L | FOOD-API-089 | POST `/biz/x/food/stores/{storeNo}/custodies` | 寄存建账 |
| L | FOOD-API-090 | POST `/biz/x/food/custodies/{custodyNo}/entries` | 寄存取出及报损 |
| L | FOOD-API-091 | POST `/biz/x/food/settlements/{settlementNo}/room-charges` | 房账转挂 |
| C | FOOD-API-092 | GET `/biz/x/food/stores/{storeNo}/table-areas` | 桌区列表 |
| C | FOOD-API-093 | POST `/biz/x/food/stores/{storeNo}/table-areas` | 创建桌区 |
| C | FOOD-API-094 | PUT `/biz/x/food/table-areas/{areaNo}` | 编辑桌区 |
| C | FOOD-API-095 | POST `/biz/x/food/table-areas/{areaNo}:set-status` | 桌区启停 |
| C | FOOD-API-096 | GET `/biz/x/food/stores/{storeNo}/tables` | 桌台列表 |
| C | FOOD-API-097 | GET `/biz/x/food/tables/{tableNo}` | 桌台详情 |
| C | FOOD-API-098 | PUT `/biz/x/food/tables/{tableNo}` | 编辑桌台 |
| C | FOOD-API-099 | POST `/biz/x/food/tables/{tableNo}:set-status` | 桌台启停 |
| C | FOOD-API-100 | POST `/biz/x/food/tables/{tableNo}:archive` | 归档桌台 |
| C | FOOD-API-101 | POST `/biz/x/food/tables/{tableNo}:revoke-token` | 吊销桌码 |
| C | FOOD-API-102 | GET `/biz/x/food/checks/{checkNo}` | 就餐台账详情 |
| C | FOOD-API-103 | PUT `/biz/x/food/checks/{checkNo}/seats` | 修改人数与座位 |
| C | FOOD-API-104 | POST `/biz/x/food/checks/{checkNo}:combine-tables` | 并入桌台 |
| C | FOOD-API-105 | POST `/biz/x/food/tables/{tableNo}:share` | 同桌新开独立台账 |
| C | FOOD-API-106 | GET `/biz/x/food/table-operations/{operationNo}` | 桌台操作恢复查询 |
| C | FOOD-API-107 | GET `/biz/x/food/stores/{storeNo}/reservations` | 预约列表 |
| C | FOOD-API-108 | GET `/biz/x/food/checks/{checkNo}/actions` | 就餐操作历史 |
| C | FOOD-API-138 | GET `/mp/x/food/checks/{checkNo}` | 顾客就餐进度 |
| C | FOOD-API-139 | POST `/mp/x/food/sessions/{sessionNo}:end` | 主动结束顾客会话 |

## 三、公共DTO输入输出字段

### Money

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| minor | int64 | 是 | 最小货币单位 |
| currency | string | 是 | ISO币种 |

### Selection

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| groupNo | string | 是 | 选择组 |
| sourceKind | string | 是 | SPEC_VALUE或MODIFIER |
| sourceNo | string | 是 | 编号 |
| qty | decimal | 是 | 数量 |
| configVersion | int64 | 是 | 规则版本 |

### OrderLineInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 源行 |
| goodsNo | string | 是 | 商品 |
| skuNo | string | 是 | SKU |
| qty | decimal | 是 | 正数量 |
| seatNo | string | 否 | 座位 |
| courseNo | string | 否 | 道次 |
| selections | Selection[] | 是 | 选择明细 |

### QuoteLine

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 源行 |
| skuNo | string | 是 | SKU |
| qty | decimal | 是 | 数量 |
| amount | Money | 是 | 核心金额 |
| coreBreakdownRef | string | 是 | 核心分摊引用 |

### Quote

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| quoteNo | string | 是 | 报价 |
| expiresAt | datetime | 是 | 失效 |
| scopeHash | string | 是 | 范围指纹 |
| lines | QuoteLine[] | 是 | 逐行金额 |
| total | Money | 是 | 应收 |
| capabilities | string[] | 是 | 可用能力 |

### Check

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 是 | 台账 |
| storeNo | string | 是 | 门店 |
| businessDate | date | 是 | 开台营业日 |
| status | string | 是 | 营业状态 |
| payMode | string | 是 | 先后付 |
| partySize | int32 | 是 | 人数 |
| tableNos | string[] | 是 | 占桌 |
| version | int64 | 是 | 版本 |

### Task

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string | 是 | 任务 |
| foodLineNo | string | 是 | 菜品源行 |
| stationNo | string | 是 | 工位 |
| status | string | 是 | 状态 |
| targetQty | decimal | 是 | 目标量 |
| readyQty | decimal | 是 | 出品量 |
| servedQty | decimal | 是 | 上菜量 |
| voidQty | decimal | 是 | 撤销量 |
| version | int64 | 是 | 版本 |

### Batch

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string | 是 | 批次 |
| status | string | 是 | 处理中或完成 |
| paymentRootNos | string[] | 是 | 核心支付根 |
| storeOrderNos | string[] | 是 | 门店单 |
| foodLineNos | string[] | 是 | 餐饮行 |
| version | int64 | 是 | 版本 |

### MetricCell

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| metricCode | string | 是 | 指标 |
| value | decimal | 是 | 值 |
| unit | string | 是 | 单位 |
| currency | string | 否 | 金额币种 |
| definitionVersion | int64 | 是 | 口径版本 |

### Report

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| rows | MetricCell[] | 是 | 指标行 |
| asOf | datetime | 是 | 数据截至 |
| complete | boolean | 是 | 完整性 |
| timeBasis | string | 是 | 时间基准 |
| watermark | string | 是 | 源水位 |
| total | int64 | 是 | 总行数 |
| page | int32 | 是 | 页码 |
| size | int32 | 是 | 每页条数 |

### ScopeItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeOrderNo | string | 是 | 核心门店单 |
| coreAllocationRef | string | 否 | 部分分摊引用 |

### Settlement

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string | 是 | 结账 |
| status | string | 是 | 状态 |
| scopeHash | string | 是 | 范围 |
| due | Money | 是 | 冻结应收 |
| coreCashierRef | string | 否 | 权威收银引用 |
| version | int64 | 是 | 版本 |

### Close

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string | 是 | 日结 |
| businessDate | date | 是 | 营业日 |
| currency | string | 是 | 币种 |
| revision | int32 | 是 | 版本 |
| status | string | 是 | 状态 |
| balances | MetricCell[] | 是 | 快照 |
| exceptionNos | string[] | 是 | 异常 |
| watermark | string | 是 | 源水位 |
| snapshotHash | string | 是 | 结果指纹 |
| version | int64 | 是 | 对象版本 |

### Config

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 对象编号 |
| kind | string | 是 | 受控配置类型 |
| revision | int64 | 是 | 版本 |
| status | string | 是 | 草稿或已发布 |
| data | object | 是 | 按对应数据库字段定义且拒未知字段 |

### ReturnItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| foodLineNo | string | 是 | 源行 |
| componentNo | string | 否 | 组件 |
| qty | decimal | 是 | 退量 |
| disposition | string | 是 | CANCEL或LOSS或KEEP或REMAKE |

### Page

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | object[] | 是 | 该端点明确的业务VO |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页码 |
| size | int32 | 是 | 每页条数 |

### MenuCategoryInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| categoryNo | string | 是 | 分类 |
| name | string | 是 | 名称 |
| sortNo | int32 | 是 | 排序 |
| items | MenuItemInput[] | 是 | 商品项 |

### MenuItemInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuItemNo | string | 是 | 项号 |
| goodsNo | string | 是 | 商品 |
| sortNo | int32 | 是 | 排序 |
| enabled | boolean | 是 | 启用 |
| skuNos | string[] | 是 | 空表示全部合法可售SKU |

### WeekScheduleInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| weekday | int32 | 是 | 1至7 |
| startTime | string | 是 | HH:mm |
| endTime | string | 是 | HH:mm |
| endDayOffset | int32 | 是 | 0或1 |

### ExceptionInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| localDate | date | 是 | 日期 |
| mode | string | 是 | OPEN或CLOSED |
| startTime | string | 否 | OPEN必填 |
| endTime | string | 否 | OPEN必填 |
| endDayOffset | int32 | 是 | 跨日标记 |

### ContextInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| orderSource | string | 是 | 受控来源 |
| fulfillment | string | 是 | 受控履约 |

### BundleGroupInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| groupNo | string | 是 | 组号 |
| name | string | 是 | 名称 |
| minQty | decimal | 是 | 最少 |
| maxQty | decimal | 是 | 最多 |
| baseSkuNo | string | 否 | 补差基准 |

### BundleChoiceInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| choiceNo | string | 是 | 选项 |
| groupNo | string | 是 | 组 |
| skuNo | string | 是 | SKU |
| unitsPerPick | decimal | 是 | 每选用量 |
| priceMode | string | 是 | INCLUDED或ADD_SKU或DIFF_BASE |
| defaultQty | decimal | 是 | 默认 |
| minQty | decimal | 是 | 下限 |
| maxQty | decimal | 是 | 上限 |

### OptionItemInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| optionItemNo | string | 是 | 项号 |
| sourceKind | string | 是 | SPEC_VALUE或MODIFIER |
| specValueNo | string | 否 | 字典值 |
| modifierNo | string | 否 | 配料 |
| maxQty | decimal | 是 | 上限 |
| defaultQty | decimal | 是 | 默认 |
| sortNo | int32 | 是 | 排序 |

### ComponentInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| memberNo | string | 是 | 叶子配料 |
| quantity | decimal | 是 | 正用量 |

### RouteInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| stationNo | string | 是 | 工位 |
| taskRole | string | 是 | MAKE或ASSEMBLE或PACK |
| routeVersion | int64 | 是 | 路由版 |

### VersionInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 是 | 台账 |
| version | int64 | 是 | 版本 |

### DeliveryLineInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| foodLineNo | string | 是 | 餐饮行 |
| qty | decimal | 是 | 交付量 |

### Table

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string | 是 | 桌号 |
| storeNo | string | 是 | 门店 |
| areaNo | string | 是 | 桌区 |
| code | string | 是 | 店内编码 |
| name | string | 是 | 名称 |
| capacity | int32 | 是 | 容量 |
| sortNo | int32 | 是 | 排序 |
| status | string | 是 | ACTIVE或DISABLED |
| turnoverState | string | 是 | IDLE或IN_USE或NEEDS_CLEANING |
| resourceNo | string | 否 | 共享资源引用，绑定写入待权威合同 |
| version | int64 | 是 | 版本 |


### SeatInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| seatNo | string | 否 | 创建省略由服务端发号，更新只能引用本台账座位 |
| label | string | 是 | 台账内唯一座位标签 |
| guestRef | string | 否 | 获准匿名或顾客引用 |


### TableVersionInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string | 是 | 桌台 |
| version | int64 | 是 | 资源版本 |


### Occupancy

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| occupancyNo | string | 是 | 占用号 |
| tableNo | string | 是 | 桌台 |
| checkNo | string | 是 | 获准台账 |
| seats | int32 | 是 | 占座 |
| startedAt | datetime | 是 | 开始 |


### ActionLog

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| actionNo | string | 是 | 记录 |
| action | string | 是 | 动作 |
| actorRef | string | 是 | 审计操作人 |
| occurredAt | datetime | 是 | 时间 |
| reason | string | 否 | 原因 |


### Reservation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string | 是 | 预约 |
| partySize | int32 | 是 | 人数 |
| startAt | datetime | 是 | 开始 |
| endAt | datetime | 是 | 结束 |
| status | string | 是 | 资源状态 |
| depositRef | string | 否 | 获准订金凭据 |
| checkNo | string | 否 | 到店台账 |
| version | int64 | 是 | 版本 |


### Operation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作 |
| status | string | 是 | 含处理中未知失败 |
| version | int64 | 是 | 当前版本 |
| resultRef | string | 否 | 结果号 |


### FoodLine

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| foodLineNo | string | 是 | 餐饮行 |
| sourceLineRef | string | 是 | 原源行 |
| goodsNo | string | 是 | 商品 |
| skuNo | string | 是 | SKU |
| qty | decimal | 是 | 数量 |
| unit | string | 是 | 单位 |
| seatNo | string | 否 | 座位 |
| configRevisionNos | string[] | 是 | 发布版本 |
| displaySnapshot | object | 是 | 受控历史展示 |
| coreAllocations | CoreLineAllocation[] | 是 | 受权核心分配 |


## 四、逐接口输入输出

### FOOD-API-001 · 查询营业策略

`GET /biz/x/food/stores/{storeNo}/policy`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| at | datetime | 否 | 查询时点 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| policyNo | string | 是 | 策略 |
| timezone | string | 是 | IANA时区 |
| cutoverMinute | int32 | 是 | 切点 |
| payPolicy | string | 是 | 先后付 |
| capabilities | string[] | 是 | 启用能力 |
| version | int64 | 是 | 版本 |

### FOOD-API-002 · 发布营业策略

`POST /biz/x/food/stores/{storeNo}/policies`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等；version首次0、追加递增且effectiveFrom晚于最新发布；能力与审批未交付前非空拒绝。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| timezone | string | 是 | IANA时区 |
| cutoverMinute | int32 | 是 | 0至1439 |
| effectiveFrom | datetime | 是 | 生效 |
| payPolicy | string | 是 | PREPAY或POSTPAY |
| capabilities | string[] | 是 | 能力码 |
| approvalRef | string | 否 | 审批 |
| version | int64 | 是 | 最新发布版，首次0，含未来版 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-003 · 餐饮接单日历例外

`PUT /biz/x/food/stores/{storeNo}/calendar/{localDate}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等；不是门店openHours编辑入口，不修改共享营业时间；localDate按策略营业日切点；CLOSED关闭该营业日，OPEN与原营业时间取交集；policy_no仅追溯。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| localDate | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| mode | string | 是 | OPEN或CLOSED |
| intervals | string[] | 是 | 本地起止时段 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-004 · 创建menus

`POST /biz/x/food/stores/{storeNo}/menus`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 菜单名 |
| menuType | string | 是 | STANDARD或TIME或SEASON或EVENT |
| timezone | string | 是 | 时区 |
| priority | int32 | 是 | 优先级 |
| exclusiveGroup | string | 否 | 互斥组 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配置号 |
| version | int64 | 是 | 草稿版本 |
| status | string | 是 | DRAFT |

### FOOD-API-005 · 读取menus

`GET /biz/x/food/stores/{storeNo}/menus/{configNo}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| config | Config | 是 | 具体配置，类型由路径确定 |

### FOOD-API-006 · 发布menus

`POST /biz/x/food/stores/{storeNo}/menus/{configNo}:publish`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 草稿版本 |
| effectiveAt | datetime | 是 | 生效时刻 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-007 · 创建option-groups

`POST /biz/x/food/stores/{storeNo}/option-groups`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 组名 |
| minQty | decimal | 是 | 下限 |
| maxQty | decimal | 是 | 上限 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配置号 |
| version | int64 | 是 | 草稿版本 |
| status | string | 是 | DRAFT |

### FOOD-API-008 · 读取option-groups

`GET /biz/x/food/stores/{storeNo}/option-groups/{configNo}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| config | Config | 是 | 具体配置，类型由路径确定 |

### FOOD-API-009 · 发布option-groups

`POST /biz/x/food/stores/{storeNo}/option-groups/{configNo}:publish`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 草稿版本 |
| effectiveAt | datetime | 是 | 生效时刻 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-010 · 创建bundles

`POST /biz/x/food/stores/{storeNo}/bundles`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 主SKU |
| configVersion | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配置号 |
| version | int64 | 是 | 草稿版本 |
| status | string | 是 | DRAFT |

### FOOD-API-011 · 读取bundles

`GET /biz/x/food/stores/{storeNo}/bundles/{configNo}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| config | Config | 是 | 具体配置，类型由路径确定 |

### FOOD-API-012 · 发布bundles

`POST /biz/x/food/stores/{storeNo}/bundles/{configNo}:publish`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 草稿版本 |
| effectiveAt | datetime | 是 | 生效时刻 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-013 · 创建stations

`POST /biz/x/food/stores/{storeNo}/stations`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 工位名 |
| kind | string | 是 | HOT或COLD或BAR或PACK |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配置号 |
| version | int64 | 是 | 草稿版本 |
| status | string | 是 | DRAFT |

### FOOD-API-014 · 读取stations

`GET /biz/x/food/stores/{storeNo}/stations/{configNo}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| config | Config | 是 | 具体配置，类型由路径确定 |

### FOOD-API-015 · 发布stations

`POST /biz/x/food/stores/{storeNo}/stations/{configNo}:publish`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 草稿版本 |
| effectiveAt | datetime | 是 | 生效时刻 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-016 · 菜品餐饮属性

`PUT /biz/x/food/stores/{storeNo}/dishes/{goodsNo}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| goodsNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| dishKind | string | 是 | SINGLE或BUNDLE或ADDON |
| prepSeconds | int32 | 是 | 制作秒数 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-017 · 菜单组成与时段配置

`PUT /biz/x/food/menus/{menuNo}/definition`

权限与守卫：菜单管理权限；逐项按菜单子表字段拒未知值，发布校验重叠及时区。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 草稿版本 |
| storeNos | string[] | 是 | 适用门店 |
| contexts | ContextInput[] | 是 | 渠道与履约 |
| weekSchedules | WeekScheduleInput[] | 是 | weekday起止时间和跨日标记 |
| exceptions | ExceptionInput[] | 是 | 日期及OPEN或CLOSED和时间 |
| categories | MenuCategoryInput[] | 是 | 分类号名称排序及商品SKU白名单 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string | 是 | 菜单 |
| version | int64 | 是 | 新草稿版本 |

### FOOD-API-018 · 套餐选择组配置

`PUT /biz/x/food/bundles/{skuNo}/definition`

权限与守卫：配置权限；priceMode仅INCLUDED/ADD_SKU/DIFF_BASE；无递归套餐。

能力依赖：核心组合报价与库存展开缺口。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| groups | BundleGroupInput[] | 是 | groupNo名称minQty/maxQty/baseSkuNo |
| choices | BundleChoiceInput[] | 是 | choiceNo/groupNo/skuNo/unitsPerPick/priceMode/defaultQty/minQty/maxQty |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | SKU |
| version | int64 | 是 | 新版本 |

### FOOD-API-019 · 配置选择项

`PUT /biz/x/food/option-groups/{groupNo}/items`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| groupNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| items | OptionItemInput[] | 是 | optionItemNo/sourceKind/specValueNo或modifierNo/maxQty/defaultQty/sortNo |
| goodsNos | string[] | 是 | 绑定商品 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 组 |
| version | int64 | 是 | 新版本 |

### FOOD-API-020 · 配料组合配置

`PUT /biz/x/food/modifiers/{modifierNo}`

权限与守卫：配料管理权限；组合禁止循环、价格和库存不双算。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| modifierNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 名称 |
| kind | string | 是 | ADDON或COMBO |
| salesSkuNo | string | 否 | 收费SKU |
| components | ComponentInput[] | 是 | memberNo及正quantity |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配料 |
| version | int64 | 是 | 新版本 |

### FOOD-API-021 · 工位路由配置

`PUT /biz/x/food/stores/{storeNo}/sku-routes/{skuNo}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| skuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| routes | RouteInput[] | 是 | stationNo/taskRole/routeVersion |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-022 · 沽清与恢复

`POST /biz/x/food/stores/{storeNo}/sales-restrictions`

权限与守卫：沽清权限不改物理库存。

能力依赖：共享可售约束或新增通用能力。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | SKU |
| mode | string | 是 | SOLD_OUT或QUOTA或CLEAR |
| quota | decimal | 否 | 正配额 |
| expiresAt | datetime | 否 | 到期 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| restrictionNo | string | 是 | 销售约束引用 |
| status | string | 是 | 结果 |

### FOOD-API-023 · 顾客菜单视图

`GET /mp/x/food/catalog`

权限与守卫：门店或会话授权，客户端at仅预览不决定提交时刻。

能力依赖：餐饮新增。

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 门店 |
| sessionNo | string | 否 | 会话 |
| channel | string | 是 | 销售渠道 |
| fulfillment | string | 是 | 履约 |
| at | datetime | 否 | 预览时间 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNos | string[] | 是 | 生效菜单 |
| items | object[] | 是 | 商品SKU名称当前价可售量选择组 |
| configVersion | int64 | 是 | 版本 |
| asOf | datetime | 是 | 时点 |

### FOOD-API-024 · 建桌

`POST /biz/x/food/stores/{storeNo}/tables`

权限与守卫：主体门店及启用桌区一致；锁桌区仲裁行后创建；同店code精确唯一且归档后不复用；初始ACTIVE/IDLE、version0；不接受客户端占用状态；非空resourceNo在权威查询合同未齐时拒绝。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string | 是 | 桌区 |
| code | string | 是 | 门店唯一桌台编码 |
| name | string | 是 | 名称 |
| capacity | int32 | 是 | 正座位数 |
| sortNo | int32 | 是 | 排序 |
| resourceNo | string | 否 | 共享资源 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| table | Table | 是 | 桌台 |

### FOOD-API-025 · 桌码轮换

`POST /biz/x/food/tables/{tableNo}:rotate-token`

权限与守卫：原STORE权限及主体门店范围；桌区→桌台稳定锁和CAS；轮换撤销旧码及该桌旧会话；只保存SHA-256，明文仅首次响应；幂等收据不存凭证，同键重放CONFLICT且不再次轮换。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| expiresAt | datetime | 是 | 到期 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| token | string | 是 | 仅本次返回明文 |
| expiresAt | datetime | 是 | 到期 |

### FOOD-API-026 · 桌码换短会话

`POST /mp/x/food/sessions`

权限与守卫：桌码哈希校验后才解析主体门店，经原StoreDirectoryPort确认ACTIVE；签发最长30分钟且不超过桌码到期的TABLE_MENU会话；不自动绑定现有check。明文只首次返回，同键重放CONFLICT；设备认证合同未齐时非空deviceCredential拒绝。。

能力依赖：餐饮新增。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableToken | string | 是 | 桌码 |
| deviceCredential | string | 否 | 可验证设备凭据，不信任deviceNo自报 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sessionNo | string | 是 | 会话 |
| accessToken | string | 是 | 短期令牌 |
| expiresAt | datetime | 是 | 到期 |
| checkNo | string | 否 | 获准台账 |

### FOOD-API-027 · 创建预约

`POST /biz/x/food/stores/{storeNo}/reservations`

权限与守卫：目标沿共享resourceNo/hold权威做预约、释放和到店接力，资源取消不等于订金退款；当前共享资源合同未验收，此写入口未开放，不生成假hold引用。资源与代办授权。

能力依赖：共享预约订金契约。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| resourceNo | string | 是 | 资源 |
| startAt | datetime | 是 | 开始 |
| endAt | datetime | 是 | 结束 |
| partySize | int32 | 是 | 人数 |
| buyerNo | string | 否 | 顾客 |
| contactRef | string | 是 | 加密联系人 |
| depositQuoteRef | string | 否 | 订金报价 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string | 是 | 预约 |
| resourceHoldNo | string | 是 | 占位 |
| status | string | 是 | 状态 |

### FOOD-API-028 · 预约cancel

`POST /biz/x/food/reservations/{reservationNo}:cancel`

权限与守卫：目标沿共享resourceNo/hold权威做预约、释放和到店接力，资源取消不等于订金退款；当前共享资源合同未验收，此写入口未开放，不生成假hold引用。预约权限及版本；订金退款/没收单独走资金审批合同，资源取消不意味着已退款。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 取消或爽约原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 资源释放和资金处理进度 |

### FOOD-API-029 · 预约no-show

`POST /biz/x/food/reservations/{reservationNo}:no-show`

权限与守卫：目标沿共享resourceNo/hold权威做预约、释放和到店接力，资源取消不等于订金退款；当前共享资源合同未验收，此写入口未开放，不生成假hold引用。预约权限及版本；订金退款/没收单独走资金审批合同，资源取消不意味着已退款。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 取消或爽约原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 资源释放和资金处理进度 |

### FOOD-API-030 · 预约arrive

`POST /biz/x/food/reservations/{reservationNo}:arrive`

权限与守卫：目标沿共享resourceNo/hold权威做预约、释放和到店接力，资源取消不等于订金退款；当前共享资源合同未验收，此写入口未开放，不生成假hold引用。新占用失败保留原预约资源；到店只生成一个台账；原预约和资金凭据保留。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 预约版本 |
| tableNos | string[] | 是 | 占桌 |
| tableVersions | TableVersionInput[] | 是 | 资源版本 |
| partySize | int32 | 是 | 实际人数 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作 |
| status | string | 是 | 状态 |
| checkNo | string | 否 | 开台结果 |

### FOOD-API-031 · 开台

`POST /biz/x/food/stores/{storeNo}/checks`

权限与守卫：原VERIFY权限及门店范围；所有目标桌完整CAS，桌区→桌台有序锁后原子新增check/occupancy/seat/audit/outbox；V2持久化EXCLUSIVE/SHARED；营业日复用门店策略。当前只开放无交易PREPAY，POSTPAY及未经验证的预约/员工/顾客引用拒绝；空tableNos支持无桌接待。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNos | string[] | 是 | 空为无桌 |
| partySize | int32 | 是 | 正人数 |
| payMode | string | 是 | PREPAY或POSTPAY |
| reservationNo | string | 否 | 来源预约 |
| tableVersions | TableVersionInput[] | 是 | 所有目标桌版本 |
| sharingMode | string | 是 | EXCLUSIVE或SHARED |
| seats | SeatInput[] | 是 | 座位 |
| serviceEmployeeNo | string | 否 | 获准员工 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 台账 |

### FOOD-API-032 · 台账桌台查询

`GET /biz/x/food/stores/{storeNo}/checks`

权限与守卫：原ORDER_VIEW权限；主体及当前门店固定过滤，状态/营业日与records/total同口径，分页1/20最大100。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| status | string | 否 | 过滤 |
| businessDate | date | 否 | 营业日 |
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Check[] | 是 | 列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 当前页 |
| size | int32 | 是 | 每页条数 |

### FOOD-API-033 · 台账transfer

`POST /biz/x/food/checks/{checkNo}:transfer`

权限与守卫：原VERIFY权限、check CAS及全部来源/目标桌CAS；当前仅无批次/结账/退餐/权益/未完成草稿的PREPAY台账；先核验并占完整目标再结束原占用，同主库事务回滚不丢原桌；approvalRef未核验时拒绝；不搬订单或资金。 外部resourceNo/resourceClaimNo或未完成资源意图拒绝本地变更。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |
| targetTableNos | string[] | 是 | 转入桌台 |
| tableVersions | TableVersionInput[] | 是 | 所有来源目标桌版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 转台受理及恢复结果 |

### FOOD-API-034 · 合并就餐服务台账

`POST /biz/x/food/checks/{checkNo}:merge`

权限与守卫：目标保留原批次及资金归属，按完整membership与冻结合同合拆；当前ORD-03/PAY-01未齐，此写入口未开放。仅合并服务/占用展示归属；原批次check及核心资金归属不改；结账冻结/未知资金时拒绝；合账收款另走settlement。

能力依赖：餐饮桌台操作意图及追加归属；资金合账取决于核心收银。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 来源版本 |
| targetCheckNo | string | 是 | 目标台账 |
| targetVersion | int64 | 是 | 目标版本 |
| tableVersions | TableVersionInput[] | 是 | 桌台版本 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 协作结果 |

### FOOD-API-035 · 拆分就餐服务台账

`POST /biz/x/food/checks/{checkNo}:split`

权限与守卫：目标保留原批次及资金归属，按完整membership与冻结合同合拆；当前ORD-03/PAY-01未齐，此写入口未开放。仅调整批次服务归属，不改成交和资金；需锁批次且拒绝冻结/未知资金范围；按菜拆账走核心应收分摊。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 来源版本 |
| targetCheckNo | string | 是 | 目标台账 |
| targetVersion | int64 | 是 | 目标版本 |
| selectedBatchNos | string[] | 是 | 允许调整展示归属的完整批次 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 协作结果 |

### FOOD-API-036 · 台账clear

`POST /biz/x/food/checks/{checkNo}:clear`

权限与守卫：原VERIFY权限及check CAS；仅释放本check明确occupancyNos，空数组只允许本来无桌；任何交易事实或未完成/未知草稿拒绝普通清台。当前只验收无交易接待，最后释放记VOIDED/RELEASED，其他客组保留；已清洁空桌IDLE；不声称资金结清或支持异常放行。 外部resourceNo/resourceClaimNo或未完成资源意图拒绝本地变更。 部分清台先锁全部现有桌位，按原人数重分配剩余占座；剩余容量不足则整笔拒绝。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |
| occupancyNos | string[] | 是 | 结束占用不删除未收应收 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-037 · 购物车读取

`GET /biz/x/food/carts/{cartNo}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| version | int64 | 是 | 版本 |
| lines | OrderLineInput[] | 是 | 草稿 |

### FOOD-API-038 · 创建购物车

`POST /biz/x/food/stores/{storeNo}/carts`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 台账 |
| sessionNo | string | 否 | 无台会话 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| version | int64 | 是 | 版本 |

### FOOD-API-039 · 替换草稿行

`PUT /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| sourceLineRef | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| line | OrderLineInput | 是 | 本行 |
| version | int64 | 是 | 购物车版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| version | int64 | 是 | 新版本 |

### FOOD-API-040 · 归档购物车草稿行

`POST /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| sourceLineRef | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 购物车版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| version | int64 | 是 | 新版本 |

### FOOD-API-041 · 点单报价

`POST /biz/x/food/stores/{storeNo}/quotes`

权限与守卫：范围与代办；不锁库存不建单。

能力依赖：已有报价适配；选配计量等分项门控。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 台账 |
| buyerNo | string | 否 | 真实买家 |
| fulfillment | string | 是 | 履约 |
| channel | string | 是 | 渠道 |
| lines | OrderLineInput[] | 是 | 源行 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| quote | Quote | 是 | 权威报价 |

### FOOD-API-042 · 提交点餐批次

`POST /biz/x/food/stores/{storeNo}/batches`

权限与守卫：强餐饮规则校验；匿名身份未就绪拒绝；后付需受理契约。

能力依赖：当前OrderService适配及通用缺口。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| cartVersion | int64 | 是 | 版本 |
| selectedLineRefs | string[] | 是 | 本次行 |
| quoteNo | string | 是 | 已确认报价 |
| payMode | string | 是 | 先后付 |
| splitByPerson | boolean | 是 | 预分单 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batch | Batch | 是 | 绑定或处理中 |

### FOOD-API-043 · 批次结果查询

`GET /biz/x/food/batches/{batchNo}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batch | Batch | 是 | 原请求结果 |

### FOOD-API-044 · 工位队列

`GET /biz/x/food/stores/{storeNo}/kitchen/tasks`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| stationNo | string | 是 | 工位 |
| status | string | 否 | 状态 |
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Task[] | 是 | 任务 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 当前页 |
| size | int32 | 是 | 每页条数 |

### FOOD-API-045 · 制作任务start

`POST /biz/x/food/tasks/{taskNo}:start`

权限与守卫：工位/传菜/退餐分别鉴权；累计数量守恒；重做审批不直接改资金。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 否 | 数量动作必填 |
| reason | string | 否 | 撤菜重做必填 |
| returnNo | string | 否 | 退餐授权引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 新任务状态 |
| eventNo | string | 是 | 追加事实 |
| newTaskNo | string | 否 | 重做任务 |

### FOOD-API-046 · 制作任务ready

`POST /biz/x/food/tasks/{taskNo}:ready`

权限与守卫：工位/传菜/退餐分别鉴权；累计数量守恒；重做审批不直接改资金。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 否 | 数量动作必填 |
| reason | string | 否 | 撤菜重做必填 |
| returnNo | string | 否 | 退餐授权引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 新任务状态 |
| eventNo | string | 是 | 追加事实 |
| newTaskNo | string | 否 | 重做任务 |

### FOOD-API-047 · 制作任务serve

`POST /biz/x/food/tasks/{taskNo}:serve`

权限与守卫：工位/传菜/退餐分别鉴权；累计数量守恒；重做审批不直接改资金。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 否 | 数量动作必填 |
| reason | string | 否 | 撤菜重做必填 |
| returnNo | string | 否 | 退餐授权引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 新任务状态 |
| eventNo | string | 是 | 追加事实 |
| newTaskNo | string | 否 | 重做任务 |

### FOOD-API-048 · 制作任务hold

`POST /biz/x/food/tasks/{taskNo}:hold`

权限与守卫：工位/传菜/退餐分别鉴权；累计数量守恒；重做审批不直接改资金。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 否 | 数量动作必填 |
| reason | string | 否 | 撤菜重做必填 |
| returnNo | string | 否 | 退餐授权引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 新任务状态 |
| eventNo | string | 是 | 追加事实 |
| newTaskNo | string | 否 | 重做任务 |

### FOOD-API-049 · 制作任务fire

`POST /biz/x/food/tasks/{taskNo}:fire`

权限与守卫：工位/传菜/退餐分别鉴权；累计数量守恒；重做审批不直接改资金。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 否 | 数量动作必填 |
| reason | string | 否 | 撤菜重做必填 |
| returnNo | string | 否 | 退餐授权引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 新任务状态 |
| eventNo | string | 是 | 追加事实 |
| newTaskNo | string | 否 | 重做任务 |

### FOOD-API-050 · 制作任务urge

`POST /biz/x/food/tasks/{taskNo}:urge`

权限与守卫：工位/传菜/退餐分别鉴权；累计数量守恒；重做审批不直接改资金。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 否 | 数量动作必填 |
| reason | string | 否 | 撤菜重做必填 |
| returnNo | string | 否 | 退餐授权引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 新任务状态 |
| eventNo | string | 是 | 追加事实 |
| newTaskNo | string | 否 | 重做任务 |

### FOOD-API-051 · 制作任务void

`POST /biz/x/food/tasks/{taskNo}:void`

权限与守卫：工位/传菜/退餐分别鉴权；累计数量守恒；重做审批不直接改资金。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 否 | 数量动作必填 |
| reason | string | 否 | 撤菜重做必填 |
| returnNo | string | 否 | 退餐授权引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 新任务状态 |
| eventNo | string | 是 | 追加事实 |
| newTaskNo | string | 否 | 重做任务 |

### FOOD-API-052 · 制作任务remake

`POST /biz/x/food/tasks/{taskNo}:remake`

权限与守卫：工位/传菜/退餐分别鉴权；累计数量守恒；重做审批不直接改资金。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 否 | 数量动作必填 |
| reason | string | 否 | 撤菜重做必填 |
| returnNo | string | 否 | 退餐授权引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 新任务状态 |
| eventNo | string | 是 | 追加事实 |
| newTaskNo | string | 否 | 重做任务 |

### FOOD-API-053 · 创建course

`POST /biz/x/food/checks/{checkNo}/courses`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 道次名 |
| sequenceNo | int32 | 是 | 顺序 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| courseNo | string | 是 | 道次 |
| status | string | 是 | HELD |
| version | int64 | 是 | 版本 |

### FOOD-API-054 · 道次叫起

`POST /biz/x/food/courses/{courseNo}:fire`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| courseNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| seatNos | string[] | 是 | 座位范围 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-055 · 交付及配送交接

`POST /biz/x/food/batches/{batchNo}:handover`

权限与守卫：履约权限；核销码防重复。

能力依赖：堂食通用交付缺口，其余复用已有履约。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| kind | string | 是 | DINE_IN或PICKUP或DELIVERY |
| coreFulfillmentRef | string | 否 | 核心履约凭据 |
| lines | DeliveryLineInput[] | 是 | foodLineNo及交付qty |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作 |
| coreDeliveryRef | string | 是 | 正式交付引用 |
| status | string | 是 | 结果 |

### FOOD-API-056 · 冻结结账范围

`POST /biz/x/food/stores/{storeNo}/settlements`

权限与守卫：收银权限及核心范围独占防双收。

能力依赖：跨单/部分分摊取决于核心收银。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| items | ScopeItem[] | 是 | 范围 |
| currency | string | 是 | 币种 |
| quoteNo | string | 是 | 权威应收 |
| checkVersions | VersionInput[] | 是 | checkNo和version |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlement | Settlement | 是 | 结账 |

### FOOD-API-057 · 读取预结账

`GET /biz/x/food/settlements/{settlementNo}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlement | Settlement | 是 | 当前状态 |
| confirmedAmount | Money | 是 | 核心已确认 |
| pendingIntentNos | string[] | 是 | 未知请求 |

### FOOD-API-058 · 结账pay

`POST /biz/x/food/settlements/{settlementNo}:pay`

权限与守卫：不得传markPaid；finalize需核心结清且无未知资金。

能力依赖：核心支付/现金确认；混合与跨根需扩展。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| tool | string | 是 | 支付工具 |
| amount | Money | 是 | 本次金额 |
| corePaymentToken | string | 否 | 临时凭证 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 请求意图 |
| status | string | 是 | 含UNKNOWN |
| coreReference | string | 否 | 核心结果 |
| paymentAction | object | 否 | 核心返回端上支付动作 |

### FOOD-API-059 · 结账cancel

`POST /biz/x/food/settlements/{settlementNo}:cancel`

权限与守卫：不得传markPaid；finalize需核心结清且无未知资金。

能力依赖：核心支付/现金确认；混合与跨根需扩展。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 请求意图 |
| status | string | 是 | 含UNKNOWN |
| coreReference | string | 否 | 核心结果 |
| paymentAction | object | 否 | 核心返回端上支付动作 |

### FOOD-API-060 · 结账finalize

`POST /biz/x/food/settlements/{settlementNo}:finalize`

权限与守卫：不得传markPaid；finalize需核心结清且无未知资金。

能力依赖：核心支付/现金确认；混合与跨根需扩展。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| scopeHash | string | 是 | 冻结范围指纹 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 请求意图 |
| status | string | 是 | 含UNKNOWN |
| coreReference | string | 否 | 核心结果 |
| paymentAction | object | 否 | 核心返回端上支付动作 |

### FOOD-API-061 · 收费优惠调整申请

`POST /biz/x/food/settlements/{settlementNo}/adjustments`

权限与守卫：费用小费分期门控及授权审批。

能力依赖：核心应收调整或AR能力。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| kind | string | 是 | DISCOUNT或FEE或TIP或AR_TRANSFER |
| amount | Money | 是 | 申请金额 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |
| coreQuoteRef | string | 是 | 报价 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 意图 |
| coreAdjustmentRef | string | 否 | 核心调整 |
| status | string | 是 | 结果 |

### FOOD-API-062 · 退餐申请

`POST /biz/x/food/stores/{storeNo}/returns`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 台账 |
| items | ReturnItem[] | 是 | 逐行退量 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string | 是 | 申请 |
| status | string | 是 | REQUESTED |
| version | int64 | 是 | 版本 |

### FOOD-API-063 · 退餐审批执行

`POST /biz/x/food/returns/{returnNo}:execute`

权限与守卫：申请审批责任分离；当前部分退款禁用。

能力依赖：核心取消/售后；行级调整退款缺口。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| approvalRef | string | 是 | 共享审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作 |
| financialStatus | string | 是 | 财务轨道 |
| kitchenStatus | string | 是 | 厨房轨道 |
| coreCaseRef | string | 否 | 售后 |

### FOOD-API-064 · 退餐查询

`GET /biz/x/food/returns/{returnNo}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string | 是 | 申请 |
| items | ReturnItem[] | 是 | 明细 |
| status | string | 是 | 申请状态 |
| financialStatus | string | 是 | 资金状态 |
| coreCaseRef | string | 否 | 核心售后 |
| version | int64 | 是 | 版本 |

### FOOD-API-065 · 提交打印

`POST /biz/print/jobs`

权限与守卫：设备与场景权限；金额内容由服务端组装不信客户端。

能力依赖：共享打印待建设。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceEventNo | string | 是 | 事件 |
| scene | string | 是 | 受控场景 |
| sourceRef | string | 是 | 业务凭据 |
| deviceNos | string[] | 否 | 授权设备 |
| templateVersion | int64 | 否 | 模板版 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNos | string[] | 是 | 分单任务 |
| status | string | 是 | 受理 |

### FOOD-API-066 · 打印查询

`GET /biz/print/jobs/{jobNo}`

权限与守卫：门店打印范围。

能力依赖：共享打印待建设。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNo | string | 是 | 任务 |
| status | string | 是 | 含UNKNOWN |
| attempts | object[] | 是 | 次数渠道任务回执 |
| originalJobNo | string | 否 | 原任务 |

### FOOD-API-067 · 人工补打

`POST /biz/print/jobs/{jobNo}:reprint`

权限与守卫：补打权限及审计不再次派工。

能力依赖：共享打印待建设。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reason | string | 是 | 原因 |
| deviceNo | string | 否 | 获准新设备 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNo | string | 是 | 新补打任务 |
| originalJobNo | string | 是 | 原任务 |
| status | string | 是 | 状态 |

### FOOD-API-068 · 开班

`POST /biz/x/food/stores/{storeNo}/cash-shifts`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| drawerNo | string | 是 | 钱箱 |
| currency | string | 是 | 币种 |
| openingMinor | int64 | 是 | 非负备用金 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string | 是 | 班次 |
| status | string | 是 | OPEN |
| version | int64 | 是 | 版本 |

### FOOD-API-069 · 非销售现金操作

`POST /biz/x/food/cash-shifts/{shiftNo}/entries`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| kind | string | 是 | FLOAT_IN或EXPENSE或DEPOSIT或TRANSFER |
| amountMinor | int64 | 是 | 正金额 |
| direction | string | 是 | IN或OUT |
| voucherRef | string | 是 | 凭证 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entryNo | string | 是 | 事实 |
| status | string | 是 | 结果 |

### FOOD-API-070 · 实盘与交班

`POST /biz/x/food/cash-shifts/{shiftNo}:count`

权限与守卫：盲盘前不泄露应有金额；差异须审批。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| countedMinor | int64 | 是 | 实盘 |
| version | int64 | 是 | 班次版本 |
| reason | string | 否 | 差异说明 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| countNo | string | 是 | 盘点 |
| differenceMinor | int64 | 否 | 获准后展示差异 |
| status | string | 是 | 状态 |

### FOOD-API-071 · 接班确认

`POST /biz/x/food/cash-shifts/{shiftNo}:handover`

权限与守卫：接班人登录确认不得客户端传acceptedBy冒充。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| countNo | string | 是 | 实盘 |
| version | int64 | 是 | 版本 |
| approvalRef | string | 否 | 差异审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string | 是 | 班次 |
| status | string | 是 | HANDED_OVER |

### FOOD-API-072 · 营业日预结

`POST /biz/x/food/stores/{storeNo}/closes`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| businessDate | date | 是 | 营业日 |
| currency | string | 是 | 币种 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| close | Close | 是 | 草稿或处理中 |

### FOOD-API-073 · 日结查询

`GET /biz/x/food/closes/{closeNo}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| close | Close | 是 | 版本快照 |

### FOOD-API-074 · 异常结转申请

`POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:carry`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| exceptionNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| targetBusinessDate | date | 是 | 下一归属日 |
| ownerNo | string | 是 | 责任人 |
| reason | string | 是 | 原因 |
| approvalRef | string | 是 | 审批 |
| version | int64 | 是 | 日结版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-075 · 签核日结

`POST /biz/x/food/closes/{closeNo}:sign`

权限与守卫：日结签核权限和责任分离；关键未知仅列例外不伪造成功。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| snapshotHash | string | 是 | 待签指纹 |
| watermark | string | 是 | 待签水位 |
| approvalRef | string | 否 | 强制例外审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| close | Close | 是 | 正式版本 |

### FOOD-API-076 · 关账后更正

`POST /biz/x/food/closes/{closeNo}/adjustments`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| coreReference | string | 是 | 已发生核心调整 |
| currentBusinessDate | date | 是 | 发生日 |
| reason | string | 是 | 原因 |
| approvalRef | string | 是 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| adjustmentNo | string | 是 | 更正 |
| newCloseNo | string | 否 | 重审版本 |
| status | string | 是 | 状态 |

### FOOD-API-077 · 经营指标报表

`GET /biz/x/food/reports/{reportCode}`

权限与守卫：reportCode受控目录；门店币种日期权限；最大同步日期范围按配置。

能力依赖：已有shop-report适配＋餐饮投影新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reportCode | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNos | string[] | 是 | 授权门店 |
| from | date | 是 | 起日 |
| to | date | 是 | 止日不含 |
| timeBasis | string | 是 | BUSINESS_DATE或NATURAL_DATE |
| currency | string | 是 | 币种 |
| metricVersion | int64 | 否 | 口径 |
| stationNo | string | 否 | 工位 |
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| report | Report | 是 | 报表 |

### FOOD-API-078 · 异步报表导出

`POST /biz/x/food/report-exports`

权限与守卫：导出权限水印脱敏，按查询权限重新校验。

能力依赖：餐饮新增。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reportCode | string | 是 | 受控报表 |
| storeNos | string[] | 是 | 授权门店 |
| from | date | 是 | 起日 |
| to | date | 是 | 止日不含 |
| currency | string | 是 | 币种 |
| timeBasis | string | 是 | 日期基准 |
| format | string | 是 | CSV或XLSX |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| exportNo | string | 是 | 任务 |
| status | string | 是 | 状态 |

### FOOD-API-079 · 导出结果

`GET /biz/x/food/report-exports/{exportNo}`

权限与守卫：本人或获准导出范围。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| exportNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| exportNo | string | 是 | 任务 |
| status | string | 是 | 状态 |
| downloadUrl | string | 否 | 短时签名链接 |
| expiresAt | datetime | 否 | 过期 |

### FOOD-API-080 · 重算投影

`POST /ops/x/food/report-rebuilds`

权限与守卫：工单授权只重建投影不重开日结。

能力依赖：餐饮新增。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 门店 |
| from | date | 是 | 起日 |
| to | date | 是 | 止日不含 |
| metricVersion | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| runNo | string | 是 | 任务 |
| status | string | 是 | 状态 |

### FOOD-API-081 · 平台能力配置

`PUT /ops/x/food/stores/{storeNo}/capabilities`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| capabilities | string[] | 是 | 允许能力 |
| version | int64 | 是 | 配置版 |
| workOrderRef | string | 是 | 授权工单 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-082 · 外部映射

`PUT /biz/x/food/integrations/{provider}/mappings`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| provider | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 门店 |
| objectType | string | 是 | 受控类型 |
| externalNo | string | 是 | 外部编号 |
| internalNo | string | 是 | 内部编号 |
| authority | string | 是 | 权威角色 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-083 · 接入异常查询

`GET /ops/x/food/integration-exceptions`

权限与守卫：工单范围脱敏诊断。

能力依赖：餐饮新增。

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 授权门店 |
| status | string | 否 | 状态 |
| page | int32 | 否 | 页码 |
| size | int32 | 否 | 每页条数 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | object[] | 是 | 编号来源类型状态责任人 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 当前页 |
| size | int32 | 是 | 每页条数 |

### FOOD-API-084 · 边缘增量认领

`POST /internal/food/sync-batches`

权限与守卫：服务身份签名租约顺序校验；事件类型白名单。

能力依赖：边缘导入正式合同待实施。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 门店 |
| edgeNo | string | 是 | 节点 |
| epoch | int64 | 是 | 代次 |
| sequenceFrom | int64 | 是 | 起点 |
| sequenceTo | int64 | 是 | 终点 |
| payloadHash | string | 是 | 签名摘要 |
| events | object[] | 是 | 事件ID类型来源号版本与冻结事实 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| syncNo | string | 是 | 批次 |
| status | string | 是 | 状态 |
| acceptedThrough | int64 | 是 | 连续接收水位 |
| conflictRefs | string[] | 是 | 冲突 |

### FOOD-API-085 · 自助权益入场

`POST /biz/x/food/checks/{checkNo}/entitlements`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| coreOrderRef | string | 是 | 有效消费单 |
| personClass | string | 是 | ADULT或CHILD或OTHER |
| persons | int32 | 是 | 人数 |
| ruleVersion | int64 | 是 | 门店规则 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entitlementNo | string | 是 | 权益 |
| startsAt | datetime | 是 | 开始 |
| expiresAt | datetime | 是 | 到期 |
| version | int64 | 是 | 版本 |

### FOOD-API-086 · 续时及最后点单校验

`POST /biz/x/food/entitlements/{entitlementNo}:extend`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entitlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| minutes | int32 | 是 | 正时长 |
| coreAdjustmentRef | string | 是 | 正式收费或减免 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-087 · 宴会计划

`POST /biz/x/food/stores/{storeNo}/banquet-plans`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string | 是 | 预约 |
| guaranteedPersons | int32 | 是 | 保证人数 |
| menuVersion | int64 | 是 | 餐牌 |
| coreQuoteRef | string | 是 | 合同价 |
| depositRef | string | 否 | 订金 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| planNo | string | 是 | 计划 |
| status | string | 是 | 状态 |
| version | int64 | 是 | 版本 |

### FOOD-API-088 · 宴会人数及加席

`POST /biz/x/food/banquet-plans/{planNo}:confirm-attendance`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| planNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| actualPersons | int32 | 是 | 实际人数 |
| coreQuoteRef | string | 是 | 加席调整报价 |
| reason | string | 否 | 变化原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-089 · 寄存建账

`POST /biz/x/food/stores/{storeNo}/custodies`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ownerRef | string | 是 | 客户 |
| skuNo | string | 是 | 酒水 |
| qty | decimal | 是 | 数量 |
| unit | string | 是 | 单位 |
| expiresAt | datetime | 是 | 期限 |
| voucherRef | string | 是 | 凭证 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| custodyNo | string | 是 | 编号 |
| version | int64 | 是 | 版本 |

### FOOD-API-090 · 寄存取出及报损

`POST /biz/x/food/custodies/{custodyNo}/entries`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| custodyNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| kind | string | 是 | WITHDRAW或LOSS |
| qty | decimal | 是 | 正数量 |
| version | int64 | 是 | 版本 |
| reason | string | 否 | 报损原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-091 · 房账转挂

`POST /biz/x/food/settlements/{settlementNo}/room-charges`

权限与守卫：房账权限不含支付成功确认。

能力依赖：核心应收与PMS契约。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| propertyNo | string | 是 | 酒店 |
| roomRef | string | 是 | 已校验房账 |
| guestAuthorization | string | 是 | 授权凭据 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 意图 |
| status | string | 是 | 含UNKNOWN |
| externalRef | string | 否 | PMS结果 |

### FOOD-API-092 · 桌区列表

`GET /biz/x/food/stores/{storeNo}/table-areas`

权限与守卫：原canAnyBiz(STORE,ORDER_VIEW)读取桌区/桌台配置元数据；配置写入仍需STORE。客组占用沿获准check详情，不在Table配置对象内嵌；主体门店及原始请求头继续一致核验。主体门店对象一致；列表与total同时按主体及当前门店过滤；page默认1、size默认20最大100；status省略含ACTIVE和DISABLED；按sortNo、areaNo升序；不暴露物理id。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| status | string | 否 | 状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | TableArea[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-093 · 创建桌区

`POST /biz/x/food/stores/{storeNo}/table-areas`

权限与守卫：主体门店对象一致；原STORE权限、可信actor及幂等；code/name去两端空白，同店code精确唯一且不可修改，名称可重名；初始ACTIVE、version沿平台0；不依赖支付库存。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| code | string | 是 | 店内唯一 |
| name | string | 是 | 名称 |
| sortNo | int32 | 是 | 排序 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| area | TableArea | 是 | 桌区 |

### FOOD-API-094 · 编辑桌区

`PUT /biz/x/food/table-areas/{areaNo}`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本及幂等；只改name和sortNo，保留code及归属；摘要包含areaNo与version；不暴露物理id。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 名称 |
| sortNo | int32 | 是 | 排序 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| area | TableArea | 是 | 桌区 |

### FOOD-API-095 · 桌区启停

`POST /biz/x/food/table-areas/{areaNo}:set-status`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本及幂等；有启用桌台或活动占用禁止停用；reason非空最长255并写原审计；同状态版本正确时无变更成功，不级联桌台或清台。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| status | string | 是 | ACTIVE或DISABLED |
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| area | TableArea | 是 | 桌区 |


### FOOD-API-096 · 桌台列表

`GET /biz/x/food/stores/{storeNo}/tables`

权限与守卫：原canAnyBiz(STORE,ORDER_VIEW)读取桌区/桌台配置元数据；配置写入仍需STORE。客组占用沿获准check详情，不在Table配置对象内嵌；主体门店及原始请求头继续一致核验。主体及当前门店过滤records与total；默认page1/size20最大100；areaNo/status/turnoverState及字面关键词过滤；按sortNo/tableNo稳定排序；排除归档，不以支付状态代替桌态。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| areaNo | string | 否 | 桌区 |
| status | string | 否 | 配置状态 |
| turnoverState | string | 否 | 资源状态 |
| keyword | string | 否 | 编码名称 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Table[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-097 · 桌台详情

`GET /biz/x/food/tables/{tableNo}`

权限与守卫：原canAnyBiz(STORE,ORDER_VIEW)读取桌区/桌台配置元数据；配置写入仍需STORE。客组占用沿获准check详情，不在Table配置对象内嵌；主体门店及原始请求头继续一致核验。主体及当前门店与对象一致；沿原门店目录核验；归档不显示，不暴露物理id。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| table | Table | 是 | 桌台详情 |

### FOOD-API-098 · 编辑桌台

`PUT /biz/x/food/tables/{tableNo}`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本和幂等；按编号锁原/目标桌区再锁桌台并复核；移区目标须启用，有活动占用禁止移区缩容；code及归属不改。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string | 是 | 同店桌区 |
| name | string | 是 | 名称 |
| capacity | int32 | 是 | 正容量 |
| sortNo | int32 | 是 | 排序 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| table | Table | 是 | 桌台 |

### FOOD-API-099 · 桌台启停

`POST /biz/x/food/tables/{tableNo}:set-status`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本和幂等；锁桌区再桌台；重新启用要求桌区启用，有活动占用禁止停用；同状态且版本正确无变更成功；reason非空最长255。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| status | string | 是 | ACTIVE或DISABLED |
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| table | Table | 是 | 桌台 |

### FOOD-API-100 · 归档桌台

`POST /biz/x/food/tables/{tableNo}:archive`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本和幂等；先停用且无活动占用；锁桌区再桌台并CAS逻辑归档；code唯一键及历史保留；原key重放Operation回执；reason非空最长255。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 归档记录 |


### FOOD-API-101 · 吊销桌码

`POST /biz/x/food/tables/{tableNo}:revoke-token`

权限与守卫：原STORE权限、主体门店及桌台CAS；同事务撤销有效码及该桌旧会话，不取消订单；安全操作收据可重放，不返回凭证。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 桌台版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 吊销结果 |

### FOOD-API-102 · 就餐台账详情

`GET /biz/x/food/checks/{checkNo}`

权限与守卫：原ORDER_VIEW权限，台账主体门店与当前门店一致；返回该check的活动占用和座位，不混入同桌其他客组。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 台账 |
| seats | SeatInput[] | 是 | 座位 |
| occupancies | Occupancy[] | 是 | 占用 |

### FOOD-API-103 · 修改人数与座位

`PUT /biz/x/food/checks/{checkNo}/seats`

权限与守卫：原VERIFY权限及check CAS；共用稳定容量锁，保留座位号/标签历史，创建省略seatNo由服务端发号，已有seatNo必须属于本check；未验证guestRef拒绝。当前有正式交易或未完成/未知草稿则拒绝，等待成交座位合同；不挤占他组座位。 外部resourceNo/resourceClaimNo或未完成资源意图拒绝本地变更。 无交易座位改名保留seatNo与变更前后快照；标签唯一冲突整笔回滚。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 台账版本 |
| partySize | int32 | 是 | 人数 |
| seats | SeatInput[] | 是 | 完整座位 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 台账 |

### FOOD-API-104 · 并入桌台

`POST /biz/x/food/checks/{checkNo}:combine-tables`

权限与守卫：原VERIFY权限、check CAS及新增桌CAS；同check并用多桌，共用有序资源锁与容量仲裁；当前限无交易台账，同事务完成并可按原operationNo查询；不合并资金/订单。 外部resourceNo/resourceClaimNo或未完成资源意图拒绝本地变更。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 台账版本 |
| tableVersions | TableVersionInput[] | 是 | 新增桌台 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 并桌结果 |

### FOOD-API-105 · 同桌新开独立台账

`POST /biz/x/food/tables/{tableNo}:share`

权限与守卫：原VERIFY权限、桌版本和SHARED容量原子仲裁；EXCLUSIVE占用即使桌容量扩大仍拒绝拼桌；新check独立，不继承原客组/购物车/支付权限；当前只PREPAY无交易接待。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 桌版本 |
| partySize | int32 | 是 | 正人数 |
| payMode | string | 是 | 获准先后付 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 新独立台账 |

### FOOD-API-106 · 桌台操作恢复查询

`GET /biz/x/food/table-operations/{operationNo}`

权限与守卫：原ORDER_VIEW及主体门店范围；读取真实fnb_table_operation及冻结结果版本，原operationNo结果稳定。当前本地无交易拓扑仅APPLIED，不伪造外部资源恢复成功。 既存未完成操作返回真实持久化状态及操作版本，不伪造已落定的check版本。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 受权操作进度 |

### FOOD-API-107 · 预约列表

`GET /biz/x/food/stores/{storeNo}/reservations`

权限与守卫：原ORDER_VIEW及主体门店范围；from/to为UTC毫秒半开窗口，按`startAt < to`且`endAt > from`重叠筛选；起止合法、状态白名单与分页同过滤；不返回联系人或buyer，depositRef仅引用不表示订金已结。创建/释放仍待共享资源合同。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| from | datetime | 是 | 起含 |
| to | datetime | 是 | 止不含 |
| status | string | 否 | 状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Reservation[] | 是 | 预约 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-108 · 就餐操作历史

`GET /biz/x/food/checks/{checkNo}/actions`

权限与守卫：原ORDER_VIEW及台账主体门店范围；真实fnb_action_log分页，保留可信操作人及原因，不输出令牌、联系人或幂等原文。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | ActionLog[] | 是 | 获准审计 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-138 · 顾客就餐进度

`GET /mp/x/food/checks/{checkNo}`

权限与守卫：目标仅显式获准的本人check/session membership，不能扫码即读取同桌其他客组；当前入席、正式批次/资金视图合同未齐，本入口未开放。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 获准视图 |
| foodLines | FoodLine[] | 是 | 仅获准行 |
| financialStatus | string | 是 | 权限过滤的资金状态 |

### FOOD-API-139 · 主动结束顾客会话

`POST /mp/x/food/sessions/{sessionNo}:end`

权限与守卫：X-Food-Session短凭证及原Idempotency-Key必需；只允许有效本人/匿名持有人会话，已绑定buyer须匹配平台CONSUMER，匿名持有人登录后可结束但不自动认领买家身份。过期或撤销拒绝新命令，已成功安全收据可重放；不取消原订单。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sessionNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 吊销结果 |


## 五、顾客与设备端复用

顾客catalog及session端点已独立列出。顾客允许的cart/quote/batch/check进度/settlement发起支付/return申请使用同结构的 /mp/x/food 路由适配，标识必须绑定session或本人；不开放代客buyerNo、现金确认、审批、签核、任务操作或任意corePaymentToken。正式控制器清单须按此白名单逐项登记权限，不能将整个biz控制器自动暴露给mp。点餐机使用设备身份加短时顾客会话，顾客离开清空令牌；设备绑定不授予店长权限。

支付动作对象由核心通道按真实客户端环境产生，业务服务不自拼收银参数。共享审批、商品编辑、会员资产、发票、税费、配送、排队及房账平台有各自合同；此处端点不重复实现它们的核心逻辑。

## 六、示例与错误恢复

点餐输入示例（选配能力启用时）：

```json
{"checkNo":"CHECK-1","buyerNo":"BUYER-1","fulfillment":"DINE_IN","channel":"DINE_IN","lines":[{"sourceLineRef":"LINE-1","goodsNo":"G-1","skuNo":"SKU-1","qty":"1","selections":[{"groupNo":"ICE","sourceKind":"SPEC_VALUE","sourceNo":"LESS_ICE","qty":"1","configVersion":"3"}]}]}
```

批次受理返回示例：

```json
{"code":0,"msg":"success","data":{"batch":{"batchNo":"BATCH-1","status":"PLACING","paymentRootNos":[],"storeOrderNos":[],"foodLineNos":[],"version":"1"}}}
```

202后按batchNo查询，不换幂等键重建；合法受理后也需按先付/后付条件判断是否制作。409返回当前version或冲突资源引用，但不泄露无权业务。日结签核前水位变化返回CLOSE_WATERMARK_CHANGED，重新预结审阅；依赖能力未开返回CAPABILITY_NOT_READY，不尝试餐饮直接写核心数据。

## 七、餐饮与基础域的输入输出合同

> 状态：待评审 · 2026-10-19 · 本文件是API生成输入片段；正式阅读入口为技术设计目录中的《餐饮系统API设计》。

以下名称为目标端口，不是已存在Java类。集成部署实现为宿主桥接，独立部署实现为服务调用；不将跨库实现细节传入餐饮领域。字段中的ref均须受权查询，不允许客户端自造已付或已退款凭据。

| 端口 | 输入（必填，?为可选） | 输出 | 当前边界 |
|---|---|---|---|
| Identity.authorize | actorToken:string, entityNo:string, storeNo:string, action:string, objectRef?:string | actorNo:string, buyerScope:string[], allowed:boolean, limits:Money[] | 复用身份；匿名就餐主体与代办需正式合同 |
| Catalog.snapshot | entityNo:string, storeNo:string, skuNos:string[], channel:string, at:datetime | items:{goodsNo,skuNo,title,unit,currency,priceMinor,onSale,availableQty,version}[] | 读取商品服务，不直接跨库JOIN |
| Catalog.validateSelections | skuNo:string, selections:Selection[], configVersion:int64 | valid:boolean, normalized:Selection[], recipeRef?:string | 强销售约束提交前校验及收费组合为缺口 |
| Order.quote | buyerNo:string, storeNo:string, fulfillment:string, channel:string, lines:OrderLineInput[] | Quote及sourceLineRef到报价行映射 | 当前preview可适配基础SKU；选配小数不直接传Item |
| Order.place | requestKey:string, quoteNo:string, buyerNo:string, storeNo:string, lines:OrderLineInput[], payMode:string | paymentRootNos:string[], storeOrderNos:string[], stableLines:{sourceRef,orderItemId,qty,amount,kind}[], phase:string | 当前create/createFor适配；源行和后付需补能力 |
| Order.findPlacement | entityNo:string, requestKey:string | phase:string, originalResult?:Batch | 超时恢复必须查同键，不任意重建 |
| Inventory.acceptDeferred | storeOrderNos:string[], acceptanceKey:string, expiresPolicy:string | commitmentRef:string, status:string, expiresAt?:datetime | 后付承诺与取消守卫缺口，不能餐饮自行二次锁库存 |
| Fulfillment.deliver | storeOrderNo:string, lineQty:{orderItemId,qty}[], operationKey:string, kind:string | deliveryRef:string, status:string | 自取配送适配现有；堂食及未付交付需补合同 |
| Cashier.freeze | scopes:ScopeItem[], currency:string, scopeHash:string | cashierRef:string, due:Money, version:int64, expiresAt:datetime | 通用应收范围预占和跨单收银缺口 |
| Payment.open | cashierRef或合法paymentRootNo:string, tool:string, amount:Money, actorNo:string, requestKey:string | paymentRef:string, status:string, clientAction?:object | 现有单根pay可适配；混合/跨根分配不可伪造 |
| Payment.query | paymentRef:string, entityNo:string | status:string, confirmedAmount:Money, allocations:{orderRef,amount}[], occurredAt?:datetime | 唯一可信支付结果，退款保持原分配追踪 |
| Payment.confirmCash | storeOrderNos:string[], requestKey:string, actorNo:string, cashEvidence:string | corePaymentRef:string, status:string | 当前confirmOfflinePay完整单店影响集限制保留 |
| Order.adjustReceivable | scope:ScopeItem[], kind:string, amount:Money, reason:string, approvalRef:string, requestKey:string | adjustmentRef:string, newQuote:Quote, status:string | 订单金额不可餐饮直接update；通用调整缺口 |
| AfterSale.request | storeOrderNo:string, lines?:{orderItemId,qty}[], reason:string, requestKey:string | caseNo:string, refundable:Money, status:string | 当前仅门店整单全额；lines部分退受能力门控 |
| Resource.holdOrOccupy | storeNo:string, resourceNos:string[], startAt?:datetime, endAt?:datetime, seats:int32, requestKey:string | claimNos:string[], status:string, expiresAt?:datetime | 时段预约与开放占用分开，统一仲裁者 |
| Resource.transfer | oldClaims:string[], newResources:string[], expectedVersion:int64, requestKey:string | newClaims:string[], status:string, recoveryRef?:string | 先占后放，失败保留可恢复占用 |
| Approval.requestOrVerify | actorNo:string, action:string, amount?:Money, objectRef:string, policyVersion:int64, approvalRef?:string | decision:string, approvalRef:string, approverNo?:string | 复用审批；未存在时建共享能力，不把审批塞进任意JSON |
| Reporting.queryOrBuild | storeNos:string[], range:{from,to}, timeBasis:string, currency:string, metricVersion:int64 | Report及runRef:string | 复用shop-report，新增营业日投影不改旧统计 |
| Reconcile.evidence | storeNo:string, dateRange:{from,to}, channel:string, currency:string, watermark?:string | totals:MetricCell[], differences:{ref,type,amount,status}[], asOf:datetime | 支付对账非餐饮重建资金账 |
| Assets.applyOrReverse | settlementRef:string, assetRef:string, amount:Money, requestKey:string, originalRef?:string | operationRef:string, allocations:{principal,gift}[], status:string | 会员资产唯一扣减，充值不计消费收入 |
| AR.transferOrApply | customerRef:string, scope:ScopeItem[], amount:Money, approvalRef:string, requestKey:string | arRef:string, balance:Money, status:string | 企业挂账/PMS房账待通用应收能力 |
| Print.submit | sourceEventNo:string, scene:string, sourceRef:string, contentVersion:int64 | jobNos:string[], status:string | 通用打印业务与渠道分层；内容服务端冻结 |

核心命令需要最终校验报价、权限、库存及资金版本；餐饮预校验不能替代核心提交校验。发票、排队、PMS、税费与第三方专项协议以其权威系统合同为准，实施前补充协议版本及失败恢复，不在此臆造现成接口。
