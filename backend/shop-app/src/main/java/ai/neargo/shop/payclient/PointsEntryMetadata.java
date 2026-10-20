package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.points.admin.PointsInspectionService;
import java.util.HashMap;
import java.util.Map;

/** Metadata for entries already scoped to a permitted holder. Never returns other holders' lines. */
public final class PointsEntryMetadata {
    private final PointsInspectionService inspection;
    private final String typeCode;
    private final Map<String, Value> cache = new HashMap<>();
    private final java.util.function.Function<String,String> authorizeOrder;
    public PointsEntryMetadata(PointsInspectionService inspection, String typeCode) { this(inspection,typeCode,null); }
    public PointsEntryMetadata(PointsInspectionService inspection, String typeCode, java.util.function.Function<String,String> authorizeOrder) {
        this.authorizeOrder = authorizeOrder;
        this.inspection = inspection;
        this.typeCode = typeCode;
    }
    public Value forVoucher(String voucherNo) {
        return cache.computeIfAbsent(voucherNo, key -> {
            PointsInspectionService.TransactionDetail detail;
            try { detail = inspection.transaction(typeCode, key); }
            catch (IllegalArgumentException ex) {
                if ("POINTS_TRANSACTION_DIRECTION_UNSUPPORTED".equals(ex.getMessage()))
                    return new Value(null, null, null);
                throw ex;
            }
            return detail == null ? new Value(null, null, null) : new Value(
                    operation(detail),
                    detail.voucher().bizRef(), order(key));
        });
    }
    private String order(String voucher) {
        if (authorizeOrder == null) return null;
        var sub = inspection.earningOrder(typeCode, voucher);
        return sub == null ? null : authorizeOrder.apply(sub);
    }
    private static String operation(PointsInspectionService.TransactionDetail detail) {
        var ref = detail.voucher().bizRef();
        if (ref != null && ref.startsWith("HOLD:")) return "HOLD";
        if (ref != null && ref.startsWith("FINALIZE:")) return "FINALIZE";
        if (ref != null && (ref.startsWith("RELEASE:") || ref.startsWith("CANCEL:"))) return "RELEASE";
        return detail.direction() == null ? null : detail.direction().name();
    }
    public record Value(String direction, String businessRef, String orderNo) {}
}
