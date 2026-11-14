package ai.neargo.shop.merchant.organization;

import jakarta.validation.constraints.*;
import java.util.Set;

/** Independent operating entities; legal subjects and payment merchants keep their own identities. */
public final class OperatingEntityModels {
    private OperatingEntityModels() {}
    public enum Market { CN, TW, AE }
    public record Create(@NotBlank @Size(max=100) String name, @NotNull Market market,
                         @Size(max=64) String legalSubjectId) {}
    public record View(String operatingEntityId, String name, Market market, String status,
                       long version, String legalSubjectId, Set<String> allowedActions) {}
    public record LegalSubject(String legalSubjectId, String name, Market market, String legalForm,
                               long version, String status, boolean verified) {}
    public record Context(String market, boolean canCreate) {}
}
