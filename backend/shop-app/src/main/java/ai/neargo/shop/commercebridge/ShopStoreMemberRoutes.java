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
            HostRoute.of("POST", STORE + "payment"));

    private ShopStoreMemberRoutes() {
    }
}
