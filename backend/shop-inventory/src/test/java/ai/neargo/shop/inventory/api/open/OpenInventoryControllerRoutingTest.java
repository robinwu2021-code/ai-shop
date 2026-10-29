package ai.neargo.shop.inventory.api.open;

import ai.neargo.inventory.client.CircuitBreaker;
import ai.neargo.inventory.client.InventoryConsole;
import ai.neargo.inventory.client.InventoryConsoleApi;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.inventory.domain.service.OpenApiCredentialService;
import ai.neargo.inventory.domain.service.StockCountService;
import ai.neargo.inventory.domain.service.StockQueryService;
import ai.neargo.shop.inventory.route.InventoryConsoleRoute;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 旧开放接口切流（ADR-046 M8）：AppKey 仍在商城认，认出的业主已翻到服务时转发，带调用方的幂等号作操作人。 */
class OpenInventoryControllerRoutingTest {

    @Test
    @SuppressWarnings("unchecked")
    void syncForAFlippedOwnerIsForwardedWithTheRequestIdAsOperator() {
        OpenApiCredentialService credentials = Mockito.mock(OpenApiCredentialService.class);
        StockQueryService query = Mockito.mock(StockQueryService.class);
        StockCountService counts = Mockito.mock(StockCountService.class);
        InventoryConsoleRoute route = Mockito.mock(InventoryConsoleRoute.class);
        when(credentials.ownerOf("K", "S", "stock:sync")).thenReturn("OWN-1");
        when(route.remoteEntityOfOwner("OWN-1")).thenReturn("E100");
        List<String> sent = new ArrayList<>();
        InventoryConsoleApi api = new InventoryConsoleApi() {
            public String get(URI uri, String o, String a) {
                return "";
            }
            public String post(URI uri, String o, String k, String a, Object body) {
                sent.add(uri.getRawPath() + " as " + o + " by " + a);
                return "{\"requestId\":\"R7\",\"applied\":1}";
            }
            public String put(URI uri, String o, String k, String a, Object body) {
                return "";
            }
            public String delete(URI uri, String o, String k, String a) {
                return "";
            }
        };
        InventoryReservationClient client = InventoryReservationClient.of(null, null, api,
                new CircuitBreaker(5, Duration.ofSeconds(10), Clock.systemUTC()));
        when(route.viaFor(eq("E100"), any(), any())).thenAnswer(inv -> ((Function<InventoryConsole, Object>)
                inv.getArgument(2)).apply(client.console(inv.getArgument(0), inv.getArgument(1))));

        OpenInventoryController controller = new OpenInventoryController(credentials, query, counts, route);
        var result = controller.sync("K", "S", new OpenInventoryController.SyncReq("R7", "LOC-1",
                List.of(new OpenInventoryController.SyncLine("ITEM-1", 3))));
        assertThat(result.applied()).isEqualTo(1);
        assertThat(sent).containsExactly("/api/inventory/v1/console/platform/stock-sync as E100 by OPENAPI:R7");
        verifyNoInteractions(query, counts);
    }
}
