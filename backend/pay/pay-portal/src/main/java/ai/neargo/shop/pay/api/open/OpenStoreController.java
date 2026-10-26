package ai.neargo.shop.pay.api.open;

import static ai.neargo.shop.pay.api.open.OpenPaymentController.ok;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.route.RouteSelectionPolicy;
import ai.neargo.shop.pay.api.auth.PortalPrincipal;
import ai.neargo.shop.pay.api.common.PortalResponse;
import ai.neargo.shop.pay.api.open.OpenViews.PayMethodView;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService.StoreRef;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import ai.neargo.shop.pay.openapp.service.AppAuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 开放 API · 门店可用支付方式（接口清单 §2.1 O08）：门店绑定的收款路由 × 启用的支付产品，同一方式只给优先级最高的那条 */
@RestController
@RequestMapping("/open/pay/v1")
public class OpenStoreController {

    private final AppAuthService apps;
    private final PayMerchantBindingService stores;
    private final RouteSelectionPolicy routes;

    public OpenStoreController(AppAuthService apps, PayMerchantBindingService stores, RouteSelectionPolicy routes) {
        this.apps = apps;
        this.stores = stores;
        this.routes = routes;
    }

    @GetMapping("/stores/{storeRef}/pay-methods")
    public PortalResponse<List<PayMethodView>> payMethods(PortalPrincipal who, @PathVariable String storeRef,
                                                          @RequestParam(required = false) String market,
                                                          HttpServletRequest http) {
        PayApp app = apps.requireActive(who.subjectId());
        apps.requireScope(app, "pay:read");
        PayCaller caller = apps.callerFor(app, "READ");
        StoreRef store = stores.storeByNo(storeRef)
                .filter(s -> caller.storeScope().contains(s.storeId()))
                .orElseThrow(() -> BizException.of(ErrorCode.PAY_FORBIDDEN));
        String m = market == null || market.isBlank() ? store.market() : market;
        Map<String, PayMethodView> byMethod = new LinkedHashMap<>();
        for (RouteSelectionPolicy.Selected s : routes.available(store.storeId(), m)) {
            byMethod.putIfAbsent(s.product().getPayMethod(), new PayMethodView(s.product().getPayMethod(),
                    s.route().payChannel(), s.route().accessMode()));
        }
        return ok(List.copyOf(byMethod.values()), http);
    }
}
