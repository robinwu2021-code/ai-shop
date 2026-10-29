package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.policy.ChannelOrderAllocationPolicy;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Accepted;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Command;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.LineAllocation;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Reference;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class ChannelOrderAllocationPolicyTest {
    Command command() {
        var id = new Identity("ORIGINAL", List.of());
        var selection = new ChannelOrderGateway.Selection("TOPPING", "OPTION", id, "0.5", 20);
        var line =
                new ChannelOrderGateway.Line(
                        "LINE", id, id, "1.25", "KG", 100, 0, 0, 100, List.of(selection), null);
        var document =
                new ChannelOrderGateway.Document(
                        1,
                        "ACCOUNT",
                        "STORE",
                        "EXTERNAL",
                        "R1",
                        1L,
                        "a".repeat(64),
                        "AED",
                        ChannelOrderGateway.State.ACCEPTED,
                        ChannelOrderGateway.PaymentState.PLATFORM_PAID,
                        "PAYMENT",
                        ChannelOrderGateway.Fulfillment.INSTANT_DELIVERY,
                        Instant.parse("2026-10-28T00:00:00Z"),
                        null,
                        null,
                        List.of(line),
                        new ChannelOrderGateway.Totals(0, 0, 0, 0, 100, 100));
        var now = Instant.now();
        return new Command(
                "IMPORT",
                "ENTITY",
                "STORE",
                "CONNECTION",
                "CONNECTION_REVISION",
                "SNAPSHOT",
                new ChannelOrderGateway.Snapshot(
                        document,
                        now,
                        now.plusSeconds(25),
                        "ORIGINAL_PLATFORM",
                        PricingSnapshotHash.of(document)),
                List.of());
    }

    LineAllocation main(long amount, String quantity) {
        return new LineAllocation(
                "LINE",
                null,
                "MAIN",
                "MAIN_COMPONENT",
                "NATIVE_LINE",
                "OPAQUE_CORE_MAIN",
                quantity,
                "KG",
                amount,
                "AED",
                "ORIGINAL_CORE_MAIN_RECEIPT");
    }

    LineAllocation topping(long amount, String quantity) {
        return new LineAllocation(
                "TOPPING",
                "LINE",
                "OPTION",
                "TOPPING_COMPONENT",
                "NATIVE_LINE",
                "OPAQUE_CORE_TOPPING",
                quantity,
                "EA",
                amount,
                "AED",
                "ORIGINAL_CORE_TOPPING_RECEIPT");
    }

    Accepted accepted(Command command, List<LineAllocation> allocations) {
        return new Accepted(
                new Reference(
                        command.importNo(),
                        command.entityNo(),
                        command.storeNo(),
                        command.original().documentHash()),
                "CORE_ORDER",
                "NATIVE_BUSINESS_DOCUMENT",
                "ORIGINAL_ACCEPTANCE",
                allocations);
    }

    @Test
    void exactOriginalPhysicalQuantitiesAndAmountsConserveWithoutRepricing() {
        var command = command();
        var accepted = accepted(command, List.of(main(80, "1.25"), topping(20, "0.625")));
        assertThatCode(() -> ChannelOrderAllocationPolicy.validate(command, accepted))
                .doesNotThrowAnyException();
        // A recipe component can exceed the sale-unit cap after multiplication; storage still uses
        // exact DECIMAL(20,6).
        var multiplied = accepted(command, List.of(main(80, "1.25"), topping(20, "1999998")));
        assertThatCode(() -> ChannelOrderAllocationPolicy.validate(command, multiplied))
                .doesNotThrowAnyException();
    }

    @Test
    void noMainLineMissingMoneyOrDuplicateCoreComponentCanBeConfirmed() {
        var command = command();
        for (var allocations :
                List.of(
                        List.of(topping(100, "0.625")),
                        List.of(main(80, "1.25")),
                        List.of(main(80, "1.24"), topping(20, "0.625")),
                        List.of(main(40, "0.625"), main(40, "0.625"), topping(20, "0.625")))) {
            assertThatThrownBy(
                            () ->
                                    ChannelOrderAllocationPolicy.validate(
                                            command, accepted(command, allocations)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void anotherExternalSelectionOrCurrencyCannotBorrowAValidOwnerReceipt() {
        var command = command();
        var wrongChoice =
                new LineAllocation(
                        "OTHER",
                        "LINE",
                        "OPTION",
                        "COMP",
                        "NATIVE_LINE",
                        "OPAQUE_CORE",
                        "0.625",
                        "EA",
                        20,
                        "AED",
                        "ORIGINAL_RECEIPT");
        var wrongCurrency =
                new LineAllocation(
                        "TOPPING",
                        "LINE",
                        "OPTION",
                        "COMP",
                        "NATIVE_LINE",
                        "OPAQUE_CORE",
                        "0.625",
                        "EA",
                        20,
                        "CNY",
                        "ORIGINAL_RECEIPT");
        for (var wrong : List.of(wrongChoice, wrongCurrency)) {
            assertThatThrownBy(
                            () ->
                                    ChannelOrderAllocationPolicy.validate(
                                            command,
                                            accepted(command, List.of(main(80, "1.25"), wrong))))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void legacyAcceptanceWithoutAllocationsAndAnotherImportIdentityStayUnconfirmed() {
        var command = command();
        var reference = accepted(command, List.of(main(100, "1.25"))).reference();
        assertThatThrownBy(
                        () ->
                                ChannelOrderAllocationPolicy.validate(
                                        command,
                                        new Accepted(reference, "CORE", "BUSINESS", "RECEIPT")))
                .isInstanceOf(IllegalStateException.class);
        var wrong =
                new Accepted(
                        new Reference(
                                "OTHER_IMPORT",
                                reference.entityNo(),
                                reference.storeNo(),
                                reference.documentHash()),
                        "CORE",
                        "BUSINESS",
                        "RECEIPT",
                        List.of(main(100, "1.25")));
        assertThatThrownBy(() -> ChannelOrderAllocationPolicy.validate(command, wrong))
                .isInstanceOf(IllegalStateException.class);
    }
}
