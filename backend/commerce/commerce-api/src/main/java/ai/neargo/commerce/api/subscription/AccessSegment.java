package ai.neargo.commerce.api.subscription;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

public record AccessSegment(String sourceNo, String entitlementCode, Instant startsAt, Instant endsAt) {}
