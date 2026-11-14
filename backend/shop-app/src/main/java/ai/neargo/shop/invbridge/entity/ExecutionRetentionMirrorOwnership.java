package ai.neargo.shop.invbridge.entity;

import lombok.Getter;
import lombok.Setter;

/** Actual permanent PLATFORM original ownership for legacy rootless ISSUE association. */
@Getter
@Setter
public final class ExecutionRetentionMirrorOwnership {
    private Long id;
    private String tenantNo;
    private String lockNo;
    private String sourceStoreNo;
    private String sourceManifestHash;
    private String confirmationOperationNo;
    private Integer lifecycleVersion;
}
