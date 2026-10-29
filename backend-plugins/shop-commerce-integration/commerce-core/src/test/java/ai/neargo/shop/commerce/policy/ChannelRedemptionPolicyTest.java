package ai.neargo.shop.commerce.policy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelRedemptionGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class ChannelRedemptionPolicyTest {
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final ChannelRedemptionGateway.Scope scope =
            new ChannelRedemptionGateway.Scope(
                    "E", "S", "CON", "MOCK_PROFILE", "ACCOUNT", "STORE", "CR", "AUTH", "vault:ref");

    @Test
    void unknownNeverInventsZeroBalanceAndConfirmedIssuerArithmeticIsExact() {
        var query = new ChannelRedemptionGateway.Verify("OP", scope, "CONTROLLED_HANDLE");
        var certificate =
                new ChannelRedemptionGateway.Certificate(
                        "a".repeat(64),
                        new ChannelResourceIdentityGateway.Identity("SKU", List.of()),
                        null,
                        new BigDecimal("10"),
                        new BigDecimal("2.5"),
                        new BigDecimal("7.5"),
                        "KG",
                        now.plusSeconds(3600),
                        now.plusSeconds(30),
                        "ORIGINAL");
        ChannelRedemptionPolicy.verified(
                query,
                new ChannelRedemptionGateway.Verified(
                        "OP",
                        PricingSnapshotHash.of(query),
                        ChannelRedemptionGateway.Status.CONFIRMED,
                        certificate),
                clock);
        assertThatThrownBy(
                        () ->
                                ChannelRedemptionPolicy.verified(
                                        query,
                                        new ChannelRedemptionGateway.Verified(
                                                "OP",
                                                PricingSnapshotHash.of(query),
                                                ChannelRedemptionGateway.Status.UNKNOWN,
                                                certificate),
                                        clock))
                .isInstanceOf(RuntimeException.class);
        var bad =
                new ChannelRedemptionGateway.Certificate(
                        certificate.certificateIdentityHash(),
                        certificate.productIdentity(),
                        null,
                        new BigDecimal("10"),
                        new BigDecimal("2"),
                        new BigDecimal("7.5"),
                        "KG",
                        certificate.expiresAt(),
                        certificate.validUntil(),
                        "ORIGINAL");
        assertThatThrownBy(
                        () ->
                                ChannelRedemptionPolicy.verified(
                                        query,
                                        new ChannelRedemptionGateway.Verified(
                                                "OP",
                                                PricingSnapshotHash.of(query),
                                                ChannelRedemptionGateway.Status.CONFIRMED,
                                                bad),
                                        clock))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void originalAppliedQuantityCannotBeReplacedWithGlobalCumulativeOrZero() {
        var action =
                new ChannelRedemptionGateway.Action(
                        "OP",
                        scope,
                        "PREVIEW",
                        "VERIFY_OP",
                        "a".repeat(64),
                        "SOURCE_AUTH",
                        "b".repeat(64),
                        new BigDecimal("1.25"),
                        "KG",
                        "ORIGINAL",
                        null);
        ChannelRedemptionPolicy.result(
                action,
                new ChannelRedemptionGateway.Result(
                        "OP",
                        PricingSnapshotHash.of(action),
                        ChannelRedemptionGateway.Status.CONFIRMED,
                        action.certificateIdentityHash(),
                        new BigDecimal("1.25"),
                        new BigDecimal("3.75"),
                        "KG",
                        "ORIGINAL_RECEIPT"));
        assertThatThrownBy(
                        () ->
                                ChannelRedemptionPolicy.result(
                                        action,
                                        new ChannelRedemptionGateway.Result(
                                                "OP",
                                                PricingSnapshotHash.of(action),
                                                ChannelRedemptionGateway.Status.CONFIRMED,
                                                action.certificateIdentityHash(),
                                                BigDecimal.ZERO,
                                                new BigDecimal("3.75"),
                                                "KG",
                                                "ORIGINAL_RECEIPT")))
                .isInstanceOf(RuntimeException.class);
    }
}
