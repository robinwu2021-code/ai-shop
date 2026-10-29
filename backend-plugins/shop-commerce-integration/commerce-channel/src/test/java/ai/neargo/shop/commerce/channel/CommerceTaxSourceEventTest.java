package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.commerce.dto.TaxSourceNoticeContracts;
import ai.neargo.shop.commerce.service.TaxSourceNoticeService;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.spi.trade.PricingTaxSourceChanged;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

class CommerceTaxSourceEventTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final TaxSourceNoticeService notices = mock(TaxSourceNoticeService.class);

    OutboxEnvelope event(String entity, String key, String aggregate) {
        var original = new PricingTaxSourceChanged("MAIN", entity, key, 7, "a".repeat(64), "ACTOR");
        return new OutboxEnvelope(
                "O",
                "PLATFORM",
                "ORIGINAL_TAX_EVENT",
                "PRICING_TAX_SETTING",
                aggregate,
                "PRICING_TAX_SOURCE_CHANGED",
                1,
                "TAX:" + key,
                7,
                json.writeValueAsString(original),
                Instant.now());
    }

    @Test
    void originalPlatformDefaultKeepsItsRealGlobalScopeInsteadOfInventingAMerchant() {
        new CommerceTaxSourceEventConsumer(notices, json)
                .handle(event(null, "pricing.tax.default", "pricing.tax.default"));
        var captured = ArgumentCaptor.forClass(TaxSourceNoticeContracts.Notice.class);
        verify(notices).receive(captured.capture());
        assertThat(captured.getValue().entityNo()).isNull();
        assertThat(captured.getValue().version()).isEqualTo(7);
        assertThat(captured.getValue().eventNo()).isEqualTo("ORIGINAL_TAX_EVENT");
    }

    @Test
    void mismatchedOriginalAggregateIsNotAcknowledged() {
        assertThatThrownBy(
                        () ->
                                new CommerceTaxSourceEventConsumer(notices, json)
                                        .handle(event("E", "pricing.tax.merchant.E", "OTHER")))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(notices);
    }

    @Test
    void missingDurableTaxHintOwnerPropagatesForOriginalOutboxRetry() {
        when(notices.receive(any())).thenThrow(new IllegalStateException("persist failed"));
        assertThatThrownBy(
                        () ->
                                new CommerceTaxSourceEventConsumer(notices, json)
                                        .handle(
                                                event(
                                                        "E",
                                                        "pricing.tax.merchant.E",
                                                        "pricing.tax.merchant.E")))
                .isInstanceOf(IllegalStateException.class);
    }
}
