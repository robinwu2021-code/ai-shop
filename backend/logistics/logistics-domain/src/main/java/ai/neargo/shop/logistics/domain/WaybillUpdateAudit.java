package ai.neargo.shop.logistics.domain;

import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.spi.logistics.LogisticsPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Durable, append-only channel history; never stores receiver phone or raw callback bodies. */
@Component
public class WaybillUpdateAudit {
    private final JdbcTemplate db;

    public WaybillUpdateAudit(JdbcTemplate db) { this.db = db; }

    public void record(LgsWaybill w, String source, String mode, String before, String after,
                       int added, String outcome, String detail, long at) {
        db.update("INSERT INTO lgs_update_log "
                        + "(shipment_no,biz_ref,carrier,waybill_no,source,mode,before_status,after_status,"
                        + "nodes_added,outcome,detail,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                w.getShipmentNo(), w.getBizRef(), w.getCarrier(), w.getWaybillNo(), source, mode,
                before, after, added, outcome, detail, at);
    }

    public List<LogisticsPort.UpdateRecord> recent(String shipmentNo, String waybillNo, int limit) {
        return db.query("SELECT source,mode,before_status,after_status,nodes_added,outcome,detail,occurred_at "
                        + "FROM lgs_update_log WHERE shipment_no=? AND waybill_no=? "
                        + "AND outcome<>'QUERY_STARTED' ORDER BY id DESC LIMIT ?",
                (rs, row) -> new LogisticsPort.UpdateRecord(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6),
                        rs.getString(7), rs.getLong(8)), shipmentNo, waybillNo, limit);
    }

    /** Kuaidi100 limits the same waybill to one live query per 30 minutes and 48 per day. */
    public boolean reserveK100(LgsWaybill w, long now) {
        Long last = db.query("SELECT MAX(occurred_at) FROM lgs_update_log WHERE carrier=? AND waybill_no=? "
                        + "AND source='kuaidi100' AND mode='QUERY' AND outcome='QUERY_STARTED'",
                rs -> {
                    if (!rs.next()) return null;
                    long value = rs.getLong(1);
                    return rs.wasNull() ? null : value;
                }, w.getCarrier(), w.getWaybillNo());
        Integer today = db.queryForObject("SELECT COUNT(*) FROM lgs_update_log WHERE carrier=? AND waybill_no=? "
                        + "AND source='kuaidi100' AND mode='QUERY' AND outcome='QUERY_STARTED' AND occurred_at>=?",
                Integer.class, w.getCarrier(), w.getWaybillNo(), now - 86_400_000L);
        if ((last != null && now - last < 1_800_000L) || (today != null && today >= 48)) {
            record(w, "kuaidi100", "QUERY", w.getStatus(), w.getStatus(), 0,
                    "THROTTLED", "同运单30分钟内最多查询一次、24小时最多48次", now);
            return false;
        }
        record(w, "kuaidi100", "QUERY", w.getStatus(), w.getStatus(), 0,
                "QUERY_STARTED", null, now);
        return true;
    }
}
