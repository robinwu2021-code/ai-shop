package ai.neargo.shop.numbering.store;
import java.time.ZoneId;
import java.util.Objects;
public record DocumentNumberProperties(ZoneId businessZone, int segmentSize) {
    public DocumentNumberProperties {
        Objects.requireNonNull(businessZone);
        if(segmentSize<1 || segmentSize>100000)throw new IllegalArgumentException("Segment size must be 1..100000");
    }
}
