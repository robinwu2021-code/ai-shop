package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.commerce.port.FoodOriginalInvoiceAllocation;
import ai.neargo.shop.food.commerce.port.FoodOriginalInvoiceAllocation.Amount;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Line;

import org.junit.jupiter.api.Test;

import java.util.List;

class FoodOriginalInvoiceAllocationTest {
    Line invoice(long gross, long discount, long tax) {
        return new Line(
                "LINE",
                null,
                null,
                "1.25",
                "KG",
                gross,
                discount,
                tax,
                gross - discount + tax,
                List.of(),
                null);
    }

    @Test
    void originalInvoiceRemainsAuthoritativeWhenSupplierGrossDiffersFromPublishedPrice() {
        var amounts =
                FoodOriginalInvoiceAllocation.allocate(
                        invoice(101, 11, 7),
                        List.of(100L, 30L, 0L),
                        "ACTUAL_PUBLICATION",
                        "a".repeat(64));
        assertThat(amounts).extracting(Amount::gross).containsExactly(78L, 23L, 0L);
        assertThat(amounts).extracting(Amount::discount).containsExactly(8L, 3L, 0L);
        assertThat(amounts).extracting(Amount::tax).containsExactly(5L, 2L, 0L);
        assertThat(amounts).extracting(Amount::net).containsExactly(75L, 22L, 0L);
        assertThat(amounts)
                .extracting(Amount::basisRef)
                .containsOnly(amounts.getFirst().basisRef());
    }

    @Test
    void fullDiscountAndFreeIncludedChildrenNeverCreateNegativeAmountsOrInventTax() {
        var amounts =
                FoodOriginalInvoiceAllocation.allocate(
                        invoice(3, 3, 0),
                        List.of(1L, 1L, 0L),
                        "ACTUAL_PUBLICATION",
                        "a".repeat(64));
        assertThat(amounts).allSatisfy(a -> assertThat(a.net()).isZero());
        assertThat(amounts.stream().mapToLong(Amount::gross).sum()).isEqualTo(3);
        assertThatThrownBy(
                        () ->
                                FoodOriginalInvoiceAllocation.allocate(
                                        invoice(0, 0, 1),
                                        List.of(1L),
                                        "ACTUAL_PUBLICATION",
                                        "a".repeat(64)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void freePublicationCannotExplainPaidInvoiceAndReceiptChangesAllocationEvidence() {
        assertThatThrownBy(
                        () ->
                                FoodOriginalInvoiceAllocation.allocate(
                                        invoice(10, 0, 0),
                                        List.of(0L),
                                        "ACTUAL_PUBLICATION",
                                        "a".repeat(64)))
                .isInstanceOf(IllegalStateException.class);
        var a =
                FoodOriginalInvoiceAllocation.allocate(
                        invoice(1, 0, 0), List.of(1L), "P1", "a".repeat(64));
        var b =
                FoodOriginalInvoiceAllocation.allocate(
                        invoice(1, 0, 0), List.of(1L), "P2", "a".repeat(64));
        assertThat(a.getFirst().basisRef()).isNotEqualTo(b.getFirst().basisRef());
    }
}
