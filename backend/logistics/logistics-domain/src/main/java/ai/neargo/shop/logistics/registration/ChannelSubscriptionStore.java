package ai.neargo.shop.logistics.registration;

import ai.neargo.shop.logistics.entity.LgsChannelSubscription;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.ChannelSubscriptionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Persists each channel independently; a failed channel never changes its peer's row. */
@Component
public class ChannelSubscriptionStore {
    private final ChannelSubscriptionMapper mapper;

    public ChannelSubscriptionStore(ChannelSubscriptionMapper mapper) {
        this.mapper = mapper;
    }

    public static String identityKey(LgsWaybill waybill) {
        if (waybill.getSourceShipmentNo() != null && waybill.getSourceShipmentVersion() != null) {
            return waybill.getSourceShipmentNo() + ":" + waybill.getSourceShipmentVersion();
        }
        return "LEGACY:" + waybill.getCarrier() + ":" + waybill.getWaybillNo();
    }

    public LgsChannelSubscription find(String shipmentNo, String identityKey, String channel) {
        return mapper.selectOne(Wrappers.<LgsChannelSubscription>query()
                .eq("shipment_no", shipmentNo).eq("identity_key", identityKey)
                .eq("channel", channel).last("LIMIT 1"));
    }

    public LgsChannelSubscription ensurePending(LgsWaybill waybill, String channel) {
        String identity = identityKey(waybill);
        LgsChannelSubscription existing = find(waybill.getShipmentNo(), identity, channel);
        if (existing != null) return existing;
        LgsChannelSubscription row = new LgsChannelSubscription();
        row.setShipmentNo(waybill.getShipmentNo());
        row.setIdentityKey(identity);
        row.setSourceShipmentNo(waybill.getSourceShipmentNo());
        row.setSourceShipmentVersion(waybill.getSourceShipmentVersion());
        row.setCarrier(waybill.getCarrier());
        row.setWaybillNo(waybill.getWaybillNo());
        row.setChannel(channel);
        row.setState("PENDING");
        row.setAttempts(0);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(row.getCreatedAt());
        try {
            mapper.insert(row);
            return row;
        } catch (DuplicateKeyException duplicate) {
            return find(waybill.getShipmentNo(), identity, channel);
        }
    }

    public void mark(String shipmentNo, String identityKey, String channel,
                     String state, String ref, String error) {
        LgsChannelSubscription row = find(shipmentNo, identityKey, channel);
        if (row == null) throw new IllegalStateException("Missing channel subscription: " + shipmentNo + "/" + channel);
        Long retryAt = null;
        if ("WAITING".equals(state)) retryAt = System.currentTimeMillis() + 600_000L;
        else if ("RETRYABLE".equals(state)) retryAt = System.currentTimeMillis() + 10_000L;
        mapper.update(null, Wrappers.<LgsChannelSubscription>update().eq("id", row.getId())
                .eq("identity_key", identityKey).eq("channel", channel)
                .set("state", state).set("ref", ref).set("error", error)
                .set("next_retry_at", retryAt).set("updated_at", LocalDateTime.now()));
    }

    /** At most one external request per channel and identity during the short lease. */
    public boolean claim(String shipmentNo, String identityKey, String channel) {
        LocalDateTime now = LocalDateTime.now();
        long leaseExpired = System.currentTimeMillis() - 60_000L;
        return mapper.update(null, Wrappers.<LgsChannelSubscription>update()
                .eq("shipment_no", shipmentNo).eq("identity_key", identityKey).eq("channel", channel)
                .and(w -> w.nested(ready -> ready.in("state", "PENDING", "RETRYABLE", "WAITING")
                                .and(due -> due.isNull("next_retry_at").or().le("next_retry_at", System.currentTimeMillis())))
                        .or(x -> x.eq("state", "ATTEMPTING")
                                .and(y -> y.isNull("last_attempt_at").or().lt("last_attempt_at", leaseExpired))))
                .set("state", "ATTEMPTING").set("last_attempt_at", System.currentTimeMillis())
                .setSql("attempts = attempts + 1").set("updated_at", now)) == 1;
    }
}
