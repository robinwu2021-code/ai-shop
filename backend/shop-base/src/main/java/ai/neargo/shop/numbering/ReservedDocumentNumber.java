package ai.neargo.shop.numbering;
import java.time.LocalDate;
import java.util.Objects;

/** Reserve before the domain transaction; a reservation is not a committed business fact. */
public record ReservedDocumentNumber(DocumentType type, LocalDate businessDate, String value) {
    public ReservedDocumentNumber {
        Objects.requireNonNull(type); Objects.requireNonNull(businessDate);
        if(!DocumentNumberFormatter.isValid(type,value)
                || !value.substring(type.prefix().length(),type.prefix().length()+8)
                    .equals(businessDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)))
            throw new IllegalArgumentException("Number does not match type/date");
    }
}
