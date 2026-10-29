package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.entity.InvLedger;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.entity.InvStockBalance;
import ai.neargo.inventory.domain.entity.InvStockEpisode;
import ai.neargo.inventory.domain.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ItemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockEpisodeMapper;
import ai.neargo.inventory.domain.service.WriteAuthorityFlipService;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ConditionalOnInventory
@Service
public class WriteAuthorityFlipServiceImpl implements WriteAuthorityFlipService {

    private final OwnerMapper ownerMapper;
    private final BalanceMapper balanceMapper;
    private final LedgerMapper ledgerMapper;
    private final StockEpisodeMapper episodeMapper;
    private final ItemMapper itemMapper;
    private final TransactionTemplate tx;

    /**
     * 置 {@code FLIPPING} 后、对账前等多久：解析业主在事务外的调用（商城控制器先解析业主再进服务事务）
     * 可能在置位前已拿到业主号，这段时间让它们落地或失败。对账本身在此之后才读，所以对账结果包含它们。
     */
    private long drainMillis = 2000;

    public WriteAuthorityFlipServiceImpl(OwnerMapper ownerMapper, BalanceMapper balanceMapper,
                                         LedgerMapper ledgerMapper, StockEpisodeMapper episodeMapper,
                                         ItemMapper itemMapper,
                                         @Qualifier("invTransactionManager") PlatformTransactionManager txManager) {
        this.ownerMapper = ownerMapper;
        this.balanceMapper = balanceMapper;
        this.ledgerMapper = ledgerMapper;
        this.episodeMapper = episodeMapper;
        this.itemMapper = itemMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    @Value("${inventory.flip.drain-millis:2000}")
    void setDrainMillis(long drainMillis) {
        this.drainMillis = drainMillis;
    }

    @Override
    public Result flip(String ownerId, String to, String operator) {
        if (!InvEnums.WriteAuthority.EMBEDDED.equals(to) && !InvEnums.WriteAuthority.SVC.equals(to)) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "writeAuthority");
        }
        // ① 置翻转中：锁业主行，状态从「原写入方」改过来。已在翻转中拒绝 —— 两个人同时翻会互相翻回
        String from = tx.execute(s -> {
            InvOwner owner = ownerMapper.selectOne(Wrappers.<InvOwner>lambdaQuery()
                    .eq(InvOwner::getOwnerId, ownerId).last("FOR UPDATE"));
            if (owner == null) {
                throw InventoryException.of(InventoryError.NOT_FOUND, "owner");
            }
            if (InvEnums.WriteAuthority.FLIPPING.equals(owner.getWriteAuthority())) {
                throw InventoryException.of(InventoryError.CONFLICT, "flipping");
            }
            String previous = owner.getWriteAuthority();
            set(ownerId, InvEnums.WriteAuthority.FLIPPING, operator);
            return previous;
        });
        // ② 等在途写入落地
        drain();
        // ③ 对账：平就翻到目标，不平就翻回原值
        Reconciliation r;
        try {
            r = reconcile(ownerId);
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> set(ownerId, from, operator));
            throw e;
        }
        String result = r.clean() ? to : from;
        tx.executeWithoutResult(s -> set(ownerId, result, operator));
        return new Result(from, to, result, r);
    }

    @Override
    public Reconciliation reconcile(String ownerId) {
        return tx.execute(s -> {
            List<Diff> diffs = new ArrayList<>();
            Map<String, BigDecimal> ledger = new HashMap<>();
            for (Map<String, Object> row : ledgerMapper.selectMaps(new QueryWrapper<InvLedger>()
                    .select("item_id", "location_id", "SUM(qty_delta) AS total")
                    .eq("owner_id", ownerId).groupBy("item_id", "location_id"))) {
                ledger.put(key(value(row, "item_id"), value(row, "location_id")), decimal(value(row, "total")));
            }
            Map<String, BigDecimal> inStock = new HashMap<>();
            for (InvStockEpisode e : episodeMapper.selectList(Wrappers.<InvStockEpisode>lambdaQuery()
                    .eq(InvStockEpisode::getOwnerId, ownerId).eq(InvStockEpisode::getStatus, "OPEN"))) {
                inStock.merge(key(e.getItemId(), e.getLocationId()), BigDecimal.ONE, BigDecimal::add);
            }
            Set<String> serialItems = new HashSet<>();
            itemMapper.selectList(Wrappers.<InvItem>lambdaQuery().eq(InvItem::getOwnerId, ownerId)
                            .eq(InvItem::getTrackingMode, InvEnums.TrackingMode.SERIAL))
                    .forEach(i -> serialItems.add(i.getItemId()));

            List<InvStockBalance> balances = balanceMapper.selectList(Wrappers.<InvStockBalance>lambdaQuery()
                    .eq(InvStockBalance::getOwnerId, ownerId));
            Set<String> seen = new HashSet<>();
            for (InvStockBalance b : balances) {
                String k = key(b.getItemId(), b.getLocationId());
                seen.add(k);
                BigDecimal onHand = nz(b.getOnHandQuantity());
                BigDecimal sum = ledger.getOrDefault(k, BigDecimal.ZERO);
                if (onHand.compareTo(sum) != 0) {
                    diffs.add(new Diff("LEDGER", b.getItemId(), b.getLocationId(), onHand, sum));
                }
                if (serialItems.contains(b.getItemId())) {
                    BigDecimal pieces = inStock.getOrDefault(k, BigDecimal.ZERO);
                    if (onHand.compareTo(pieces) != 0) {
                        diffs.add(new Diff("SERIAL", b.getItemId(), b.getLocationId(), onHand, pieces));
                    }
                }
            }
            // 有流水没余额行：流水之和不为零就是丢了余额
            ledger.forEach((k, sum) -> {
                if (!seen.contains(k) && sum.signum() != 0) {
                    String[] p = k.split("\n", 2);
                    diffs.add(new Diff("LEDGER", p[0], p[1], BigDecimal.ZERO, sum));
                }
            });
            return new Reconciliation(diffs.isEmpty(), balances.size(), diffs);
        });
    }

    private void set(String ownerId, String authority, String operator) {
        ownerMapper.update(null, Wrappers.<InvOwner>lambdaUpdate()
                .set(InvOwner::getWriteAuthority, authority).set(InvOwner::getUpdatedBy, operator)
                .eq(InvOwner::getOwnerId, ownerId));
    }

    private void drain() {
        if (drainMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(drainMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String key(String itemId, String locationId) {
        return itemId + "\n" + locationId;
    }

    private static String value(Map<String, Object> row, String column) {
        Object v = row.containsKey(column) ? row.get(column) : row.get(column.toUpperCase());
        return v == null ? null : v.toString();
    }

    private static BigDecimal decimal(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
