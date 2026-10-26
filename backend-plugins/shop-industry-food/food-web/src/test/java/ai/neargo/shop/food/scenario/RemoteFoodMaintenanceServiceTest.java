package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.foodclient.RemoteFoodMaintenanceService;
import ai.neargo.shop.food.service.FoodMaintenanceService.Report;
import ai.neargo.shop.food.service.FoodMaintenanceService.Task;
import ai.neargo.shop.food.spi.FoodMaintenanceInternalApi;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RemoteFoodMaintenanceServiceTest {
    @Test
    void standaloneForwardsTheFixedTaskAndBoundedBatchToFoodServer() {
        AtomicReference<FoodMaintenanceInternalApi.Run> sent = new AtomicReference<>();
        var service = new RemoteFoodMaintenanceService(api(request -> {
            sent.set(request);
            return new Report(3, 1);
        }));
        assertThat(service.run(Task.SCHEDULED_DISPATCH, 100)).isEqualTo(new Report(3, 1));
        assertThat(sent.get()).isEqualTo(new FoodMaintenanceInternalApi.Run(Task.SCHEDULED_DISPATCH, 100));
    }

    @Test
    void rejectsImpossibleOwnerReceiptsAndInvalidRequests() {
        var service = new RemoteFoodMaintenanceService(api(request -> new Report(request.limit() + 1, 0)));
        assertThatThrownBy(() -> service.run(Task.QUEUE_NOTICE, 10)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new FoodMaintenanceInternalApi.Run(null, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FoodMaintenanceInternalApi.Run(Task.QUEUE_NOTICE, 201))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FoodMaintenanceInternalApi api(java.util.function.Function<FoodMaintenanceInternalApi.Run, Report> body) {
        return (FoodMaintenanceInternalApi) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {FoodMaintenanceInternalApi.class},
                (proxy, method, args) -> body.apply((FoodMaintenanceInternalApi.Run) args[0]));
    }
}
