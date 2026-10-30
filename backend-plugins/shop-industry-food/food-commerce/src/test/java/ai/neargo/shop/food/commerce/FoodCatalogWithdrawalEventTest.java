package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.commerce.dto.SourceNoticeContracts;
import ai.neargo.shop.commerce.service.SourceNoticeService;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.food.commerce.event.FoodCatalogSourceEventConsumer;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.spi.food.MenuChanged;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

class FoodCatalogWithdrawalEventTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final SourceNoticeService notices = mock(SourceNoticeService.class);
    final FoodCatalogSourceEventConsumer consumer =
            new FoodCatalogSourceEventConsumer(notices, json);

    OutboxEnvelope envelope(String action, String status) {
        var head = new MenuVO("M", "S", "Menu", "TIME", "UTC", 0, null, status, 2L, 1L);
        var event = new MenuChanged("E", "S", "M", action, 2, "ACTUAL_ACTION", head);
        return new OutboxEnvelope(
                "O",
                "PLATFORM",
                "SOURCE_EVENT",
                "FOOD_MENU",
                "M",
                "FOOD_MENU_CHANGED",
                1,
                "MENU:M",
                1,
                json.writeValueAsString(event),
                Instant.now());
    }

    @Test
    void onlyActualDisableActionCarriesExplicitWithdrawalHintWithOriginalEventIdentity() {
        consumer.handle(envelope("DISABLE_MENU", "DISABLED"));
        var captured = ArgumentCaptor.forClass(SourceNoticeContracts.Notice.class);
        verify(notices).receive(captured.capture());
        assertThat(captured.getValue().kind()).isEqualTo("WITHDRAWAL");
        assertThat(captured.getValue().eventNo()).isEqualTo("SOURCE_EVENT");
        assertThat(captured.getValue().sourceRef()).isEqualTo("M");
    }

    @Test
    void ordinaryDraftChangeNeverRequestsClearAndMalformedDisableIsRejected() {
        consumer.handle(envelope("UPDATE_MENU", "DRAFT"));
        verifyNoInteractions(notices);
        assertThatThrownBy(() -> consumer.handle(envelope("DISABLE_MENU", "DRAFT")))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(notices);
    }

    @Test
    void sourcePersistenceFailurePropagatesSoTheOriginalOutboxCanRetry() {
        when(notices.receive(any()))
                .thenThrow(new IllegalStateException("original notice unavailable"));
        assertThatThrownBy(() -> consumer.handle(envelope("DISABLE_MENU", "DISABLED")))
                .isInstanceOf(IllegalStateException.class);
    }
}
