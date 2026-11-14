package ai.neargo.commerce.api.mall.reporting;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;

/** Internal reporting contracts. Access is issued by the authenticated host, never by an HTTP body. */
public final class PurchaseReporting {
    private PurchaseReporting() {}

    public enum Model { ENTITY, STORE }

    public record Access(String tenantNo, boolean allEntities, Set<String> entityNos) {
        public Access {
            if (tenantNo == null || tenantNo.isBlank()) throw new IllegalArgumentException("tenantNo");
            entityNos = Set.copyOf(Objects.requireNonNull(entityNos, "entityNos"));
        }
        public boolean allows(String entity) { return entity != null && (allEntities || entityNos.contains(entity)); }
    }

    public record Filter(String entityNo, String storeNo, String orderNo, String status,
                         String currency, Long createdFrom, Long createdTo, boolean pendingOnly) {}
    public record Cursor(long createdAt, Model model, String orderNo) {
        public Cursor { Objects.requireNonNull(model); Objects.requireNonNull(orderNo); }
    }
    public record Query(Access access, Filter filter, Cursor after, int limit) {
        public Query {
            Objects.requireNonNull(access);
            Objects.requireNonNull(filter);
            if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit");
        }
    }
    public record Row(Model model, String orderNo, String entityNo, String storeNo,
                      String currency, String payableMinor, String status, String claimStatus,
                      long createdAt) {}

    public static final Comparator<Row> ORDER = Comparator.comparingLong(Row::createdAt).reversed()
            .thenComparing(Row::model).thenComparing(Row::orderNo, Comparator.reverseOrder());
}
