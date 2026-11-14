package ai.neargo.shop.inventory.api.biz;

import ai.neargo.inventory.client.CircuitBreaker;
import ai.neargo.inventory.client.InventoryConsole;
import ai.neargo.inventory.client.InventoryConsoleApi;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.inventory.domain.service.InboundService;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.OutboundService;
import ai.neargo.inventory.domain.service.StockCountService;
import ai.neargo.inventory.domain.service.TransferService;
import ai.neargo.shop.inventory.route.InventoryConsoleRoute;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 商家后台单据读写全切（ADR-046 M8）：已翻商家转发到服务，员工门店范围随请求带过去，不碰内嵌领域服务。 */
class BizStockDocControllerRoutingTest {

    @Test
    @SuppressWarnings("unchecked")
    void flippedMerchantDocumentsGoToTheServiceWithTheStaffScope() {
        InboundService inbound = Mockito.mock(InboundService.class);
        OutboundService outbound = Mockito.mock(OutboundService.class);
        StockCountService counts = Mockito.mock(StockCountService.class);
        TransferService transfers = Mockito.mock(TransferService.class);
        InventoryConsoleRoute route = Mockito.mock(InventoryConsoleRoute.class);
        BizStockDocController controller = new BizStockDocController(inbound, outbound, counts, transfers,
                Mockito.mock(InventoryAclService.class), Mockito.mock(LocationService.class), route);
        List<String> sent = new ArrayList<>();
        InventoryConsoleApi api = new InventoryConsoleApi() {
            public String get(URI uri, String o, String a) {
                sent.add("GET " + uri.getRawPath() + "?" + uri.getRawQuery());
                return "";
            }
            public String post(URI uri, String o, String k, String a, Object body) {
                sent.add("POST " + uri.getRawPath() + "?" + uri.getRawQuery());
                return uri.getRawPath().endsWith("/inbounds") ? "{\"no\":\"IN1\"}" : "";
            }
            public String put(URI uri, String o, String k, String a, Object body) {
                return "";
            }
            public String delete(URI uri, String o, String k, String a) {
                return "";
            }
        };
        InventoryConsole console = InventoryReservationClient.of(null, null, api,
                new CircuitBreaker(5, Duration.ofSeconds(10), Clock.systemUTC())).console("E100", "U1");
        when(route.remote()).thenReturn(true);
        when(route.store()).thenReturn("S-01");
        when(route.scopeStores()).thenReturn("S-01,S-02");
        when(route.via(any())).thenAnswer(inv -> ((Function<InventoryConsole, Object>) inv.getArgument(0)).apply(console));
        Mockito.doAnswer(inv -> {
            ((Consumer<InventoryConsole>) inv.getArgument(0)).accept(console);
            return null;
        }).when(route).run(any());

        assertThat(controller.createInbound(new BizStockDocController.InboundReq("PURCHASE", null, null, null, null,
                List.of())).no()).isEqualTo("IN1");
        controller.postInbound("IN1");
        controller.transfer("T1");
        assertThat(sent).containsExactly(
                "POST /api/inventory/v1/console/docs/inbounds?storeRef=S-01",
                "POST /api/inventory/v1/console/docs/inbounds/IN1/post?scopeStores=S-01%2CS-02",
                "GET /api/inventory/v1/console/docs/transfers/T1?scopeStores=S-01%2CS-02");
        verifyNoInteractions(inbound, outbound, counts, transfers);
    }
}
