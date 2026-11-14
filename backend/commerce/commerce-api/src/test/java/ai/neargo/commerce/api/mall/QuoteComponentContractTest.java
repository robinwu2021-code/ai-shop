package ai.neargo.commerce.api.mall;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;

import org.junit.jupiter.api.Test;

class QuoteComponentContractTest {
    @Test
    void legacyConstructorPreservesGrossAndDerivesExplicitDealFromCreditAndCash() {
        var component =
                new QuoteComponent(
                        "c",
                        ComponentKind.PLAN,
                        new Money("CNY", 151),
                        new Money("CNY", 50),
                        new Money("CNY", 51),
                        30,
                        2);
        assertEquals(151, component.listAmount().minor());
        assertEquals(101, component.dealAmount().minor());
    }

    @Test
    void legacySettlementDerivationRejectsMixedCurrenciesAndOverflow() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new QuoteComponent(
                                "c",
                                ComponentKind.PLAN,
                                new Money("CNY", 100),
                                new Money("CNY", 50),
                                new Money("USD", 50),
                                30,
                                1));
        assertThrows(
                ArithmeticException.class,
                () ->
                        new QuoteComponent(
                                "c",
                                ComponentKind.PLAN,
                                new Money("CNY", Long.MAX_VALUE),
                                new Money("CNY", Long.MAX_VALUE),
                                new Money("CNY", 1),
                                30,
                                1));
    }
}
