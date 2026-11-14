package ai.neargo.shop.paybridge;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("shop.payment-collections")
public class PaymentCollectionProperties {
    private boolean enabled;

    @Pattern(regexp = "SANDBOX")
    private String environment = "SANDBOX";

    @NotBlank
    @Size(min = 32)
    private String secret;

    @NotBlank
    @Size(min = 32)
    private String cursorSecret;

    @Min(100)
    private long pollMs = 1000;

    @Min(1000)
    private long heartbeatMs = 15000;

    @Min(1000)
    private long retentionMs = 604800000;

    @Min(2)
    @Max(1024)
    private int sendQueueCapacity = 64;

    @Min(100)
    private long writeTimeoutMs = 5000;
}
