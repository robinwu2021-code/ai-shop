package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.dto.InventoryVOs.BalanceVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.ItemDetailVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.LedgerPageVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.LedgerVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.LocationQty;
import ai.neargo.shop.inventory.dto.InventoryVOs.SummaryVO;
import ai.neargo.shop.inventory.entity.InvItem;
import ai.neargo.shop.inventory.entity.InvItemRef;
import ai.neargo.shop.inventory.entity.InvLedger;
import ai.neargo.shop.inventory.entity.InvLocation;
import ai.neargo.shop.inventory.entity.InvStockBalance;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemRefMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LocationMapper;
import ai.neargo.shop.inventory.service.StockQueryService;
import ai.neargo.shop.inventory.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 读侧实现。 */
@Service
public class StockQueryServiceImpl implements StockQueryService {

    /** 多少天没动就算滞销。与需求 B-5 的口径一致，**由服务端判**。 */
    private static final int STALE_DAYS = 90;
    private static final String FLAG_SHORTAGE = "SHORTAGE";
    private static final String FLAG_STALE = "STALE";

    private final BalanceMapper balanceMapper;
    private final ItemMapper itemMapper;
    private final ItemRefMapper refMapper;
    private final LedgerMapper ledgerMapper;
    private final LocationMapper locationMapper;

    public StockQueryServiceImpl(BalanceMapper balanceMapper, ItemMapper itemMapper,
                                 ItemRefMapper refMapper, LedgerMapper ledgerMapper,
                                 LocationMapper locationMapper) {
        this.balanceMapper = balanceMapper;
        this.itemMapper = itemMapper;
        this.refMapper = refMapper;
        this.ledgerMapper = ledgerMapper;
        this.locationMapper = locationMapper;
    }

    @Override
    public SummaryVO summary(String ownerId, String locationId) {
        List<BalanceVO> all = build(rows(ownerId, locationId));
        int shortage = (int) all.stream().filter(b -> b.flags().contains(FLAG_SHORTAGE)).count();
        int stale = (int) all.stream().filter(b -> b.flags().contains(FLAG_STALE)).count();
        return new SummaryVO(all.size(), shortage, stale);
    }

    @Override
    public List<BalanceVO> balances(String ownerId, String locationId, String filter, int limit) {
        List<BalanceVO> all = build(rows(ownerId, locationId));
        List<BalanceVO> picked = switch (filter == null ? "todo" : filter) {
            case "all" -> all;
            case "reserved" -> all.stream().filter(b -> b.reserved() > 0).toList();
            default -> all.stream().filter(b -> !b.flags().isEmpty()).toList();
        };
        // 缺货排在滞销前面：断货是「今天就要补」，滞销是「这周想想怎么清」
        return picked.stream()
                .sorted(Comparator.comparingInt((BalanceVO b) ->
                        b.flags().contains(FLAG_SHORTAGE) ? 0 : b.flags().contains(FLAG_STALE) ? 1 : 2))
                .limit(limit)
                .toList();
    }

