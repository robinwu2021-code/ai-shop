package ai.neargo.shop.numbering.store;

import ai.neargo.shop.numbering.DocumentNumberGenerator;
import ai.neargo.shop.numbering.DocumentSource;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.ReservedDocumentNumber;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit operation-scoped reservations; never performs a hidden sequence refill in a transaction. */
public final class DocumentNumberCoordinator {
    private final JdbcDocumentNumberRepository repository;
    private final DocumentNumberGenerator generator;
    private final TransactionTemplate transactions;
    private final ThreadLocal<Map<DocumentType,Deque<ReservedDocumentNumber>>> scope=new ThreadLocal<>();
    public DocumentNumberCoordinator(JdbcDocumentNumberRepository repository,DocumentNumberGenerator generator,
            PlatformTransactionManager manager) {
        this.repository=Objects.requireNonNull(repository);this.generator=Objects.requireNonNull(generator);
        this.transactions=new TransactionTemplate(Objects.requireNonNull(manager));
    }
    public <T> T withReservation(Map<DocumentType,Integer> counts,Supplier<T> action) {
        Objects.requireNonNull(counts);Objects.requireNonNull(action);
        for(var entry:counts.entrySet()) {
            repository.requireOwner(entry.getKey());
            if(entry.getValue()==null || entry.getValue()<0)
                throw new IllegalArgumentException("Document reservation count must be non-negative");
        }
        // A nested writer participates in the already prepared budget; it must not silently top it up.
        if(scope.get()!=null)return action.get();
        JdbcDocumentNumberRepository.requireOutsideTransaction();
        var prepared=new EnumMap<DocumentType,Deque<ReservedDocumentNumber>>(DocumentType.class);
        Instant date=Instant.now();
        for(var entry:counts.entrySet()) {
            var queue=new ArrayDeque<ReservedDocumentNumber>();
            for(int i=0;i<entry.getValue();i++)queue.addLast(generator.reserve(entry.getKey(),date));
            prepared.put(entry.getKey(),queue);
        }
        scope.set(prepared);
        try { return action.get(); } finally { scope.remove(); }
    }
    public <T> T transaction(Map<DocumentType,Integer> counts,Supplier<T> action) {
        return withReservation(counts,()->transactions.execute(status->action.get()));
    }
    public String bind(DocumentType type,DocumentSource source) {
        repository.requireOwner(type);repository.requireBusinessTransaction();
        var original=repository.findBySource(type,source);
        if(original.isPresent())return original.get();
        return repository.bind(type,source,takeReserved(type));
    }
    /** For new domain keys that adopt the human number itself; no database sequence access here. */
    public ReservedDocumentNumber takeReserved(DocumentType type) {
        repository.requireOwner(type);repository.requireBusinessTransaction();
        var prepared=scope.get();
        var available=prepared==null?null:prepared.get(type);
        if(available==null || available.isEmpty())
            throw new IllegalStateException("Missing pre-transaction document reservation for "+type);
        return available.removeFirst();
    }
    public String bindReserved(DocumentSource source,ReservedDocumentNumber reserved) {
        return repository.bind(reserved.type(),source,reserved);
    }
    public Optional<String> find(DocumentType type,DocumentSource source) { return repository.findBySource(type,source); }
    public Map<DocumentSource,String> findAll(DocumentType type,Collection<DocumentSource> sources) { return repository.findAll(type,sources); }
}
