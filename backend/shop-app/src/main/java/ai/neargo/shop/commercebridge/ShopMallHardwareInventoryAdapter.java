package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.common.AsyncStatus;
import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.inventory.service.CoordinatedInventoryService;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Pure mapping metadata for readiness; inventory owns validation, persistence, fencing and stock.
 */
final class ShopMallHardwareInventoryAdapter implements HardwareInventoryPort {
    private final CoordinatedInventoryService service;
    private final Map<String, CoordinatedInventoryService.Binding> mappings;

    ShopMallHardwareInventoryAdapter(
            CoordinatedInventoryService service,
            Map<String, CoordinatedInventoryService.Binding> mappings) {
        this.service = service;
        this.mappings = Map.copyOf(mappings);
    }

    boolean mapped(String sku) {
        return service != null && mappings.containsKey(sku);
    }

    static String reference(String tenant, String component) {
        return digest("mall-stock:v1", tenant, component);
    }

    static String requestReference(String order, String sku) {
        return digest("mall-stock-request:v1", order, sku);
    }

    private static String digest(String domain, String first, String second) {
        if (first == null || first.isBlank() || second == null || second.isBlank())
            throw new IllegalArgumentException("inventory identity required");
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (domain + "\0" + first + "\0" + second)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CoordinatedInventoryService.Reference ref(String tenant, String component) {
        return new CoordinatedInventoryService.Reference(
                "PLATFORM_MALL", reference(tenant, component));
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Inventory IO must run outside commerce transactions");
    }

    public InventoryOutcome reserve(InventoryCommand c) {
        outside();
        if (service == null)
            return unavailable(
                    c.orderComponentNo(),
                    c.operationNo(),
                    AsyncStatus.FAILED,
                    "INVENTORY_UNAVAILABLE");
        try {
            var r = ref(c.tenantNo(), c.orderComponentNo());
            var prior = service.query(r, c.operationNo());
            if (prior.status() != CoordinatedInventoryService.Status.NOT_FOUND) {
                if (prior.binding() != null
                        && (!requestReference(c.orderNo(), c.skuNo())
                                        .equals(prior.binding().requestReference())
                                || prior.status() == CoordinatedInventoryService.Status.RESERVED
                                        && prior.operationQuantity() != c.quantity()))
                    return unavailable(
                            c.orderComponentNo(),
                            c.operationNo(),
                            AsyncStatus.FAILED,
                            "INVENTORY_INTENT_CONFLICT");
                return outcome(c.orderComponentNo(), prior);
            }
            var configured = mappings.get(c.skuNo());
            if (configured == null)
                return unavailable(
                        c.orderComponentNo(),
                        c.operationNo(),
                        AsyncStatus.FAILED,
                        "INVENTORY_MAPPING_UNAVAILABLE");
            var frozen =
                    new CoordinatedInventoryService.Binding(
                            configured.ownerId(),
                            configured.itemId(),
                            configured.locationId(),
                            configured.mappingVersion(),
                            requestReference(c.orderNo(), c.skuNo()));
            return outcome(
                    c.orderComponentNo(),
                    service.reserve(
                            new CoordinatedInventoryService.Reserve(
                                    r, c.operationNo(), frozen, Math.toIntExact(c.quantity()))));
        } catch (RuntimeException uncertain) {
            return unavailable(
                    c.orderComponentNo(),
                    c.operationNo(),
                    AsyncStatus.UNKNOWN,
                    "INVENTORY_CHECK_UNAVAILABLE");
        }
    }

    public InventoryOutcome query(InventoryQuery q) {
        outside();
        if (service == null)
            return unavailable(
                    q.orderComponentNo(),
                    q.operationNo(),
                    AsyncStatus.UNKNOWN,
                    "INVENTORY_UNAVAILABLE");
        try {
            return outcome(
                    q.orderComponentNo(),
                    service.query(ref(q.tenantNo(), q.orderComponentNo()), q.operationNo()));
        } catch (RuntimeException uncertain) {
            return unavailable(
                    q.orderComponentNo(),
                    q.operationNo(),
                    AsyncStatus.UNKNOWN,
                    "INVENTORY_CHECK_UNAVAILABLE");
        }
    }

    public InventoryOutcome release(InventoryQuery q) {
        outside();
        if (service == null)
            return unavailable(
                    q.orderComponentNo(),
                    q.operationNo(),
                    AsyncStatus.UNKNOWN,
                    "INVENTORY_UNAVAILABLE");
        try {
            return outcome(
                    q.orderComponentNo(),
                    service.release(ref(q.tenantNo(), q.orderComponentNo()), q.operationNo()));
        } catch (RuntimeException uncertain) {
            return unavailable(
                    q.orderComponentNo(),
                    q.operationNo(),
                    AsyncStatus.UNKNOWN,
                    "INVENTORY_CHECK_UNAVAILABLE");
        }
    }

    public InventoryOutcome ship(InventoryCommand c) {
        outside();
        if (service == null)
            return unavailable(
                    c.orderComponentNo(),
                    c.operationNo(),
                    AsyncStatus.UNKNOWN,
                    "INVENTORY_UNAVAILABLE");
        try {
            var r = ref(c.tenantNo(), c.orderComponentNo());
            var original = service.query(r, c.operationNo());
            if (original.binding() == null
                    || !requestReference(c.orderNo(), c.skuNo())
                            .equals(original.binding().requestReference()))
                return unavailable(
                        c.orderComponentNo(),
                        c.operationNo(),
                        AsyncStatus.FAILED,
                        "INVENTORY_INTENT_CONFLICT");
            return outcome(
                    c.orderComponentNo(),
                    service.ship(
                            new CoordinatedInventoryService.Shipment(
                                    r,
                                    c.operationNo(),
                                    Math.toIntExact(c.quantity()),
                                    c.carrierCode(),
                                    c.trackingNo(),
                                    c.operatorNo() == null
                                            ? "COMMERCE_FULFILLMENT"
                                            : c.operatorNo())));
        } catch (RuntimeException uncertain) {
            return unavailable(
                    c.orderComponentNo(),
                    c.operationNo(),
                    AsyncStatus.UNKNOWN,
                    "INVENTORY_CHECK_UNAVAILABLE");
        }
    }

    private static InventoryOutcome outcome(
            String component, CoordinatedInventoryService.Evidence e) {
        return new InventoryOutcome(
                component,
                e.status() == CoordinatedInventoryService.Status.NOT_FOUND
                        ? AsyncStatus.UNKNOWN
                        : AsyncStatus.SUCCEEDED,
                e.held(),
                e.issued(),
                e.status() == CoordinatedInventoryService.Status.NOT_FOUND
                        ? Optional.of("NOT_FOUND")
                        : Optional.empty(),
                e.operationNo(),
                e.operationQuantity());
    }

    private static InventoryOutcome unavailable(
            String component, String op, AsyncStatus state, String reason) {
        return new InventoryOutcome(component, state, 0, 0, Optional.of(reason), op, 0);
    }
}
