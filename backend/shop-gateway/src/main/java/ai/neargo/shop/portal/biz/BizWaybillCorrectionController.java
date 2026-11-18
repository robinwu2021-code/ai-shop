package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.ExpressCompanies;
import ai.neargo.shop.common.StoreMarkets;
import ai.neargo.shop.spi.logistics.LogisticsAdminPort;
import ai.neargo.shop.spi.logistics.LogisticsPort;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Set;

/**
 * Merchant correction uses the audited source/waybill replacement path.
 *
 * <p>承运商<b>不收紧</b>（TDD-商户App快递公司 E9）：App 的改运单是预填当前承运商的自由文本框，
 * 收紧成 10400 的话，台湾门店与存量单会变成「已发货的订单改不了运单」。所以：不改承运商（空、或与当前相同）
 * 原样放行；改成本市场清单里的码或名称 → 归一成码；清单外的新值照旧写入，只记 WARN ——
 * 写进去的后果与改动前一样（查不到轨迹、报不了微信，后者由 {@code WxShippingUploadResolver} 挡）。
 * WARN 用来看 App 改版后还有没有人在写自由文本，再决定何时收紧。
 */
@Profile("api")
@RestController
public class BizWaybillCorrectionController {
    private static final Logger log = LoggerFactory.getLogger(BizWaybillCorrectionController.class);

    private final MerchantOrderService orders;
    private final LogisticsAdminPort logistics;
    private final LogisticsPort logisticsPort;
    private final StoreMarkets markets;

    public BizWaybillCorrectionController(MerchantOrderService orders, LogisticsAdminPort logistics,
                                          LogisticsPort logisticsPort, StoreMarkets markets) {
        this.orders = orders;
        this.logistics = logistics;
        this.logisticsPort = logisticsPort;
        this.markets = markets;
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
        logistics.changeWaybill(shipment.shipmentNo(), carrierOf(subOrderNo, order, storeNo,
                request.expressCompany()), request.expressNo(), request.reason(), Set.of(merchantNo));
        var sync = logisticsPort.syncAfterShip(subOrderNo);
        return orders.detail(merchantNo, storeNo, subOrderNo)
                .withLogisticsSync(new OrderVO.LogisticsSync(sync.status(), sync.source(),
                        sync.lastEventAt(), sync.pending(), sync.reason()));
    }

    /** E9：返回交给 {@code changeWaybill} 的承运商；空 = 沿用运单原值。市场取订单所属门店，取不到用当前门店 */
    private String carrierOf(String subOrderNo, OrderVO order, String currentStoreNo, String requested) {
        if (requested == null || requested.isBlank()) {
            return requested;
        }
        String value = requested.trim();
        if (value.equals(order.expressCompany())) {
            return value;
        }
        String storeNo = order.store() != null && order.store().storeNo() != null
                ? order.store().storeNo() : currentStoreNo;
        return ExpressCompanies.resolve(markets.of(storeNo), value).orElseGet(() -> {
            log.warn("改运单承运商不在本店清单内，照旧写入：subOrderNo={} storeNo={} carrier={}",
                    subOrderNo, storeNo, value);
            return value;
        });
    }
}
