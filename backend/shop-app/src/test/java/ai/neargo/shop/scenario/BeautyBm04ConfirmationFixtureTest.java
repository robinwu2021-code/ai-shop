package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Keeps confirmation fixture evidence strict without starting deployment owners. */
class BeautyBm04ConfirmationFixtureTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void validConfirmationUsesPreparedQuoteEvidence() {
        var quote =
                json.readTree(
                        """
                        {"quoteNo":"quote-prepared-37","quoteRevision":9876543210,
                         "quoteHash":"prepared-hash-8d7e","operation":{"operationNo":"checkout-73"}}
                        """);

        assertThat(BeautyBm04EmbeddedHttpIT.confirmationBody(quote))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "quoteNo", "quote-prepared-37",
                                "quoteRevision", 9876543210L,
                                "quoteHash", "prepared-hash-8d7e"));
    }

    @Test
    void wrongRealmAssertionRejectsMalformedBodyFailure() {
        JsonNode malformedBody = json.valueToTree(Map.of("code", 10400, "message", "invalid body"));

        assertThatThrownBy(
                        () ->
                                BeautyBm04EmbeddedHttpIT.assertWrongRealmConfirmation(
                                        200, malformedBody))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(
                        () ->
                                BeautyBm04EmbeddedHttpIT.assertWrongRealmConfirmation(
                                        400, malformedBody))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void wrongRealmAssertionRequiresExactAuthorityFailure() {
        JsonNode unauthorized = json.valueToTree(Map.of("code", 10401));
        assertThatCode(
                        () ->
                                BeautyBm04EmbeddedHttpIT.assertWrongRealmConfirmation(
                                        401, unauthorized))
                .doesNotThrowAnyException();

        int[][] rejected = {
            {401, 10402},
            {200, 10401},
            {403, 10403},
            {401, 10403},
            {400, 10401},
            {500, 10500},
            {200, 0}
        };
        for (int[] response : rejected) {
            JsonNode body = json.valueToTree(Map.of("code", response[1]));
            assertThatThrownBy(
                            () ->
                                    BeautyBm04EmbeddedHttpIT.assertWrongRealmConfirmation(
                                            response[0], body))
                    .as(
                            "HTTP %s / code %s is not wrong-realm authority evidence",
                            response[0], response[1])
                    .isInstanceOf(AssertionError.class);
        }
    }
}
