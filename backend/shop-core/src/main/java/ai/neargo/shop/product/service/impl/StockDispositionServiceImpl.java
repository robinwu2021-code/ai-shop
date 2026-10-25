package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStockLock;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockLockMapper;
import ai.neargo.shop.product.port.StockPortImpl;
import ai.neargo.shop.product.service.StockDispositionService;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort.Confirmation;
import ai.neargo.shop.spi.product.StockDispositionPort.CurrentState;
import ai.neargo.shop.spi.product.StockDispositionPort.Disposition;
import ai.neargo.shop.spi.product.StockDispositionPort.Lookup;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import ai.neargo.shop.spi.product.StockDispositionPort.Outcome;
import ai.neargo.shop.spi.product.StockDispositionPort.OwnerLine;
import ai.neargo.shop.spi.product.StockPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The existing platform stock owner proves complete original rows and tracked successful mutations.
 */
@Service
public class StockDispositionServiceImpl implements StockDispositionService {
    private final StockLockMapper locks;
    private final SkuMapper skus;
    private final StockPortImpl stock;
    private final ObjectMapper json;

    public StockDispositionServiceImpl(
            StockLockMapper locks, SkuMapper skus, StockPortImpl stock, ObjectMapper json) {
        this.locks = locks;
        this.skus = skus;
        this.stock = stock;
        this.json = json;
    }

