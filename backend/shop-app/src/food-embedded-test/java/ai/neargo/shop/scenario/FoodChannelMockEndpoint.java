package ai.neargo.shop.scenario;

import ai.neargo.shop.commerce.channel.mock.MockCommercePlatform;
import org.springframework.context.ApplicationContext;

/** Test provisioning uses the same peer as the configured gateway. */
final class FoodChannelMockEndpoint {
    private FoodChannelMockEndpoint() {}
    static String of(ApplicationContext context) {
        String remote = context.getEnvironment().getProperty("shop.commerce.mock.base-url", "");
        return remote.isBlank()
                ? "http://127.0.0.1:" + context.getBean(MockCommercePlatform.class).port()
                : remote;
    }
}
