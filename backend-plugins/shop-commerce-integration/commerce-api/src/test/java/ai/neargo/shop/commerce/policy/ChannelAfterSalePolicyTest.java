package ai.neargo.shop.commerce.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleFundsPort;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class ChannelAfterSalePolicyTest {
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final ChannelOrderGateway.Query scope =
            new ChannelOrderGateway.Query(
                    "E", "S", "C", "PP", "EA", "ES", "ORDER", "R1", "AUTH", "CREDENTIAL");

    ChannelAfterSaleGateway.Document document(String account, long total) {
        return new ChannelAfterSaleGateway.Document(
                account,
                "ES",
                "ORDER",
                "CASE",
                "R1",
                1L,
                ChannelAfterSaleGateway.Type.REFUND_ONLY,
                ChannelAfterSaleGateway.State.REQUESTED,
                "CNY",
                total,
                List.of(new ChannelAfterSaleGateway.RequestedLine("LINE", "0.5", "KG", 40)),
                now.minusSeconds(60));
    }

    ChannelAfterSaleGateway.Snapshot snapshot(
            ChannelAfterSaleGateway.Document document, Instant until) {
        return new ChannelAfterSaleGateway.Snapshot(
                document, PricingSnapshotHash.of(document), now, until, "SUPPLIER_READ");
    }

    @Test
    void foreignScopeOrUnconservedAmountsCannotBecomeNormalizedCaseEvidence() {
        assertThatThrownBy(
                        () ->
                                ChannelAfterSalePolicy.snapshot(
                                        scope,
                                        snapshot(document("FOREIGN", 40), now.plusSeconds(25)),
                                        clock))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                ChannelAfterSalePolicy.snapshot(
                                        scope,
                                        snapshot(document("EA", 41), now.plusSeconds(25)),
                                        clock))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiredAndExtendedReadReceiptsCannotAuthorizeAChangedCase() {
        for (var until : List.of(now, now.plusSeconds(31)))
            assertThatThrownBy(
                            () ->
                                    ChannelAfterSalePolicy.snapshot(
                                            scope, snapshot(document("EA", 40), until), clock))
                    .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void independentFundsMustBindExactSourceRevisionAndSupplierCaseHash() {
        var query =
                new ChannelAfterSaleFundsPort.Query(
                        "E",
                        "S",
                        "CORE",
                        "CASE",
                        "DECISION",
                        "a".repeat(64),
                        "b".repeat(64),
                        "CNY",
                        40);
        var wrong =
                new ChannelAfterSaleFundsPort.Query(
                        "E",
                        "S",
                        "CORE",
                        "CASE",
                        "OTHER",
                        "a".repeat(64),
                        "b".repeat(64),
                        "CNY",
                        40);
        var facts =
                new ChannelAfterSaleFundsPort.Facts(
                        wrong,
                        ChannelAfterSaleFundsPort.Status.AUTHORIZED,
                        "ORIGINAL_FUNDS_AUTH",
                        now,
                        now.plusSeconds(25),
                        "FUNDS_READ");
        assertThatThrownBy(() -> ChannelAfterSalePolicy.funds(query, facts, clock))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void omittedMoneyAndNumericEnumOrdinalsNeverCoerceIntoSupplierFacts() {
        var json = JsonMapper.builder().findAndAddModules().build();
        var valid = json.writeValueAsString(document("EA", 40));
        assertThat(
                        json.readValue(valid, ChannelAfterSaleGateway.Document.class)
                                .merchantRefundMinor())
                .isEqualTo(40);
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        valid.replace("\"type\":\"REFUND_ONLY\"", "\"type\":0"),
                                        ChannelAfterSaleGateway.Document.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        valid.replace("\"merchantRefundMinor\":40,", ""),
                                        ChannelAfterSaleGateway.Document.class))
                .isInstanceOf(RuntimeException.class);
    }
}
