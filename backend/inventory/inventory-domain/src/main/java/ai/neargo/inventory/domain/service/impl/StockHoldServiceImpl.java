package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvStockBalance;
import ai.neargo.inventory.domain.entity.InvStockHold;
import ai.neargo.inventory.domain.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockHoldMapper;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.StockHoldService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.inventory.domain.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ConditionalOnInventory
@Service
public class StockHoldServiceImpl implements StockHoldService {

    private static final Set<String> REASONS = Set.of(
            InvEnums.HoldReason.COUNT, InvEnums.HoldReason.RETURN_PENDING, InvEnums.HoldReason.MANUAL);

    private final StockHoldMapper holdMapper;
    private final BalanceMapper balanceMapper;
    private final AuditService audit;

    public StockHoldServiceImpl(StockHoldMapper holdMapper, BalanceMapper balanceMapper, AuditService audit) {
        this.holdMapper = holdMapper;
        this.balanceMapper = balanceMapper;
        this.audit = audit;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String place(Caller caller, String itemId, String locationId, String serialId, BigDecimal qty,
                        String reason, String sourceRef, String note) {
        if (!REASONS.contains(reason)) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "reason");
        }
        BigDecimal amount = serialId != null ? BigDecimal.ONE : qty;
        if (amount == null || amount.signum() <= 0) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "qty");
        }
        InvStockBalance balance = lockBalance(caller.ownerId(), itemId, locationId);
        if (balance == null) {
            throw InventoryException.of(InventoryError.STOCK_SHORT, itemId);
        }
        InvStockHold hold = new InvStockHold();
        hold.setHoldId(InvKeys.next(InvKeys.HOLD));
        hold.setOwnerId(caller.ownerId());
        hold.setItemId(itemId);
        hold.setLocationId(locationId);
        hold.setSerialId(serialId);
        hold.setQty(amount);
        hold.setReason(reason);
        hold.setSourceRef(sourceRef);
        hold.setNote(note);
        hold.setStatus(InvEnums.HoldStatus.ACTIVE);
        hold.setCreatedBy(caller.actor() == null ? caller.systemCode() : caller.actor());
        holdMapper.insert(hold);
        recompute(caller.ownerId(), itemId, locationId);
        audit.record(caller, "hold.place", "HOLD", hold.getHoldId(), null, hold, note);
        return hold.getHoldId();
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void release(Caller caller, String holdId) {
        InvStockHold hold = holdMapper.selectOne(Wrappers.<InvStockHold>lambdaQuery()
                .eq(InvStockHold::getOwnerId, caller.ownerId())
                .eq(InvStockHold::getHoldId, holdId));
        if (hold == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "hold");
        }
        if (InvEnums.HoldStatus.RELEASED.equals(hold.getStatus())) {
            return;
        }
        lockBalance(caller.ownerId(), hold.getItemId(), hold.getLocationId());
        releaseRow(caller, hold);
        recompute(caller.ownerId(), hold.getItemId(), hold.getLocationId());
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public int releaseBySource(Caller caller, String reason, String sourceRef) {
        List<InvStockHold> rows = holdMapper.selectList(Wrappers.<InvStockHold>lambdaQuery()
                .eq(InvStockHold::getOwnerId, caller.ownerId())
                .eq(InvStockHold::getReason, reason)
                .eq(InvStockHold::getSourceRef, sourceRef)
                .eq(InvStockHold::getStatus, InvEnums.HoldStatus.ACTIVE)
                .orderByAsc(InvStockHold::getItemId, InvStockHold::getLocationId));
        Set<String> touched = new HashSet<>();
        for (InvStockHold hold : rows) {
            if (touched.add(hold.getItemId() + "\n" + hold.getLocationId())) {
                lockBalance(caller.ownerId(), hold.getItemId(), hold.getLocationId());
            }
            releaseRow(caller, hold);
        }
        for (String key : touched) {
            String[] parts = key.split("\n", 2);
            recompute(caller.ownerId(), parts[0], parts[1]);
        }
        return rows.size();
    }

    @Override
    public List<InvStockHold> active(String ownerId, String itemId, String locationId) {
        return holdMapper.selectList(Wrappers.<InvStockHold>lambdaQuery()
                .eq(InvStockHold::getOwnerId, ownerId)
                .eq(itemId != null, InvStockHold::getItemId, itemId)
                .eq(locationId != null, InvStockHold::getLocationId, locationId)
                .eq(InvStockHold::getStatus, InvEnums.HoldStatus.ACTIVE)
                .orderByAsc(InvStockHold::getId));
    }

    private void releaseRow(Caller caller, InvStockHold hold) {
        hold.setStatus(InvEnums.HoldStatus.RELEASED);
        hold.setReleasedAt(LocalDateTime.now());
        hold.setReleasedBy(caller.actor() == null ? caller.systemCode() : caller.actor());
        hold.setUpdatedBy(hold.getReleasedBy());
        holdMapper.updateById(hold);
        audit.record(caller, "hold.release", "HOLD", hold.getHoldId(), null, null, null);
    }

    /**
     * 按「同一件至多计 1」重算不可用数量，写回余额行。
     * 单件按件去重；数量冻结按原因求和后取最大（不同原因冻的是同一批货）。
     */
    private void recompute(String ownerId, String itemId, String locationId) {
        Set<String> serials = new HashSet<>();
        Map<String, BigDecimal> byReason = new HashMap<>();
        for (InvStockHold h : active(ownerId, itemId, locationId)) {
            if (h.getSerialId() != null) {
                serials.add(h.getSerialId());
            } else {
                byReason.merge(h.getReason(), h.getQty(), BigDecimal::add);
            }
        }
        BigDecimal quantity = byReason.values().stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal unavailable = quantity.add(BigDecimal.valueOf(serials.size()));
        if (balanceMapper.setUnavailable(ownerId, itemId, locationId, unavailable) == 0) {
            // 冻不进已预留的货：整笔回滚，冻结行一起撤销
            throw InventoryException.of(InventoryError.STOCK_SHORT, itemId);
        }
    }

    /** 固定先锁余额行再动冻结：与过账、预留同一把锁、同一个顺序，不会互相等死。 */
    private InvStockBalance lockBalance(String ownerId, String itemId, String locationId) {
        return balanceMapper.selectOne(Wrappers.<InvStockBalance>lambdaQuery()
                .eq(InvStockBalance::getOwnerId, ownerId)
                .eq(InvStockBalance::getItemId, itemId)
                .eq(InvStockBalance::getLocationId, locationId)
                .last("FOR UPDATE"));
    }
}
