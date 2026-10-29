package ai.neargo.shop.retail.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.retail.commerce.api.internal.InternalRetailCatalogSourceController;
import ai.neargo.shop.retail.commerce.port.RetailCatalogSourcePortImpl;
import ai.neargo.shop.retail.commerce.port.RetailSourceValidationProviderImpl;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Kind;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class InternalRetailCatalogSourceControllerTest {
    final RetailSourceValidationProviderImpl validation =
            mock(RetailSourceValidationProviderImpl.class);

    @SuppressWarnings("unchecked")
    final ObjectProvider<RetailCatalogSourcePortImpl> catalog = mock(ObjectProvider.class);

    final ObjectMapper json = new ObjectMapper();
    final InternalRetailCatalogSourceController controller =
            new InternalRetailCatalogSourceController(
                    "secret",
                    validation,
                    catalog,
                    json,
                    Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void tokenIsVerifiedBeforeInvalidBodyOrOwnerLookup() {
        assertThat(
                        controller
                                .validate("wrong", "{".getBytes(StandardCharsets.UTF_8))
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        assertThat(controller.capture(null, null).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(validation, catalog);
    }

    @Test
    void missingBodyAndUnknownFieldsAreRejectedBeforeOwner() {
        assertThat(controller.validate("secret", null).getStatusCode().value()).isEqualTo(400);
        var body =
                "{\"entityNo\":\"E\",\"storeNo\":\"S\",\"owner\":\"RETAIL\",\"kind\":\"SELECTION\","
                        + "\"sourceRef\":\"SEL\",\"sourceRevision\":\"R\",\"success\":true}";
        assertThat(
                        controller
                                .validate("secret", body.getBytes(StandardCharsets.UTF_8))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        verifyNoInteractions(validation);
    }

    @Test
    void unavailableCatalogOwnerCannotReturnSuccessfulEmptyCatalog() {
        assertThat(
                        controller
                                .capture("secret", "{}".getBytes(StandardCharsets.UTF_8))
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
    }

    @Test
    void unsupportedOriginalValidationIsPreservedWithoutGrantingProof() {
        when(validation.validate(any()))
                .thenReturn(new Validation(Status.UNSUPPORTED, null, null, null, null, null));
        var source = new Source("E", "S", Owner.RETAIL, Kind.CATEGORY, "CAT", "R");
        var result = controller.validate("secret", json.writeValueAsBytes(source));
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(((Validation) result.getBody()).status()).isEqualTo(Status.UNSUPPORTED);
    }
}
