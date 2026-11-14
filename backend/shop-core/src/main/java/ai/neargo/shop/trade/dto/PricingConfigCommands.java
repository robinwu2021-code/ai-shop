package ai.neargo.shop.trade.dto;

import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.Objects;
import ai.neargo.shop.common.pricing.CompilationResult;
import ai.neargo.shop.common.pricing.PricingCapabilities;

/** Internal service contracts: trusted identity/capabilities must be resolved before calling, never from HTTP JSON. */
public final class PricingConfigCommands {
    private PricingConfigCommands() { }

    public record Context(String tenantNo, String actorNo, boolean platformOperator, String entityNo, String storeNo,
            PricingCapabilities capabilities, String capabilityRef, Instant expiresAt,
                    Set<String> verifiedTaxProfiles) {
        public Context {
            identity(tenantNo); identity(actorNo); identity(capabilityRef);
            if (!"MAIN".equals(tenantNo)) throw new IllegalArgumentException("Only existing MAIN tenant is supported");
            if (!platformOperator) { identity(entityNo); identity(storeNo); }
            Objects.requireNonNull(capabilities); Objects.requireNonNull(expiresAt);
            verifiedTaxProfiles = Set.copyOf(verifiedTaxProfiles);
        }
    }
    public record CreateTemplateReq(String industryCode, String templateCode, String name, String definition) { }
    public record DraftSaveReq(long expectedDraftVersion, String content, String templateRevisionNo) { }
    public record ResetReq(long expectedDraftVersion, List<String> paths) {
        public ResetReq {
            if (paths == null || paths.isEmpty() || paths.size() > 64) {
                throw new IllegalArgumentException("Reset paths");
            }
            paths = paths.stream().distinct().sorted().toList();
        }
    }
    public record UpgradeReq(long expectedDraftVersion, String templateRevisionNo) { }
    public record InitializeReq(String templateRevisionNo, String reason, String initialOverride) {
        public InitializeReq(String templateRevisionNo, String reason) { this(templateRevisionNo, reason, "{}"); }
        public InitializeReq { initialOverride = initialOverride == null ? "{}" : initialOverride; }
    }
    public record PublishReq(long expectedDraftVersion, String expectedActiveRevisionNo,
            String previewHash, String reason, String rollbackFromRevisionNo) { }
    public record DraftVO(String no, String industryCode, long draftVersion, String activeRevisionNo,
            String content, String templateRevisionNo) { }
    public record PreviewVO(String previewHash, CompilationResult compilation) { }
    public record RevisionVO(String revisionNo, String parentNo, long revision, String templateRevisionNo,
            String overrideSnapshot, String effectiveSnapshot, String effectiveHash, String previousRevisionNo,
            String rollbackFromRevisionNo) { }

    public static void identity(String value) {
        if (value == null || value.isBlank() || value.length() > 32
                || value.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("Canonical business identity required");
        }
    }
}
