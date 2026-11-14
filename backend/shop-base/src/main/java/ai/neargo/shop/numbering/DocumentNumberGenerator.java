package ai.neargo.shop.numbering;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

public interface DocumentNumberGenerator {
    ReservedDocumentNumber reserve(DocumentType type, LocalDate businessDate);
    ReservedDocumentNumber reserve(DocumentType type, Instant occurredAt);
    List<ReservedDocumentNumber> reserve(DocumentType type, LocalDate businessDate, int count);
}
