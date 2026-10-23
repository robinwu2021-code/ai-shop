package ai.neargo.shop.spi.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

class CheckoutContractTest {
    @Test
    void rejectsInvalidScopeQuantityAndOperation() {
        assertThatThrownBy(() -> new CheckoutPort.CheckoutScope("OTHER", "M1", "ST1", "actor"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutPort.CheckoutLineReq("line", "sku", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutPort.CheckoutLineReq("line", "sku", 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutPort.OperationRef("OTHER", "op"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutPort.OperationRef("BEAUTY", " op "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateSkuOrClientLineAndMoreThanTwentyLines() {
        assertThatThrownBy(() -> prepare(List.of(line("a", "sku"), line("b", "sku"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prepare(List.of(line("a", "sku"), line("a", "other"))))
                .isInstanceOf(IllegalArgumentException.class);
        var lines = new ArrayList<CheckoutPort.CheckoutLineReq>();
        for (int i = 0; i < 21; i++) {
            lines.add(line("line-" + i, "sku-" + i));
        }
        assertThatThrownBy(() -> prepare(lines)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freezesInputCollectionAndRedactsCredentialsFromDiagnosticText() {
        var lines = new ArrayList<>(List.of(line("line", "sku")));
        var request = prepare(lines);
        lines.clear();
        assertThat(request.lines()).hasSize(1);
        assertThatThrownBy(() -> request.lines().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(request.toString()).doesNotContain("cr1.secret-reference", "secret-request-key");
        var confirmation =
                new CheckoutPort.ConfirmCheckoutReq(
                        new CheckoutPort.OperationRef("BEAUTY", "op"),
                        "CQ" + "a".repeat(32),
                        1L,
                        "a".repeat(64),
                        "confirm-secret");
        assertThat(confirmation.toString()).doesNotContain("confirm-secret");
        var place =
                new CheckoutPort.PlaceCheckoutReq(
                        new CheckoutPort.OperationRef("BEAUTY", "op"),
                        "CC" + "b".repeat(32),
                        "place-secret");
        assertThat(place.toString()).doesNotContain("place-secret");
    }

    private static CheckoutPort.CheckoutLineReq line(String ref, String sku) {
        return new CheckoutPort.CheckoutLineReq(ref, sku, 1);
    }

    private static CheckoutPort.PrepareCheckoutReq prepare(
            List<CheckoutPort.CheckoutLineReq> lines) {
        return new CheckoutPort.PrepareCheckoutReq(
                new CheckoutPort.OperationRef("BEAUTY", "op"),
                "secret-request-key",
                "cr1.secret-reference",
                1L,
                "a".repeat(64),
                lines,
                "ONLINE",
                null,
                Map.of(),
                null);
    }

    @Test
    void sixOwnerMethodsExposeNoPaymentOrDelegatedConsumerWrite() {
        assertThat(
                        Arrays.stream(CheckoutPort.class.getDeclaredMethods())
                                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                                .map(Method::getName)
                                .toList())
                .containsExactlyInAnyOrder(
                        "prepare", "readQuote", "confirm", "place", "findOperation", "source");
        assertThat(
                        Arrays.stream(CheckoutInternalApi.class.getDeclaredMethods())
                                .map(Method::getName)
                                .toList())
                .containsExactlyInAnyOrder("prepare", "readQuote", "findOperation", "source");
    }
}
