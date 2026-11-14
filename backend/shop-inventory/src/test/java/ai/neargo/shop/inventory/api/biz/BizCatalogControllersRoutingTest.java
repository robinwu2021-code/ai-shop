package ai.neargo.shop.inventory.api.biz;

import ai.neargo.inventory.client.CircuitBreaker;
import ai.neargo.inventory.client.InventoryConsole;
import ai.neargo.inventory.client.InventoryConsoleApi;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.InventoryReportService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.StockQueryService;
import ai.neargo.inventory.domain.service.SupplierService;
import ai.neargo.shop.inventory.route.InventoryConsoleRoute;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 商家后台报表、导出、供应商读写全切（ADR-046 M8）：已翻商家转发到服务，不碰内嵌领域服务。 */
class BizCatalogControllersRoutingTest {

    @Test
    @SuppressWarnings("unchecked")
    void reportsExportAndSuppliersGoToTheService() {
        List<String> sent = new ArrayList<>();
        InventoryConsoleApi api = new InventoryConsoleApi() {
            public String get(URI uri, String o, String a) {
                sent.add("GET " + uri.getRawPath() + "?" + uri.getRawQuery());
                return uri.getRawPath().endsWith("/export") ? "doc,qty\nIN1,3" : "[]";
            }
            public String post(URI uri, String o, String k, String a, Object body) {
                sent.add("POST " + uri.getRawPath());
                return "{\"supplierNo\":\"SUP1\"}";
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
        InventoryConsoleRoute route = Mockito.mock(InventoryConsoleRoute.class);
        when(route.remote()).thenReturn(true);
        when(route.store()).thenReturn("S-01");
        when(route.via(any())).thenAnswer(inv -> ((Function<InventoryConsole, Object>) inv.getArgument(0)).apply(console));

        StockQueryService query = Mockito.mock(StockQueryService.class);
        InventoryReportService reports = Mockito.mock(InventoryReportService.class);
        SupplierService suppliers = Mockito.mock(SupplierService.class);
        BizStockReportController report = new BizStockReportController(query, reports,
                Mockito.mock(InventoryAclService.class), Mockito.mock(LocationService.class), route);
        BizSupplierController supplier = new BizSupplierController(suppliers, Mockito.mock(InventoryAclService.class),
                route);

        assertThat(new String(report.export("ledger").getBody(), StandardCharsets.UTF_8)).isEqualTo("doc,qty\nIN1,3");
        report.ranking("slow", 7, 5);
        assertThat(supplier.create(new BizSupplierController.SupplierReq("茶园", null, null, null, null, null))
                .supplierNo()).isEqualTo("SUP1");
        assertThat(sent).containsExactly(
                "GET /api/inventory/v1/console/export?type=ledger&storeRef=S-01",
                "GET /api/inventory/v1/console/report/ranking?type=slow&days=7&limit=5&storeRef=S-01",
                "POST /api/inventory/v1/console/suppliers");
        verifyNoInteractions(query, reports, suppliers);
    }
}
