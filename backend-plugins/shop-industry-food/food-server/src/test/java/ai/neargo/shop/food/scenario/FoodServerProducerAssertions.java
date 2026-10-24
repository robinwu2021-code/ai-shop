package ai.neargo.shop.food.scenario;

import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.runtime.OutboxRunner;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.server.config.FoodServerOutboxConfiguration;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Native fixtures use the scanned standalone producer, without replacing its ownership or guards. */
final class FoodServerProducerAssertions {
    private FoodServerProducerAssertions() { }
    static void verify(ApplicationContext context) {
        assertThat(context.getBeansOfType(FoodServerOutboxConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(UnifiedOutboxFacade.class)).hasSize(1);
        var facade=context.getBean(UnifiedOutboxFacade.class);
        assertThat(context.getBean("foodServerOutboxFacade")).isSameAs(facade);
        assertThat(facade.enabled("PLATFORM")).isTrue();
        assertThat(facade.enabled("INVENTORY")).isFalse();
        assertThat(facade.localHandlerIds("PLATFORM")).isEmpty();
        assertThat(context.getBeansOfType(OutboxHandler.class)).isEmpty();
        assertThat(context.getBeansOfType(OutboxRunner.class)).isEmpty();
    }
}
