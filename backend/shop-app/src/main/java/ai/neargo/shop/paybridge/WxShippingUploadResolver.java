package ai.neargo.shop.paybridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.ExpressCompanies;
import ai.neargo.shop.common.GoodsDesc;
import ai.neargo.shop.common.WxLogisticsTypes;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

/** Payer comes from the original successful payment; mapped package material comes from its frozen queue revision. */
@Component
public class WxShippingUploadResolver {
    private final StoreOrderMapper subOrders;
    private final OrderItemMapper items;
    private final PaymentLedgerService ledger;
    private final ShippingUploadSources sources;
    private final ObjectMapper json;

    public WxShippingUploadResolver(StoreOrderMapper subOrders, OrderItemMapper items, PaymentLedgerService ledger,
                                    ShippingUploadSources sources, ObjectMapper json) {
        this.subOrders = subOrders;
        this.items = items;
        this.ledger = ledger;
        this.sources = sources;
        this.json = json;
    }

    public Material resolve(TrdShippingUpload row) {
        var paid = ledger.paidPayment(row.getOrderNo());
        if (paid.isEmpty() || !Objects.equals(paid.get().outTradeNo(), row.getOutTradeNo())) {
            return Material.missing("支付流水缺少队列原始支付单成功事实");
        }
        String openid = paid.get().payerOpenid();
        if (blank(openid)) { return Material.missing("支付流水上没有付款人 openid（存量单需人工核对）"); }
        ShippingUploadSnapshot frozen = ShippingUploadSnapshot.parse(row.getSourceSnapshot(), json);
        if (frozen != null) { return mapped(row, frozen, openid); }
        String desc = describe(DataScopeContext.executeWithoutScope(() -> items.selectList(
                Wrappers.<OrdItem>lambdaQuery().eq(OrdItem::getOrderNo, row.getOrderNo()))));
        if (blank(desc)) { return Material.missing("订单没有商品描述（微信 10060008 不许为空）"); }
        if (!WxLogisticsTypes.needsTracking(row.getLogisticsType())) {
            return new Material(desc, null, null, openid, null);
        }
        List<OrdSubOrder> legacy = DataScopeContext.executeWithoutScope(() -> subOrders.selectList(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getOrderNo, row.getOrderNo())));
        OrdSubOrder shipped = legacy.stream().filter(s -> !blank(s.getExpressNo())).findFirst().orElse(null);
        if (shipped == null || !ExpressCompanies.isValid(shipped.getExpressCompany())) {
            return Material.missing("存量快递单缺运单号或可识别的承运商编码");
        }
        return new Material(desc, shipped.getExpressNo(), shipped.getExpressCompany(), openid, null);
    }

    private Material mapped(TrdShippingUpload row, ShippingUploadSnapshot frozen, String openid) {
        if (!Objects.equals(frozen, sources.snapshotOf(row.getOrderNo()))) {
            return Material.missing("发货来源已改变，等待原始发货事件刷新同一支付队列");
        }
        if (frozen.blocker() != null) { return Material.missing(frozen.blocker()); }
        if (frozen.shipments().isEmpty()) { return Material.missing("没有实际已发出的快递包裹"); }
        for (WxShippingPort.ShippingItem item : frozen.shipments()) {
            if (blank(item.itemDesc()) || blank(item.trackingNo())
                    || !ExpressCompanies.isValid(item.expressCompany())) {
                return Material.missing("已发出包裹缺商品描述、运单号或有效承运商");
            }
        }
        WxShippingPort.ShippingItem first = frozen.shipments().getFirst();
        return new Material(first.itemDesc(), first.trackingNo(), first.expressCompany(), openid, null,
                frozen.shipments(), frozen.deliveryMode(), frozen.allDelivered());
    }

    static String describe(List<OrdItem> items) {
        return GoodsDesc.of(items == null ? List.of() : items.stream().map(OrdItem::getTitle).toList(),
                GoodsDesc.SHIPPING_MAX);
    }

    public record Material(String itemDesc, String trackingNo, String expressCompany, String payerOpenid,
                           String missReason, List<WxShippingPort.ShippingItem> shipments,
                           int deliveryMode, boolean allDelivered) {
        public Material(String itemDesc, String trackingNo, String expressCompany, String payerOpenid,
                        String missReason) {
            this(itemDesc, trackingNo, expressCompany, payerOpenid, missReason, List.of(), 1, true);
        }
        static Material missing(String reason) { return new Material(null, null, null, null, reason); }
        public boolean ready() { return missReason == null; }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
