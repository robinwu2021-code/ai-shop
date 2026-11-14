package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Configuration revision and effective revision differ when a future policy has already been published. */
public record StorePolicyVO(String policyNo, String storeNo, long revision, String policyVersionNo,
        long effectiveRevision, OffsetDateTime validFrom, PublishStorePolicyVersionReq.Policy policy) { }
