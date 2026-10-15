package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.logistics.LogisticsAdminPort;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Set;

/** Merchant correction uses the audited source/waybill replacement path. */
@Profile("api")
@RestController
public class BizWaybillCorrectionController {
    private final MerchantOrderService orders;
    private final LogisticsAdminPort logistics;

    public BizWaybillCorrectionController(MerchantOrderService orders, LogisticsAdminPort logistics) {
        this.orders = orders;
        this.logistics = logistics;
    }

    public record Correction(String expressNo, String expressCompany, String reason) { }

    @PreAuthorize("@perm.canBiz('" + BizPerms.SHIP + "')")
    @PostMapping("/biz/order/{subOrderNo}/waybill")
    public OrderVO correct(@PathVariable String subOrderNo, @RequestBody Correction request) {
        var ctx = BizContext.current();
        String merchantNo = ctx.requireMerchantNo();
        String storeNo = ctx.currentStoreNo();
        OrderVO order = orders.detail(merchantNo, storeNo, subOrderNo);
        if (!"EXPRESS".equals(order.fulfillment()) || !"FULFILLING".equals(order.status())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var page = logistics.list(new LogisticsAdminPort.ShipmentQuery(null, null, subOrderNo,
                null, null, null, null, Set.of(merchantNo), 1, 20));
        var shipment = page.records().stream()
                .filter(s -> subOrderNo.equals(s.orderNo()) && merchantNo.equals(s.entityNo())
                        && Objects.equals(storeNo, s.storeNo()))
                .findFirst().orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        logistics.changeWaybill(shipment.shipmentNo(), request.expressCompany(), request.expressNo(),
                request.reason(), Set.of(merchantNo));
        return orders.detail(merchantNo, storeNo, subOrderNo);
    }
}
