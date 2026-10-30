package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.policy.ChannelOrderEconomicPolicy;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Customer;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Document;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Fulfillment;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Line;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.PaymentState;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.State;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Totals;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class ChannelOrderEconomicPolicyTest {
    final Instant ordered = Instant.parse("2026-10-29T00:00:00Z");
    final Identity identity = new Identity("EXTERNAL_SKU", List.of());
    final Customer customer = new Customer("CUSTOMER", "Name", "PHONE", "ADDRESS");

    Line line(String quantity, long net, String note) {
        return new Line("L", identity, identity, quantity, "EA", net, 0, 0, net, List.of(), note);
    }

    Document document(
            State state,
            PaymentState paid,
            String revision,
            String hash,
            Instant requested,
            Customer recipient,
            List<Line> lines,
            Totals totals) {
        return new Document(
                1,
                "ACCOUNT",
                "STORE",
                "ORDER",
                revision,
                1L,
                hash,
                "AED",
                state,
                paid,
                paid == PaymentState.PLATFORM_PAID ? "ORIGINAL_PAYMENT" : null,
                Fulfillment.INSTANT_DELIVERY,
                ordered,
                requested,
                recipient,
                lines,
                totals);
    }

    Document original() {
        return document(
                State.WAITING_ACCEPTANCE,
                PaymentState.UNPAID,
                "R1",
                "a".repeat(64),
                null,
                customer,
                List.of(line("1", 100, null)),
                new Totals(0, 0, 0, 0, 100, 100));
    }

    @Test
    void originalBusinessSurvivesPaymentAndAcceptanceRevisionWithoutReplacingTheInvoice() {
        var paid =
                document(
                        State.ACCEPTED,
                        PaymentState.PLATFORM_PAID,
                        "R2",
                        "a".repeat(64),
                        null,
                        customer,
                        original().lines(),
                        original().totals());
        assertThat(ChannelOrderEconomicPolicy.sameBusiness(original(), paid)).isTrue();
        assertThat(ChannelOrderEconomicPolicy.fingerprint(original()))
                .isEqualTo(ChannelOrderEconomicPolicy.fingerprint(paid));
    }

    @Test
    void cancellationCannotBorrowAUnchangedInvoiceAsAcceptanceAuthority() {
        var cancelled =
                document(
                        State.CANCELLED,
                        PaymentState.PLATFORM_PAID,
                        "R2",
                        "a".repeat(64),
                        null,
                        customer,
                        original().lines(),
                        original().totals());
        assertThat(ChannelOrderEconomicPolicy.sameBusiness(original(), cancelled)).isFalse();
        assertThat(ChannelOrderEconomicPolicy.sameBusiness(cancelled, original())).isFalse();
    }

    @Test
    void scheduleRecipientCatalogPhysicalQuantityAmountAndPreparationNotesRemainBusinessChanges() {
        var original = original();
        var totals = original.totals();
        for (var revised :
                List.of(
                        document(
                                State.ACCEPTED,
                                PaymentState.PLATFORM_PAID,
                                "R2",
                                "b".repeat(64),
                                null,
                                customer,
                                original.lines(),
                                totals),
                        document(
                                State.ACCEPTED,
                                PaymentState.PLATFORM_PAID,
                                "R2",
                                "a".repeat(64),
                                ordered.plusSeconds(3600),
                                customer,
                                original.lines(),
                                totals),
                        document(
                                State.ACCEPTED,
                                PaymentState.PLATFORM_PAID,
                                "R2",
                                "a".repeat(64),
                                null,
                                new Customer("CUSTOMER", "Name", "PHONE", "OTHER_ADDRESS"),
                                original.lines(),
                                totals),
                        document(
                                State.ACCEPTED,
                                PaymentState.PLATFORM_PAID,
                                "R2",
                                "a".repeat(64),
                                null,
                                customer,
                                List.of(line("2", 100, null)),
                                totals),
                        document(
                                State.ACCEPTED,
                                PaymentState.PLATFORM_PAID,
                                "R2",
                                "a".repeat(64),
                                null,
                                customer,
                                List.of(line("1", 101, null)),
                                new Totals(0, 0, 0, 0, 101, 101)),
                        document(
                                State.ACCEPTED,
                                PaymentState.PLATFORM_PAID,
                                "R2",
                                "a".repeat(64),
                                null,
                                customer,
                                List.of(line("1", 100, "No peanuts")),
                                totals))) {
            assertThat(ChannelOrderEconomicPolicy.sameBusiness(original, revised)).isFalse();
        }
    }
}
