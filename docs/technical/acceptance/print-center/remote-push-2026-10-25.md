# 统一打印分支正常推送记录 · 2026-10-25

状态：推送阻断修复和定向回归完成，等待正常全量推送闸门；未绕过检查、未强制推送。

本次用户明确要求将本地统一分支提交远端，目标为`origin/codex/ai-shop-codex`。打印适配及集中软件验收见[统一合并记录](unified-merge-2026-10-25.md)。推送从隔离工作区执行，不使用原统一工作区未提交的餐饮或异常处理改动。

## 推送检查发现与修复

- 三个新增打印页面未进入界面清单：重新生成ui-catalog.json，303项新鲜度检查通过，提交18252beae。
- 20个储值接口已存在真实商家入口，延期登记过期：删除对应延期行，原孤立接口检查通过，提交7f2a98c98。未扩大历史欠账清单。
- `payment.register/payment.void`是有限后台权限动作，并非翻译键：识别后台权限目录中的结算动作别名，真实翻译调用仍优先检查。15项扫描器回归和实际扫描通过，提交ef4b2c382。
- 验收状态更新后的文档索引重新生成，提交76b39a57a。
- 四项未登记前端守卫失败：补203个DTO源字段说明并生成OpenAPI，修复金额长度、两页首屏空态保护及选中样式；未改变业务权限或原有测试断言。
- 打印运营页面已明确延期交付，没有可标记开关的菜单：开关检查仅承认经过原Handler/公开契约/无真实UI校验的完整延期端点组。部分登记、新端点和真实菜单仍必须登记功能开关。运营全量842项通过。

共享层全量585项通过，剩余4项均在仓库已有守卫基线内；没有新增或扩大known清单。商家端相关80项共享专项、15项结算流程和类型检查通过。前端修复提交ec5398d90。

## 后端原始闸门

在76b39a57a干净副本执行原始`bash scripts/check-head-compiles.sh 76b39a57a`，不设置任何SKIP选项。全部源码和测试源码编译通过，23模块运行2165项，唯一未登记失败为`MemberOperatingScopeLockTest.sameBrandEnrollmentLocksSerializeAcrossDifferentStores`：夹具绑定表缺少业务PREPARING查询需要的binding_no字段。宿主应用因依赖失败未执行，不能宣称全仓验收通过。

已补齐真实夹具字段，并按现有开发规范修复先前已确认的SPI实现位置、品牌服务接口、imports及授权目录证据；原断言和业务授权保留。独立复审核对品牌方法体与原文一致、DTO和旧Bean名称保留，8个定价Port方法委托原事务服务，授权登记有实际检查链支撑。

后续场景回归复现并修复既有快照读写问题：ExecutionScope的零值coreFinanceProtocol因NON_DEFAULT省略，Jackson3默认拒绝读取缺失primitive。采用该字段专用反序列化约定，缺失读为0，输出JSON及既有hash不变；显式null、负数、非整数和溢出仍拒绝，其余primitive保持严格，保留原非负版本及readiness合同。

最终10类54项按每类最新结果全部通过，覆盖原始故障场景、缺失/零值/未来非负版本、快照hash和篡改校验、架构、权限目录、锁并发及品牌/定价行为。详情见`remote-push-backend-results.json`。买家633项、商家341项、两端类型检查及运营842项通过；共享585通过、4项既有登记基线失败。正常全量推送结果以最终Git推送日志和远端分支为准，不以定向结果替代全仓验证。

## 后续全量复核

35ee1c00f正常推送检查完成干净编译，24模块全量2777项发现5项失败：CommonPackageIsolation内部接口包检查，以及CheckoutOffers、ComboActivity和CouponBestConsistency的4条优惠断言。

两个内部Controller按既有包约束移动至portal.internal，仍保留原模块；URL、类名、Bean名、DTO、条件装配和认证方法体均不变，不修改wrapper或断言。Architecture、CommonPackageIsolation和InternalMaterialCatalogApi三类26项通过，见`remote-push-internal-package-results.json`。

优惠失败已精确定位到CampaignDiscountFlowTest最后用例留下的满减800分活动。三个受害测试类独立10项通过，连同来源类执行28项则完整复现原4个失败；仅为原有限前缀清理方法补上AfterEach后，同命令28项全部通过。所有金额断言与生产算法保持不变，见`remote-push-campaign-isolation-results.json`。随后重新执行正常全量推送闸门。

## 订单身份合同与权限目录复核

e8171d925正常闸门在3153项、25模块处停止于D–F批次：权益订单写入打印事实时，新增打印列仅32字符，而V1原有商户、门店、订单和子订单标识允许64字符。V585以追加迁移扩展对应关联列，并同步API/策略校验和生成夹具；模板作用域键容纳前缀，打印自身生成的编号保持原合同。不缩短测试数据，不改旧迁移，不截断或重映射真实身份。

另一个失败来自FunctionPoint守卫仍把迁出ai-hxkey的六个权限常量计作本仓端点，V528已正式撤销对应菜单和授权。移除过期的外部常量注入，保留真实Controller扫描、双向权限断言与既有欠账清单；不重新授予已退役权限。

8类47项定向验收通过，覆盖原权益HTTP/协调失败、权限守卫、64字符订单打印事实重复合并与消费投影、新旧设备UNKNOWN门控、文档策略及数据库验收，见remote-push-owner-width-results.json。MySQL 9.7.2一次性隔离库8组通过，新增校验字段属性保持、64字符身份/门控原样读回及65字符拒绝；仍是打印迁移专项，不代表整仓Flyway或实机验收。

旧e817快照另以failure-ignore仅收集所有后续批次故障，不用其退出码或结果作为验收；最终仍须原始严格推送闸门通过。

复核追加发现V1商家用户编号也允许64字符：V585在尚未发布时补齐原五张规范表的created_by/updated_by、requested_by及revoked_by引用，保留撤销人的utf8mb4_bin排序规则；旧scope与PrintActor接受完整操作人身份。bridge_actor_no是bindDevice显式校验的独立执行器32字符合同，保持原协议。追加原生MySQL验收9组通过，精确读回64字符请求人、创建人、更新人和撤销人；旧迁移hash、原lease和UNKNOWN门控仍保持。

合并远端395ff958c期间的多语言增量后，fc5eb0047正常推送被三份过期生成文件挡下（ui-lib、两份glossary），尚未进入后端。后续统一重新生成；没有上传不完整版本。

## 后续批次的夹具隔离

G–L的GroupOrder商品页团排名失败，由GroupAudit及GroupOrder遗留的OPEN团挤占前三名造成。仅运行两来源/受害类即可重现19项中1失败；按本类精确活动号、团号清理成员、团和活动后，同命令19项全部通过。生产排序与所有断言保持，见remote-push-group-isolation-results.json。

GoodsActivityOnly测试留下4笔成功创建但未支付订单，导致后续InventoryFlow全库对账出现SK0003平台占用4、库存占用0；组合命令已复现。现在逐笔保留本类订单号与原买家token，通过认证取消接口释放自身占用，并在finally恢复自身活动和商品模式，不直接改库存或放宽clean判据。TTL探针改用有效ACTIVE业主，正常900秒必须由缺货拒绝STOCK_SHORT_ITEMS，超长一年仍必须由TTL上限精确拒绝BAD_REQUEST。包含打印64字符操作人链路在内，同6类66项全部通过，见remote-push-actor-inventory-results.json。独立复核确认未修改库存算法、金额、业务授权或原对账断言。
