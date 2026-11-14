package ai.neargo.commerce.api.subscription;

import java.time.Instant;
import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record CoverageView(String entitlementCode, boolean permanent, Optional<CoverageInterval> currentCoverage, Optional<CoverageInterval> mostRecentEndedCoverage, Optional<Instant> nextBoundaryAt, Optional<Instant> nextStartAt) {}
