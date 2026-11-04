package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.AsyncStatus;
import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.inventory.service.CoordinatedInventoryService;

import org.junit.jupiter.api.Test;

import java.util.*;

class MallInventoryAdapterTest {
    @Test
    void recoveryUsesOriginalBindingAndNeverReservesFromTheBuyerWarehouse() {
        var binding =
                new CoordinatedInventoryService.Binding(
                        "platform",
                        "item",
                        "warehouse",
                        "v1",
                        ShopMallHardwareInventoryAdapter.requestReference("order", "device"));
        var domain = org.mockito.Mockito.mock(CoordinatedInventoryService.class);
        var query = new InventoryQuery("MAIN", "component", "reserve");
        var ref =
                new CoordinatedInventoryService.Reference(
                        "PLATFORM_MALL",
                        ShopMallHardwareInventoryAdapter.reference("MAIN", "component"));
        org.mockito.Mockito.when(domain.query(ref, "reserve"))
                .thenReturn(
                        new CoordinatedInventoryService.Evidence(
                                CoordinatedInventoryService.Status.RESERVED,
                                "reserve",
                                5,
                                5,
                                0,
                                binding,
                                null));
        var adapter =
                new ShopMallHardwareInventoryAdapter(
                        domain,
                        Map.of(
                                "device",
                                new CoordinatedInventoryService.Binding(
                                        "other", "other", "other", "v2")));
        var result =
                adapter.reserve(
                        new InventoryCommand("MAIN", "order", "component", "device", 5, "reserve"));
        assertEquals(AsyncStatus.SUCCEEDED, result.status());
        assertEquals(5, result.operationQuantity());
        assertEquals(result, adapter.query(query));
        assertEquals(
                AsyncStatus.FAILED,
                adapter.reserve(
                                new InventoryCommand(
                                        "MAIN", "order", "component", "changed", 5, "reserve"))
                        .status());
        org.mockito.Mockito.when(domain.release(ref, "reserve"))
                .thenReturn(
                        new CoordinatedInventoryService.Evidence(
                                CoordinatedInventoryService.Status.RELEASED,
                                "reserve",
                                0,
                                0,
                                0,
                                binding,
                                null));
        assertEquals(0, adapter.release(query).reservedQuantity());
        assertEquals(AsyncStatus.SUCCEEDED, adapter.release(query).status());
        org.mockito.Mockito.verify(domain, org.mockito.Mockito.never())
                .reserve(org.mockito.Mockito.any());
        var unavailable = new ShopMallHardwareInventoryAdapter(null, Map.of());
        assertEquals(AsyncStatus.UNKNOWN, unavailable.release(query).status());
        assertEquals(
                AsyncStatus.FAILED,
                unavailable
                        .reserve(new InventoryCommand("MAIN", "o", "c", "unknown", 1, "r"))
                        .status());
    }
}
