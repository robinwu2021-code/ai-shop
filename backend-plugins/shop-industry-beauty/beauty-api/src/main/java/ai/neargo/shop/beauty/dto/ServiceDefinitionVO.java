package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** Historical execution snapshot with the current configuration head revision for the next CAS command. */
public record ServiceDefinitionVO(String definitionNo, String storeNo, String skuNo, long revision,
        String definitionVersionNo, long effectiveRevision, OffsetDateTime publishedAt,
        PublishServiceDefinitionVersionReq.Duration duration,
        List<PublishServiceDefinitionVersionReq.Requirement> requirements, String snapshotHash) { }
