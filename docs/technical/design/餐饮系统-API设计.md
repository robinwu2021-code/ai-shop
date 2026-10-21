# 餐饮系统API设计

> 状态：待评审 · 2026-10-20 · V1含73张fnb_业务表；增强目标82张（9张PLANNED），当前迁移版本2；目标API不代表已交付，prn/rpt保持共享候选。
> 由 `node scripts/gen-food-system-design.mjs` 生成，勿手改；真源：[领域数据库API设计源](./schema/food-system-design.json)。生成依赖沿用 @viz-js/viz，可用 FOOD_ER_VIZ_MODULE 指定模块路径。
> 业务入口：[后端业务模块功能清单](../../requirements/餐饮系统-后端业务模块功能清单.md) · [数据库与API评审对齐](../TDD-餐饮系统-数据库与API评审对齐.md)。按桌台→菜品→订单查阅，A–L仍保留为持久化分域编号。
> 项目约束：[底座兼容对齐](./餐饮系统-项目底座兼容对齐.md)，账号、门店、营业时间、公共实体及协议冲突时优先按该对齐说明修订。
> 产品依据：[完整需求文档](../../requirements/餐饮系统-完整需求文档.md) · [109项需求矩阵](../../requirements/餐饮系统-需求矩阵.md)。

## 零、按业务模块阅读

先查主责模块，再查表字段或接口编号。13个业务模块与A–L持久化域不是一一对应；模块可协作调用相同服务，不能据此复制实体。109项追踪结果：目标已定义32项，依赖共享/核心合同59项，尚缺专项设计18项。以上均为设计状态，非实现验收。

| 模块 | 名称 | 需求数 | 关联表数 | 关联API数 |
|---|---|---|---|---|
| MOD-01 | 桌台与就餐管理 | 8 | 11 | 31 |
| MOD-02 | 菜品与餐牌管理 | 12 | 27 | 40 |
| MOD-03 | 点餐与订单管理 | 9 | 14 | 29 |
| MOD-04 | 厨房与履约管理 | 10 | 10 | 17 |
| MOD-05 | 收银与结账管理 | 11 | 6 | 13 |
| MOD-06 | 退餐与售后管理 | 8 | 7 | 12 |
| MOD-07 | 打印、通知与设备管理 | 8 | 5 | 3 |
| MOD-08 | 班次与现金管理 | 4 | 5 | 5 |
| MOD-09 | 营业日、日结与对账管理 | 6 | 6 | 6 |
| MOD-10 | 经营报表管理 | 14 | 3 | 4 |
| MOD-11 | 专项业态管理 | 6 | 6 | 8 |
| MOD-12 | 第三方接入与离线营业 | 7 | 8 | 7 |
| MOD-13 | 营业配置与权限治理 | 6 | 7 | 7 |

逐需求表/API引用与未决缺口见API册第八节；首三模块扩展及评审结论见[评审对齐记录](../TDD-餐饮系统-数据库与API评审对齐.md)。

## 一、协议与能力边界

154个目标端点，统一JSON语义；路径为建议合同，未全部注册在当前Controller。本地适配器和远程适配器遵守相同DTO、权限和幂等。

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
| C | FOOD-API-034 | POST `/biz/x/food/checks/{checkNo}:merge` | 合并就餐服务台账 |
| C | FOOD-API-035 | POST `/biz/x/food/checks/{checkNo}:split` | 拆分就餐服务台账 |
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
| B | FOOD-API-109 | GET `/biz/x/food/stores/{storeNo}/menus` | 餐牌列表 |
| B | FOOD-API-110 | PUT `/biz/x/food/menus/{menuNo}` | 编辑餐牌头 |
| B | FOOD-API-111 | POST `/biz/x/food/menus/{menuNo}:disable` | 餐牌停用 |
| B | FOOD-API-112 | GET `/biz/x/food/stores/{storeNo}/dishes` | 菜品列表 |
| B | FOOD-API-113 | GET `/biz/x/food/stores/{storeNo}/dishes/{goodsNo}` | 菜品详情 |
| B | FOOD-API-114 | GET `/biz/x/food/stores/{storeNo}/catalog` | 经营端可售餐牌 |
| B | FOOD-API-115 | GET `/biz/x/food/config-revisions` | 配置发布历史 |
| B | FOOD-API-116 | POST `/biz/x/food/config-revisions/{revisionNo}:republish` | 回滚配置为新修订 |
| B | FOOD-API-117 | POST `/biz/x/food/modifiers/{modifierNo}:publish` | 配料组合发布 |
| B | FOOD-API-118 | GET `/biz/x/food/stores/{storeNo}/option-groups` | 选配组列表 |
| B | FOOD-API-119 | GET `/biz/x/food/stores/{storeNo}/bundles` | 套餐列表 |
| B | FOOD-API-120 | GET `/biz/x/food/stores/{storeNo}/modifiers` | 配料列表 |
| B | FOOD-API-121 | GET `/biz/x/food/stores/{storeNo}/stations` | 工位列表 |
| B | FOOD-API-122 | GET `/biz/x/food/stores/{storeNo}/sales-restrictions` | 可售限制查询 |
| D | FOOD-API-123 | GET `/biz/x/food/stores/{storeNo}/batches` | 按台账或条件查询批次 |
| D | FOOD-API-124 | POST `/biz/x/food/batches/{batchNo}:cancel` | 取消正式批次 |
| D | FOOD-API-125 | GET `/biz/x/food/batches/{batchNo}/operations` | 批次操作恢复查询 |
| D | FOOD-API-126 | POST `/biz/x/food/carts/{cartNo}:archive` | 关闭草稿购物车 |
| D | FOOD-API-127 | GET `/mp/x/food/carts/{cartNo}` | 顾客购物车读取 |
| D | FOOD-API-128 | POST `/mp/x/food/stores/{storeNo}/carts` | 顾客创建购物车 |
| D | FOOD-API-129 | PUT `/mp/x/food/carts/{cartNo}/lines/{sourceLineRef}` | 顾客替换草稿行 |
| D | FOOD-API-130 | POST `/mp/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive` | 顾客归档购物车草稿行 |
| D | FOOD-API-131 | POST `/mp/x/food/stores/{storeNo}/quotes` | 顾客点单报价 |
| D | FOOD-API-132 | POST `/mp/x/food/stores/{storeNo}/batches` | 顾客提交点餐批次 |
| D | FOOD-API-133 | GET `/mp/x/food/batches/{batchNo}` | 顾客批次结果查询 |
| F | FOOD-API-134 | GET `/mp/x/food/settlements/{settlementNo}` | 顾客读取预结账 |
| F | FOOD-API-135 | POST `/mp/x/food/settlements/{settlementNo}:pay` | 顾客结账pay |
| G | FOOD-API-136 | POST `/mp/x/food/stores/{storeNo}/returns` | 顾客退餐申请 |
| G | FOOD-API-137 | GET `/mp/x/food/returns/{returnNo}` | 顾客退餐查询 |
| C | FOOD-API-138 | GET `/mp/x/food/checks/{checkNo}` | 顾客就餐进度 |
| C | FOOD-API-139 | POST `/mp/x/food/sessions/{sessionNo}:end` | 主动结束顾客会话 |
| F | FOOD-API-140 | POST `/mp/x/food/checks/{checkNo}/settlements` | 顾客申请获准范围结账 |
| B | FOOD-API-141 | POST `/biz/x/food/stores/{storeNo}/modifiers` | 创建配料或配料组合 |
| B | FOOD-API-142 | POST `/biz/x/food/stores/{storeNo}/menu-channel-bindings` | 创建餐牌渠道绑定 |
| B | FOOD-API-143 | GET `/biz/x/food/stores/{storeNo}/menu-channel-bindings` | 渠道绑定列表 |
| B | FOOD-API-144 | GET `/biz/x/food/menu-channel-bindings/{bindingNo}` | 渠道绑定详情 |
| B | FOOD-API-145 | PUT `/biz/x/food/menu-channel-bindings/{bindingNo}` | 修改渠道绑定 |
| K | FOOD-API-146 | POST `/biz/x/food/menu-channel-bindings/{bindingNo}/syncs` | 发起餐牌渠道同步 |
| K | FOOD-API-147 | GET `/biz/x/food/catalog-syncs/{syncNo}` | 查询餐牌同步结果 |
| B | FOOD-API-148 | POST `/biz/x/food/stores/{storeNo}/recipes` | 创建配方工作头 |
| B | FOOD-API-149 | GET `/biz/x/food/stores/{storeNo}/recipes` | 配方列表 |
| B | FOOD-API-150 | GET `/biz/x/food/recipes/{recipeNo}` | 配方详情 |
| B | FOOD-API-151 | PUT `/biz/x/food/recipes/{recipeNo}/definition` | 维护配方物料与调整 |
| B | FOOD-API-152 | POST `/biz/x/food/recipes/{recipeNo}:publish` | 发布配方 |
| E | FOOD-API-153 | GET `/biz/x/food/stores/{storeNo}/production-consumptions` | 制作耗用列表 |
| E | FOOD-API-154 | GET `/biz/x/food/production-consumptions/{consumptionNo}` | 制作耗用详情 |

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
| checkNo | string | 否 | 原台账 |
| storeNo | string | 是 | 门店 |
| businessDate | date | 是 | 本批归期 |
| payMode | string | 是 | 先后付 |
| orderSource | string | 是 | 真实入口 |
| fulfillment | string | 是 | 履约 |
| status | string | 是 | 含UNKNOWN及CANCEL_PENDING |
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
| skuScope | string | 是 | ALL或EXPLICIT |
| skuNos | string[] | 是 | EXPLICIT必须非空且属于商品 |
| optionMode | string | 是 | INHERIT或REPLACE |
| optionGroupNos | string[] | 是 | REPLACE显式组，空表示禁选 |
| skuRecipes | MenuSkuRecipeInput[] | 是 | 特定SKU配方，必须EXPLICIT白名单 |

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
| channelBindingNo | string | 否 | 受权渠道绑定，本地省略 |
| pricePolicyRef | string | 否 | 权威价格策略引用，不接受金额 |

### BundleGroupInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| groupNo | string | 是 | 组号 |
| name | string | 是 | 名称 |
| minQty | decimal | 是 | 最少 |
| maxQty | decimal | 是 | 最多 |
| baseSkuNo | string | 否 | 补差基准 |
| sortNo | int32 | 是 | 排序 |

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
| sortNo | int32 | 是 | 排序 |

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
| minQty | decimal | 是 | 本选项下限 |

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

### TableArea

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string | 是 | 桌区号 |
| storeNo | string | 是 | 门店 |
| code | string | 是 | 编码 |
| name | string | 是 | 名称 |
| sortNo | int32 | 是 | 排序 |
| status | string | 是 | 启停 |
| version | int64 | 是 | 版本 |

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