    @Override
    public ItemDetailVO itemDetail(String ownerId, String itemId) {
        InvItem item = itemMapper.selectOne(Wrappers.<InvItem>lambdaQuery()
                .eq(InvItem::getOwnerId, ownerId).eq(InvItem::getItemId, itemId));
        if (item == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        List<InvStockBalance> balances = balanceMapper.selectList(Wrappers.<InvStockBalance>lambdaQuery()
                .eq(InvStockBalance::getOwnerId, ownerId).eq(InvStockBalance::getItemId, itemId));
        Map<String, String> names = locationMapper.selectList(Wrappers.<InvLocation>lambdaQuery()
                        .eq(InvLocation::getOwnerId, ownerId)).stream()
                .collect(Collectors.toMap(InvLocation::getLocationId, InvLocation::getName, (a, b) -> a));
        List<LocationQty> byLocation = new ArrayList<>();
        int onHand = 0;
        int reserved = 0;
        for (InvStockBalance b : balances) {
            onHand += b.getOnHand();
            reserved += b.getReserved();
            byLocation.add(new LocationQty(b.getLocationId(),
                    names.getOrDefault(b.getLocationId(), b.getLocationId()), b.getOnHand()));
        }
        return new ItemDetailVO(itemId, item.getName(), item.getSpecText(), item.getBaseUom(),
                refOf(ownerId, itemId, InvEnums.RefSystem.BARCODE), item.getItemCode(),
                onHand, reserved, onHand - reserved, byLocation);
    }

    @Override
    public LedgerPageVO ledger(String ownerId, String itemId, String locationId, Long cursor, int size) {
        List<InvLedger> rows = ledgerMapper.selectList(Wrappers.<InvLedger>lambdaQuery()
                .eq(InvLedger::getOwnerId, ownerId)
                .eq(itemId != null, InvLedger::getItemId, itemId)
                .eq(locationId != null, InvLedger::getLocationId, locationId)
                // 游标按 id 倒序：时间会被回填、时钟会回拨，而 id 是单调的
                .lt(cursor != null, InvLedger::getId, cursor)
                .orderByDesc(InvLedger::getId)
                .last("LIMIT " + size));
        List<LedgerVO> out = rows.stream().map(e -> new LedgerVO(e.getId(), e.getDocKind(),
                e.getDocNo(), e.getReasonCode(), e.getQtyDelta(), e.getBalanceAfter(),
                e.getOccurredAt(), e.getOperator())).toList();
        Long next = out.isEmpty() ? null : out.get(out.size() - 1).id();
        return new LedgerPageVO(out, next);
    }

    // ────────────────────────────────────────────────────────────────────

    private List<InvStockBalance> rows(String ownerId, String locationId) {
        return balanceMapper.selectList(Wrappers.<InvStockBalance>lambdaQuery()
                .eq(InvStockBalance::getOwnerId, ownerId)
                .eq(locationId != null, InvStockBalance::getLocationId, locationId));
    }

    private List<BalanceVO> build(List<InvStockBalance> balances) {
        if (balances.isEmpty()) {
            return List.of();
        }
        List<String> itemIds = balances.stream().map(InvStockBalance::getItemId).distinct().toList();
        Map<String, InvItem> items = itemMapper.selectList(Wrappers.<InvItem>lambdaQuery()
                        .in(InvItem::getItemId, itemIds)).stream()
                .collect(Collectors.toMap(InvItem::getItemId, Function.identity(), (a, b) -> a));
        LocalDateTime staleBefore = LocalDateTime.now().minusDays(STALE_DAYS);
        List<BalanceVO> out = new ArrayList<>();
        for (InvStockBalance b : balances) {
            InvItem item = items.get(b.getItemId());
            int available = b.getOnHand() - b.getReserved();
            Integer safety = b.getSafetyStock() != null ? b.getSafetyStock()
                    : item != null ? item.getSafetyStock() : 0;
            List<String> flags = new ArrayList<>();
            if (safety != null && safety > 0 && available < safety) {
                flags.add(FLAG_SHORTAGE);
            } else if (available <= 0) {
                flags.add(FLAG_SHORTAGE);
            }
            // 滞销要「还有货」才算 —— 零库存零动销是已经清完了，不是压着
            if (b.getOnHand() > 0 && b.getLastMovedAt() != null
                    && b.getLastMovedAt().isBefore(staleBefore)) {
                flags.add(FLAG_STALE);
            }
            out.add(new BalanceVO(b.getItemId(),
                    item == null ? b.getItemId() : item.getName(),
                    item == null ? null : item.getSpecText(),
                    item == null ? null : item.getBaseUom(),
                    b.getOnHand(), b.getReserved(), available, safety, b.getLastMovedAt(), flags));
        }
        return out;
    }

    private String refOf(String ownerId, String itemId, String system) {
        InvItemRef ref = refMapper.selectOne(Wrappers.<InvItemRef>lambdaQuery()
                .eq(InvItemRef::getOwnerId, ownerId).eq(InvItemRef::getItemId, itemId)
                .eq(InvItemRef::getRefSystem, system).last("LIMIT 1"));
        return ref == null ? null : ref.getRef();
    }
}
