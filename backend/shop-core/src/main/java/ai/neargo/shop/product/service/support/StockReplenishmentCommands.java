package ai.neargo.shop.product.service.support;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.product.entity.PrdStockReplenishmentCommand;
import ai.neargo.shop.product.entity.PrdStockWriter;
import ai.neargo.shop.product.mapper.ProductMappers.StockReplenishmentCommandMapper;
import ai.neargo.shop.product.policy.StockReplenishmentWriterGuard;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Existing product command journal shared by admission and quantity effects; no remote IO in work. */
@Component
public class StockReplenishmentCommands {
    private final StockReplenishmentCommandMapper commands;
    private final StockReplenishmentWriterGuard writers;
    private final ObjectMapper json;
    private final TransactionTemplate writes;

    public StockReplenishmentCommands(StockReplenishmentCommandMapper commands, StockReplenishmentWriterGuard writers,
            ObjectMapper json, @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.commands = commands; this.writers = writers; this.json = json;
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public <T> T execute(Scope scope, String actor, String action, String key, String hash,
            Class<T> type, Function<PrdStockWriter, T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Replenishment commands own their transaction");
        requireScope(scope); IdempotencyScope.requireKey(key);
        T old = findCompleted(scope, action, key, hash, type);
        if (old != null) return old;
        try {
            return DataScopeContext.executeWithoutScope(() -> writes.execute(tx -> {
                var writer = writers.lock(scope.entityNo());
                T replay = findCompleted(scope, action, key, hash, type);
                if (replay != null) return replay;
                T result = work.apply(writer);
                var receipt = new PrdStockReplenishmentCommand();
                receipt.setTenantNo("MAIN"); receipt.setEntityNo(scope.entityNo()); receipt.setStoreNo(scope.storeNo());
                receipt.setCreatedBy(actor); receipt.setUpdatedBy(actor); receipt.setVersion(0L); receipt.setDeleted(0);
                receipt.setCreatedAt(LocalDateTime.now()); receipt.setUpdatedAt(receipt.getCreatedAt());
                receipt.setAction(action); receipt.setOperationKey(key); receipt.setRequestHash(hash);
                receipt.setResultJson(json.writeValueAsString(result)); commands.insert(receipt);
                return result;
            }));
        } catch (DuplicateKeyException | ConcurrencyFailureException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    public <T> T findCompleted(Scope scope, String action, String key, String hash, Class<T> type) {
        requireScope(scope); IdempotencyScope.requireKey(key);
        var receipt = row(scope, action, key);
        if (receipt == null) return null;
        if (!hash.equals(receipt.getRequestHash())) throw BizException.of(ErrorCode.CONFLICT);
        return json.readValue(receipt.getResultJson(), type);
    }

    /** Owner-local business evidence lookup, never a substitute for authenticated replay/hash checks. */
    public <T> T evidence(Scope scope, String action, String key, Class<T> type) {
        requireScope(scope);
        var receipt = row(scope, action, key);
        return receipt == null ? null : json.readValue(receipt.getResultJson(), type);
    }

    private PrdStockReplenishmentCommand row(Scope scope, String action, String key) {
        return DataScopeContext.executeWithoutScope(() -> commands.selectOne(
                Wrappers.<PrdStockReplenishmentCommand>lambdaQuery()
                        .eq(PrdStockReplenishmentCommand::getTenantNo, "MAIN")
                        .eq(PrdStockReplenishmentCommand::getEntityNo, scope.entityNo())
                        .eq(PrdStockReplenishmentCommand::getStoreNo, scope.storeNo())
                        .eq(PrdStockReplenishmentCommand::getAction, action)
                        .eq(PrdStockReplenishmentCommand::getOperationKey, key)));
    }

    private static void requireScope(Scope scope) {
        if (!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.FORBIDDEN);
    }
}
