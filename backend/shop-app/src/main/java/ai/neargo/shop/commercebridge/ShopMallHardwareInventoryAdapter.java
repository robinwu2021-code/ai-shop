package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.common.AsyncStatus;
import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.inventory.service.CoordinatedInventoryService;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 商城硬件的库存口，两种模式（{@link ShopMallProperties.HardwareStock}）：
 *
 * <ul>
 *   <li><b>UNTRACKED</b>（默认）：商城不接进销存。硬件照常可下单、可发货，预留 / 发货 / 释放都直接成功，
 *       不占库存、不扣库存。操作身份与幂等由商城自己的履约操作记录保证（commerce 先落 operation 再调这里）；
 *       查询一律回 NOT_FOUND，commerce 据此重放同一条幂等命令，而重放也是直接成功。
 *       库存与发货是否有货由运营线下把关。
 *   <li><b>COORDINATED</b>：经 {@link CoordinatedInventoryService} 预留与扣减；没有实现 bean 或没有 SKU 映射时
 *       fail closed（不能下单）。inventory owns validation, persistence, fencing and stock.
 * </ul>
 */
final class ShopMallHardwareInventoryAdapter implements HardwareInventoryPort {
    private final boolean untracked;
    private final CoordinatedInventoryService service;
    private final Map<String, CoordinatedInventoryService.Binding> mappings;

    ShopMallHardwareInventoryAdapter(
            CoordinatedInventoryService service,
            Map<String, CoordinatedInventoryService.Binding> mappings) {
        this(false, service, mappings);
    }

    private ShopMallHardwareInventoryAdapter(
            boolean untracked,
            CoordinatedInventoryService service,
            Map<String, CoordinatedInventoryService.Binding> mappings) {
        this.untracked = untracked;
        this.service = service;
        this.mappings = Map.copyOf(mappings);
    }

    /** 不接进销存：硬件不占、不扣库存。 */
    static ShopMallHardwareInventoryAdapter untracked() {
        return new ShopMallHardwareInventoryAdapter(true, null, Map.of());
    }

    boolean mapped(String sku) {
        if (untracked) return sku != null && !sku.isBlank();
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
        if (untracked)
            return new InventoryOutcome(
                    c.orderComponentNo(),
                    AsyncStatus.SUCCEEDED,
                    c.quantity(),
                    0,
                    Optional.empty(),
                    c.operationNo(),
                    c.quantity());
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
        // 不留账：没有可查的证据。NOT_FOUND 让 commerce 重放原命令（同样直接成功），从不被当作已释放
        if (untracked)
            return unavailable(q.orderComponentNo(), q.operationNo(), AsyncStatus.UNKNOWN, "NOT_FOUND");
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
        // 从未占过库存：释放即成功，数量全为 0（commerce 对 RELEASE 成功的要求）
        if (untracked)
            return new InventoryOutcome(
                    q.orderComponentNo(), AsyncStatus.SUCCEEDED, 0, 0, Optional.empty(), q.operationNo(), 0);
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
        if (untracked)
            return new InventoryOutcome(
                    c.orderComponentNo(),
                    AsyncStatus.SUCCEEDED,
                    0,
                    c.quantity(),
                    Optional.empty(),
                    c.operationNo(),
                    c.quantity());
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
