package ai.neargo.shop.promotion.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort.Store;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Public coupon inputs omit issuer, recipients and native source references, which come from trusted owners. */
public final class BrandCouponModels {
    private BrandCouponModels() { }

    @JsonIgnoreProperties("ruleLabel")
    public record SaveCoupon(@NotBlank @Size(max = 100) String name,
                             @NotBlank @Pattern(regexp = "cashCoupon") String type,
                             @NotBlank @Pattern(regexp = "draft") String status,
                             @NotEmpty List<@NotBlank @Size(max = 64) String> storeIds,
                             @NotBlank @Pattern(regexp = "CNY") String currency,
                             @NotNull @Min(2) @Max(2) Integer currencyScale,
                             @NotNull @Positive Long faceValueMinor,
                             @NotNull @PositiveOrZero Long thresholdMinor,
                             @NotNull @Positive Integer validDays,
                             @NotNull @Positive Long limit,
                             @NotNull @Positive Integer perPerson) {
        public SaveCoupon { storeIds = storeIds == null ? null : List.copyOf(storeIds); }
    }

    public record PublishCoupon(@JsonAlias("couponId") @NotBlank String id,
                                @JsonAlias("couponRevision") @NotNull @PositiveOrZero Long expectedRevision,
                                @NotBlank String costRuleVersionNo) { }
    public record PauseCoupon(@JsonAlias("couponId") @NotBlank String id,
                              @JsonAlias("couponRevision") @NotNull @PositiveOrZero Long expectedRevision) { }
    public record PreviewAudience(@JsonAlias("couponId") @NotBlank String id,
                                   @NotBlank @Pattern(regexp = "ALL") String audience) { }
    public record IssueCoupon(@JsonAlias("couponId") @NotBlank String id,
                              @NotBlank @Pattern(regexp = "ALL") String audience,
                              @JsonAlias("previewToken") @NotBlank String audienceSnapshotNo) { }

    /** Native definition and store ownership evidence are immutable even before publication is accepted. */
    public record SourceSnapshot(SaveCoupon coupon, EntitlementDefinition definition, List<Store> stores) {
        public SourceSnapshot { stores = List.copyOf(stores); }
    }

    public record Source(String sourceNo, String tenantNo, String managementNo, String brandNo, String entityNo,
                         String actorNo, String status, String costRuleVersionNo, String nativeTemplateNo,
                         String nativeVersionNo, long nativeRevision, String definitionHash, SourceSnapshot snapshot) {
        public List<String> storeNos() { return snapshot.stores().stream().map(Store::storeNo).toList(); }
    }

    /** An original request has one permanent namespace and never substitutes a new source or audience. */
    public record Intent(String commandNo, String action, String sourceNo, String actorNo, String status,
                         Map<String, Object> request,
                         Map<String, Object> checkpoint, Map<String, Object> response, Instant createdAt) { }

    /** Local completion attaches only remote owner references, never inferred or caller-supplied native identities. */
    public record Completion(String templateNo, String versionNo, long nativeRevision, String sourceStatus) { }
}
