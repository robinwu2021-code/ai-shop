package ai.neargo.shop.logistics.domain;

import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort.ShipmentIdentity;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.Objects;

/** Captured identity predicates isolate all asynchronous replies from a later correction of the same row. */
public final class WaybillSourceIdentity {
    private WaybillSourceIdentity() { }

    public static UpdateWrapper<LgsWaybill> unchanged(LgsWaybill w) {
        var same = Wrappers.<LgsWaybill>update().eq("id", w.getId()).eq("waybill_no", w.getWaybillNo())
                .eq("carrier", w.getCarrier());
        if (w.getSourceShipmentNo() == null) {
            same.isNull("source_shipment_no");
        } else {
            same.eq("source_shipment_no", w.getSourceShipmentNo())
                    .eq("source_shipment_version", w.getSourceShipmentVersion());
        }
        return same;
    }

    public static boolean matches(LgsWaybill w, ShipmentIdentity s) {
        if (w.getSourceShipmentNo() == null) { return true; }
        String originalCarrier = w.getCarrierCorrectedFrom() == null ? w.getCarrier() : w.getCarrierCorrectedFrom();
        return s != null && Objects.equals(w.getSourceShipmentNo(), s.sourceShipmentNo())
                && Objects.equals(w.getSourceShipmentVersion(), s.sourceShipmentVersion())
                && Objects.equals(w.getWaybillNo(), s.waybillNo()) && Objects.equals(originalCarrier, s.carrier());
    }
}
