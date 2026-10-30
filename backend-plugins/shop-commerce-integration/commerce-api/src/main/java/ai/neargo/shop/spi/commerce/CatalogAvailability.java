package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.CommerceInstantDeserializer;
import ai.neargo.shop.commerce.dto.CommerceTextDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Absolute source-specific availability is distinct from the short original-owner evidence deadline. */
public record CatalogAvailability(
        @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant horizonStart,
        @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant horizonEnd,
        List<SourceWindow> sources) {
    public CatalogAvailability { sources = sources == null ? null : List.copyOf(sources); }
    @JsonAnySetter public void rejectUnknown(String key, Object value) { throw unknown(); }

    /** An empty window list means this source is closed, never an unrestricted weekly schedule. */
    public record SourceWindow(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String selectionRevision,
            List<UtcWindow> effectiveWindows) {
        public SourceWindow { effectiveWindows = effectiveWindows == null ? null : List.copyOf(effectiveWindows); }
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw unknown(); }
    }

    /** Half-open intervals preserve original instant precision, including future policy boundaries. */
    public record UtcWindow(
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant fromInclusive,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant toExclusive) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw unknown(); }
    }

    private static IllegalArgumentException unknown() {
        return new IllegalArgumentException("Unknown catalog availability field");
    }
}