### Occupancy

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| occupancyNo | string | 是 | 占用号 |
| tableNo | string | 是 | 桌台 |
| checkNo | string | 是 | 获准台账 |
| seats | int32 | 是 | 占座 |
| startedAt | datetime | 是 | 开始 |

### SeatInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| seatNo | string | 否 | 创建省略由服务端发号，更新只能引用本台账座位 |
| label | string | 是 | 台账内唯一座位标签 |
| guestRef | string | 否 | 获准匿名或顾客引用 |

### Dish

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| goodsNo | string | 是 | 商品 |
| dishKind | string | 是 | SINGLE或BUNDLE或ADDON |
| prepSeconds | int32 | 是 | 制作时间 |
| presentation | DishPresentation | 是 | 覆盖展示 |
| measurement | MeasurementPolicy | 是 | 计量 |
| configVersion | int64 | 是 | 规则版本 |
| version | int64 | 是 | 并发版本 |

### DishPresentation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| displayName | string | 否 | 餐饮别名 |
| localizedNames | LocalizedName[] | 是 | 语言名称 |
| imageRefs | string[] | 是 | 已有媒资引用 |
| tags | string[] | 是 | 标签 |
| allergenNotes | string[] | 是 | 食材过敏提示非医疗保证 |

### LocalizedName

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| locale | string | 是 | 受控语言 |
| name | string | 是 | 名称 |

### MeasurementPolicy

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| unit | string | 是 | 受控单位 |
| mode | string | 是 | COUNT或WEIGHT |
| scale | int32 | 是 | 精度 |
| allowedDeviation | decimal | 否 | 需顾客确认的允差 |

### ConfigRevision

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| revisionNo | string | 是 | 冻结发布号 |
| configKind | string | 是 | 类型 |
| configNo | string | 是 | 对象 |
| revision | int64 | 是 | 发布修订 |
| effectiveAt | datetime | 是 | 生效 |
| contentHash | string | 是 | 摘要 |
| status | string | 是 | 状态 |

### Menu

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string | 是 | 餐牌 |
| name | string | 是 | 名称 |
| status | string | 是 | 草稿头状态 |
| configVersion | int64 | 是 | 草稿规则版 |
| publishedRevision | ConfigRevision | 否 | 已生效发布 |
| version | int64 | 是 | 并发版 |

### BatchDetail

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batch | Batch | 是 | 批次 |
| lines | FoodLine[] | 是 | 冻结源行 |
| financialStatus | string | 是 | 核心资金投影 |
| fulfillmentStatus | string | 是 | 核心履约投影 |
| pendingOperationNos | string[] | 是 | 未知操作 |

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

### CoreLineAllocation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| allocationNo | string | 是 | 分配号 |
| qty | decimal | 是 | 数量 |
| amount | Money | 是 | 核心分配金额 |
| coreSnapshotHash | string | 是 | 快照摘要 |

### Cart

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| checkNo | string | 否 | 台账 |
| version | int64 | 是 | 版本 |
| lines | OrderLineInput[] | 是 | 草稿 |
| orderSource | string | 是 | 来源 |
| fulfillment | string | 是 | 履约 |
| scheduledAt | datetime | 否 | 出餐时点 |

### Operation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作 |
| status | string | 是 | 含处理中未知失败 |
| version | int64 | 是 | 当前版本 |
| resultRef | string | 否 | 结果号 |

### TableVersionInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string | 是 | 桌台 |
| version | int64 | 是 | 资源版本 |

### SelectionRules

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| mutexSets | string[][] | 是 | 不可同选的optionItemNo集合 |
| requires | SelectionDependency[] | 是 | 选择依赖 |

### SelectionDependency

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| optionItemNo | string | 是 | 被选项 |
| requiresItemNos | string[] | 是 | 必须同时选项 |

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

### ActionLog

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| actionNo | string | 是 | 记录 |
| action | string | 是 | 动作 |
| actorRef | string | 是 | 审计操作人 |
| occurredAt | datetime | 是 | 时间 |
| reason | string | 否 | 原因 |

### CatalogItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuItemNo | string | 是 | 实际餐牌条目 |
| menuRevisionNo | string | 是 | 餐牌发布修订 |
| goodsNo | string | 是 | 商品 |
| skuNo | string | 是 | 规格 |
| name | string | 是 | 名称 |
| imageRefs | string[] | 是 | 媒资 |
| price | Money | 否 | 仅权威报价可用时返回 |
| pricingStatus | string | 是 | AVAILABLE或UNAVAILABLE |
| pricePolicyRef | string | 否 | 权威依据 |
| available | boolean | 是 | 服务端综合可售 |
| reasonCodes | string[] | 是 | 不可售原因 |
| configRevisionNos | string[] | 是 | 冻结依赖 |

### SalesRestriction

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| restrictionNo | string | 是 | 约束 |
| skuNo | string | 是 | SKU |
| mode | string | 是 | 类型 |
| status | string | 是 | 状态 |
| quotaQty | decimal | 否 | 额度 |
| reservedQty | decimal | 是 | 预占 |
| consumedQty | decimal | 是 | 已用 |
| version | int64 | 是 | 版本 |

### MenuSkuRecipeInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 本菜品SKU |
| recipeNo | string | 是 | 本SKU获准配方变体 |

### MenuChannelBinding

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string | 是 | 绑定 |
| menuNo | string | 是 | 餐牌 |
| storeNo | string | 是 | 门店 |
| provider | string | 是 | 提供方 |
| channelAccountRef | string | 是 | 已有账号引用 |
| externalStoreRef | string | 是 | 外部门店 |
| status | string | 是 | ACTIVE或DISABLED |
| version | int64 | 是 | CAS |

### CatalogSync

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| syncNo | string | 是 | 任务 |
| bindingNo | string | 是 | 绑定 |
| revisionNo | string | 是 | 冻结修订 |
| status | string | 是 | 真实同步状态 |
| items | CatalogSyncItem[] | 是 | 分项结果 |
| version | int64 | 是 | CAS |

### CatalogSyncItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| lineNo | int32 | 是 | 行 |
| menuItemNo | string | 是 | 菜项 |
| status | string | 是 | 状态 |
| externalObjectRef | string | 否 | 外部编号 |
| errorCode | string | 否 | 脱敏错误 |
| confirmedRevisionNo | string | 否 | 确认修订 |

### Recipe

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipeNo | string | 是 | 配方 |
| storeNo | string | 是 | 门店 |
| skuNo | string | 是 | SKU |
| recipeCode | string | 是 | DEFAULT或变体 |
| yieldQty | decimal | 是 | 正产出 |
| yieldUnit | string | 是 | 受控单位 |
| status | string | 是 | 草稿或启用 |
| configVersion | int64 | 是 | 内容版本 |
| version | int64 | 是 | CAS |
| lines | RecipeLineInput[] | 是 | 物料 |
| adjustments | RecipeAdjustmentInput[] | 是 | 选配调整 |
| publishedRevisionNo | string | 否 | 已发布修订 |

### RecipeLineInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| lineNo | string | 否 | 创建省略服务端发号 |
| itemRef | string | 是 | 获准库存物料 |
| qty | decimal | 是 | 正净用量 |
| unit | string | 是 | 物料单位 |
| conversionRef | string | 是 | 权威换算版本 |
| lossRate | decimal | 是 | 投入损失比例非负且小于1 |

### RecipeAdjustmentInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| adjustmentNo | string | 否 | 创建省略服务端发号 |
| optionItemNo | string | 是 | 选项 |
| targetLineNo | string | 是 | 本配方行 |
| action | string | 是 | SET或ADD或REMOVE |
| qty | decimal | 否 | SET/ADD非负，REMOVE省略 |
| unit | string | 是 | 与目标一致 |

### ProductionConsumption

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| consumptionNo | string | 是 | 耗用号 |
| storeNo | string | 是 | 门店 |
| sourceEventNo | string | 是 | 原制作事件 |
| purpose | string | 是 | 耗用目的 |
| status | string | 是 | 真实状态 |
| businessDate | date | 是 | 营业日 |
| inventoryOperationRef | string | 否 | 库存原操作 |
| lines | ConsumptionLine[] | 是 | 明细 |

### ConsumptionLine

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| lineNo | int32 | 是 | 序号 |
| componentPath | string | 是 | 实例路径 |
| itemRef | string | 是 | 物料 |
| theoryQty | decimal | 是 | 理论量 |
| unit | string | 是 | 权威单位 |
| recipeRevisionNo | string | 是 | 历史配方 |
| status | string | 是 | 回执状态 |
| actualQty | decimal | 否 | 仅真实库存回执 |
| cost | Money | 否 | 仅真实历史总成本 |
| inventoryReceiptRef | string | 否 | 回执行号 |

### ChannelQuoteReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entityNo | string | 是 | 可信服务身份解析的经营主体 |
| storeNo | string | 是 | 已授权门店 |
| channelBindingNo | string | 否 | 仅适配器认证所得渠道绑定 |
| menuRevisionNo | string | 是 | 不可变餐牌修订 |
| menuItemNo | string | 是 | 原餐牌条目不可按SKU合并 |
| sourceLineRef | string | 是 | 购买实例引用 |
| skuNo | string | 是 | 当前菜项内实际购买规格 |
| quantity | decimal | 是 | 购买份数 |
| selections | QuoteSelection[] | 是 | 按购买实例绑定的口味加料套餐选择 |
| pricePolicyRef | string | 否 | 报价权威校验的策略引用 |
| currency | string | 是 | ISO币种 |
| selectionRevisionNos | string[] | 是 | 已发布选配套餐配方修订 |
| demands | FrozenMaterialDemand[] | 是 | 唯一库存需求树 |

### ChannelQuoteResult

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| quoteRef | string | 是 | 权威报价引用 |
| policyRevisionNo | string | 是 | 权威策略版本 |
| expiresAt | datetime | 是 | 到期时需重报 |
| currency | string | 是 | 币种 |
| totalMinor | int64 | 是 | 最小货币单位金额 |
| allocations | QuoteLineAllocation[] | 是 | 核心行分摊合计等于总额 |
| stockDemandTree | FrozenMaterialDemand[] | 是 | 与受理一致且只执行一次的需求树 |

### QuoteLineAllocation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 购买实例引用 |
| componentPath | string | 是 | 完整套餐选配实例路径 |
| coreLineRef | string | 是 | 核心行分配引用 |
| qty | decimal | 是 | 数量 |
| amountMinor | int64 | 是 | 优惠后权威分摊金额 |

