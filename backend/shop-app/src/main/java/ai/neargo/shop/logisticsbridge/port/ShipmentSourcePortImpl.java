package ai.neargo.shop.logisticsbridge.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.mapper.TradeMappers;
import ai.neargo.shop.trade.port.FulfillmentStatsPortImpl;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 物流登记时取的那一次快照（{@link ShipmentSourcePort}，物流唯一的反向 Port）。
 *
 * <p>住在装配层而不是交易域：付款人 openid 在支付台账里（支付域），交易域不该为了物流去认识支付台账。
 * 拼法沿用 {@code WxWaybillBindService}（批 3 删掉它之后这里是唯一一份）。
 *
 * <p><b>微信支付键三样齐了才给</b>（交易单号 + 付款人 openid）：缺一样微信物流接口都调不通，
 * 给半个只会让物流把这单当成 WX、然后在换 token 时失败。
 */
@Component
public class ShipmentSourcePortImpl implements ShipmentSourcePort {

    private final TradeMappers.SubOrderMapper subOrders;
    private final TradeMappers.OrderMapper orders;
    private final TradeMappers.OrderItemMapper items;
    private final TradeMappers.ShippingUploadMapper uploads;
    private final PaymentLedgerService ledger;

    public ShipmentSourcePortImpl(TradeMappers.SubOrderMapper subOrders, TradeMappers.OrderMapper orders,
                                  TradeMappers.OrderItemMapper items, TradeMappers.ShippingUploadMapper uploads,
                                  PaymentLedgerService ledger) {
        this.subOrders = subOrders;
        this.orders = orders;
        this.items = items;
        this.uploads = uploads;
        this.ledger = ledger;
    }

    @Override
    public Optional<ShipmentSource> sourceOf(String subOrderNo) {
        OrdSubOrder s = DataScopeContext.executeWithoutScope(() -> subOrders.selectOne(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("limit 1")));
        if (s == null) {
            return Optional.empty();
        }
        WxKey wx = wxKeyOf(s.getOrderNo());
        List<GoodsBrief> goods = DataScopeContext.executeWithoutScope(() -> items.selectList(
                        Wrappers.<OrdItem>lambdaQuery().eq(OrdItem::getSubOrderNo, subOrderNo).last("limit 3")))
                .stream().map(i -> new GoodsBrief(i.getTitle(), i.getCover())).toList();
        return Optional.of(new ShipmentSource(subOrderNo, s.getOrderNo(), s.getEntityNo(), s.getStoreNo(),
                s.getExpressCompany(), s.getExpressNo(),
                s.getReceiverName(), s.getReceiverPhone(), FulfillmentStatsPortImpl.regionOf(s.getReceiverAddress()),
                wx, goods,
                // C 端订单详情按子单号查（OrderServiceImpl.detailOf 先认子单）
                "/pages/order/index?orderNo=" + subOrderNo,
                uploadedAtOf(s.getOrderNo())));
    }

    /** 微信发货信息已上传的时刻。上传事件可能先于登记到达（那一次通知会落空），快照里带一份兜住 */
    private Long uploadedAtOf(String orderNo) {
        if (orderNo == null) {
            return null;
        }
        TrdShippingUpload u = DataScopeContext.executeWithoutScope(() -> uploads.selectOne(
                Wrappers.<TrdShippingUpload>lambdaQuery()
                        .eq(TrdShippingUpload::getOrderNo, orderNo).last("limit 1")));
        if (u == null || !TrdShippingUpload.SUCCESS.equals(u.getStatus())) {
            return null;
        }
        return u.getUploadedAt() == null ? System.currentTimeMillis()
                : u.getUploadedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private WxKey wxKeyOf(String orderNo) {
        if (orderNo == null) {
            return null;
        }
        OrdOrder o = DataScopeContext.executeWithoutScope(() -> orders.selectOne(
                Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getOrderNo, orderNo).last("limit 1")));
        String transId = o == null ? null : o.getPayTradeNo();
        Optional<PaymentLedgerService.PaidPayment> paid = ledger.paidPayment(orderNo);
        String openid = paid.map(PaymentLedgerService.PaidPayment::payerOpenid).orElse(null);
        if (blank(transId) || blank(openid)) {
            return null;   // 线下付款 / APP 单：SELF
        }
        return new WxKey(transId, paid.map(PaymentLedgerService.PaidPayment::outTradeNo).orElse(orderNo), openid);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
