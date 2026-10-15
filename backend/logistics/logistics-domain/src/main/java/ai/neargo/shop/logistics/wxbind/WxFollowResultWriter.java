package ai.neargo.shop.logistics.wxbind;

import ai.neargo.shop.logistics.domain.WaybillProgress;
import ai.neargo.shop.logistics.domain.WaybillSourceIdentity;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.registration.ChannelSubscriptionStore;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import ai.neargo.shop.spi.logistics.TraceResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Writes a returned token and its first status in a short transaction after the HTTP call. */
@Component
public class WxFollowResultWriter {
    private final WaybillMapper waybills;
    private final ShipmentSourcePort source;
    private final ChannelSubscriptionStore subscriptions;
    private final WaybillProgress progress;

    public WxFollowResultWriter(WaybillMapper waybills, ShipmentSourcePort source,
                                ChannelSubscriptionStore subscriptions, WaybillProgress progress) {
        this.waybills = waybills;
        this.source = source;
        this.subscriptions = subscriptions;
        this.progress = progress;
    }

    @Transactional
    public void recordSuccess(LgsWaybill snapshot, String token, Optional<TraceResult> trace) {
        LgsWaybill current = waybills.selectOne(Wrappers.<LgsWaybill>query()
                .eq("id", snapshot.getId()).last("FOR UPDATE"));
        if (current == null || !WaybillSourceIdentity.matches(current,
                source.identityOf(current.getBizRef()).orElse(null))
                || !ChannelSubscriptionStore.identityKey(snapshot).equals(ChannelSubscriptionStore.identityKey(current))
                || !snapshot.getWaybillNo().equals(current.getWaybillNo())) return;
        subscriptions.mark(snapshot.getShipmentNo(), ChannelSubscriptionStore.identityKey(snapshot),
                "wx-follow", "DONE", token, null);
        trace.ifPresent(result -> progress.apply(current, result, "wx-follow", "QUERY",
                LgsWaybill.patch(current.getId()), System.currentTimeMillis()));
    }
}
