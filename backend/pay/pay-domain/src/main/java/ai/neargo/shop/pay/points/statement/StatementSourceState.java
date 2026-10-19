package ai.neargo.shop.pay.points.statement;

import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Normalized statements own the financial phase; a refund preparation still owns its source lock. */
public final class StatementSourceState {
    private StatementSourceState() {}
    public static String effective(Map<String,Object> source,Map<String,Object> head) {
        String raw=(String)source.get("state");
        if (head==null || "REFUND_PREPARED".equals(raw) || "CLOSED".equals(raw) || "RELEASED".equals(raw)) return raw;
        var snapshot=new ObjectMapper().readValue((String)head.get("payload_json"),StoreStatementService.Snapshot.class);
        if (snapshot.sourceBuildNo()==null) return raw;
        return switch (snapshot.state()) {
            case "BUILDING","DRAFT","DISPUTED","CLEARED" -> "SETTLEABLE";
            case "CONFIRMED" -> "PAYABLE";
            case "PAYING" -> "PAYING";
            case "PAID" -> "PAID";
            default -> throw new IllegalStateException("POINTS_STATEMENT_STATE_INVALID");
        };
    }
}
