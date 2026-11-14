package ai.neargo.shop.numbering;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

public final class DocumentNumberFormatter {
    private static final DateTimeFormatter DATE=DateTimeFormatter.BASIC_ISO_DATE;
    private DocumentNumberFormatter() { }
    public static String format(DocumentType type, LocalDate date, long sequence) {
        Objects.requireNonNull(type); Objects.requireNonNull(date);
        if(sequence<1 || date.getYear()<1 || date.getYear()>9999)
            throw new IllegalArgumentException("Unsupported document date or sequence");
        String number=type.prefix()+DATE.format(date)+String.format(Locale.ROOT,"%0"+type.minimumSequenceWidth()+"d",sequence);
        if(number.length()>32)throw new IllegalArgumentException("Document number exceeds 32 characters");
        return number;
    }
    public static boolean isValid(DocumentType type, String number) {
        if(type==null || number==null || number.length()>32 || !number.startsWith(type.prefix()))return false;
        int start=type.prefix().length();
        if(number.length()<start+8+type.minimumSequenceWidth())return false;
        try {
            String rawDate=number.substring(start,start+8), rawSequence=number.substring(start+8);
            if(!rawDate.matches("[0-9]{8}") || !rawSequence.matches("[0-9]+"))return false;
            LocalDate date=LocalDate.parse(rawDate,DATE);
            return format(type,date,Long.parseLong(rawSequence)).equals(number);
        } catch (RuntimeException invalid) { return false; }
    }
}
