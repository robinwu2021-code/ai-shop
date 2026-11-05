package ai.neargo.shop.inventory.service.impl;

import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.coordinated.CoordinatedOperations;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.embed.InventoryErrors;
import ai.neargo.shop.inventory.service.CoordinatedInventoryService;

import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * 内嵌形态：宿主契约 {@link CoordinatedInventoryService} → 库存领域 {@link CoordinatedOperations}。
 *
 * <p>只做记录互转与错误码翻译（{@link InventoryErrors}），语义全在领域里。
 * 有了这个 bean，商城硬件 SKU 不再一律「库存不可用」（{@code ShopMallHardwareInventoryAdapter}）。
 */
@Service
@ConditionalOnInventory
public class CoordinatedInventoryServiceImpl implements CoordinatedInventoryService {
    private final CoordinatedOperations operations;

    public CoordinatedInventoryServiceImpl(CoordinatedOperations operations) {
        this.operations = operations;
    }

    @Override
    public Evidence reserve(Reserve c) {
        return call(
                () ->
                        operations.reserve(
                                c == null
                                        ? null
                                        : new CoordinatedOperations.Reserve(
                                                ref(c.reference()),
                                                c.operationNo(),
                                                binding(c.binding()),
                                                c.quantity())));
    }

    @Override
    public Evidence query(Reference reference, String operationNo) {
        return call(() -> operations.query(ref(reference), operationNo));
    }

    @Override
    public Evidence release(Reference reference, String originalReserveOperationNo) {
        return call(() -> operations.release(ref(reference), originalReserveOperationNo));
    }

    @Override
    public Evidence ship(Shipment c) {
        return call(
                () ->
                        operations.ship(
                                c == null
                                        ? null
                                        : new CoordinatedOperations.Shipment(
                                                ref(c.reference()),
                                                c.operationNo(),
                                                c.quantity(),
                                                c.carrier(),
                                                c.trackingNo(),
                                                c.operatorNo())));
    }

    private static Evidence call(Supplier<CoordinatedOperations.Evidence> action) {
        CoordinatedOperations.Evidence e;
        try {
            e = action.get();
        } catch (InventoryException failure) {
            throw InventoryErrors.toShop(failure);
        }
        return new Evidence(
                Status.valueOf(e.status().name()),
                e.operationNo(),
                e.operationQuantity(),
                e.held(),
                e.issued(),
                e.binding() == null
                        ? null
                        : new Binding(
                                e.binding().ownerId(),
                                e.binding().itemId(),
                                e.binding().locationId(),
                                e.binding().mappingVersion(),
                                e.binding().requestReference()),
                e.documentNo());
    }

    private static CoordinatedOperations.Reference ref(Reference r) {
        return r == null ? null : new CoordinatedOperations.Reference(r.client(), r.externalRef());
    }

    private static CoordinatedOperations.Binding binding(Binding b) {
        return b == null
                ? null
                : new CoordinatedOperations.Binding(
                        b.ownerId(), b.itemId(), b.locationId(), b.mappingVersion(), b.requestReference());
    }
}
