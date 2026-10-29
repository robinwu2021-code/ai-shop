package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvRefundRestore;
import ai.neargo.shop.inventory.mapper.RefundRestoreMapper;
import ai.neargo.shop.inventory.mapper.ReturnReceiptMapper;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.OriginalReservationRestoreService;
import ai.neargo.shop.inventory.service.impl.OriginalReservationSource.Source;
import ai.neargo.shop.spi.product.StockPort.SkuQty;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final ReturnReceiptMapper returns;

    public OriginalReservationRestoreServiceImpl(OriginalReservationSource source, RefundRestoreMapper claims,
                                                 InboundService inbound, ReturnReceiptMapper returns) {
        this.source = source;
        this.claims = claims;
        this.inbound = inbound;
        this.returns = returns;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", isolation = Isolation.READ_COMMITTED)
    public void restore(String purpose, String originalRef, List<ItemQty> items) {
        Map<String, Integer> needed = quantities(purpose, originalRef, items);
        List<Source> rows = source.lock(originalRef);
        restoreLocked(purpose, rows, needed);
    }

    private void restoreLocked(String purpose, List<Source> rows, Map<String, Integer> needed) {
        if (replayed(purpose, rows, needed)) {
            return;
        }
        List<Long> ids = rows.stream().map(row -> row.line().getId()).toList();
        Map<Long, Integer> used = new HashMap<>();
        claims.lockByOriginalLines(ids).forEach(claim -> {
            if (claim.getQty() == null || claim.getQty() <= 0) {
                throw conflict();
            }
            used.merge(claim.getOriginalLineId(), claim.getQty(), Math::addExact);
        });
        returns.lockByOriginalLines(ids).forEach(claim -> {
            if (claim.getQty() == null || claim.getQty() <= 0) {
                throw conflict();
            }
            used.merge(claim.getOriginalLineId(), claim.getQty(), Math::addExact);
        });
        var planned = new ArrayList<RestoreLine>();
        for (Source row : rows) {
            int wanted = needed.getOrDefault(row.line().getItemId(), 0);
            int available = row.restorableQty() - used.getOrDefault(row.line().getId(), 0);
            if (available < 0) {
                throw conflict();
            }
            int quantity = Math.min(wanted, available);
            if (quantity > 0) {
                planned.add(new RestoreLine(row, quantity));
                needed.put(row.line().getItemId(), wanted - quantity);
            }
        }
        if (needed.values().stream().anyMatch(qty -> qty != 0)) {
            throw conflict();
        }
        for (var line : planned) {
            post(purpose, line.source(), line.quantity());
        }
    }

    private record RestoreLine(Source source, int quantity) {
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", isolation = Isolation.READ_COMMITTED)
    public boolean completed(String purpose, String originalRef, List<ItemQty> items) {
        Map<String, Integer> needed = quantities(purpose, originalRef, items);
        return replayed(purpose, source.lock(originalRef), needed);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", isolation = Isolation.READ_COMMITTED)
    public void restoreSourceSkus(String purpose, String originalRef, List<SkuQty> items) {
        var rows = source.lock(originalRef);
        var needed = quantities(purpose, originalRef, frozenItems(rows, items));
        restoreLocked(purpose, rows, needed);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", isolation = Isolation.READ_COMMITTED)
    public boolean completedSourceSkus(String purpose, String originalRef, List<SkuQty> items) {
        var rows = source.lock(originalRef);
        return replayed(purpose, rows, quantities(purpose, originalRef, frozenItems(rows, items)));
    }

    private List<ItemQty> frozenItems(List<Source> rows, List<SkuQty> requested) {
        if (requested == null || requested.isEmpty()) {
            throw conflict();
        }
        var result = new ArrayList<ItemQty>();
        var itemSources = new HashMap<String, String>();
        for (var item : requested) {
            var matches = rows.stream().map(Source::line)
                    .filter(line -> item.skuNo().equals(line.getSourceSkuNo())
                            && (item.storeNo() == null || item.storeNo().equals(line.getSourceStoreNo()))
                            && (item.fulfillmentRef() == null
                                    || item.fulfillmentRef().equals(line.getFulfillmentRef())))
                    .toList();
            var identities = matches.stream().map(line -> line.getItemId()).distinct().toList();
            if (identities.size() != 1 || item.qty() <= 0) {
                throw conflict();
            }
            String identity = identities.getFirst();
            String previous = itemSources.putIfAbsent(identity, item.skuNo());
            // Ambiguous historical many-SKU-to-one-item mappings need an explicit original tuple protocol.
            if (previous != null && !previous.equals(item.skuNo()) || rows.stream().map(Source::line)
                    .anyMatch(line -> identity.equals(line.getItemId())
                            && (!item.skuNo().equals(line.getSourceSkuNo())
                                    || item.storeNo() != null && !item.storeNo().equals(line.getSourceStoreNo())
                                    || item.fulfillmentRef() != null
                                            && !item.fulfillmentRef().equals(line.getFulfillmentRef())))) {
                throw conflict();
            }
            result.add(new ItemQty(identity, item.qty()));
        }
        return result;
    }

    private boolean replayed(String purpose, List<Source> rows, Map<String, Integer> needed) {
        List<InvRefundRestore> old = claims.selectList(Wrappers.<InvRefundRestore>lambdaQuery()
                .eq(InvRefundRestore::getPurposeNo, purpose).orderByAsc(InvRefundRestore::getOriginalLineId)
                .last("FOR UPDATE"));
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
