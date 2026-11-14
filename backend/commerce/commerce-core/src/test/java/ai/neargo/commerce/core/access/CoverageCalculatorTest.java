package ai.neargo.commerce.core.access;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.subscription.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class CoverageCalculatorTest {
    Instant at(long ms) {
        return Instant.ofEpochMilli(ms);
    }

    AccessSegment row(long start, Long end) {
        return new AccessSegment("source", "POS", at(start), end == null ? null : at(end));
    }

    @Test
    void gapsAdjacencyAndD0() {
        var rows = List.of(row(5, 10L), row(1, 5L), row(15, null));
        var gap = CoverageCalculator.evaluate(rows, at(10));
        assertTrue(gap.currentCoverage().isEmpty());
        assertEquals(
                new CoverageInterval(at(1), at(10)), gap.mostRecentEndedCoverage().orElseThrow());
        assertEquals(Optional.of(at(15)), gap.nextBoundaryAt());
        assertEquals(gap.nextBoundaryAt(), gap.nextStartAt());
        var permanent = CoverageCalculator.evaluate(rows, at(15));
        assertTrue(permanent.permanent());
        assertNull(permanent.currentCoverage().orElseThrow().endsAt());
        assertTrue(permanent.nextBoundaryAt().isEmpty());
    }

    @Test
    void rejectsMixedCodes() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CoverageCalculator.evaluate(
                                List.of(row(0, 1L), new AccessSegment("x", "OTHER", at(1), at(2))),
                                at(0)));
    }
}
