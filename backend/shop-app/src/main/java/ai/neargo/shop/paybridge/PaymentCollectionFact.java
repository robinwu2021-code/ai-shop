package ai.neargo.shop.paybridge;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PaymentCollectionFact(
        @Min(1) @Max(1) int schemaVersion,
        @Min(2) @Max(2) int stateVersion,
        @NotBlank @Size(max = 64) @Pattern(regexp = "PAYMENT_SUCCEEDED\\|O[0-9]{1,19}\\|2")
                String eventId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "PAYMENT_SUCCEEDED") String eventType,
        @NotNull @Positive Long payAppId,
        @NotBlank @Pattern(regexp = "CHECKOUT|INTERNAL|BIZ|PARTNER") String sourceType,
        @NotNull @Positive Long sourceId,
        @NotBlank @Size(max = 64) String payOrderNo,
        @NotBlank @Size(max = 64) String outOrderNo,
        @NotBlank @Pattern(regexp = "CHECKOUT_QR|BIZ_COLLECT") String bizType,
        @NotBlank @Size(max = 128) String bizNo,
        @NotNull @Positive Long storeId,
        @NotBlank @Size(max = 8) String market,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @Positive long amountMinor,
        @Positive long paidMinor,
        @NotBlank @Size(max = 64) String paymentNo,
        @NotBlank @Size(max = 128) String outTradeNo,
        @NotBlank @Size(max = 32) String payChannel,
        @NotBlank @Size(max = 128) String channelTradeNo,
        @Positive long succeededAt) {}
