package ai.neargo.shop.numbering;
import java.util.Optional;

/** Binding joins the owning domain transaction. Lookup does not create historical mappings. */
public interface DocumentNumberService {
    String bind(DocumentType type, DocumentSource source, ReservedDocumentNumber reserved);
    Optional<String> findBySource(DocumentType type, DocumentSource source);
    Optional<DocumentSource> resolve(String tenantNo, DocumentType type, String documentNo);
}
