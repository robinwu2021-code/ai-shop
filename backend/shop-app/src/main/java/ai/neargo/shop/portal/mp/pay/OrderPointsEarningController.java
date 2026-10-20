package ai.neargo.shop.portal.mp.pay;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.MerchantOrderService;
import ai.neargo.shop.payclient.OrderPointsEarningViewService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Profile("api")
@RestController
public class OrderPointsEarningController {
    private final OrderService buyers;
    private final MerchantOrderService stores;
    private final OrderPointsEarningViewService views;
    public OrderPointsEarningController(OrderService buyers, MerchantOrderService stores, OrderPointsEarningViewService views) {
        this.buyers=buyers;this.stores=stores;this.views=views;
    }
    @GetMapping("/mp/order/{orderNo}/points-earning")
    public List<OrderPointsEarningViewService.View> buyer(@PathVariable String orderNo) {
        SecurityUtils.requireUser();
        return views.read(buyers.detail(orderNo,"MP"));
    }
    @GetMapping("/biz/order/{orderNo}/points-earning")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public List<OrderPointsEarningViewService.View> store(@PathVariable String orderNo) {
        var ctx=BizContext.current();
        if(ctx.courierOnlyOrderView())throw new org.springframework.security.access.AccessDeniedException("ORDER_VIEW_REQUIRED");
        return views.read(stores.detail(ctx.requireMerchantNo(),ctx.requireStoreNo(),orderNo));
    }
}
