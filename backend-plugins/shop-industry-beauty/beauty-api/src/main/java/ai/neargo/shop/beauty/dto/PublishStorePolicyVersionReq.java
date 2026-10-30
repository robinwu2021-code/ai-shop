package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Typed P1 policy command; identity is supplied by the authenticated entry rather than the request. */
public record PublishStorePolicyVersionReq(Long expectedRevision, OffsetDateTime validFrom, Policy policy) {
    public PublishStorePolicyVersionReq {
        if (expectedRevision == null || expectedRevision < 0 || validFrom == null || policy == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        validFrom = validFrom.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    /** Only the approved prepaid single-service mode is accepted in this first slice. */
    public record Policy(Integer bookingAheadDays, CancellationRule cancelRule, Set<String> allowedModes) {
        public Policy {
            if (bookingAheadDays == null || bookingAheadDays < 0 || cancelRule == null || allowedModes == null
                    || !allowedModes.equals(Set.of("PREPAID_SINGLE"))) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            allowedModes = Collections.unmodifiableSet(new TreeSet<>(allowedModes));
        }
    }

    /** Cancellation configuration does not authorize a refund or an asset reversal. */
    public record CancellationRule(Integer freeCancelBeforeMinutes, Boolean allowCustomerCancel) {
        public CancellationRule {
            if (freeCancelBeforeMinutes == null || freeCancelBeforeMinutes < 0 || allowCustomerCancel == null) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
    }
}
