package ai.neargo.shop.commerce.policy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

class SourceWithdrawalTransportTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void nativeOwnerAndIdentifiersRejectCoercionAndExtraFields() {
        for (var body :
                List.of(
                        "{\"entityNo\":1,\"storeNo\":\"S\",\"owner\":\"FOOD\",\"sourceRef\":\"M\","
                                + "\"previousRevision\":\"R\"}",
                        "{\"entityNo\":\"E\",\"storeNo\":\"S\",\"owner\":0,\"sourceRef\":\"M\","
                                + "\"previousRevision\":\"R\"}",
                        "{\"entityNo\":\"E\",\"storeNo\":\"S\",\"owner\":\"FOOD\",\"sourceRef\":\"M\","
                            + "\"previousRevision\":\"R\",\"allowClear\":true}"))
            assertThatThrownBy(
                            () ->
                                    CatalogSourceTransportPolicy.read(
                                            json,
                                            body.getBytes(StandardCharsets.UTF_8),
                                            ChannelSourceWithdrawalProvider.Query.class))
                    .isInstanceOf(RuntimeException.class);
    }

    @Test
    void withdrawalStateRejectsOrdinalsAndUnrecognizedNativeStatus() {
        for (String state : List.of("0", "\"withdrawn\"", "\"MISSING\"")) {
            var body = "{\"state\":" + state + "}";
            assertThatThrownBy(
                            () ->
                                    CatalogSourceTransportPolicy.read(
                                            json,
                                            body.getBytes(StandardCharsets.UTF_8),
                                            ChannelSourceWithdrawalProvider.Disposition.class))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
