package ai.neargo.shop.paybridge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Protected deployment coordinates only; no credential or received payment fact is a source grant. */
@Validated
@ConfigurationProperties("shop.payment-collections")
public record PaymentCollectionSourceProperties(@Valid List<@NotNull Binding> sourceBindings) {
    public PaymentCollectionSourceProperties {
        sourceBindings = sourceBindings == null ? List.of() : List.copyOf(sourceBindings);
    }

    public record Binding(
            @NotBlank @Pattern(regexp = "SANDBOX") String environment,
            @Positive long payAppId,
            @NotBlank @Pattern(regexp = "CHECKOUT") String sourceType,
            @Positive long sourceId,
            @Positive long paymentStoreId,
            @Positive long shopStoreId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String storeNo,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entityNo,
            @NotBlank @Pattern(regexp = "TW") String market) {
        @AssertTrue(message = "CHECKOUT sourceId must equal payAppId")
        public boolean isCheckoutIdentityConsistent() {
            return sourceId == payAppId;
        }
    }
}
