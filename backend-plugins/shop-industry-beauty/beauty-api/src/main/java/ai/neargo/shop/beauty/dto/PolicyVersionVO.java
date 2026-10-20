package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Stable result retained by common idempotency; physical IDs and client keys are not exposed. */
public record PolicyVersionVO(String policyNo, String policyVersionNo, long revision,
                              OffsetDateTime validFrom, String snapshotHash) {
}
