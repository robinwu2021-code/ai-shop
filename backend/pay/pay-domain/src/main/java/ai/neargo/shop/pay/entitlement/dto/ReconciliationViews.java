package ai.neargo.shop.pay.entitlement.dto;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
public final class ReconciliationViews {
    private ReconciliationViews() { }
    public record CheckRights(LocalDate bizDate,String marketZone,String afterInstanceNo,int limit) {
        public CheckRights {
            if(bizDate==null || marketZone==null || afterInstanceNo==null || afterInstanceNo.length()>64 || limit<1 || limit>200) {
                throw new IllegalArgumentException("Bounded reconciliation scope required");
            }
            ZoneId.of(marketZone);
        }
    }
    /** A page is a current snapshot; bizDate labels the run rather than reconstructing past balances. */
    public record Check(String axis,String currency,LocalDate bizDate,String marketZone,String afterResourceNo,int limit) {
        public Check {
            if(axis==null || !Set.of("ISSUANCE","RIGHTS","HOLD","MONEY").contains(axis)) {
                throw new IllegalArgumentException("Unknown reconciliation axis");
            }
            new CheckRights(bizDate,marketZone,afterResourceNo,limit);
            if("MONEY".equals(axis) ? currency==null || !currency.matches("[A-Z]{3}") : currency!=null) {
                throw new IllegalArgumentException("Currency belongs exclusively to MONEY reconciliation");
            }
        }
    }
    /** Snapshots stay internal; operators receive identifiers and reason codes only. */
    public record DiffView(String diffNo,String reconNo,String entityNo,String resourceNo,String diffKind,String status, String documentNo) {
        public DiffView(String diffNo, String reconNo, String entityNo, String resourceNo, String diffKind, String status) { this(diffNo, reconNo, entityNo, resourceNo, diffKind, status, null); }
        public DiffView withDocumentNo(String documentNo) { return new DiffView(diffNo, reconNo, entityNo, resourceNo, diffKind, status, documentNo); }
 }
    public record RunView(String reconNo,String entityNo,String axis,String status,long checkedCount,long diffCount,String nextCursor,
                          LocalDate bizDate,String marketZone,String currency, String documentNo) {
        public RunView(String reconNo, String entityNo, String axis, String status, long checkedCount, long diffCount, String nextCursor, LocalDate bizDate, String marketZone, String currency) { this(reconNo, entityNo, axis, status, checkedCount, diffCount, nextCursor, bizDate, marketZone, currency, null); }
        public RunView withDocumentNo(String documentNo) { return new RunView(reconNo, entityNo, axis, status, checkedCount, diffCount, nextCursor, bizDate, marketZone, currency, documentNo); }
 }
    public record Units(Long remaining,long reserved,long sequence) { }
}