### FrozenMaterialDemand

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 购买实例引用 |
| componentPath | string | 是 | 不能仅按SKU折叠的路径 |
| executionMode | string | 是 | SELLABLE_SKU/RECIPE_LEAVES/NONE |
| itemRef | string | 是 | 库存权威物料或成品引用 |
| recipeRevisionNo | string | 否 | 配方不可变修订 |
| qty | decimal | 是 | 按权威数量精度的毛需求 |
| unit | string | 是 | 库存权威单位 |
| conversionRevision | string | 是 | 不可变换算版本 |

### InventoryMaterialFacts

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| itemRef | string | 是 | 权威物料引用 |
| storeNo | string | 是 | 所属门店 |
| unit | string | 是 | 库存基本单位 |
| conversionRevision | string | 是 | 单位换算版本 |
| quantityScale | int32 | 是 | 数量有效小数位 |
| valuationScale | int32 | 是 | 单位成本内部精度 |
| roundingMode | string | 是 | 权威数量及成本舍入方式 |
| status | string | 是 | ACTIVE/DISABLED |

### MaterialConsumptionReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entityNo | string | 是 | 可信主体 |
| storeNo | string | 是 | 获准门店 |
| productionEventNo | string | 是 | 稳定制作事实编号 |
| requestKey | string | 是 | 重试不变的业务键 |
| requestHash | string | 是 | 同键异载荷冲突检测 |
| originalReservationRef | string | 是 | 受理时原预占 |
| purpose | string | 是 | PRODUCTION/REMAKE/MATERIAL_WASTE/REVERSAL |
| originalOperationRef | string | 否 | 冲回必须引用原已确认操作 |
| demands | FrozenMaterialDemand[] | 是 | 来自受理冻结树的本次制作数量 |

### MaterialConsumptionResult

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationRef | string | 是 | 库存权威操作编号 |
| requestKey | string | 是 | 原请求键 |
| status | string | 是 | CONFIRMED/PARTIAL/UNKNOWN/FAILED |
| receipts | MaterialConsumptionReceipt[] | 是 | 逐行确认不以请求数量冒充实耗 |
| occurredAt | datetime | 是 | 权威事实发生时点 |

### MaterialConsumptionReceipt

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 原购买实例 |
| componentPath | string | 是 | 原冻结路径 |
| receiptRef | string | 否 | 已确认库存流水引用 |
| status | string | 是 | CONFIRMED/UNKNOWN/FAILED |
| actualQty | decimal | 否 | 仅确认后返回实耗不得用理论量填充 |
| unit | string | 是 | 库存单位 |
| costMinor | int64 | 否 | 权威历史分摊成本未知为null |
| currency | string | 否 | 成本币种 |
| valuationRevision | string | 否 | 历史计价依据 |

### QuoteSelection

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| componentPath | string | 是 | 购买实例内完整组件路径 |
| groupRevisionNo | string | 是 | 已发布组修订 |
| optionItemNo | string | 是 | 该修订内合法选项 |
| quantity | decimal | 是 | 选择份数 |
| selectionInstanceRef | string | 是 | 同SKU不同选择仍独立的实例号 |

### ProductSummary

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| goodsNo | string | 是 | 基础商品编号 |
| entityNo | string | 是 | 已验证主体 |
| storeNo | string | 是 | 已验证门店 |
| title | string | 是 | 商品名称 |
| cover | string | 否 | 原商品图片 |
| baseOnSale | boolean | 是 | 基础上下架事实非餐饮可售 |
| auditStatus | string | 是 | 审核事实 |
| version | int64 | 是 | 原商品版本 |

### ProductSku

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 基础规格编号 |
| spec | string | 否 | 规格展示 |
| version | int64 | 是 | 基础规格版本 |

### ProductCatalog

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| goodsNo | string | 是 | 基础商品编号 |
| entityNo | string | 是 | 已验证主体 |
| storeNo | string | 是 | 已验证门店 |
| title | string | 是 | 商品名称 |
| cover | string | 否 | 原商品图片 |
| baseOnSale | boolean | 是 | 基础上下架事实非餐饮可售 |
| auditStatus | string | 是 | 审核事实 |
| version | int64 | 是 | 原商品版本 |
| skus | ProductSku[] | 是 | 原商品规格事实，不含金额库存 |

### DishCatalogItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| dish | Dish | 是 | 餐饮属性 |
| product | ProductSummary | 是 | 当前门店商品权威图文与管理状态 |

### DishOptionItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| optionItemNo | string | 是 | 当前组条目 |
| sourceKind | string | 是 | SPEC_VALUE或MODIFIER |
| specValueNo | string | 否 | 原口味值 |
| modifierNo | string | 否 | 餐饮配料 |
| minQty | decimal | 是 | 下限 |
| maxQty | decimal | 是 | 上限 |
| defaultQty | decimal | 是 | 默认量 |
| sortNo | int32 | 是 | 顺序 |

### DishOptionData

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 组名称 |
| minQty | decimal | 是 | 组下限 |
| maxQty | decimal | 是 | 组上限 |
| publishedRevisionNo | string | 否 | 已发布引用独立于当前草稿 |
| selectionRules | object | 是 | 存储的管理选择规则非执行授权 |
| items | DishOptionItem[] | 是 | 真实当前条目 |

### DishOptionGroup

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 选择组编号 |
| kind | string | 是 | 固定OPTION_GROUP |
| revision | int64 | 是 | 当前管理草稿并发版本 |
| status | string | 是 | 管理状态 |
| data | DishOptionData | 是 | 实际工作头与条目 |

## 四、逐接口输入输出

### FOOD-API-001 · 查询营业策略

`GET /biz/x/food/stores/{storeNo}/policy`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

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

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

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

权限与守卫：CAS草稿头及完整聚合校验；冻结依赖发布版本和definition；发布内容不可改，客户端不可自填已发布凭据。

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
| publication | ConfigRevision | 是 | 冻结发布修订 |

### FOOD-API-007 · 创建option-groups

`POST /biz/x/food/stores/{storeNo}/option-groups`

权限与守卫：原商品权限及当前主体门店校验；主体共享配置；服务端编号与DRAFT/0；数量DECIMAL(18,6)非负且min≤max；归一化幂等、审计与Outbox同事务；拒绝未知字段。

能力依赖：已实现草稿管理，不代表选项维护或发布。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 组名 |
| minQty | decimal | 是 | 非负下限 |
| maxQty | decimal | 是 | 不小于下限 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配置号 |
| version | int64 | 是 | 草稿版本 |
| status | string | 是 | DRAFT |

### FOOD-API-008 · 读取option-groups

`GET /biz/x/food/stores/{storeNo}/option-groups/{configNo}`

权限与守卫：原商品权限及当前主体门店校验；仅返回当前主体共享工作头与真实有序条目；跨主体404；拒绝查询参数；不作为已发布授权。

能力依赖：已实现管理查询，不代表可售。

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

权限与守卫：CAS草稿头及完整聚合校验；冻结依赖发布版本和definition；发布内容不可改，客户端不可自填已发布凭据。

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
| publication | ConfigRevision | 是 | 冻结发布修订 |

### FOOD-API-010 · 创建bundles

`POST /biz/x/food/stores/{storeNo}/bundles`

权限与守卫：主SKU属于当前主体且未重复建套餐定义；configVersion由服务端分配，不接受客户端指定。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 主SKU |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 主SKU业务号 |
| version | int64 | 是 | 草稿CAS |
| status | string | 是 | DRAFT |

### FOOD-API-011 · 读取bundles

`GET /biz/x/food/stores/{storeNo}/bundles/{configNo}`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

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

权限与守卫：CAS草稿头及完整聚合校验；冻结依赖发布版本和definition；发布内容不可改，客户端不可自填已发布凭据。

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
| publication | ConfigRevision | 是 | 冻结发布修订 |

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
| stationType | string | 是 | HOT或COLD或BAR或PACK |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配置号 |
| version | int64 | 是 | 草稿版本 |
| status | string | 是 | DRAFT |

### FOOD-API-014 · 读取stations

`GET /biz/x/food/stores/{storeNo}/stations/{configNo}`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

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

权限与守卫：CAS草稿头及完整聚合校验；冻结依赖发布版本和definition；发布内容不可改，客户端不可自填已发布凭据。

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
| publication | ConfigRevision | 是 | 冻结发布修订 |

### FOOD-API-016 · 菜品餐饮属性

`PUT /biz/x/food/stores/{storeNo}/dishes/{goodsNo}`

权限与守卫：商品同主体；缺version仅当餐饮扩展不存在时创建，存在则冲突；传version只允许CAS修改已存在扩展；基础图文价格库存仍在商品域；当前仅COUNT/EA整数份数展示；自定义imageRefs/WEIGHT待权威合同，明确拒绝。

能力依赖：已实现餐饮属性草稿维护；COUNT/EA展示、原商品图片沿用；不含称重、自定义媒资及可售发布。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| goodsNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| dishKind | string | 是 | SINGLE或BUNDLE或ADDON |
| prepSeconds | int32 | 是 | 非负 |
| presentation | DishPresentation | 是 | 餐饮展示覆盖 |
| measurement | MeasurementPolicy | 是 | 计量规则 |
| version | int64 | 否 | 创建时不传，修改时必须CAS |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| dish | Dish | 是 | 菜品 |

### FOOD-API-017 · 菜单组成与时段配置

`PUT /biz/x/food/menus/{menuNo}/definition`

权限与守卫：仅更新草稿；分类/菜项同属本餐牌；SKU须属于商品；时段跨日及例外完整校验；渠道绑定/价格引用同主体门店验真；INHERIT或REPLACE显式，不能客户端提交scopeKey；特定配方SKU必须白名单；同组同优先级重叠拒绝。。

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
| validFrom | date | 否 | 适用首日 |
| validTo | date | 否 | 适用末日 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menu | Menu | 是 | 草稿头 |

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

权限与守卫：SPEC_VALUE与MODIFIER二选一；绑定菜品可有多组选配；min/default/max及互斥不得矛盾；依赖图拒循环。

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
| selectionRules | SelectionRules | 是 | 互斥依赖结构 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 组 |
| version | int64 | 是 | 新版本 |

### FOOD-API-020 · 配料组合配置

`PUT /biz/x/food/modifiers/{modifierNo}`

权限与守卫：已存在配料同主体且CAS更新；新建使用141由服务端生成业务号；ADDON/COMBO结构及递归防循环；叶子单位一致或有正式换算合同。

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
| components | ComponentInput[] | 是 | 成员及正数量 |
| version | int64 | 是 | 现有对象CAS |

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

权限与守卫：店SKU有效约束串行化；CLEAR仅清指定限制；配额批次幂等预占释放一次且不修改实存。

