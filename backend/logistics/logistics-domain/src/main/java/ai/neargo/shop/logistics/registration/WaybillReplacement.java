package ai.neargo.shop.logistics.registration;

import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.entity.LgsWaybillNode;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillNodeMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.LocalDateTime;

/** Explicitly clears nullable prior-waybill facts while preserving genuine Ops correction audit nodes. */
public final class WaybillReplacement {
    private WaybillReplacement() { }

    public static void reset(WaybillMapper waybills, WaybillNodeMapper nodes, LgsWaybill w, String carrier,
                             String number, String sourceNo, Long generation) {
        boolean wx = LgsWaybill.PROFILE_WX.equals(w.getProfile());
        waybills.update(null, Wrappers.<LgsWaybill>update().eq("id", w.getId())
                .set("carrier", carrier).set("waybill_no", number)
                .set("source_shipment_no", sourceNo).set("source_shipment_version", generation)
                .set("status", WaybillStatus.CREATED).set("sub_state", LgsWaybill.SUB_PENDING)
                .set("sub_channel", null).set("sub_ref", null).set("sub_attempts", 0).set("sub_error", null)
                .set("kd100_sub_month", null).set("kd100_sub_count", 0)
                .set("bind_state", wx ? LgsWaybill.BIND_WAITING : LgsWaybill.BIND_NA)
                .set("bind_error", null).set("display_token", null).set("wx_status_checked_at", null)
                .set("picked_up_at", null).set("signed_at", null).set("at_locker", 0)
                .set("carrier_corrected_from", null).set("last_event_at", null).set("wx_uploaded_at", null)
                .set("updated_at", LocalDateTime.now()));
        nodes.delete(Wrappers.<LgsWaybillNode>query().eq("shipment_no", w.getShipmentNo()).ne("mode", "OPS"));
    }
}
