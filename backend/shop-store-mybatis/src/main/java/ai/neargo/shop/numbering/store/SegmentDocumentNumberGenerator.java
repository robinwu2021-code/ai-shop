package ai.neargo.shop.numbering.store;

import ai.neargo.shop.numbering.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Bounded in-process segments; discarded segments are never reused, including after restart. */
public final class SegmentDocumentNumberGenerator implements DocumentNumberGenerator {
    private record Key(DocumentType type,LocalDate date) { }
    private static final class Segment {
        long next, end; boolean exhausted;
        Segment(long next,long end){this.next=next;this.end=end;}
        long take(){long value=next;if(next==end)exhausted=true;else next++;return value;}
    }
    private final JdbcDocumentNumberRepository repository;
    private final DocumentNumberProperties properties;
    private final Map<Key,Segment> segments=new LinkedHashMap<>(32,0.75f,true);
    public SegmentDocumentNumberGenerator(JdbcDocumentNumberRepository repository,DocumentNumberProperties properties) {
        this.repository=Objects.requireNonNull(repository);this.properties=Objects.requireNonNull(properties);
    }
    @Override public ReservedDocumentNumber reserve(DocumentType type,Instant occurredAt) {
        return reserve(type,Objects.requireNonNull(occurredAt).atZone(properties.businessZone()).toLocalDate());
    }
    @Override public synchronized ReservedDocumentNumber reserve(DocumentType type,LocalDate date) {
        repository.requireOwner(type);JdbcDocumentNumberRepository.requireOutsideTransaction();
        // Validate before changing the sequence or cache.
        DocumentNumberFormatter.format(type,date,1);
        Key key=new Key(type,date);Segment segment=segments.get(key);
        if(segment==null || segment.exhausted) {
            long[] range=repository.claim(type,date,properties.segmentSize());
            segment=new Segment(range[0],range[1]);segments.put(key,segment);
            if(segments.size()>256)segments.remove(segments.keySet().iterator().next());
        }
        return new ReservedDocumentNumber(type,date,DocumentNumberFormatter.format(type,date,segment.take()));
    }
    @Override public synchronized List<ReservedDocumentNumber> reserve(DocumentType type,LocalDate date,int count) {
        if(count<1 || count>10000)throw new IllegalArgumentException("Reservation batch size must be 1..10000");
        var result=new ArrayList<ReservedDocumentNumber>(count);
        for(int i=0;i<count;i++)result.add(reserve(type,date));
        return List.copyOf(result);
    }
}
