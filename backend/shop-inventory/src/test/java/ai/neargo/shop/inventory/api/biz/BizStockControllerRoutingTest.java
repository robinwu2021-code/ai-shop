package ai.neargo.shop.inventory.api.biz;

import ai.neargo.inventory.client.CircuitBreaker;
import ai.neargo.inventory.client.InventoryConsole;
import ai.neargo.inventory.client.InventoryConsoleApi;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.inventory.domain.dto.InventoryVOs.SummaryVO;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.ItemService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.StockCountService;
import ai.neargo.inventory.domain.service.StockQueryService;
import ai.neargo.shop.inventory.route.InventoryConsoleRoute;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 商家后台读写全切（ADR-046 M8 T8.3）：已翻到库存服务的商家，库存页每个端点转给服务的 /console/stock，
 * 不碰内嵌领域服务；内嵌商家一字不改。
 */
class BizStockControllerRoutingTest {

    StockQueryService query;
    StockCountService counts;
    InventoryConsoleRoute route;
    BizStockController controller;
    List<String> sent;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        query = Mockito.mock(StockQueryService.class);
        counts = Mockito.mock(StockCountService.class);
        route = Mockito.mock(InventoryConsoleRoute.class);
        controller = new BizStockController(query, counts, Mockito.mock(ItemService.class),
                Mockito.mock(InventoryAclService.class), Mockito.mock(LocationService.class), route);
        sent = new ArrayList<>();
        InventoryConsoleApi api = new InventoryConsoleApi() {
            public String get(URI uri, String owner, String actor) {
                sent.add("GET " + uri.getRawPath() + "?" + uri.getRawQuery() + " as " + owner);
                return uri.getRawPath().endsWith("/summary")
                        ? "{\"itemCount\":7,\"shortageCount\":1,\"staleCount\":0,\"inTransitCount\":0,\"openCountNo\":null}" : "[]";
            }
            public String post(URI uri, String owner, String key, String actor, Object body) {
                sent.add("POST " + uri.getRawPath() + " " + body);
                return "";
            }
            public String put(URI uri, String owner, String key, String actor, Object body) {
                sent.add("PUT " + uri.getRawPath() + " " + body);
                return "";
            }
            public String delete(URI uri, String owner, String key, String actor) {
                return "";
            }
        };
        InventoryConsole console = InventoryReservationClient.of(null, null, api,
                new CircuitBreaker(5, Duration.ofSeconds(10), Clock.systemUTC())).console("E100", "STAFF:U1");
        when(route.store()).thenReturn("S-01");
        when(route.via(any())).thenAnswer(inv -> ((Function<InventoryConsole, Object>) inv.getArgument(0)).apply(console));
        Mockito.doAnswer(inv -> {
            ((Consumer<InventoryConsole>) inv.getArgument(0)).accept(console);
            return null;
        }).when(route).run(any());
    }

    @Test
    void flippedMerchantIsServedByTheServiceAndTheEmbeddedDomainIsUntouched() {
        when(route.remote()).thenReturn(true);
        SummaryVO summary = controller.summary();
        assertThat(summary.itemCount()).isEqualTo(7);
        controller.balances("shortage", null, 500);
        controller.adjust(new BizStockController.AdjustReq("ITEM-1", 4, "OTHER"));
        assertThat(sent).containsExactly(
                "GET /api/inventory/v1/console/stock/summary?storeRef=S-01 as E100",
                "GET /api/inventory/v1/console/stock/balances?filter=shortage&storeRef=S-01&size=500 as E100",
                "POST /api/inventory/v1/console/stock/adjust RemoteAdjust[itemId=ITEM-1, countedQty=4, reasonCode=OTHER, storeRef=S-01]");
        verifyNoInteractions(query, counts);
    }

    @Test
    void embeddedMerchantStaysOnTheDomain() {
        when(route.remote()).thenReturn(false);
        ai.neargo.shop.auth.BizContext.set(new ai.neargo.shop.auth.BizContext("E100", java.util.Set.of(),
                java.util.Set.of()));
        try {
            controller.crossStore(null, 10);
        } finally {
            ai.neargo.shop.auth.BizContext.clear();
        }
        Mockito.verify(query).crossStore(any(), any(), anyInt());
        assertThat(sent).isEmpty();
    }
}
