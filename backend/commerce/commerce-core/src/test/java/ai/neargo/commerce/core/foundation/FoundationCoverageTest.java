package ai.neargo.commerce.core.foundation;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.foundation.FoundationQueryFacade.Interval;
import ai.neargo.commerce.api.subscription.AccessSegment;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class FoundationCoverageTest {
    private static AccessSegment slice(String source, long from, long until) {
        return new AccessSegment(
                source, "END", Instant.ofEpochMilli(from), Instant.ofEpochMilli(until));
    }

    @Test
    void overlapsAndAdjacentSlicesAddCoverageOnceAndKeepTheGapAtTheExclusiveEnd() {
        var slices =
                List.of(
                        slice("paid", 10, 30),
                        slice("grant", 20, 40),
                        slice("paid", 40, 50),
                        slice("grant", 70, 90));
        var before = FoundationQueryService.right("END", "desk", slices, false, false, 25);
        assertEquals(new Interval(10, 50L), before.current());
        assertEquals(List.of(new Interval(70, 90L)), before.future());
        assertEquals(45L, before.remainingMs());
        assertEquals(50L, before.nextBoundaryAt());
        assertEquals(4, before.slices().size());
        var gap = FoundationQueryService.right("END", "desk", slices, false, true, 50);
        assertNull(gap.current());
        assertEquals("NOT_STARTED", gap.commercialStatus());
        assertEquals(70L, gap.nextBoundaryAt());
        assertEquals("WAIT_FOR_START", gap.nextAction());
        var expired = FoundationQueryService.right("END", "desk", slices, false, false, 90);
        assertEquals("EXPIRED", expired.commercialStatus());
        assertEquals("CONTACT_OWNER", expired.nextAction());
        assertEquals(
                "VIEW_SUBSCRIPTION",
                FoundationQueryService.right("END", "desk", slices, false, true, 90).nextAction());
        assertEquals(
                "REVOKED",
                FoundationQueryService.right("END", "desk", slices, true, false, 90)
                        .commercialStatus());
    }
}
