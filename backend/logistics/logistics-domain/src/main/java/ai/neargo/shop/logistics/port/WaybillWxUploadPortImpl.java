package ai.neargo.shop.logistics.port;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.domain.WaybillSourceIdentity;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.wxbind.WxBindPolicy;
import ai.neargo.shop.logistics.wxbind.WxBindRequested;
import ai.neargo.shop.spi.logistics.WaybillWxUploadPort;
import ai.neargo.shop.spi.trade.WxShippingPort.ShippingItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;

import java.util.List;
import java.util.Objects;

/**
 * 微信发货信息上传成功：把这次上传的那几个包裹对应的运单记成“已上传”，满足条件的再申请换 waybill_token。
 *
 * <p>Uploaded means this exact package was sent, rather than every child sharing the payment.
 * 上传台账与运单同库，上传服务在自己的事务里直接调。
 */
@Component
public class WaybillWxUploadPortImpl implements WaybillWxUploadPort {
    private final WaybillMapper waybills;
    private final OutboxEventBus events;
    private final LogisticsProperties props;
    private ShipmentSourcePort source;

    @Autowired(required = false)
    public void sourcePort(ShipmentSourcePort source) { this.source = source; }

    public WaybillWxUploadPortImpl(WaybillMapper waybills, OutboxEventBus events, LogisticsProperties props) {
        this.waybills = waybills;
        this.events = events;
        this.props = props;
    }

    private boolean currentSource(LgsWaybill waybill) {
        return waybill.getSourceShipmentNo() == null || (source != null
                && source.identityOf(waybill.getBizRef())
                .map(identity -> WaybillSourceIdentity.matches(waybill, identity)).orElse(false));
    }

    @Override
    public void markUploaded(String outTradeNo, long at, List<ShippingItem> sent) {
        if (outTradeNo == null || outTradeNo.isBlank() || at <= 0) { return; }
        for (LgsWaybill waybill : waybills.selectList(Wrappers.<LgsWaybill>query()
                .eq("wx_out_trade_no", outTradeNo))) {
            if (!matches(waybill, sent) || !currentSource(waybill)) { continue; }
            if (waybill.getWxUploadedAt() == null) {
                int changed = waybills.update(null, WaybillSourceIdentity.unchanged(waybill)
                        .isNull("wx_uploaded_at").set("wx_uploaded_at", at));
                if (changed != 1) { continue; }
                waybill.setWxUploadedAt(at);
            }
            if (WxBindPolicy.ready(waybill, props.getWxBind().onShip())) {
                events.publish(new WxBindRequested(waybill.getShipmentNo()));
            }
        }
    }

    private static boolean matches(LgsWaybill waybill, List<ShippingItem> sent) {
        if (sent == null || sent.isEmpty()) { return waybill.getSourceShipmentNo() == null; }
        String carrier = waybill.getCarrierCorrectedFrom() == null
                ? waybill.getCarrier() : waybill.getCarrierCorrectedFrom();
        for (ShippingItem item : sent) {
            if (Objects.equals(waybill.getBizRef(), item.subOrderNo())
                    && Objects.equals(waybill.getSourceShipmentNo(), item.sourceShipmentNo())
                    && Objects.equals(waybill.getSourceShipmentVersion(), item.sourceShipmentVersion())
                    && Objects.equals(waybill.getWaybillNo(), item.trackingNo())
                    && Objects.equals(carrier, item.expressCompany())) { return true; }
        }
        return false;
    }
}
