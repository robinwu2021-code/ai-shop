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

    static final List<HostRoute> ALL = List.of(
            // 商品：列表、编辑、草稿与发布、版本、库存与本店价、识别与规格
            HostRoute.of("GET,POST,PUT", "/biz/goods(?:/.*)?"),
            HostRoute.of("GET,POST", "/biz/sku-identity(?:/.*)?"),
            HostRoute.of("GET,POST", "/biz/(?:spec-dims|spec-props|spec-values|spec-templates|spec-override|my-spec-dims)(?:/.*)?"),
            HostRoute.of("GET", "/biz/(?:spu-std|pickable-props|category/tree|freight-template/list)"),
            // 订单与售后
            HostRoute.of("GET,POST", "/biz/order(?:/.*)?"),
            HostRoute.of("GET,POST", "/biz/after-sale(?:/.*)?"),
            // 收款（「收款」Tab、收款码、退款、代客收款）
            HostRoute.of("GET,POST,PUT,DELETE", "/biz/payment(?:/.*)?"),
            // 商户主体（「我的门店 › 商户」）
            HostRoute.of("GET,POST,PUT", "/biz/entities(?:/.*)?"),
            HostRoute.of("GET", "/biz/entity/[A-Za-z0-9_-]{1,64}"),
            // 本店的分类、发货设置、运费模板、线上售卖规则、库存同步
            HostRoute.of("GET,POST,PUT", STORE + "(?:categories|ship-setting|freight-template|sell-rules|stock-sync)"),
            // 本店开通收款：把收款商户挂到这家店
            HostRoute.of("POST", STORE + "payment"),
            // 店员与角色（「我的 › 店员管理」）：权限码由控制器判，这里只放进门店上下文
            HostRoute.of("GET,POST", "/biz/staff(?:/logs|/[A-Za-z0-9_-]{1,64}/(?:status|store))?"),
            HostRoute.of("GET,POST", "/biz/(?:roles|role-perms)"),
            HostRoute.of("POST", "/biz/role/[A-Za-z0-9_.:-]{1,64}(?:/delete)?"),
            // 管理空间（多门店授权）
            HostRoute.of("GET", "/biz/management/context"),
            HostRoute.of("GET,POST,DELETE", "/biz/management/spaces(?:/[A-Za-z0-9_-]{1,64}/(?:entities|recipients|resources|stores|grants(?:/[A-Za-z0-9_-]{1,64})?))?"),
            // 证照、协议与收款渠道（标准开店：选了门店之后补证照）
            HostRoute.of("GET,POST", "/biz/qualifications(?:/recognize|/save)?"),
            HostRoute.of("POST", "/biz/merchant/agreement/accept"),
            HostRoute.of("GET", "/biz/merchant/pay-channel"),
            // 申请支付服务（near-pay 平台商户，TDD-运营端申请支付服务 §7）
            HostRoute.of("POST", "/biz/merchant/pay-service/apply"),
            HostRoute.of("GET", "/biz/merchant/pay-service"),
            // 地图选点（门店地址）
            HostRoute.of("GET", "/biz/geo/(?:tips|reverse)"),
            // 首页看板
            HostRoute.of("GET", "/biz/dashboard/(?:todo|stats)"));

    private ShopStoreMemberRoutes() {
    }
}