能力依赖：共享可售约束或新增通用能力。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | SKU |
| mode | string | 是 | SOLD_OUT或QUOTA或PREORDER_LIMIT或CLEAR |
| restrictionNo | string | 否 | CLEAR时必填 |
| quota | decimal | 否 | 额度模式必填正值 |
| businessDate | date | 否 | 每日额度必填 |
| startsAt | datetime | 否 | 缺省现在 |
| expiresAt | datetime | 否 | 到期 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| restrictionNo | string | 是 | 销售约束引用 |
| status | string | 是 | 结果 |

### FOOD-API-023 · 顾客菜单视图

`GET /mp/x/food/catalog`

权限与守卫：公开目录或合法会话范围；来源须与客户端身份匹配；用户不能指定任意未来时间绕过接单；菜单条目不可仅按SKU去重；未取得权威报价不显示0元或可成交；用户不能伪造渠道身份。。

能力依赖：餐饮新增。

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 门店 |
| sessionNo | string | 否 | 会话 |
| orderSource | string | 是 | 销售来源 |
| fulfillment | string | 是 | 履约 |
| channelBindingNo | string | 否 | 服务端按获准来源解析；外部身份须匹配 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menus | Menu[] | 是 | 生效菜单 |
| items | CatalogItem[] | 是 | 商品规格选配可售提示 |
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

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

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
| cart | Cart | 是 | 获准购物车 |

### FOOD-API-038 · 创建购物车

`POST /biz/x/food/stores/{storeNo}/carts`

权限与守卫：无台也需店级授权；session及check归属匹配；来源终端经鉴权验证，客户端不能开启后付能力。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 获准台账 |
| sessionNo | string | 否 | 无台会话 |
| buyerNo | string | 否 | 受权代客买家 |
| orderSource | string | 是 | 受控来源 |
| fulfillment | string | 是 | 受控履约 |
| scheduledAt | datetime | 否 | 定时出餐 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cart | Cart | 是 | 购物车 |

### FOOD-API-039 · 替换草稿行

`PUT /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}`

权限与守卫：path sourceLineRef与line.sourceLineRef必须一致；购物车CAS；未提交且SKU选配合法。

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

权限与守卫：报价绑定主体门店/车版本/源行内容/规则版本；确认价格变化；服务端最终重验可售及核心库存能力。

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
| orderSource | string | 是 | 经认证来源 |
| lines | OrderLineInput[] | 是 | 源行 |
| cartNo | string | 是 | 源车 |
| cartVersion | int64 | 是 | 车版本 |
| scheduledAt | datetime | 否 | 出餐时点 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| quote | Quote | 是 | 权威报价 |

### FOOD-API-042 · 提交点餐批次

`POST /biz/x/food/stores/{storeNo}/batches`

权限与守卫：提交必须匹配报价指纹、源行集合和车版本；冻结selected行；绑定核心成功后仅清理这些行；UNKNOWN同键恢复；payMode由获准策略校验。

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

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

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
| detail | BatchDetail | 是 | 原批次及资金履约独立状态 |

### FOOD-API-044 · 工位队列

`GET /biz/x/food/stores/{storeNo}/kitchen/tasks`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

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

权限与守卫：获准工位/传菜权限；只推进合法前态数量，CAS及累计数量守恒；重放不重复加量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 是 | 本次正数量 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 数量事实 |

### FOOD-API-046 · 制作任务ready

`POST /biz/x/food/tasks/{taskNo}:ready`

权限与守卫：获准工位/传菜权限；只推进合法前态数量，CAS及累计数量守恒；重放不重复加量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 是 | 本次正数量 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 数量事实 |

### FOOD-API-047 · 制作任务serve

`POST /biz/x/food/tasks/{taskNo}:serve`

权限与守卫：获准工位/传菜权限；只推进合法前态数量，CAS及累计数量守恒；重放不重复加量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 是 | 本次正数量 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 数量事实 |

### FOOD-API-048 · 制作任务hold

`POST /biz/x/food/tasks/{taskNo}:hold`

权限与守卫：获准工位/服务权限；hold/fire仅操作尚未制作部分，urge只记录催菜不加制作数量或销量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| reason | string | 否 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 状态事实 |

### FOOD-API-049 · 制作任务fire

`POST /biz/x/food/tasks/{taskNo}:fire`

权限与守卫：获准工位/服务权限；hold/fire仅操作尚未制作部分，urge只记录催菜不加制作数量或销量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| reason | string | 否 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 状态事实 |

### FOOD-API-050 · 制作任务urge

`POST /biz/x/food/tasks/{taskNo}:urge`

权限与守卫：获准工位/服务权限；hold/fire仅操作尚未制作部分，urge只记录催菜不加制作数量或销量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| reason | string | 否 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 状态事实 |

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
| qty | decimal | 是 | 正撤销量 |
| reason | string | 是 | 原因 |
| returnNo | string | 是 | 已获准退餐凭据 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 撤菜事实 |

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
| qty | decimal | 是 | 正重做量 |
| reason | string | 是 | 原因 |
| approvalRef | string | 是 | 重做授权 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 原任务 |
| eventNo | string | 是 | 重做事实 |
| newTaskNo | string | 是 | 关联重做任务 |

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

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

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

权限与守卫：服务端查询核心资金；不接受markPaid。现金仅biz收银权限且班次/完整单根影响集匹配，找零由服务端计算；顾客端只获准线上工具；未知状态按原意图恢复。

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
| shiftNo | string | 否 | 现金时必填当班 |
| cashTendered | Money | 否 | 现金时必填实收 |
| cashEvidenceRef | string | 否 | 现金凭据引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 请求意图 |
| status | string | 是 | 含UNKNOWN |
| coreReference | string | 否 | 核心结果 |
| paymentAction | object | 否 | 核心返回端上支付动作 |

### FOOD-API-059 · 结账cancel

`POST /biz/x/food/settlements/{settlementNo}:cancel`

权限与守卫：仅无成功/未知不可撤销资金时由核心释放；与付款回调串行仲裁；不得仅按客户端状态解冻。

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
| operation | Operation | 是 | 取消冻结范围结果 |

### FOOD-API-060 · 结账finalize

`POST /biz/x/food/settlements/{settlementNo}:finalize`

权限与守卫：scopeHash与冻结版本一致；核心结清且无未知资金；不能接受markPaid或以上菜/清台作为已付证据。

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
| settlement | Settlement | 是 | 核心已结清且无未知请求时终结 |

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

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

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
| kitchenStatus | string | 是 | 厨房轨道 |

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

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

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

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等；映射按账号范围/修订追加，旧订单不按当前映射重解释。。

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
| channelBindingNo | string | 否 | 外部账号绑定，本地省略 |
| mappingRevisionNo | string | 是 | 不可变映射修订 |

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

### FOOD-API-109 · 餐牌列表

