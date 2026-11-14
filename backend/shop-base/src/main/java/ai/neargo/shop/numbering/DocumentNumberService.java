package ai.neargo.shop.numbering;
import java.util.Optional;
import java.util.Collection;
import java.util.Map;

/** Binding joins the owning domain transaction. Lookup does not create historical mappings. */
public interface DocumentNumberService {
    String bind(DocumentType type, DocumentSource source, ReservedDocumentNumber reserved);
    Optional<String> findBySource(DocumentType type, DocumentSource source);
    Map<DocumentSource,String> findAll(DocumentType type, Collection<DocumentSource> sources);
    Optional<DocumentSource> resolve(String tenantNo, DocumentType type, String documentNo);
}
