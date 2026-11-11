package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.starter.security.CommerceRoutePolicy.HostRoute;
import java.util.List;

/**
 * 主机的日常经营接口：登录的是账户，选了门店之后按「令牌 + 该门店成员 + 接口自己的权限码」放行
 * （TDD-账户登录与选店校验）。权限码仍由各控制器的 {@code @PreAuthorize} 判，这里只决定「能不能进门店上下文」。
 *
 * <p><b>为什么逐个登记而不是放开兜底</b>：商城路由策略对未登记的受保护路由默认拒绝（commerce-portable-design §164）。
 * 新模型门店上线之后，没登记的老经营接口全部 70006 —— 新店主建完第一家店，商品、订单、主体、收款一个都打不开。
 * 在这里登记，默认拒绝的规矩不变；新增经营接口时同步登记，测试 {@code StoreScopedDailyRoutesFlowTest} 盯着。
 */
final class ShopStoreMemberRoutes {

    private static final String STORE = "/biz/store/[A-Za-z0-9_-]{1,64}/";
    private static final String STORE_PLURAL = "/biz/stores/[A-Za-z0-9_-]{1,64}/";

    static final List<HostRoute> ALL = List.of(
            // 商品：列表、编辑、草稿与发布、版本、库存与本店价、识别与规格
            HostRoute.of("GET,POST,PUT", "/biz/goods(?:/.*)?"),
            HostRoute.of("GET,POST", "/biz/sku-identity(?:/.*)?"),
            HostRoute.of("GET,POST", "/biz/(?:spec-dims|spec-props|spec-values|spec-templates|spec-override|my-spec-dims)(?:/.*)?"),
            HostRoute.of("GET", "/biz/(?:spu-std|pickable-props|category/tree|freight-template/list)"),
            // 订单与售后
            HostRoute.of("GET,POST", "/biz/order(?:/.*)?"),
            HostRoute.of("GET,POST", "/biz/after-sale(?:/.*)?"),
            // 本店可选快递公司（发货页 / 改运单 / 发货设置的承运商清单，TDD-商户App快递公司 E7）
            HostRoute.of("GET", "/biz/express/companies"),
            // 收款（「收款」Tab、收款码、退款、代客收款）
            HostRoute.of("GET,POST,PUT,DELETE", "/biz/payment(?:/.*)?"),
            // 商户主体（「我的门店 › 商户」）
            HostRoute.of("GET,POST,PUT", "/biz/entities(?:/.*)?"),
            HostRoute.of("GET", "/biz/entity/[A-Za-z0-9_-]{1,64}"),
            // 本店的分类、发货设置、运费模板、线上售卖规则、库存同步
            HostRoute.of("GET,POST,PUT", STORE + "(?:categories|ship-setting|freight-template|sell-rules|stock-sync)"),
            // 本店门店代码（立牌链接 /h5/s/{代码}；2026-11-07 台北早餐 166 上 70006）
            HostRoute.of("POST", STORE + "slug"),
            // 本店开通收款：把收款商户挂到这家店
            HostRoute.of("POST", STORE + "payment"),
            HostRoute.of("GET", STORE + "entity/relink-check"),
            HostRoute.of("POST", STORE + "entity"),
            // 店员与角色（「我的 › 店员管理」）：权限码由控制器判，这里只放进门店上下文
            HostRoute.of("GET,POST", "/biz/staff(?:/logs|/[A-Za-z0-9_-]{1,64}/(?:status|store))?"),
            HostRoute.of("GET,POST", "/biz/(?:roles|role-perms)"),
            HostRoute.of("POST", "/biz/role/[A-Za-z0-9_.:-]{1,64}(?:/delete)?"),
            // P1 管理空间/品牌/组织上下文按 actor + 目标资源授权，精确 ACCOUNT 路由见 CommerceRoutePolicy。
            // 证照、协议与收款渠道（标准开店：选了门店之后补证照）
            HostRoute.of("GET,POST", "/biz/qualifications(?:/recognize|/save)?"),
            HostRoute.of("POST", "/biz/merchant/agreement/accept"),
            HostRoute.of("GET", "/biz/merchant/pay-channel"),
            // 出款账户（App F06 收款账户；按选中门店所属主体取数，权限码 biz:finance 由控制器判）
            HostRoute.of("GET,POST", "/biz/payout-account"),
            // 申请支付服务不在这里：它要主体级收款目标，登记在 CommerceRoutePolicy 的 foundation 通道（同 pay-channel）
            // 地图选点（门店地址）
            HostRoute.of("GET", "/biz/geo/(?:tips|reverse)"),
            // 首页看板
            HostRoute.of("GET", "/biz/dashboard/(?:todo|stats)"),

            // —— App 第二批（2026-11-08 台北早餐 166 上 70006；TDD-账户登录与选店校验 §2 第三步）——
            // 门店资料、公告、分享物料。/biz/store 不按族登记：/{门店}/pay-setting 要业务端权益，整族会绕过它
            HostRoute.of("GET,POST", "/biz/store"),
            HostRoute.of("POST", "/biz/store/(?:announcement|announcement/recent/remove|scope-preview)"),
            HostRoute.of("GET", "/biz/store/(?:share-kit|poster|qrcode)"),
            HostRoute.of("POST", STORE + "(?:rename|status)"),
            // 方案（含试用）
            HostRoute.of("GET", "/biz/plan"),
            HostRoute.of("POST", "/biz/plan/trial"),
            // 评价
            HostRoute.of("GET,POST", "/biz/review(?:/.*)?"),
            // 列印：打印机、任务、模板、场景、监控
            HostRoute.of("GET,POST,PUT", "/biz/print/.+"),
            // 通知设置
            HostRoute.of("GET,PUT", "/biz/notify/(?:setting|sms-phones|email|wecom)"),
            // 自提与自提点
            HostRoute.of("GET,POST", "/biz/pickup(?:/.*)?"),
            HostRoute.of("GET,POST", "/biz/pickup-points(?:/candidates)?"),
            // 进销存，及本店线下销售、库存总览 / 对齐 / 对账（sell-rules、stock-sync 见上）
            HostRoute.of("GET,POST,PUT", "/biz/inventory/.+"),
            // 本地库存预检与补货收货（零售 PLATFORM / 餐饮 FOOD_LOCAL，类级 @PreAuthorize biz:stock，门店范围由控制器复核）
            HostRoute.of("POST", "/biz/stock-control/preflight"),
            HostRoute.of("GET,POST", "/biz/stock-control/(?:replenishments|retail-replenishment)(?:/.*)?"),
            HostRoute.of("GET,POST", STORE + "(?:offline-sale|stock-overview|stock-alignment|stock-reconcile)(?:/.*)?"),
            // 门店级配送方式（App W05 多选；取代主体级 fulfillmentReach 单选。TDD-商户App接口字段对齐「已拍板 2」）
            HostRoute.of("GET,PUT", "/biz/stores/[A-Za-z0-9_-]{1,64}/fulfillment"),
            // 关掉一路前的影响清单（…/fulfillment/{channel}/impact）**刻意不在这里登记**：它走 LEGACY_PREVIEW，
            // 老模型账号的「目标店必须在本人范围内」靠那条通道校验（CommerceAccessSecurityFlowTest#assertLegacyImpact）。
            // 登记成门店成员路由会让老账号跳过它。新模型门店怎么开放另行处理（TDD-商户App接口字段对齐 §6）
            // 餐饮插件（门店功能矩阵开发计划 E3）：242 个商家接口各自 @PreAuthorize 判权、按所选门店取数据范围，
            // 这里只放进门店上下文。按方案拦截（END_FNB）留到 App 接入业务端令牌后改走 END_PROTECTED（TDD-B端门店功能矩阵与方案准入 §4-4）
            HostRoute.of("GET,POST,PUT", "/biz/x/food/.+"),
            // 外卖平台接单 / 拒单、渠道连接与对账（餐饮首批的外送部分）：68 个商家接口同样各自 @PreAuthorize
            HostRoute.of("GET,POST,PUT", "/biz/x/commerce/.+"),
            // 外卖接单策略（含暂停 / 恢复接单的 enabled 开关；类级 @PreAuthorize biz:store:admin）：App 餐饮 14-14
            HostRoute.of("GET,POST", STORE_PLURAL + "channel-order-policies(?:/[A-Za-z0-9_.:-]{1,64}(?:/enabled)?)?"),
            // 业务单收款汇总（TDD-业务单收款汇总）：@PreAuthorize mch:pay:order:view，路径门店须等于所选门店
            HostRoute.of("GET", STORE_PLURAL + "receivables/[A-Za-z0-9_.:-]{1,64}"),
            // 美业插件（开发计划 B3）：26 个商家接口在服务层判权（AppointmentAccess / ReceptionAccess /
            // ServiceSubmissionAccess / ServiceExecutionAccess），另 5 个 @PreAuthorize；逐条审计见开发计划第八节
            HostRoute.of("GET,POST", "/biz/x/beauty/.+"),
            // 服务业共用的人员、资源、周排班（shop-merchant）：各接口 @PreAuthorize biz:store:admin
            HostRoute.of("GET,POST", "/biz/personnel(?:/.*)?"),
            HostRoute.of("GET,POST", "/biz/resources(?:/.*)?"),
            HostRoute.of("GET,POST", "/biz/(?:schedules|schedule-rules)(?:/.*)?"),
            // 预约时段与停约（BizStoreFulfillmentController，@PreAuthorize biz:store）：App 服务业 15-13 停约要用
            HostRoute.of("GET,POST", "/biz/stores/[A-Za-z0-9_-]{1,64}/appointment-slots"),
            HostRoute.of("POST", "/biz/appointment-slots/[A-Za-z0-9_-]{1,64}/close"),
            // 会员、标签、分群、设置、触达
            HostRoute.of("GET,POST,PUT", "/biz/(?:members|member-tags|member-segments|member-reach)(?:/.*)?"),
            HostRoute.of("GET,PUT", "/biz/member-settings"),
            // 优惠券与核销、门店积分
            HostRoute.of("GET,POST,PUT", "/biz/coupons(?:/.*)?"),
            HostRoute.of("GET", "/biz/coupon-issues"),
            HostRoute.of("GET,POST", "/biz/coupon-redeem(?:/.*)?"),
            HostRoute.of("GET,POST", "/biz/points/store-[a-z-]+(?:/[a-z-]+)?"),
            // 团购 / 接龙 / 报价、账期
            HostRoute.of("GET,POST", "/biz/(?:groups|group|group-request|quote|period)(?:/.*)?"),
            // 平台活动与营销活动
            HostRoute.of("GET,POST", "/biz/(?:platform-activity|activities|campaign)(?:/.*)?"),
            HostRoute.of("GET,POST", "/biz/(?:activity-presets|activity-conflicts|marketing-summary|marketing/summary)"));

    private ShopStoreMemberRoutes() {
    }
}
