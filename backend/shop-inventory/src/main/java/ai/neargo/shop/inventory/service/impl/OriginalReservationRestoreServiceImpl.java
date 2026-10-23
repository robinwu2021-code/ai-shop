package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvRefundRestore;
import ai.neargo.shop.inventory.entity.InvReturnReceipt;
import ai.neargo.shop.inventory.mapper.RefundRestoreMapper;
import ai.neargo.shop.inventory.mapper.ReturnReceiptMapper;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.OriginalReservationRestoreService;
import ai.neargo.shop.inventory.service.impl.OriginalReservationSource.Source;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Claims and actual RETURN postings commit in the same inventory transaction. */
@Service
@ConditionalOnInventory
public class OriginalReservationRestoreServiceImpl implements OriginalReservationRestoreService {
    private final OriginalReservationSource source;
    private final RefundRestoreMapper claims;
    private final InboundService inbound;
    private final ReturnReceiptMapper receipts;

    public OriginalReservationRestoreServiceImpl(OriginalReservationSource source, RefundRestoreMapper claims,
                                                 InboundService inbound, ReturnReceiptMapper receipts) {
        this.source = source;
        this.claims = claims;
        this.inbound = inbound;
        this.receipts = receipts;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", isolation = Isolation.READ_COMMITTED)
    public void restore(String purpose, String originalRef, List<ItemQty> items) {
        Map<String, Integer> needed = quantities(purpose, originalRef, items);
        List<Source> rows = source.lock(originalRef);
        if (replayed(purpose, rows, needed)) {
            return;
        }
        List<Long> ids = rows.stream().map(row -> row.line().getId()).toList();
        Map<Long, Integer> used = new HashMap<>();
        claims.selectList(Wrappers.<InvRefundRestore>lambdaQuery().in(InvRefundRestore::getOriginalLineId, ids))
                .forEach(claim -> used.merge(claim.getOriginalLineId(), claim.getQty(), Math::addExact));
        // Both receipt and refund restoration hold the same original reservation row locks.
        // Count physical receipts too, so the second path cannot return the same issued unit again.
        receipts.selectList(Wrappers.<InvReturnReceipt>lambdaQuery().in(InvReturnReceipt::getOriginalLineId, ids))
                .forEach(receipt -> used.merge(receipt.getOriginalLineId(), receipt.getQty(), Math::addExact));
        for (Source row : rows) {
            int wanted = needed.getOrDefault(row.line().getItemId(), 0);
            int available = row.restorableQty() - used.getOrDefault(row.line().getId(), 0);
            if (available < 0) {
                throw conflict();
            }
            int quantity = Math.min(wanted, available);
            if (quantity > 0) {
                post(purpose, row, quantity);
                needed.put(row.line().getItemId(), wanted - quantity);
            }
        }
        if (needed.values().stream().anyMatch(qty -> qty != 0)) {
            throw conflict();
        }
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", isolation = Isolation.READ_COMMITTED)
    public boolean completed(String purpose, String originalRef, List<ItemQty> items) {
        Map<String, Integer> needed = quantities(purpose, originalRef, items);
        return replayed(purpose, source.lock(originalRef), needed);
    }

    private boolean replayed(String purpose, List<Source> rows, Map<String, Integer> needed) {
        List<InvRefundRestore> old = claims.selectList(Wrappers.<InvRefundRestore>lambdaQuery()
                .eq(InvRefundRestore::getPurposeNo, purpose));
        if (old.isEmpty()) {
            return false;
        }
        Map<Long, Source> byId = rows.stream().collect(Collectors.toMap(row -> row.line().getId(),
                Function.identity()));
        Map<String, Integer> actual = new HashMap<>();
        for (InvRefundRestore claim : old) {
            Source row = byId.get(claim.getOriginalLineId());
            if (row == null || claim.getQty() == null || claim.getQty() <= 0) {
                throw conflict();
            }
            actual.merge(row.line().getItemId(), claim.getQty(), Math::addExact);
        }
        if (!actual.equals(needed)) {
            throw conflict();
        }
        return true;
    }

    private void post(String purpose, Source sourceRow, int quantity) {
        var row = sourceRow.line();
        InvRefundRestore claim = new InvRefundRestore();
        claim.setPurposeNo(purpose);
        claim.setOriginalLineId(row.getId());
        claim.setQty(quantity);
        claim.setCreatedBy("REFUND");
        claims.insert(claim);
        inbound.postDirectly(new InboundService.Draft(row.getOwnerId(), row.getLocationId(), "RETURN", purpose,
                null, null, LocalDateTime.now(), "Original reservation " + row.getReservationId(),
                List.of(new InboundService.Line(row.getItemId(), quantity, null, sourceRow.unitCostMinor()))),
                "REFUND");
    }

    private Map<String, Integer> quantities(String purpose, String originalRef, List<ItemQty> items) {
        if (purpose == null || purpose.isBlank() || originalRef == null || originalRef.isBlank()
                || items == null || items.isEmpty()) {
            throw conflict();
        }
        Map<String, Integer> result = new HashMap<>();
        for (ItemQty item : items) {
            if (item.itemId() == null || item.qty() <= 0) {
                throw conflict();
            }
            result.merge(item.itemId(), item.qty(), Math::addExact);
        }
        return result;
    }

    private IllegalStateException conflict() {
        return new IllegalStateException("STOCK_RESTORE_PURPOSE_OR_QUANTITY_CONFLICT");
    }
}
