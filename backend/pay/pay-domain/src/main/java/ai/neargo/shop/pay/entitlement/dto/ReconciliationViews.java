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
    public record DiffView(String diffNo,String reconNo,String entityNo,String resourceNo,String diffKind,String status) { }
    public record RunView(String reconNo,String entityNo,String axis,String status,long checkedCount,long diffCount,String nextCursor,
                          LocalDate bizDate,String marketZone,String currency) { }
    public record Units(Long remaining,long reserved,long sequence) { }
}
