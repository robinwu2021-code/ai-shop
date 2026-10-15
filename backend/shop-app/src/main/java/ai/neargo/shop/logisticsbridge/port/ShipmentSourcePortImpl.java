package ai.neargo.shop.logisticsbridge.port;

import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.mapper.ShipmentMapper;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.command.ShipCommand;
import ai.neargo.shop.trade.order.service.OrderShippingService;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.paybridge.ShippingUploadProgress;
import ai.neargo.shop.paybridge.ShippingUploadSnapshot;
import ai.neargo.shop.paybridge.WxShippingUploadService;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.ObjectMapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import ai.neargo.shop.trade.order.mapper.ShippingUploadMapper;

import ai.neargo.shop.trade.order.mapper.PaymentMapper;

import ai.neargo.shop.trade.order.mapper.OrderItemMapper;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.port.FulfillmentStatsPortImpl;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;

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

    private final StoreOrderMapper subOrders;
    private final PaymentMapper orders;
    private final OrderItemMapper items;
    private final ShippingUploadMapper uploads;
    private final PaymentLedgerService ledger;
    private final ShipmentMapper shipments;
    private final OrderShippingService shipping;
    private final WxShippingUploadService uploadService;
    private final ShippingUploadProgress progress;
    private final StatusCasMapper locks;
    private final ObjectMapper json;

    public ShipmentSourcePortImpl(StoreOrderMapper subOrders, PaymentMapper orders,
                                  OrderItemMapper items, ShippingUploadMapper uploads,
                                  PaymentLedgerService ledger, ShipmentMapper shipments, OrderShippingService shipping,
                                  WxShippingUploadService uploadService, ShippingUploadProgress progress,
                                  StatusCasMapper locks, ObjectMapper json) {
        this.subOrders = subOrders;
        this.orders = orders;
        this.items = items;
        this.uploads = uploads;
        this.ledger = ledger;
        this.shipments = shipments;
        this.shipping = shipping;
        this.uploadService = uploadService;
        this.progress = progress;
        this.locks = locks;
        this.json = json;
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
        List<OrdShipment> live = DataScopeContext.executeWithoutScope(() -> shipments.selectList(
                Wrappers.<OrdShipment>lambdaQuery().eq(OrdShipment::getSubOrderNo, subOrderNo)
                        .ne(OrdShipment::getStatus, OrdShipment.VOIDED).last("LIMIT 2")));
        OrdShipment dispatch = live.size() == 1 ? live.getFirst() : null;
        return Optional.of(new ShipmentSource(subOrderNo, s.getOrderNo(), s.getEntityNo(), s.getStoreNo(),
                s.getExpressCompany(), s.getExpressNo(),
                s.getReceiverName(), s.getReceiverPhone(), FulfillmentStatsPortImpl.regionOf(s.getReceiverAddress()),
                wx, goods,
                // C 端订单详情按子单号查（OrderServiceImpl.detailOf 先认子单）
                "/pages/order/index?orderNo=" + subOrderNo,
                uploadedAtOf(s, dispatch), dispatch == null ? null : dispatch.getShipmentNo(),
                dispatch == null ? null : 0L));
    }

    @Override
    public Optional<ShipmentSource> correctSource(String subOrderNo, String expectedShipmentNo, long generation,
                                                  String carrier, String waybillNo, String reason) {
        if (generation != 0) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        shipping.correctByPlatform(subOrderNo, expectedShipmentNo, new ShipCommand(List.of(), carrier, waybillNo),
                SecurityUtils.currentUserNo(), reason);
        return sourceOf(subOrderNo);
    }

    @Override
    public Optional<ShipmentIdentity> identityOf(String subOrderNo) {
        List<OrdShipment> live = DataScopeContext.executeWithoutScope(() -> shipments.selectList(
                Wrappers.<OrdShipment>lambdaQuery().eq(OrdShipment::getSubOrderNo, subOrderNo)
                        .ne(OrdShipment::getStatus, OrdShipment.VOIDED).last("LIMIT 2")));
        if (live.size() == 1) {
            OrdShipment current = live.getFirst();
            return Optional.of(new ShipmentIdentity(current.getShipmentNo(), 0L,
                    current.getCarrier(), current.getWaybillNo()));
        }
        return Optional.empty();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ShipmentIdentity> lockedIdentityOf(String subOrderNo) {
        OrdSubOrder child = DataScopeContext.executeWithoutScope(() -> locks.lockOrder(subOrderNo));
        return child == null ? Optional.empty() : identityOf(subOrderNo);
    }

    @Override
    public Map<String, UploadState> uploadStatesOf(List<String> subOrderNos) {
        return progress.statesOf(subOrderNos);
    }

    @Override
    @Transactional
    public void retryUpload(String subOrderNo, String expectedSource, long generation) {
        OrdSubOrder child = DataScopeContext.executeWithoutScope(() -> locks.lockOrder(subOrderNo));
        ShipmentIdentity current = identityOf(subOrderNo).orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
        if (child == null || !Objects.equals(expectedSource, current.sourceShipmentNo())
                || !Objects.equals(generation, current.sourceShipmentVersion())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        uploadService.retry(child.getOrderNo());
    }

    private Long uploadedAtOf(OrdSubOrder child, OrdShipment source) {
        TrdShippingUpload row = DataScopeContext.executeWithoutScope(() -> uploads.selectOne(
                Wrappers.<TrdShippingUpload>lambdaQuery().eq(TrdShippingUpload::getOrderNo, child.getOrderNo())
                        .last("LIMIT 1")));
        if (row == null || row.getUploadedAt() == null || !TrdShippingUpload.SUCCESS.equals(row.getStatus())) {
            return null;
        }
        ShippingUploadSnapshot sent = ShippingUploadSnapshot.parse(row.getSourceSnapshot(), json);
        if (source != null && (sent == null || !sent.contains(child.getSubOrderNo(), source.getShipmentNo(), 0L,
                source.getCarrier(), source.getWaybillNo()))) { return null; }
        if (source == null && sent != null && !sent.contains(child.getSubOrderNo(), null, null,
                child.getExpressCompany(), child.getExpressNo())) { return null; }
        return row.getUploadedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
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
