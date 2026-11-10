package ai.neargo.shop.beauty.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.beauty.service.WorkOrderBillService;
import ai.neargo.shop.beauty.service.WorkOrderSettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class WorkOrderSettlementWiringTest {
    @Test void disabledNeedsNoOwnersAndEnabledHasOneBillBackedService() {
        var runner = new ApplicationContextRunner().withUserConfiguration(BeautySettlementConfiguration.class);
        runner.withPropertyValues("shop.beauty.enabled=false").run(c -> assertThat(c)
                .hasNotFailed().doesNotHaveBean(WorkOrderSettlementService.class));
        runner.withPropertyValues("shop.beauty.enabled=true")
                .withBean(WorkOrderBillService.class, () -> mock(WorkOrderBillService.class))
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                .run(c -> assertThat(c).hasNotFailed().hasSingleBean(WorkOrderSettlementService.class));
    }
}
