package ai.neargo.shop.commerce.policy;

import static ai.neargo.shop.commerce.policy.CatalogDocumentPolicy.require;

import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;

/**
 * V3 windows are finite per source; neither missing sources nor empty windows grant permanent
 * availability.
 */
public final class CatalogAvailabilityPolicy {
    private CatalogAvailabilityPolicy() {}

    /**
     * Fresh admission allows a bounded midnight crossing while preserving the original capture
     * horizon.
     */
    public static void validate(Document document, Instant now) {
        var availability = document.availability();
        require(
                availability != null
                        && availability.horizonStart() != null
                        && availability.horizonEnd() != null);
        var start = availability.horizonStart();
        require(
                start.equals(start.truncatedTo(ChronoUnit.DAYS))
                        && availability.horizonEnd().equals(start.plus(7, ChronoUnit.DAYS))
                        && !start.isBefore(now.minusSeconds(30).truncatedTo(ChronoUnit.DAYS))
                        && !start.isAfter(now.truncatedTo(ChronoUnit.DAYS))
                        && !document.validUntil().isAfter(now.plusSeconds(30)));
        require(availability.sources() != null && availability.sources().size() <= 100);
        var expected = new HashSet<String>();
        document.sources()
                .forEach(
                        s -> {
                            require(
                                    s.priority() >= 0
                                            && s.priority() <= 10000
                                            && !s.proof()
                                                    .validUntil()
                                                    .isAfter(now.plusSeconds(30)));
                            expected.add(key(s.sourceRef(), s.selectionRevision()));
                        });
        var observed = new HashSet<String>();
        for (var source : availability.sources()) {
            require(
                    source != null
                            && observed.add(key(source.sourceRef(), source.selectionRevision()))
                            && source.effectiveWindows() != null
                            && source.effectiveWindows().size() <= 4096);
            Instant previous = null;
            for (var window : source.effectiveWindows()) {
                require(
                        window != null
                                && window.fromInclusive() != null
                                && window.toExclusive() != null
                                && window.fromInclusive().isBefore(window.toExclusive())
                                && !window.fromInclusive().isBefore(start)
                                && !window.toExclusive().isAfter(availability.horizonEnd())
                                && (previous == null || window.fromInclusive().isAfter(previous)));
                previous = window.toExclusive();
            }
        }
        require(observed.equals(expected));
    }

    private static String key(String ref, String revision) {
        CommerceValidation.text(ref, 128);
        CommerceValidation.text(revision, 128);
        return ChannelIdentity.part(ref) + ChannelIdentity.part(revision);
    }
}
