package ai.neargo.commerce.api.subscription;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

public record CoverageInterval(Instant startsAt, Instant endsAt) {}
