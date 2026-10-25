package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.food.dto.BusinessOperationsContracts.Evidence;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Source;
import ai.neargo.shop.food.foodclient.RemoteFoodSupportService;
import ai.neargo.shop.food.spi.FoodSupportInternalApi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

class RemoteFoodSupportContractTest {
    @Test
    void originalScopeAndUnknownSourceStatusSurviveTypedTransport() {
        AtomicReference<FoodSupportInternalApi.SourceQuery> sent = new AtomicReference<>();
        FoodSupportInternalApi api =
                (FoodSupportInternalApi)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {FoodSupportInternalApi.class},
                                (proxy, method, args) -> {
                                    var query = (FoodSupportInternalApi.SourceQuery) args[0];
                                    sent.set(query);
                                    return new Evidence(
                                            query.source(), "UNKNOWN", 4, "frozen", false, "ACTOR");
                                });
        var source = new Source("BATCH", "B1");
        var result = new RemoteFoodSupportService(api).evidence("M1", "S1", source);
        assertThat(sent.get())
                .isEqualTo(new FoodSupportInternalApi.SourceQuery("M1", "S1", source));
        assertThat(result.source()).isEqualTo(source);
        assertThat(result.status()).isEqualTo("UNKNOWN");
    }
}
