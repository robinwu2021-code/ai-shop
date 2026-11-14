package ai.neargo.shop.product.policy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdStockWriter;
import ai.neargo.shop.product.entity.PrdStoreStockSync;
import ai.neargo.shop.product.mapper.ProductMappers.StockWriterMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockSyncMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Entity accounting authority fence. OFF does not silently hand a historical local pool back to legacy. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class StockReplenishmentWriterGuard {
    private final StockWriterMapper writers;
    private final StoreStockSyncMapper syncs;

    public StockReplenishmentWriterGuard(StockWriterMapper writers, StoreStockSyncMapper syncs) {
        this.writers = writers; this.syncs = syncs;
    }

    public PrdStockWriter lock(String entity) {
        return DataScopeContext.executeWithoutScope(() -> {
            writers.ensure(entity);
            var row = writers.lock(entity);
            if (row == null || !Integer.valueOf(0).equals(row.getDeleted())
                    || (!"LEGACY".equals(row.getMode()) && !"REPLENISH".equals(row.getMode()))) throw conflict();
            return row;
        });
    }

    public boolean legacyAllowed(String entity) { return "LEGACY".equals(lock(entity).getMode()); }

    public void requireLegacy(String entity) {
        if (!legacyAllowed(entity)) throw conflict();
    }

    public void takeLocal(PrdStockWriter row, String actor) {
        String entity = row.getEntityNo();
        // Admission owns READ_COMMITTED; enable/disable writers use this same entity fence.
        // Do not lock configuration before goods: metadata editing may already hold goods locks.
        var old = DataScopeContext.executeWithoutScope(() -> syncs.selectList(Wrappers.<PrdStoreStockSync>lambdaQuery()
                .eq(PrdStoreStockSync::getTenantNo, "MAIN").eq(PrdStoreStockSync::getEntityNo, entity)));
        if (old.stream().anyMatch(s -> !Integer.valueOf(0).equals(s.getEnabled()))) throw conflict();
        if ("REPLENISH".equals(row.getMode())) return;
        row.setMode("REPLENISH"); row.setUpdatedBy(actor);
        if (DataScopeContext.executeWithoutScope(() -> writers.updateById(row)) != 1) throw conflict();
    }

    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
}
