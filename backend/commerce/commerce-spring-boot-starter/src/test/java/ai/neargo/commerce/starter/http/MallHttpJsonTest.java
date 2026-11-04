package ai.neargo.commerce.starter.http;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.Money;
import ai.neargo.commerce.api.mall.MallException;

import org.junit.jupiter.api.Test;

import java.util.List;

class MallHttpJsonTest {
    record Prices(List<Money> prices) {}

    @Test
    void instantsUseIsoUtcTextInNewHttpViews() {
        assertEquals(
                "2026-11-04T00:00:00Z",
                new MallHttpJson()
                        .view(java.time.Instant.parse("2026-11-04T00:00:00Z"))
                        .asString());
    }

    @Test
    void exactMoneyBeyondJavascriptSafeIntegerAndLongMaximumRoundTripsAsDecimalString() {
        var json = new MallHttpJson();
        var value =
                new Prices(
                        List.of(
                                new Money("CNY", 9007199254740993L),
                                new Money("TWD", Long.MAX_VALUE)));
        var tree = json.view(value);
        assertTrue(tree.get("prices").get(0).get("minor").isString());
        assertEquals("9007199254740993", tree.get("prices").get(0).get("minor").asString());
        assertEquals(value, json.read(tree.toString(), Prices.class));
    }

    @Test
    void rejectsFractionalOverflowAndUnsafeNumericAmountsWithoutChangingLegacyMapper() {
        var json = new MallHttpJson();
        for (String amount :
                List.of(
                        "1.5",
                        "1.0",
                        "9223372036854775808",
                        "9007199254740993",
                        "\"9223372036854775808\"",
                        "\"1.5\"",
                        "\"01\""))
            assertThrows(
                    MallException.class,
                    () -> json.read("{\"currency\":\"CNY\",\"minor\":" + amount + "}", Money.class),
                    amount);
        assertEquals(
                new Money("CNY", 1), json.read("{\"currency\":\"CNY\",\"minor\":1}", Money.class));
    }
}
