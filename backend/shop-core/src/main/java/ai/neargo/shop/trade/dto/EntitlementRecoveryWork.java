package ai.neargo.shop.trade.dto;

import java.util.List;

/** Recovery pointers contain no credential or command snapshots; original coordinators reload durable decisions. */
public final class EntitlementRecoveryWork {
    private EntitlementRecoveryWork() { }
    public enum Kind { RECHARGE, SERVICE_USE, ORDER_USE, ACTIVITY_BENEFIT, LEGACY_COUPON, HISTORICAL_COUPON_RETURN }
    public record Row(long cursorId,String entityNo,String actorNo,String resourceNo) { }
    public record ScanPage(List<Row> records,long nextId) {
        public ScanPage { records=List.copyOf(records); }
    }
}
