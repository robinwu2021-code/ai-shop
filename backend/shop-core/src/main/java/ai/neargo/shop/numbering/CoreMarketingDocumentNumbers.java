package ai.neargo.shop.numbering;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.numbering.DocumentSource;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** Core-only numbering adapter. Business inserts and bindings share the caller's transaction. */
@Component
public class CoreMarketingDocumentNumbers {
    private final DocumentNumberCoordinator coordinator;
    public CoreMarketingDocumentNumbers(@Qualifier("coreDocumentNumberCoordinator") DocumentNumberCoordinator coordinator) {
        this.coordinator = coordinator;
    }
    public <T> T transaction(Map<DocumentType, Integer> counts, Supplier<T> action) {
        return coordinator.transaction(counts, action);
    }
    public <T> T withReservation(Map<DocumentType, Integer> counts, Supplier<T> action) {
        return coordinator.withReservation(counts, action);
    }
    public <T extends BaseEntity> int bind(DocumentType type, String system, String resource, T row,
            String sourceNo, ToIntFunction<T> insert, BiConsumer<T, String> assign) {
        int changed = insert.applyAsInt(row);
        if (changed > 0) assign.accept(row, coordinator.bind(type, source(row, system, resource, sourceNo)));
        return changed;
    }
    /** Call only with rows returned by the original scoped query. One mapping query per list. */
    public <T extends BaseEntity> List<T> load(DocumentType type, String system, String resource,
            List<T> rows, Function<T, String> key, BiConsumer<T, String> assign) {
        if (rows == null || rows.isEmpty()) return rows;
        var sources = rows.stream().map(row -> source(row, system, resource, key.apply(row))).distinct().toList();
        var numbers = coordinator.findAll(type, sources);
        for (T row : rows) assign.accept(row, numbers.get(source(row, system, resource, key.apply(row))));
        return rows;
    }
    public <T extends BaseEntity> T loadOne(DocumentType type, String system, String resource,
            T row, Function<T, String> key, BiConsumer<T, String> assign) {
        if (row != null) load(type, system, resource, List.of(row), key, assign);
        return row;
    }
    private DocumentSource source(BaseEntity row, String system, String resource, String key) {
        // BaseEntity/AuditMetaObjectHandler currently use MAIN for legacy null tenants.
        return new DocumentSource(row.getTenantNo() == null ? "MAIN" : row.getTenantNo(), system, resource, key);
    }
}