`GET /biz/x/food/stores/{storeNo}/menus`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id。

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
| keyword | string | 否 | 名称 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Menu[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-110 · 编辑餐牌头

`PUT /biz/x/food/menus/{menuNo}`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id；仅改草稿不改变现行发布。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 名称 |
| menuType | string | 是 | 受控类型 |
| timezone | string | 是 | 时区 |
| priority | int32 | 是 | 优先级 |
| exclusiveGroup | string | 否 | 互斥组 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menu | Menu | 是 | 草稿头 |

### FOOD-API-111 · 餐牌停用

`POST /biz/x/food/menus/{menuNo}:disable`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id；旧订单快照保留。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 接单门控结果 |

### FOOD-API-112 · 菜品列表

`GET /biz/x/food/stores/{storeNo}/dishes`

权限与守卫：当前主体门店和原商品权限；先按原商品权威过滤及名称匹配，再统计与分页；goodsNo稳定排序；不接受未知或重复查询参数；不返回价格库存假值。

能力依赖：已实现管理查询，不代表可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| keyword | string | 否 | 最长128商品名或餐饮别名多语言名，忽略大小写字面子串 |
| dishKind | string | 否 | SINGLE或BUNDLE或ADDON |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | DishCatalogItem[] | 是 | 当前店授权菜品与原商品图文 |
| total | int64 | 是 | 全部授权筛选后数量 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-113 · 菜品详情

`GET /biz/x/food/stores/{storeNo}/dishes/{goodsNo}`

权限与守卫：当前主体门店原商品权限；真实组绑定及条目同主体；目标丢失/跨主体绑定CONFLICT，不用空组隐藏；发布引用独立返回，管理草稿不作为可售定义。

能力依赖：已实现管理详情，不代表发布或可交易。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| goodsNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| dish | Dish | 是 | 当前餐饮配置 |
| optionGroups | DishOptionGroup[] | 是 | 当前真实管理草稿组 |
| coreCatalogRef | string | 是 | 已核验的基础商品编号 |
| coreCatalog | ProductCatalog | 是 | 基础图文及规格 |

### FOOD-API-114 · 经营端可售餐牌

`GET /biz/x/food/stores/{storeNo}/catalog`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id；仅解析已发布快照；可售提交时再次验证；菜单条目不可仅按SKU去重；未取得权威报价不显示0元或可成交；用户不能伪造渠道身份。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| orderSource | string | 是 | 授权来源 |
| fulfillment | string | 是 | 履约 |
| at | datetime | 否 | 有预览权限才可指定 |
| channelBindingNo | string | 否 | 服务端按获准来源解析；外部身份须匹配 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menus | Menu[] | 是 | 生效餐牌 |
| items | CatalogItem[] | 是 | 可售菜品 |
| asOf | datetime | 是 | 截至 |

### FOOD-API-115 · 配置发布历史

`GET /biz/x/food/config-revisions`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id。

能力依赖：餐饮新增。

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configKind | string | 是 | 受控类型 |
| configNo | string | 是 | 配置号 |
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | ConfigRevision[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-116 · 回滚配置为新修订

`POST /biz/x/food/config-revisions/{revisionNo}:republish`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id；旧快照不可改，旧引用现可售性重新校验。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| revisionNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 当前头版本 |
| effectiveAt | datetime | 是 | 生效 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 新发布号 |

### FOOD-API-117 · 配料组合发布

`POST /biz/x/food/modifiers/{modifierNo}:publish`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id；递归检查循环及计量，冻结成员版本。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| modifierNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 草稿版 |
| effectiveAt | datetime | 是 | 生效 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 发布快照 |

### FOOD-API-118 · 选配组列表

`GET /biz/x/food/stores/{storeNo}/option-groups`

权限与守卫：原商品权限及当前主体门店校验；先主体过滤再SQL统计分页，groupNo稳定排序，批量读取本页真实条目；拒绝未知或重复参数；不作为已发布授权。

能力依赖：已实现管理查询，不代表可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Config[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-119 · 套餐列表

`GET /biz/x/food/stores/{storeNo}/bundles`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id。

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

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Config[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-120 · 配料列表

`GET /biz/x/food/stores/{storeNo}/modifiers`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id。

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

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Config[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-121 · 工位列表

`GET /biz/x/food/stores/{storeNo}/stations`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id。

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

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Config[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-122 · 可售限制查询

`GET /biz/x/food/stores/{storeNo}/sales-restrictions`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id。

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
| skuNo | string | 否 | SKU |
| status | string | 否 | 状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | SalesRestriction[] | 是 | 约束 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-123 · 按台账或条件查询批次

`GET /biz/x/food/stores/{storeNo}/batches`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id；资金按原核心单归属，查询不更新历史。

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
| checkNo | string | 否 | 原/当前服务台账 |
| membershipMode | string | 否 | ORIGINAL或CURRENT |
| status | string | 否 | 受理状态 |
| businessDate | date | 否 | 批次营业日 |
| orderSource | string | 否 | 来源 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Batch[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-124 · 取消正式批次

`POST /biz/x/food/batches/{batchNo}:cancel`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id；调用核心取消；支付竞争/已制作按核心守卫和退餐处理；未知不换键重建。

能力依赖：核心取消及后付库存合同。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 批次版 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 取消进度 |
| batch | Batch | 是 | 批次 |

### FOOD-API-125 · 批次操作恢复查询

`GET /biz/x/food/batches/{batchNo}/operations`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Operation[] | 是 | 建单取消受理恢复 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-126 · 关闭草稿购物车

`POST /biz/x/food/carts/{cartNo}:archive`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id；不删除已提交批次；提交中冻结行须先恢复。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 购物车版 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 关闭结果 |

### FOOD-API-127 · 顾客购物车读取

`GET /mp/x/food/carts/{cartNo}`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

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
| cart | Cart | 是 | 获准购物车 |

### FOOD-API-128 · 顾客创建购物车

`POST /mp/x/food/stores/{storeNo}/carts`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 获准台账 |
| fulfillment | string | 是 | 受控履约 |
| scheduledAt | datetime | 否 | 定时出餐 |
| sessionNo | string | 是 | 获准会话 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cart | Cart | 是 | 购物车 |

### FOOD-API-129 · 顾客替换草稿行

`PUT /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

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

### FOOD-API-130 · 顾客归档购物车草稿行

`POST /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

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

### FOOD-API-131 · 顾客点单报价

`POST /mp/x/food/stores/{storeNo}/quotes`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

能力依赖：已有报价适配；选配计量等分项门控。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 台账 |
| fulfillment | string | 是 | 履约 |
| orderSource | string | 是 | 经认证来源 |
| lines | OrderLineInput[] | 是 | 源行 |
| cartNo | string | 是 | 源车 |
| cartVersion | int64 | 是 | 车版本 |
| scheduledAt | datetime | 否 | 出餐时点 |
| sessionNo | string | 是 | 获准会话 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| quote | Quote | 是 | 权威报价 |

### FOOD-API-132 · 顾客提交点餐批次

`POST /mp/x/food/stores/{storeNo}/batches`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

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

### FOOD-API-133 · 顾客批次结果查询

`GET /mp/x/food/batches/{batchNo}`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

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
| detail | BatchDetail | 是 | 原批次及资金履约独立状态 |

### FOOD-API-134 · 顾客读取预结账

`GET /mp/x/food/settlements/{settlementNo}`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

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

### FOOD-API-135 · 顾客结账pay

`POST /mp/x/food/settlements/{settlementNo}:pay`

权限与守卫：服务端查询核心资金；不接受markPaid。现金仅biz收银权限且班次/完整单根影响集匹配，找零由服务端计算；顾客端只获准线上工具；未知状态按原意图恢复。

能力依赖：核心支付/现金确认；混合与跨根需扩展。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| tool | string | 是 | 获准线上工具 |
| amount | Money | 是 | 本次付款 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 请求意图 |
| status | string | 是 | 含UNKNOWN |
| coreReference | string | 否 | 核心结果 |
| paymentAction | object | 否 | 核心返回端上支付动作 |

### FOOD-API-136 · 顾客退餐申请

`POST /mp/x/food/stores/{storeNo}/returns`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

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

### FOOD-API-137 · 顾客退餐查询

`GET /mp/x/food/returns/{returnNo}`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

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
| kitchenStatus | string | 是 | 厨房轨道 |

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

### FOOD-API-140 · 顾客申请获准范围结账

`POST /mp/x/food/checks/{checkNo}/settlements`

权限与守卫：会话本人范围；服务端解析核心单及分配，不接受任意核心单号；整桌代付须台账明确授权；冻结排他与未知资金守卫同biz；不得创建现金/免单/跨币种结账。

能力依赖：核心应收冻结；部分/跨根能力门控。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 台账版本 |
| foodLineNos | string[] | 是 | 获准源行范围，空代表本人获准未结范围 |
| scopeHash | string | 是 | 顾客已确认应收摘要 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlement | Settlement | 是 | 核心冻结范围 |

### FOOD-API-141 · 创建配料或配料组合

`POST /biz/x/food/stores/{storeNo}/modifiers`

权限与守卫：主体门店权限与创建幂等；SKU同主体；COMBO引用已存在配料并拒循环；不接受客户端伪造业务号或价格。

能力依赖：餐饮配置；收费成交需核心报价。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 名称 |
| kind | string | 是 | ADDON或COMBO |
| salesSkuNo | string | 否 | 收费SKU |
| components | ComponentInput[] | 是 | 组合成员或空 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 服务端生成配料业务号 |
| version | int64 | 是 | 草稿CAS版本 |
| status | string | 是 | DRAFT |

### FOOD-API-142 · 创建餐牌渠道绑定

`POST /biz/x/food/stores/{storeNo}/menu-channel-bindings`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。渠道账号必须验真；Keeta仅规划。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string | 是 | 本店餐牌 |
| provider | string | 是 | 受控渠道 |
| channelAccountRef | string | 是 | 已有受权账号引用 |
| externalStoreRef | string | 是 | 受权外部门店 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| binding | MenuChannelBinding | 是 | 服务端生成绑定 |

### FOOD-API-143 · 渠道绑定列表

`GET /biz/x/food/stores/{storeNo}/menu-channel-bindings`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| provider | string | 否 | 渠道 |
| menuNo | string | 否 | 餐牌 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | MenuChannelBinding[] | 是 | 授权记录 |
| total | int64 | 是 | 同过滤总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-144 · 渠道绑定详情

`GET /biz/x/food/menu-channel-bindings/{bindingNo}`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| binding | MenuChannelBinding | 是 | 获准对象 |

### FOOD-API-145 · 修改渠道绑定

`PUT /biz/x/food/menu-channel-bindings/{bindingNo}`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。不改原provider/账号/menu/store；历史映射保留，停用不取消旧订单。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | CAS |
| status | string | 是 | ACTIVE或DISABLED |
| externalStoreRef | string | 是 | 获准外部门店 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| binding | MenuChannelBinding | 是 | 新版本 |

### FOOD-API-146 · 发起餐牌渠道同步

`POST /biz/x/food/menu-channel-bindings/{bindingNo}/syncs`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。当前Keeta没有启用适配；无真实提供方拒绝，不能返回虚构成功。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| revisionNo | string | 是 | 本绑定餐牌已发布修订 |
| version | int64 | 是 | 绑定CAS |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sync | CatalogSync | 是 | 原号任务 |

### FOOD-API-147 · 查询餐牌同步结果

`GET /biz/x/food/catalog-syncs/{syncNo}`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。部分/未知分列；旧ACK不能覆盖新目标。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| syncNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sync | CatalogSync | 是 | 持久化真实状态 |

### FOOD-API-148 · 创建配方工作头

`POST /biz/x/food/stores/{storeNo}/recipes`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。单位与物料目录未联通时不得假校验。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 本店SKU |
| recipeCode | string | 是 | DEFAULT或受控变体 |
| yieldQty | decimal | 是 | 正产出 |
| yieldUnit | string | 是 | 受控单位 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipe | Recipe | 是 | 服务端生成配方 |

### FOOD-API-149 · 配方列表

`GET /biz/x/food/stores/{storeNo}/recipes`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| skuNo | string | 否 | 本店SKU |
| status | string | 否 | 状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Recipe[] | 是 | 记录 |
| total | int64 | 是 | 同过滤总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-150 · 配方详情

`GET /biz/x/food/recipes/{recipeNo}`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipe | Recipe | 是 | 获准配置 |

### FOOD-API-151 · 维护配方物料与调整

`PUT /biz/x/food/recipes/{recipeNo}/definition`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。目标行属于本配方；先SET后ADD，REMOVE互斥；权威单位/损失比例；禁止改历史发布。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | CAS |
| yieldQty | decimal | 是 | 正产出 |
| yieldUnit | string | 是 | 单位 |
| lines | RecipeLineInput[] | 是 | 物料 |
| adjustments | RecipeAdjustmentInput[] | 是 | 调整 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipe | Recipe | 是 | 新草稿版本 |

### FOOD-API-152 · 发布配方

`POST /biz/x/food/recipes/{recipeNo}:publish`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。RECIPE schema校验及物料/单位验真，冻结转换；不意味着库存扣料合同已就绪。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | CAS |
| effectiveAt | datetime | 是 | 生效时间 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 不可变修订 |

### FOOD-API-153 · 制作耗用列表

`GET /biz/x/food/stores/{storeNo}/production-consumptions`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。只读不补扣库存；未知成本与实际量保持为空。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| from | date | 是 | 起日 |
| to | date | 是 | 止日不含 |
| skuNo | string | 否 | 本店SKU |
| itemRef | string | 否 | 物料 |
| status | string | 否 | 真实状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | ProductionConsumption[] | 是 | 获准事实 |
| total | int64 | 是 | 同过滤总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-154 · 制作耗用详情

`GET /biz/x/food/production-consumptions/{consumptionNo}`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。只读核验逐行回执，不能以退款推返料。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| consumptionNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| consumption | ProductionConsumption | 是 | 原事实与回执 |

## 五、顾客与设备端复用

顾客catalog/session与购物车、报价、批次、进度、结账、退餐申请/查询、主动结束会话均已逐项登记API编号。只有目录中的 /mp/x/food 白名单可实现，不能将整个biz控制器自动暴露到mp。顾客作用域绑定本人或明确授权的台账/源行，桌码仅授权桌级会话，不自动共享同桌其他独立台账。顾客禁止代客buyerNo、现金确认、审批、签核、任务操作和自造corePaymentToken；点餐机使用已验证设备凭据与短会话。

支付动作对象由核心通道按真实客户端环境产生，业务服务不自拼收银参数。共享审批、商品编辑、会员资产、发票、税费、配送、排队及房账平台有各自合同；此处端点不重复实现它们的核心逻辑。

## 六、示例与错误恢复

点餐输入示例（选配能力启用时）：

```json
{"cartNo":"CART-1","cartVersion":1,"checkNo":"CHECK-1","buyerNo":"BUYER-1","fulfillment":"DINE_IN","orderSource":"WAITER","lines":[{"sourceLineRef":"LINE-1","goodsNo":"G-1","skuNo":"SKU-1","qty":"1","selections":[{"groupNo":"ICE","sourceKind":"SPEC_VALUE","sourceNo":"LESS_ICE","qty":"1","configVersion":3}]}]}
```

批次受理返回示例：

```json
{"code":0,"msg":"success","data":{"batch":{"batchNo":"BATCH-1","status":"PLACING","paymentRootNos":[],"storeOrderNos":[],"foodLineNos":[],"version":1}}}
```

data.status为PLACING/UNKNOWN时按batchNo查询；HTTP状态仍沿宿主包装及异常协议，不强制返回202。请求受理后，不换幂等键重建；合法受理后也需按先付/后付条件判断是否制作。冲突错误沿宿主错误码返回当前version或冲突资源引用，但不泄露无权业务。日结签核前水位变化返回CLOSE_WATERMARK_CHANGED，重新预结审阅；依赖能力未开返回CAPABILITY_NOT_READY，不尝试餐饮直接写核心数据。

## 七、餐饮与基础域的输入输出合同

> 状态：待评审 · 2026-10-19 · 本文件是API生成输入片段；正式阅读入口为技术设计目录中的《餐饮系统API设计》。

Catalog.goodsMetadata已实现为GoodsCatalogQueryPort及typed内部查询；其余名称仍为目标端口，不是已存在Java类。集成部署实现为宿主桥接，独立部署实现为服务调用；不将跨库实现细节传入餐饮领域。字段中的ref均须受权查询，不允许客户端自造已付或已退款凭据。

| 端口 | 输入（必填，?为可选） | 输出 | 当前边界 |
|---|---|---|---|
| Identity.authorize | actorToken:string, entityNo:string, storeNo:string, action:string, objectRef?:string | actorNo:string, buyerScope:string[], allowed:boolean, limits:Money[] | 复用身份；匿名就餐主体与代办需正式合同 |
| Catalog.goodsMetadata | entityNo:string, storeNo:string, goodsNo:string | GoodsFact:{goodsNo,entityNo,storeNo,title,cover?,baseOnSale,auditStatus?,version,skus:{skuNo,spec?,version}[]} | CAT-01A基础管理事实；明确商品真实归属及全部CN规格，无价格库存，不表示最终可售；独立经GoodsCatalogInternalApi读取PLATFORM |
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

## 八、逐需求与业务模块对齐

目标已定义仅表示本轮结构与目标合同有明确落点，不表示接口已实现。依赖共享/核心合同必须由权威模块接通验证；尚缺专项设计不能作为已覆盖需求进入开发完成统计。跨域关系为逻辑引用，不允许餐饮直接写核心表。

### MOD-01 桌台与就餐管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-A04 | 目标已定义 | `fnb_table_area`、`fnb_table`、`fnb_table_token` | FOOD-API-024、FOOD-API-025、FOOD-API-092、FOOD-API-093、FOOD-API-094、FOOD-API-095、FOOD-API-096、FOOD-API-097、FOOD-API-098、FOOD-API-099、FOOD-API-100、FOOD-API-101 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-C01 | 尚缺专项设计 | `fnb_reservation` | FOOD-API-027、FOOD-API-030、FOOD-API-107 | 预约资源已设计；预点菜草稿到预约的引用与预点命令未定义，实施预约预点前补齐，不能提前生成交易单 |
| FNB-C02 | 依赖共享/核心合同 | `fnb_reservation` | FOOD-API-027、FOOD-API-028、FOOD-API-029 | 订金/预付款核销、没收审批与退款状态取权威资金合同；deposit_ref只存引用，不表示已覆盖全部资金动作 |
| FNB-C03 | 尚缺专项设计 | 共享服务/运行合同 | 待专项合同，不假造端点 | 复用共享排队/通知，但当前餐饮设计没有正式取号/叫号/过号合同；不得把自取取餐号当排队号 |
| FNB-C04 | 目标已定义 | `fnb_reservation`、`fnb_table_operation`、`fnb_table_occupancy` | FOOD-API-027、FOOD-API-030、FOOD-API-031 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-C05 | 目标已定义 | `fnb_check`、`fnb_guest_session`、`fnb_seat`、`fnb_table_occupancy` | FOOD-API-026、FOOD-API-031、FOOD-API-102、FOOD-API-103、FOOD-API-105、FOOD-API-138、FOOD-API-139 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-C06 | 目标已定义 | `fnb_table_operation`、`fnb_table_occupancy`、`fnb_check_relation`、`fnb_check_batch_membership` | FOOD-API-033、FOOD-API-034、FOOD-API-035、FOOD-API-104、FOOD-API-105、FOOD-API-106 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-C08 | 目标已定义 | `fnb_check`、`fnb_table_occupancy`、`fnb_table_operation` | FOOD-API-036、FOOD-API-096、FOOD-API-102、FOOD-API-108 | 结构与目标接口已定义；仍需实现与业务验收 |

### MOD-02 菜品与餐牌管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-B01 | 依赖共享/核心合同 | `fnb_dish` | FOOD-API-016、FOOD-API-112、FOOD-API-113 | 基础SKU/上下架/价格库存复用商品服务；本文不新增餐饮商品副本 |
| FNB-B02 | 目标已定义 | `fnb_menu`、`fnb_menu_store`、`fnb_menu_context`、`fnb_menu_schedule`、`fnb_menu_exception`、`fnb_menu_category`、`fnb_menu_item`、`fnb_menu_item_sku`、`fnb_config_revision`、`fnb_menu_channel_binding`、`fnb_menu_item_option_group` | FOOD-API-004、FOOD-API-005、FOOD-API-006、FOOD-API-017、FOOD-API-109、FOOD-API-110、FOOD-API-111、FOOD-API-114、FOOD-API-115、FOOD-API-116、FOOD-API-142、FOOD-API-143、FOOD-API-144、FOOD-API-145 | 时段/渠道范围及菜项覆盖已入设计；报价引用须权威核验，Keeta仅规划，实际发布/同步未实现。 |
| FNB-B03 | 目标已定义 | `fnb_dish`、`fnb_option_group`、`fnb_option_group_item`、`fnb_dish_option_group`、`fnb_config_revision` | FOOD-API-007、FOOD-API-008、FOOD-API-009、FOOD-API-016、FOOD-API-019、FOOD-API-118 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-B04 | 目标已定义 | `fnb_dish`、`fnb_option_group`、`fnb_option_group_item`、`fnb_dish_option_group`、`fnb_config_revision`、`fnb_menu_item_option_group` | FOOD-API-007、FOOD-API-008、FOOD-API-009、FOOD-API-016、FOOD-API-019、FOOD-API-118 | 商品默认选配与餐牌REPLACE显式分开；免费口味复用SPEC_VALUE，未实现运行时选择校验。 |
| FNB-B05 | 依赖共享/核心合同 | `fnb_bundle`、`fnb_bundle_group`、`fnb_bundle_choice`、`fnb_config_revision` | FOOD-API-010、FOOD-API-011、FOOD-API-012、FOOD-API-018、FOOD-API-041、FOOD-API-119 | 配置及版本已定义，套餐成交分摊、补差、组件库存展开需核心组合报价合同 |
| FNB-B06 | 依赖共享/核心合同 | `fnb_modifier`、`fnb_modifier_component`、`fnb_option_group_item`、`fnb_config_revision` | FOOD-API-019、FOOD-API-020、FOOD-API-041、FOOD-API-117、FOOD-API-120、FOOD-API-141 | 组合配置防循环；双拼/半份的单位换算与收费必须核心计量/报价通过后启用 |
| FNB-B07 | 依赖共享/核心合同 | `fnb_dish`、`fnb_store_policy` | FOOD-API-041、FOOD-API-061 | 餐段/渠道价、茶位低消服务费等规则归共享报价；当前缺明确通用费用规则配置合同，不在settings任意JSON里影子计价 |
| FNB-B08 | 目标已定义 | `fnb_sales_restriction`、`fnb_sales_quota_claim` | FOOD-API-022、FOOD-API-122 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-B09 | 尚缺专项设计 | `fnb_dish`、`fnb_order_line` | FOOD-API-016、FOOD-API-041 | 计量规则有位置；标称/实称/允差确认及补差动作尚缺正式DTO和命令，不能只配置就售卖称重菜 |
| FNB-B10 | 依赖共享/核心合同 | `fnb_dish`、`fnb_store_policy` | FOOD-API-041、FOOD-API-061 | 餐段/渠道价、茶位低消服务费等规则归共享报价；当前缺明确通用费用规则配置合同，不在settings任意JSON里影子计价 |
| FNB-B11 | 尚缺专项设计 | `fnb_dish`、`fnb_config_revision` | FOOD-API-016、FOOD-API-112、FOOD-API-113 | 展示字段及冻结已补；批量导入预检/结果/导出尚依赖商品导入合同，明确接入后才算完成 |
| FNB-K01 | 目标已定义 | `fnb_config_revision`、`fnb_menu_store` | FOOD-API-006、FOOD-API-017、FOOD-API-115、FOOD-API-116 | 结构与目标接口已定义；仍需实现与业务验收 |

### MOD-03 点餐与订单管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-D01 | 依赖共享/核心合同 | `fnb_guest_session`、`fnb_check_cart`、`fnb_order_batch` | FOOD-API-026、FOOD-API-038、FOOD-API-041、FOOD-API-042、FOOD-API-127、FOOD-API-128、FOOD-API-129、FOOD-API-130、FOOD-API-131、FOOD-API-132、FOOD-API-133、FOOD-API-138、FOOD-API-139 | 顾客路由已逐项编号；匿名主体、代客、设备鉴权及各客户端支付参数必须沿身份/资金权威合同 |
| FNB-D02 | 依赖共享/核心合同 | `fnb_guest_session`、`fnb_check_cart`、`fnb_order_batch` | FOOD-API-026、FOOD-API-038、FOOD-API-041、FOOD-API-042、FOOD-API-127、FOOD-API-128、FOOD-API-129、FOOD-API-130、FOOD-API-131、FOOD-API-132、FOOD-API-133、FOOD-API-138、FOOD-API-139 | 顾客路由已逐项编号；匿名主体、代客、设备鉴权及各客户端支付参数必须沿身份/资金权威合同 |
| FNB-D03 | 目标已定义 | `fnb_check_cart`、`fnb_check_cart_item`、`fnb_seat` | FOOD-API-037、FOOD-API-038、FOOD-API-039、FOOD-API-040、FOOD-API-103、FOOD-API-126、FOOD-API-127、FOOD-API-128、FOOD-API-129、FOOD-API-130 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-D04 | 依赖共享/核心合同 | `fnb_check_cart`、`fnb_order_batch` | FOOD-API-041、FOOD-API-131 | 报价绑定草稿版本及指纹；价格变化须顾客确认；核心报价是权威 |
| FNB-D05 | 依赖共享/核心合同 | `fnb_order_batch`、`fnb_order_line`、`fnb_core_line_allocation`、`fnb_order_modifier`、`fnb_order_component` | FOOD-API-042、FOOD-API-043、FOOD-API-123、FOOD-API-125、FOOD-API-132、FOOD-API-133 | 源行/批次追踪已补；当前核心Item缺源行映射，需正式扩展合同 |
| FNB-D06 | 尚缺专项设计 | `fnb_return`、`fnb_return_item`、`fnb_charge_intent` | FOOD-API-061、FOOD-API-062、FOOD-API-063 | 赠菜/换菜/缺货替换的顾客确认和原新源行关系未定义专用命令；不能仅凭调整或退餐接口宣称覆盖 |
| FNB-D07 | 依赖共享/核心合同 | `fnb_order_batch`、`fnb_charge_intent` | FOOD-API-042、FOOD-API-058、FOOD-API-124、FOOD-API-125、FOOD-API-132、FOOD-API-135 | 先付权威回调与核心取消竞争；UNKNOWN恢复不能重建 |
| FNB-D08 | 依赖共享/核心合同 | `fnb_check`、`fnb_order_batch`、`fnb_settlement` | FOOD-API-031、FOOD-API-042、FOOD-API-056、FOOD-API-058 | 后付受理、库存承诺、未付堂食交付、超时取消守卫需核心扩展，不能用延长支付期限代替 |
| FNB-D09 | 尚缺专项设计 | `fnb_check_cart`、`fnb_order_batch` | FOOD-API-038、FOOD-API-041、FOOD-API-042 | scheduled_at位置已补；预售截止/产能预占/定时派工的正式策略及恢复合同仍待细化 |

### MOD-04 厨房与履约管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-E01 | 目标已定义 | `fnb_kitchen_ticket`、`fnb_kitchen_task` | FOOD-API-044、FOOD-API-045 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-E02 | 目标已定义 | `fnb_kitchen_task`、`fnb_service_event` | FOOD-API-048、FOOD-API-049、FOOD-API-050 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-E03 | 目标已定义 | `fnb_kitchen_task`、`fnb_service_event` | FOOD-API-046、FOOD-API-047 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-E04 | 目标已定义 | `fnb_course`、`fnb_kitchen_task` | FOOD-API-053、FOOD-API-054、FOOD-API-048、FOOD-API-049 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-E05 | 尚缺专项设计 | `fnb_service_event`、`fnb_kitchen_task` | FOOD-API-046、FOOD-API-047、FOOD-API-055 | 出品/上菜已分轨，撤回交接和漏菜反馈动作需明确数量冲销规则及端点，不以负qty复用serve |
| FNB-E06 | 尚缺专项设计 | `fnb_kitchen_task`、`fnb_order_component` | FOOD-API-044、FOOD-API-046、FOOD-API-055 | 有组件与跨工位任务，但齐套/打包清单确认缺专用合同，不能仅全部任务ready替代人工打包核对 |
| FNB-E07 | 依赖共享/核心合同 | `fnb_order_batch` | FOOD-API-055 | 叫号/核销/配送状态复用对应履约配送合同；handover不能代替叫号及防重核销全部步骤 |
| FNB-E08 | 依赖共享/核心合同 | `fnb_order_batch` | FOOD-API-055 | 叫号/核销/配送状态复用对应履约配送合同；handover不能代替叫号及防重核销全部步骤 |
| FNB-E09 | 依赖共享/核心合同 | `fnb_kitchen_task`、`fnb_service_event`、`fnb_return_item` | FOOD-API-051、FOOD-API-052、FOOD-API-062、FOOD-API-063 | 制作损耗及退餐回库由库存权威判定；重做不自动计新增销量 |
| FNB-E10 | 尚缺专项设计 | `fnb_store_policy`、`fnb_kitchen_task` | FOOD-API-044、FOOD-API-081 | 工位队列已有；超时阈值/拥堵暂停的配置、告警订阅与恢复命令尚未明确 |

### MOD-05 收银与结账管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-C07 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item`、`fnb_charge_intent` | FOOD-API-056、FOOD-API-057、FOOD-API-058、FOOD-API-059、FOOD-API-060、FOOD-API-140 | 主责MOD-05；桌台merge/split只改服务归属，资金拆合必须核心应收范围及分配合同 |
| FNB-F01 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item` | FOOD-API-056、FOOD-API-057、FOOD-API-059、FOOD-API-060、FOOD-API-134、FOOD-API-140 | 冻结范围及防重复占应收需核心权威仲裁；预结不是已付款 |
| FNB-F02 | 依赖共享/核心合同 | `fnb_charge_intent`、`fnb_cash_shift`、`fnb_cash_payment_link` | FOOD-API-058、FOOD-API-068、FOOD-API-070、FOOD-API-071、FOOD-API-135 | 单根支付可适配；现金仍遵守完整门店单影响集限制，班次/证据必须齐全 |
| FNB-F03 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item`、`fnb_charge_intent` | FOOD-API-056、FOOD-API-058、FOOD-API-140 | 跨根、按人按菜AA、混合支付及会员资产原路退款分配需核心合同；单根成功不能markPaid其他单 |
| FNB-F04 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item`、`fnb_charge_intent` | FOOD-API-056、FOOD-API-058、FOOD-API-140 | 跨根、按人按菜AA、混合支付及会员资产原路退款分配需核心合同；单根成功不能markPaid其他单 |
| FNB-F05 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item`、`fnb_charge_intent` | FOOD-API-056、FOOD-API-058、FOOD-API-140 | 跨根、按人按菜AA、混合支付及会员资产原路退款分配需核心合同；单根成功不能markPaid其他单 |
| FNB-F06 | 依赖共享/核心合同 | `fnb_charge_intent` | FOOD-API-061 | 折扣抹零免单及税费小费归权威报价/审批；小费保留P4且一期不收超额，不能覆盖旧成交快照 |
| FNB-F07 | 依赖共享/核心合同 | `fnb_charge_intent` | FOOD-API-061 | 折扣抹零免单及税费小费归权威报价/审批；小费保留P4且一期不收超额，不能覆盖旧成交快照 |
| FNB-F08 | 依赖共享/核心合同 | `fnb_charge_intent`、`fnb_room_charge_intent` | FOOD-API-061、FOOD-API-091 | 企业挂账及房账须权威应收/信用核销合同；不能通过餐饮调整伪装已付款 |
| FNB-F09 | 依赖共享/核心合同 | 共享服务/运行合同 | 待专项合同，不假造端点 | 押金器具租赁引用共享资金/资源合同，当前未设计完整动作和引用模型；押金不得计营收，须专项细化后启用 |
| FNB-F10 | 依赖共享/核心合同 | 共享服务/运行合同 | 待专项合同，不假造端点 | 税务发票/电子收据/冲红由共享票据服务提供正式合同；普通打印不是发票 |

### MOD-06 退餐与售后管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-G01 | 依赖共享/核心合同 | `fnb_check_cart_item`、`fnb_order_batch` | FOOD-API-040、FOOD-API-124、FOOD-API-126 | 草稿归档已定义；正式取消受核心支付/制作守卫约束 |
| FNB-G02 | 依赖共享/核心合同 | `fnb_return`、`fnb_return_item` | FOOD-API-062、FOOD-API-063、FOOD-API-064、FOOD-API-136、FOOD-API-137 | 当前只可整门店单全额；未付应收调整/部分退分摊均需合同。退款按原请求恢复，厨房结果分轨 |
| FNB-G03 | 依赖共享/核心合同 | `fnb_return`、`fnb_return_item` | FOOD-API-062、FOOD-API-063、FOOD-API-064、FOOD-API-136、FOOD-API-137 | 当前只可整门店单全额；未付应收调整/部分退分摊均需合同。退款按原请求恢复，厨房结果分轨 |
| FNB-G04 | 依赖共享/核心合同 | `fnb_return`、`fnb_return_item` | FOOD-API-062、FOOD-API-063、FOOD-API-064、FOOD-API-136、FOOD-API-137 | 当前只可整门店单全额；未付应收调整/部分退分摊均需合同。退款按原请求恢复，厨房结果分轨 |
| FNB-G05 | 依赖共享/核心合同 | `fnb_return`、`fnb_return_item` | FOOD-API-062、FOOD-API-063、FOOD-API-064、FOOD-API-136、FOOD-API-137 | 当前只可整门店单全额；未付应收调整/部分退分摊均需合同。退款按原请求恢复，厨房结果分轨 |
| FNB-G06 | 尚缺专项设计 | `fnb_action_log`、`fnb_order_batch` | FOOD-API-108、FOOD-API-125 | 错单重开/离线补录认领关系及申请审批命令未定义，不能删除旧单重造销量 |
| FNB-G07 | 依赖共享/核心合同 | `fnb_close_adjustment`、`fnb_charge_intent` | FOOD-API-076、FOOD-API-061 | 关账后更正有模型；反结账和坏账必须权威应收审批合同，不重写原签核 |
| FNB-G08 | 依赖共享/核心合同 | `fnb_action_log` | FOOD-API-108 | 审计表与查询已设计；复用全平台不可篡改审计存储/权限，餐饮本地日志本身不构成不可抵赖证据 |

### MOD-07 打印、通知与设备管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-A08 | 尚缺专项设计 | `prn_device`、`prn_route` | FOOD-API-065、FOOD-API-066 | 设备自检/开班检查聚合查询尚无明确请求响应；不可用打印任务接口代替，模块07实施前补齐 |
| FNB-H01 | 尚缺专项设计 | `prn_device`、`prn_template`、`prn_route` | FOOD-API-065 | 打印配置表已设计，设备绑定/模板版本/路由维护与测试端点尚缺明确共享合同 |
| FNB-H02 | 依赖共享/核心合同 | `prn_job`、`prn_template` | FOOD-API-065、FOOD-API-066、FOOD-API-067 | 内容由餐饮冻结业务凭据，投递归共享打印；日结/交班票依赖对应模块真实版本 |
| FNB-H03 | 依赖共享/核心合同 | `prn_job`、`prn_template` | FOOD-API-065、FOOD-API-066、FOOD-API-067 | 内容由餐饮冻结业务凭据，投递归共享打印；日结/交班票依赖对应模块真实版本 |
| FNB-H04 | 依赖共享/核心合同 | `prn_job`、`prn_template` | FOOD-API-065、FOOD-API-066、FOOD-API-067 | 内容由餐饮冻结业务凭据，投递归共享打印；日结/交班票依赖对应模块真实版本 |
| FNB-H05 | 目标已定义 | `prn_job`、`prn_attempt` | FOOD-API-066、FOOD-API-067 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-H06 | 尚缺专项设计 | `prn_device` | 待专项合同，不假造端点 | KDS订阅/通知/语音和远程设备诊断权限、配置下发、状态失效合同未定义；不能以任务查询代替推送治理 |
| FNB-H07 | 尚缺专项设计 | `prn_device` | 待专项合同，不假造端点 | KDS订阅/通知/语音和远程设备诊断权限、配置下发、状态失效合同未定义；不能以任务查询代替推送治理 |

### MOD-08 班次与现金管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-I01 | 目标已定义 | `fnb_cash_shift`、`fnb_cash_entry`、`fnb_cash_payment_link`、`fnb_cash_count` | FOOD-API-068、FOOD-API-069、FOOD-API-070、FOOD-API-071 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I02 | 目标已定义 | `fnb_cash_shift`、`fnb_cash_entry`、`fnb_cash_payment_link`、`fnb_cash_count` | FOOD-API-068、FOOD-API-069、FOOD-API-070、FOOD-API-071 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I03 | 目标已定义 | `fnb_cash_shift`、`fnb_cash_entry`、`fnb_cash_payment_link`、`fnb_cash_count` | FOOD-API-068、FOOD-API-069、FOOD-API-070、FOOD-API-071 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I07 | 尚缺专项设计 | `fnb_cash_shift`、`fnb_close_exception` | FOOD-API-071、FOOD-API-074 | 班次交接有入口，跨班未收责任移交/认领需细化非现金应收责任合同，不迁移原营业日 |

### MOD-09 营业日、日结与对账管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-I04 | 依赖共享/核心合同 | `fnb_business_day`、`fnb_close_run`、`fnb_close_balance`、`fnb_close_exception` | FOOD-API-072、FOOD-API-073 | 预结快照引用权威对账和支付证据；通道账期不等同营业日 |
| FNB-I05 | 依赖共享/核心合同 | `fnb_business_day`、`fnb_close_run`、`fnb_close_balance`、`fnb_close_exception` | FOOD-API-072、FOOD-API-073 | 预结快照引用权威对账和支付证据；通道账期不等同营业日 |
| FNB-I06 | 目标已定义 | `fnb_close_run`、`fnb_close_balance` | FOOD-API-072、FOOD-API-073、FOOD-API-075 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I08 | 依赖共享/核心合同 | `fnb_close_exception`、`fnb_close_run` | FOOD-API-074、FOOD-API-075 | 例外需获准审批；资金UNKNOWN只能移交证据，不能改成成功 |
| FNB-I09 | 目标已定义 | `fnb_close_adjustment`、`fnb_close_run` | FOOD-API-076 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I10 | 依赖共享/核心合同 | `fnb_report_run`、`fnb_close_run` | FOOD-API-080 | 复用任务水位/幂等补跑；投影重建不等于重新签核或重开钱箱 |

### MOD-10 经营报表管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-J01 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J02 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J03 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J04 | 依赖共享/核心合同 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 应收账龄依赖共享AR核销流水 |
| FNB-J05 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J06 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J07 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J08 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J09 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J10 | 依赖共享/核心合同 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 会员充值/本金/赠金/消费沿资产权威，不重复记销售 |
| FNB-J11 | 依赖共享/核心合同 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily`、`fnb_recipe`、`fnb_recipe_line`、`fnb_recipe_adjustment`、`fnb_production_consumption`、`fnb_production_consumption_line` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 配方/制作理论耗用/真实回执分开；单位数量及历史估值合同PENDING，当前不能把领料当逐菜实耗或估算为实际成本。 |
| FNB-J12 | 依赖共享/核心合同 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 预约排队/客诉的权威事实及分母合同需补齐 |
| FNB-J13 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-L06 | 依赖共享/核心合同 | `fnb_metric_definition`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079 | 专项报表只对已开通能力开放，依赖对应事实和口径定义 |

### MOD-11 专项业态管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-D10 | 依赖共享/核心合同 | `fnb_banquet_plan` | FOOD-API-087、FOOD-API-088 | 宴会保证人数、实际加席及收费依赖预约/报价资金合同；冻结菜单与计划 |
| FNB-L01 | 依赖共享/核心合同 | `fnb_dining_entitlement`、`fnb_charge_intent` | FOOD-API-085、FOOD-API-086、FOOD-API-061 | 人数限时规则已有；频控/最后点单在提交守卫校验，超时浪费费用需核心报价和审批 |
| FNB-L02 | 依赖共享/核心合同 | `fnb_dining_entitlement`、`fnb_charge_intent` | FOOD-API-085、FOOD-API-086、FOOD-API-061 | 人数限时规则已有；频控/最后点单在提交守卫校验，超时浪费费用需核心报价和审批 |
| FNB-L03 | 依赖共享/核心合同 | `fnb_dining_entitlement`、`fnb_charge_intent` | FOOD-API-085、FOOD-API-086、FOOD-API-061 | 人数限时规则已有；频控/最后点单在提交守卫校验，超时浪费费用需核心报价和审批 |
| FNB-L04 | 依赖共享/核心合同 | `fnb_bottle_custody`、`fnb_custody_entry` | FOOD-API-089、FOOD-API-090 | 寄存归属及授权须实名/凭据合同；超领CAS拒绝，报损不等同销售 |
| FNB-L05 | 依赖共享/核心合同 | `fnb_room_charge_intent` | FOOD-API-091 | PMS授权、应收转挂及原键查询撤销需专项合同，UNKNOWN不能结清 |

### MOD-12 第三方接入与离线营业

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-K02 | 尚缺专项设计 | `fnb_external_mapping`、`fnb_integration_exception` | FOOD-API-082、FOOD-API-083 | 仅映射与异常查询，第三方订单导入/回调鉴权/商品同步/配送及对账适配仍需选定供应商协议；不能宣称已完整接入 |
| FNB-K03 | 尚缺专项设计 | `fnb_edge_lease`、`fnb_sync_batch`、`fnb_integration_exception` | FOOD-API-084 | 批次认领已有；租约获取/续租/隔离、离线可售额度与冲突处理协议未完整定义，需专项合同并实机演练 |
| FNB-K04 | 尚缺专项设计 | `fnb_edge_lease`、`fnb_sync_batch`、`fnb_integration_exception` | FOOD-API-084 | 批次认领已有；租约获取/续租/隔离、离线可售额度与冲突处理协议未完整定义，需专项合同并实机演练 |
| FNB-K05 | 依赖共享/核心合同 | `fnb_store_policy` | FOOD-API-081 | 双部署本地远程同合同、开关及版本灰度需装配/CI验证；HTTP接口不能证明可独立部署 |
| FNB-K06 | 依赖共享/核心合同 | `fnb_store_policy` | FOOD-API-081 | 双部署本地远程同合同、开关及版本灰度需装配/CI验证；HTTP接口不能证明可独立部署 |
| FNB-K07 | 依赖共享/核心合同 | `fnb_action_log` | FOOD-API-081、FOOD-API-083、FOOD-API-108 | 沿底座隔离、脱敏、审计和导出权限；平台诊断无营业收退款权 |
| FNB-K08 | 依赖共享/核心合同 | 共享服务/运行合同 | 待专项合同，不假造端点 | 备份恢复告警灾备为运行合同和演练，不能伪造餐饮CRUD接口覆盖 |

### MOD-13 营业配置与权限治理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-A01 | 依赖共享/核心合同 | `fnb_store_policy`、`fnb_action_log` | FOOD-API-001、FOOD-API-081 | 沿现有身份/门店授权及共享审批；金额阈值和权限种子需实施时逐项登记，不能由客户端自报主体或审批人 |
| FNB-A02 | 目标已定义 | `fnb_store_policy`、`fnb_service_calendar`、`fnb_business_day` | FOOD-API-001、FOOD-API-002、FOOD-API-003 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-A03 | 目标已定义 | `fnb_store_policy`、`fnb_config_revision` | FOOD-API-001、FOOD-API-002、FOOD-API-081 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-A05 | 依赖共享/核心合同 | `fnb_store_policy`、`fnb_check_cart` | FOOD-API-001、FOOD-API-002、FOOD-API-038、FOOD-API-041 | 渠道/履约/先后付独立字段；核心需确认堂食和后付可用合同 |
| FNB-A06 | 依赖共享/核心合同 | `fnb_store_policy`、`fnb_service_calendar`、`fnb_sales_restriction` | FOOD-API-002、FOOD-API-003、FOOD-API-022 | 暂停/节日限制可配置；全局结构化营业时间与接单容量依赖共享合同，当前openHours文本不能假定覆盖多时段多时区硬校验 |
| FNB-A07 | 依赖共享/核心合同 | `fnb_store_policy`、`fnb_action_log` | FOOD-API-001、FOOD-API-081 | 沿现有身份/门店授权及共享审批；金额阈值和权限种子需实施时逐项登记，不能由客户端自报主体或审批人 |

## 九、增强跨域合同（设计定义，未实现）

字段类型见上方DTO字典。主体、渠道由可信服务身份解析；终端不可自报价格策略授权。

| 权威 | 输入DTO | 输出DTO | 实现状态 | 失败与恢复 |
|---|---|---|---|---|
| pricing / PLATFORM product/quote | ChannelQuoteReq | ChannelQuoteResult | PENDING | BAD_REQUEST/FORBIDDEN/NOT_FOUND/CONFLICT/UNAVAILABLE/UNKNOWN；用原requestKey/operationRef查询UNKNOWN；不可另起业务键重发 |
| inventory / INVENTORY | MaterialConsumptionReq | MaterialConsumptionResult | PENDING | BAD_REQUEST/FORBIDDEN/NOT_FOUND/CONFLICT/UNAVAILABLE/UNKNOWN；用原requestKey/operationRef查询UNKNOWN；不可另起业务键重发 |

Keeta：首批规划渠道，按用户决定暂不接入；不注册真实适配器或同步任务。真实报价和库存合同未验收前，增强交易保持关闭。
