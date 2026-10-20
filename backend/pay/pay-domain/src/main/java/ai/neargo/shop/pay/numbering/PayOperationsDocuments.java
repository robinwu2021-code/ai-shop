package ai.neargo.shop.pay.numbering;

import ai.neargo.shop.numbering.DocumentNumberService;
import ai.neargo.shop.numbering.DocumentSource;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Pay-owned human identifiers. Reads never create mappings or reveal internal ids as display ids. */
@Component
public class PayOperationsDocuments {
    private final DocumentNumberCoordinator numbers;
    @Autowired
    @Qualifier("payDocumentNumbers")
    private DocumentNumberService lookup;
    private final ThreadLocal<Map<DocumentSource,String>> projection = new ThreadLocal<>();
    private final ThreadLocal<DocumentType> projectionType = new ThreadLocal<>();
    public PayOperationsDocuments(@Qualifier("payDocumentNumberCoordinator") DocumentNumberCoordinator numbers) {
        this.numbers = numbers;
    }
    public String bind(DocumentType type, String tenant, String no) {
        return numbers.bind(type, source(type, tenant, no));
    }
    public String find(DocumentType type, String tenant, String no) {
        if (no == null) return null;
        var cached = projection.get();
        if (cached != null && projectionType.get() == type) return cached.get(source(type, tenant, no));
        return numbers.find(type, source(type, tenant, no)).orElse(null);
    }
    /** Resolve first, then let the caller run its unchanged ownership and permission checks. */
    public String internal(DocumentType type, String tenant, String candidate) {
        if (candidate == null || !candidate.matches(type.prefix() + "[0-9]{14,}")) return candidate;
        return lookup.resolve(tenant, type, candidate)
                .filter(source -> "PAY".equals(source.sourceSystem()) && type.name().equals(source.resourceType()))
                .map(DocumentSource::sourceNo).orElse(candidate);
    }
    public Map<String,String> findAll(DocumentType type, String tenant, Collection<String> ids) {
        var sources = ids.stream().filter(Objects::nonNull).distinct()
                .map(id -> source(type, tenant, id)).toList();
        var result = new HashMap<String,String>();
        numbers.findAll(type, sources).forEach((source, number) -> result.put(source.sourceNo(), number));
        return result;
    }
    /** One mapping query for a list, including an explicit null for historical unmapped rows. */
    public <S,T> List<T> project(DocumentType type, List<S> rows,
            Function<S,String> tenant, Function<S,String> id,
            Function<S,T> render) {
        var sources = rows.stream().map(row -> source(type, tenant.apply(row), id.apply(row))).toList();
        var previous = projection.get(); var previousType = projectionType.get();
        projection.set(numbers.findAll(type, sources)); projectionType.set(type);
        try { return rows.stream().map(render).toList(); }
        finally {
            if (previous == null) { projection.remove(); projectionType.remove(); }
            else { projection.set(previous); projectionType.set(previousType); }
        }
    }
    private static DocumentSource source(DocumentType type, String tenant, String no) {
        return new DocumentSource(tenant, "PAY", type.name(), no);
    }
}
