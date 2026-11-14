package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.spi.trade.OfflineReceiptPort.MinorAmountDeserializer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Diagnostic amounts never supply authority to collect or to debit an asset. */
public record PreviewWorkOrderSettlementReq(
        @NotNull @Pattern(regexp = "[a-f0-9]{64}") String expectedFingerprint,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @PositiveOrZero @Max(99_999_999_999L)
        @JsonDeserialize(using = MinorAmountDeserializer.class) Long walletMinor,
        @NotNull @PositiveOrZero @Max(99_999_999_999L)
        @JsonDeserialize(using = MinorAmountDeserializer.class) Long externalMinor,
        @NotNull @Pattern(regexp = "CASH|EXTERNAL_POS|SCAN") String method) {}
