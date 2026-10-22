package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.CatalogQuery;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.foodclient.RemoteCatalogService;
import ai.neargo.shop.food.spi.CatalogInternalApi;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CatalogTransportTest {
    @Test void queryRejectsOwnershipTimeInjectionAndScalarCoercion() {
        var json = new ObjectMapper();
        assertThat(json.readValue("{\"orderSource\":\"POS\",\"fulfillment\":\"DINE_IN\"}", CatalogQuery.class)
                .orderSource()).isEqualTo("POS");
        for (String body : List.of("{\"orderSource\":1,\"fulfillment\":\"DINE_IN\"}",
                "{\"orderSource\":\"POS\",\"fulfillment\":\"DINE_IN\",\"at\":\"2030-01-01T00:00:00Z\"}",
                "{\"orderSource\":\"POS\",\"fulfillment\":\"DINE_IN\",\"entityNo\":\"OTHER\"}")) {
            assertThatThrownBy(() -> json.readValue(body, CatalogQuery.class)).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void typedRemoteRejectsForeignScopeAndPreservesEmptyTruthWithoutInventingItems() {
        var api = mock(CatalogInternalApi.class); var service = new RemoteCatalogService(api);
        var q = new CatalogQuery("POS", "DINE_IN", null);
        when(api.read(any())).thenReturn(new CatalogVO("A", "POS", "DINE_IN", Instant.parse("2026-10-21T12:00:00Z"),
                List.of(), List.of()));
        assertThat(service.read("E", "A", q).items()).isEmpty();
        when(api.read(any())).thenReturn(new CatalogVO("OTHER", "POS", "DINE_IN", Instant.parse("2026-10-21T12:00:00Z"),
                List.of(), List.of()));
        assertThatThrownBy(() -> service.read("E", "A", q)).isInstanceOf(IllegalStateException.class);
        when(api.read(any())).thenReturn(new CatalogVO("A", "WAITER", "DINE_IN", Instant.parse("2026-10-21T12:00:00Z"),
                List.of(), List.of()));
        assertThatThrownBy(() -> service.read("E", "A", q)).isInstanceOf(IllegalStateException.class);
    }
}
