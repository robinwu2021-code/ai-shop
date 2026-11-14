package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.food.config.FoodProperties;
import ai.neargo.shop.food.config.FoodSchemaReady;
import ai.neargo.shop.food.service.BusinessOperationsService;
import ai.neargo.shop.food.service.impl.FoodSupportServiceImpl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;
import java.util.Map;

class FoodSupportReadinessTest {
    private final BusinessOperationsService operations = mock(BusinessOperationsService.class);

    private <T> ObjectProvider<T> provider(Class<T> type, T value) {
        var beans = new DefaultListableBeanFactory();
        if (value != null) {
            beans.registerSingleton("actual", value);
        }
        return beans.getBeanProvider(type);
    }

    @Test
    void migrationOffOrMissingTypedOwnerCannotReportReady() {
        var service =
                new FoodSupportServiceImpl(
                        new FoodProperties(true, FoodProperties.Deployment.EMBEDDED),
                        provider(FoodSchemaReady.class, FoodSchemaReady.off()),
                        Map.of("original-order", provider(Object.class, null)),
                        operations);
        var result = service.readiness();
        assertThat(result.ready()).isFalse();
        assertThat(result.schemaValidated()).isFalse();
        assertThat(result.missingCapabilities()).contains("food.migration.off", "original-order");
        assertThat(result.typedProviderCounts()).containsEntry("original-order", 0);
        verifyNoInteractions(operations);
    }

    @Test
    void validatedReceiptAndActualUniqueProviderReportOnlyLocalReadiness() {
        var service =
                new FoodSupportServiceImpl(
                        new FoodProperties(true, FoodProperties.Deployment.STANDALONE),
                        provider(FoodSchemaReady.class, new FoodSchemaReady(true, "25", List.of())),
                        Map.of("original-order", provider(Object.class, new Object())),
                        operations);
        var result = service.readiness();
        assertThat(result.ready()).isTrue();
        assertThat(result.deployment()).isEqualTo("STANDALONE");
        assertThat(result.schemaVersion()).isEqualTo("25");
        assertThat(result.typedProviderCounts())
                .containsExactlyEntriesOf(Map.of("original-order", 1));
        verifyNoInteractions(operations);
    }
}