    @Override
    @Transactional
    public Disposition confirmOriginal(Confirmation command) {
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var original = command.original();
                    var rows = locks.lockOriginal(original.orderNo());
                    String invalid = invalid(original, rows);
                    if (invalid != null) {
                        return disposition(
                                original,
                                command.operationNo(),
                                rows,
                                Outcome.REJECTED,
                                rows.isEmpty() ? CurrentState.MISSING : CurrentState.INCONSISTENT,
                                invalid);
                    }
                    for (var row : rows) {
                        if (row.getConfirmationOperationNo() == null) {
                            if (!PrdStockLock.LOCKED.equals(row.getStatus())) {
                                return disposition(
                                        original,
                                        command.operationNo(),
                                        rows,
                                        Outcome.REJECTED,
                                        state(row),
                                        "UNTRACKED_COMPLETED_ORIGINAL");
                            }
                        } else if (!command.operationNo()
                                .equals(row.getConfirmationOperationNo())) {
                            return disposition(
                                    original,
                                    command.operationNo(),
                                    rows,
                                    Outcome.REJECTED,
                                    CurrentState.INCONSISTENT,
                                    "STOCK_OPERATION_CONFLICT");
                        }
                    }
                    for (var row : rows) {
                        if (row.getConfirmationOperationNo() == null) {
                            row.setConfirmationOperationNo(command.operationNo());
                            changed(locks.updateById(row));
                        }
                    }
                    stock.confirm(original.orderNo(), original.lifecycleVersion());
                    return observeLocked(original, command.operationNo());
                });
    }

    @Override
    @Transactional
    public Disposition observeOriginal(Lookup query) {
        return DataScopeContext.executeWithoutScope(
                () -> observeLocked(query.original(), query.operationNo()));
    }

    private Disposition observeLocked(Original original, String operation) {
        var rows = locks.lockOriginal(original.orderNo());
        String invalid = invalid(original, rows);
        if (invalid != null) {
            return disposition(
                    original,
                    operation,
                    rows,
                    Outcome.REJECTED,
                    rows.isEmpty() ? CurrentState.MISSING : CurrentState.INCONSISTENT,
                    invalid);
        }
        if (rows.stream().anyMatch(row -> PrdStockLock.RELEASED.equals(row.getStatus()))) {
            return disposition(
                    original,
                    operation,
                    rows,
                    Outcome.REJECTED,
                    CurrentState.RELEASED,
                    "STOCK_RELEASED");
        }
        boolean consumed =
                rows.stream().allMatch(row -> PrdStockLock.CONFIRMED.equals(row.getStatus()));
        boolean allocated =
                original.lifecycleVersion() == 2
                        && rows.stream()
                                .allMatch(row -> PrdStockLock.ALLOCATED.equals(row.getStatus()));
        boolean marked =
                rows.stream().allMatch(row -> operation.equals(row.getConfirmationOperationNo()));
        if (marked && (consumed || allocated)) {
            return disposition(
                    original,
                    operation,
                    rows,
                    Outcome.APPLIED,
                    consumed ? CurrentState.CONSUMED : CurrentState.ALLOCATED,
                    null);
        }
        if (rows.stream()
                .allMatch(
                        row ->
                                PrdStockLock.LOCKED.equals(row.getStatus())
                                        && (row.getConfirmationOperationNo() == null
                                                || operation.equals(
                                                        row.getConfirmationOperationNo())))) {
            return disposition(
                    original,
                    operation,
                    rows,
                    Outcome.PENDING,
                    CurrentState.RESERVED,
                    "STOCK_RESERVED");
        }
        return disposition(
                original,
                operation,
                rows,
                Outcome.REJECTED,
                CurrentState.INCONSISTENT,
                "STOCK_STATE_CONFLICT");
    }

    private String invalid(Original original, List<PrdStockLock> rows) {
        if (!List.of("PLATFORM", "DUAL").contains(original.authority()) || rows.isEmpty()) {
            return "STOCK_ORIGINAL_MISSING";
        }
        var actual = new ArrayList<StockPort.SkuQty>();
        for (var row : rows) {
            if (!original.tenantNo().equals(row.getTenantNo())
                    || !original.manifestHash().equals(row.getSourceManifestHash())
                    || !Objects.equals(original.lifecycleVersion(), row.getLifecycleVersion())
                    || row.getQty() == null
                    || row.getQty() <= 0
                    || row.getFulfillmentRef() == null) {
                return "STOCK_MANIFEST_CONFLICT";
            }
            var sku =
                    skus.selectOne(
                            Wrappers.<PrdSku>lambdaQuery()
                                    .eq(PrdSku::getSkuNo, row.getSkuNo())
                                    .last("LIMIT 1"));
            if (sku == null || !original.entityNo().equals(sku.getEntityNo())) {
                return "STOCK_OWNER_CONFLICT";
            }
            actual.add(
                    new StockPort.SkuQty(
                            row.getSkuNo(),
                            row.getQty(),
                            row.getSourceStoreNo(),
                            row.getFulfillmentRef(),
                            row.getLifecycleVersion()));
        }
        return StockDispositionContract.manifestHash(StockDispositionContract.needs(actual))
                        .equals(original.manifestHash())
                ? null
                : "STOCK_MANIFEST_COVERAGE";
    }

    private Disposition disposition(
            Original original,
            String operation,
            List<PrdStockLock> rows,
            Outcome outcome,
            CurrentState current,
            String reason) {
        var lines =
                rows.stream()
                        .map(
                                row -> {
                                    var state = state(row);
                                    int qty = row.getQty() == null ? 0 : row.getQty();
                                    return new OwnerLine(
                                            Long.toString(row.getId()),
                                            row.getSkuNo(),
                                            row.getSourceStoreNo(),
                                            row.getFulfillmentRef(),
                                            original.entityNo(),
                                            row.getSkuNo(),
                                            row.getStoreNo(),
                                            row.getPresale(),
                                            qty,
                                            state == CurrentState.ALLOCATED ? qty : 0,
                                            state == CurrentState.CONSUMED ? qty : 0,
                                            state == CurrentState.RELEASED ? qty : 0,
                                            row.getVersion() == null ? 0 : row.getVersion(),
                                            state);
                                })
                        .toList();
        String hash =
                StockDispositionContract.hash(
                        json.writeValueAsString(
                                Arrays.asList(
                                        "STOCK_DISPOSITION_V1",
                                        original,
                                        operation,
                                        outcome,
                                        current,
                                        lines,
                                        reason)));
        return new Disposition(
                original, operation, outcome, current, lines, hash, Instant.now(), reason);
    }

    private static CurrentState state(PrdStockLock row) {
        return switch (row.getStatus()) {
            case PrdStockLock.LOCKED -> CurrentState.RESERVED;
            case PrdStockLock.ALLOCATED -> CurrentState.ALLOCATED;
            case PrdStockLock.CONFIRMED -> CurrentState.CONSUMED;
            case PrdStockLock.RELEASED -> CurrentState.RELEASED;
            default -> CurrentState.INCONSISTENT;
        };
    }

    private static void changed(int affected) {
        if (affected != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
}
