package ai.neargo.shop.invbridge.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invbridge.OriginalInventoryRestoreAdapter;
import ai.neargo.shop.invbridge.OriginalMirrorRestore;
import ai.neargo.shop.product.port.StockPortImpl;
import ai.neargo.shop.spi.product.OriginalStockReturnPort;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.product.StockPort.RestoreResult;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.trade.mapper.ManualAdmissionMapper;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Persisted manual source selects the original owner even after a primary-authority configuration change. */
@Component
public final class OriginalStockReturnPortImpl implements OriginalStockReturnPort {
    private final ManualAdmissionMapper admissions;
    private final StockPortImpl platform;
    private final ObjectProvider<OriginalMirrorRestore> mirrors;
    private final ObjectProvider<OriginalInventoryRestoreAdapter> inventory;
    private final ObjectProvider<StockPort> legacy;
    private final ObjectMapper json;

    public OriginalStockReturnPortImpl(ManualAdmissionMapper admissions, StockPortImpl platform,
            ObjectProvider<OriginalMirrorRestore> mirrors, ObjectProvider<OriginalInventoryRestoreAdapter> inventory,
            ObjectProvider<StockPort> legacy, ObjectMapper json) {
        this.admissions = admissions;
        this.platform = platform;
        this.mirrors = mirrors;
        this.inventory = inventory;
        this.legacy = legacy;
        this.json = json;
    }

    @Override
    public RestoreResult restore(String restoreNo, String originalOrderNo, List<StockPort.SkuQty> items) {
        if (originalOrderNo == null || originalOrderNo.isBlank() || restoreNo == null || restoreNo.isBlank()
                || items == null || items.isEmpty() || items.stream().anyMatch(item -> item.qty() <= 0)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var rows = DataScopeContext.executeWithoutScope(() -> admissions.stockReturnOriginal(originalOrderNo));
        if (rows.isEmpty()) {
            var inv = inventory.getIfAvailable();
            if (DataScopeContext.executeWithoutScope(() -> admissions.trackedStockReturnIntent(originalOrderNo)) > 0
                    || platform.hasTrackedOriginal(originalOrderNo)
                    || inv != null && inv.hasTrackedOriginal(originalOrderNo)) throw unavailable();
            // Positively untracked historical retail keeps its preexisting current adapter behavior.
            legacy.getObject().restore(restoreNo, items);
            return RestoreResult.COMPLETED;
        }
        if (rows.size() != 1) throw BizException.of(ErrorCode.CONFLICT);
        var source = rows.getFirst();
        if (!Integer.valueOf(0).equals(source.getDeleted()) || !"MAIN".equals(source.getTenantNo())
                || !originalOrderNo.equals(source.getOrderNo()) || source.getEntityNo() == null
                || source.getStoreNo() == null || source.getStockManifestJson() == null
                || source.getStockAuthority() == null || source.getStockManifestHash() == null
                || source.getStockOperationNo() == null || source.getStockLifecycleVersion(
                    ) == null) throw unavailable();
        Original original = json.readValue(source.getStockManifestJson(), Original.class);
        if (!source.getTenantNo().equals(original.tenantNo()) || !source.getEntityNo().equals(original.entityNo())
                || !originalOrderNo.equals(original.orderNo()) || !source.getStockAuthority().equals(
                    original.authority())
                || !source.getStockManifestHash().equals(original.manifestHash())
                || !Integer.valueOf(original.lifecycleVersion()).equals(source.getStockLifecycleVersion())
                || original.needs().isEmpty() || original.needs().stream()
                        .anyMatch(need -> !source.getStoreNo().equals(need.sourceStoreNo()))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        StockDispositionContract.requireOperation(original, source.getStockOperationNo());
        // No source-aware overload is called recursively. Each branch reaches the actual original claim owner.
        return switch (source.getStockAuthority()) {
            case "PLATFORM" -> platform.restoreOriginal(restoreNo, original, source.getStockOperationNo(), items);
            case "DUAL" -> {
                var owner = mirrors.getIfAvailable();
                if (owner == null) throw unavailable();
                yield owner.restore(restoreNo, original, source.getStockOperationNo(), items);
            }
            case "INVENTORY" -> {
                var owner = inventory.getIfAvailable();
                if (owner == null) throw unavailable();
                owner.restore(restoreNo, originalOrderNo, items);
                yield RestoreResult.COMPLETED;
            }
            default -> throw unavailable();
        };
    }

    private static BizException unavailable() {
        return BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, "FROZEN_RETURN_AUTHORITY_UNAVAILABLE");
    }
}
