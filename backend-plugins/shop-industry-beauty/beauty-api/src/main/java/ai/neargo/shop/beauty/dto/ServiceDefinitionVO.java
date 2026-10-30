package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** Historical execution snapshot with the current configuration head revision for the next CAS command. */
public record ServiceDefinitionVO(String definitionNo, String storeNo, String skuNo, long revision,
        String definitionVersionNo, long effectiveRevision, OffsetDateTime publishedAt,
        PublishServiceDefinitionVersionReq.Duration duration,
        List<PublishServiceDefinitionVersionReq.Requirement> requirements, String snapshotHash, List<String> notes) {
    public ServiceDefinitionVO {
        requirements = List.copyOf(requirements);
        notes = List.copyOf(notes);
    }

    /** Original clients without a notes field still receive an immutable empty list. */
    public ServiceDefinitionVO(String definitionNo, String storeNo, String skuNo, long revision,
            String definitionVersionNo, long effectiveRevision, OffsetDateTime publishedAt,
            PublishServiceDefinitionVersionReq.Duration duration,
            List<PublishServiceDefinitionVersionReq.Requirement> requirements, String snapshotHash) {
        this(definitionNo, storeNo, skuNo, revision, definitionVersionNo, effectiveRevision, publishedAt,
                duration, requirements, snapshotHash, List.of());
    }
}
