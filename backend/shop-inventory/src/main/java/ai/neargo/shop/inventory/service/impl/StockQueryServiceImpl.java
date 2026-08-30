package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.dto.InventoryVOs.BalanceVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.DocumentVO;
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
import ai.neargo.shop.inventory.entity.InvInboundOrder;
import ai.neargo.shop.inventory.entity.InvOutboundOrder;
import ai.neargo.shop.inventory.entity.InvStockCount;
import ai.neargo.shop.inventory.entity.InvTransferOrder;
import ai.neargo.shop.inventory.mapper.InventoryMappers.InboundOrderMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.StockCountMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.TransferOrderMapper;
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
@ConditionalOnInventory
@Service
public class StockQueryServiceImpl implements StockQueryService {

    /** 多少天没动就算滞销。与需求 B-5 的口径一致，**由服务端判**。 */
    private static final int STALE_DAYS = 90;
    private static final String FLAG_SHORTAGE = "SHORTAGE";
    private static final String FLAG_STALE = "STALE";
    /**
     * 来源商品已下架。<b>与另外两个 flag 不是一类</b>：那两个说的是库存健不健康，
     * 这一个说的是「这一行是哪件货」—— 所以它是挑货弹层里唯一带出去的 flag。
     *
     * <p>只在 {@code source_on_sale = 0} 时加。<b>null 不算下架</b>：
     * 那是「还没同步过」，给存量物料统统标上「已下架」等于凭空造事实。
     */
    private static final String FLAG_OFF_SALE = "OFF_SALE";

    private final BalanceMapper balanceMapper;
    private final ItemMapper itemMapper;
    private final ItemRefMapper refMapper;
    private final LedgerMapper ledgerMapper;
    private final LocationMapper locationMapper;
    private final InboundOrderMapper inboundMapper;
    private final OutboundOrderMapper outboundMapper;
    private final StockCountMapper countMapper;
    private final TransferOrderMapper transferMapper;

    public StockQueryServiceImpl(BalanceMapper balanceMapper, ItemMapper itemMapper,
                                 ItemRefMapper refMapper, LedgerMapper ledgerMapper,
                                 LocationMapper locationMapper, InboundOrderMapper inboundMapper,
                                 OutboundOrderMapper outboundMapper, StockCountMapper countMapper,
                                 TransferOrderMapper transferMapper) {
        this.balanceMapper = balanceMapper;
        this.itemMapper = itemMapper;
        this.refMapper = refMapper;
        this.ledgerMapper = ledgerMapper;
        this.locationMapper = locationMapper;
        this.inboundMapper = inboundMapper;
        this.outboundMapper = outboundMapper;
        this.countMapper = countMapper;
        this.transferMapper = transferMapper;
    }

    @Override
    public SummaryVO summary(String ownerId, String locationId) {
        List<BalanceVO> all = build(rows(ownerId, locationId));
        int shortage = (int) all.stream().filter(b -> b.flags().contains(FLAG_SHORTAGE)).count();
        int stale = (int) all.stream().filter(b -> b.flags().contains(FLAG_STALE)).count();
        // 在途 = 已发出未收货的调拨单。**不按 locationId 过滤**：一张调拨单跨两个库位，
        // 按当前库位筛会让「从别处发到我这儿」的单在收货方看不见 —— 而收货正是他要做的事
        int inTransit = Math.toIntExact(transferMapper.selectCount(
                Wrappers.<InvTransferOrder>lambdaQuery()
                        .eq(InvTransferOrder::getOwnerId, ownerId)
                        .eq(InvTransferOrder::getStatus, InvEnums.TransferStatus.SHIPPED)));
        // 还开着的盘点单：给**最近的一张**。盘点是当场做的事，手上那张一定是刚开的
        InvStockCount open = countMapper.selectOne(Wrappers.<InvStockCount>lambdaQuery()
                .eq(InvStockCount::getOwnerId, ownerId)
                .eq(locationId != null, InvStockCount::getLocationId, locationId)
                .eq(InvStockCount::getStatus, "COUNTING")
                .orderByDesc(InvStockCount::getId)
                .last("LIMIT 1"));
        return new SummaryVO(all.size(), shortage, stale, inTransit,
                open == null ? null : open.getCountNo());
    }

    @Override
    public List<BalanceVO> balances(String ownerId, String locationId, String filter, int limit) {
        List<BalanceVO> all = build(rows(ownerId, locationId));
        /*
         * `shortage` / `stale` 是**精确的两档**，与 `todo`（两者的并集）分开。
         *
         * 端上那四个数是可点的：点「缺货 6」就该给这 6 条。此前它只能落到
         * `todo`，于是点「滞销」给出的列表里混着缺货，点「在售 SKU 204」
         * 给的是 18 条 —— **数字说一个数，点下去给另一个**，且不报错。
         */
        List<BalanceVO> picked = switch (filter == null ? "todo" : filter) {
            case "all" -> all;
            case "reserved" -> all.stream().filter(b -> b.reserved() > 0).toList();
            case "shortage" -> all.stream().filter(b -> b.flags().contains(FLAG_SHORTAGE)).toList();
            case "stale" -> all.stream().filter(b -> b.flags().contains(FLAG_STALE)).toList();
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
    public List<BalanceVO> pickableItems(String ownerId, String locationId, String keyword, int limit) {
        /*
         * **从物料出发。** 余额行按需建，一件从没进过货的物料没有那一行 ——
         * 从余额出发的话它不存在，商家没法给它记第一笔进货。
         */
        String k = keyword == null ? "" : keyword.trim();
        List<InvItem> items = itemMapper.selectList(Wrappers.<InvItem>lambdaQuery()
                .eq(InvItem::getOwnerId, ownerId)
                .eq(InvItem::getStatus, InvEnums.MasterStatus.ACTIVE)
                .and(!k.isEmpty(), w -> w.like(InvItem::getName, k)
                        .or().like(InvItem::getSpecText, k))
                .orderByAsc(InvItem::getId)
                .last("limit " + Math.max(1, limit)));
        if (items.isEmpty()) {
            return List.of();
        }
        List<String> ids = items.stream().map(InvItem::getItemId).toList();
        Map<String, InvStockBalance> byItem = balanceMapper.selectList(
                        Wrappers.<InvStockBalance>lambdaQuery()
                                .eq(InvStockBalance::getOwnerId, ownerId)
                                .eq(locationId != null, InvStockBalance::getLocationId, locationId)
                                .in(InvStockBalance::getItemId, ids)).stream()
                .collect(Collectors.toMap(InvStockBalance::getItemId, Function.identity(), (a, b) -> a));

        List<BalanceVO> out = new ArrayList<>();
        for (InvItem item : items) {
            InvStockBalance b = byItem.get(item.getItemId());
            int onHand = b == null ? 0 : b.getOnHand();
            int reserved = b == null ? 0 : b.getReserved();
            /*
             * **只带 OFF_SALE 一个 flag。** 缺货 / 滞销是「看库存」那一屏的判据，
             * 挑货不需要 —— 带上去会让弹层里冒出一堆红字，而商家此刻只是在找一件货。
             *
             * 而「已下架」是例外：它回答的不是「这件货健不健康」，是**「这一行是哪件货」**。
             * 线上有 13 组同名同规格的物料，弹层里几行完全一样（同库位、库存也一样），
             * 不标出来商家挑哪一行都不知道自己挑的是什么。
             */
            out.add(new BalanceVO(item.getItemId(), item.getName(), item.getSpecText(),
                    item.getBaseUom(), onHand, reserved, onHand - reserved,
                    item.getSafetyStock(), b == null ? null : b.getLastMovedAt(),
                    offSaleFlags(item)));
        }
        return out;
    }

    /**
     * <b>{@code null} 不算下架。</b> 那一列是 2026-08-30 才加的，存量 209 件物料
     * 全是 null —— 它们要等下一次商品上下架同步过来才有值。
     * 把 null 当成下架，就是给一整批还在正常卖的货凭空贴上「已下架」。
     */
    private static List<String> offSaleFlags(InvItem item) {
        return Integer.valueOf(0).equals(item.getSourceOnSale())
                ? List.of(FLAG_OFF_SALE) : List.of();
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
    public LedgerPageVO ledger(String ownerId, String itemId, String docNo, String locationId, Long cursor, int size) {
        List<InvLedger> rows = ledgerMapper.selectList(Wrappers.<InvLedger>lambdaQuery()
                .eq(InvLedger::getOwnerId, ownerId)
                .eq(itemId != null, InvLedger::getItemId, itemId)
                .eq(docNo != null && !docNo.isBlank(), InvLedger::getDocNo, docNo)
                .eq(locationId != null, InvLedger::getLocationId, locationId)
                // 游标按 id 倒序：时间会被回填、时钟会回拨，而 id 是单调的
                .lt(cursor != null, InvLedger::getId, cursor)
                .orderByDesc(InvLedger::getId)
                .last("LIMIT " + size));
        // 名字批量取：按单查时一张单十几行，逐行查等于十几趟
        Map<String, String> names = rows.isEmpty() ? Map.of()
                : itemMapper.selectList(Wrappers.<InvItem>lambdaQuery()
                        .eq(InvItem::getOwnerId, ownerId)
                        .in(InvItem::getItemId, rows.stream().map(InvLedger::getItemId).distinct().toList()))
                .stream().collect(Collectors.toMap(InvItem::getItemId, InvItem::getName, (a, b) -> a));
        List<LedgerVO> out = rows.stream().map(e -> new LedgerVO(e.getId(),
                e.getItemId(), names.getOrDefault(e.getItemId(), e.getItemId()), e.getDocKind(),
                e.getDocNo(), e.getReasonCode(), e.getQtyDelta(), e.getBalanceAfter(),
                e.getOccurredAt(), e.getOperator())).toList();
        Long next = out.isEmpty() ? null : out.get(out.size() - 1).id();
        return new LedgerPageVO(out, next);
    }

    @Override
    public List<DocumentVO> documents(String ownerId, String locationId, String kind, String docNo, int limit) {
        // 单号定位：从台账那一行点过来的「看这张单」，一次只要一张
        boolean hasNo = docNo != null && !docNo.isBlank();
        List<DocumentVO> out = new ArrayList<>();
        if (kind == null || "IN".equals(kind)) {
            for (InvInboundOrder h : inboundMapper.selectList(Wrappers.<InvInboundOrder>lambdaQuery()
                    .eq(InvInboundOrder::getOwnerId, ownerId)
                    .eq(hasNo, InvInboundOrder::getInboundNo, docNo)
                    .eq(locationId != null, InvInboundOrder::getLocationId, locationId)
                    .orderByDesc(InvInboundOrder::getId).last("LIMIT " + limit))) {
                // 差异字段收进 subtitle 由服务端拼：让端上按 kind 分四种拼法，
                // 那四段文案迟早各自漂
                out.add(new DocumentVO("IN", h.getInboundNo(), h.getStatus(),
                        subtitle(h.getSourceType(), h.getSupplierName(), h.getSourceRef()),
                        h.getTotalQty(), h.getOccurredAt(), h.getCreatedBy()));
            }
        }
        if (kind == null || "OUT".equals(kind)) {
            for (InvOutboundOrder h : outboundMapper.selectList(Wrappers.<InvOutboundOrder>lambdaQuery()
                    .eq(InvOutboundOrder::getOwnerId, ownerId)
                    .eq(hasNo, InvOutboundOrder::getOutboundNo, docNo)
                    .eq(locationId != null, InvOutboundOrder::getLocationId, locationId)
                    .orderByDesc(InvOutboundOrder::getId).last("LIMIT " + limit))) {
                out.add(new DocumentVO("OUT", h.getOutboundNo(), h.getStatus(),
                        subtitle(h.getPurpose(), h.getReasonCode(), h.getSourceRef()),
                        -h.getTotalQty(), h.getOccurredAt(), h.getCreatedBy()));
            }
        }
        if (kind == null || "COUNT".equals(kind)) {
            for (InvStockCount h : countMapper.selectList(Wrappers.<InvStockCount>lambdaQuery()
                    .eq(InvStockCount::getOwnerId, ownerId)
                    .eq(hasNo, InvStockCount::getCountNo, docNo)
                    .eq(locationId != null, InvStockCount::getLocationId, locationId)
                    .orderByDesc(InvStockCount::getId).last("LIMIT " + limit))) {
                out.add(new DocumentVO("COUNT", h.getCountNo(), h.getStatus(),
                        subtitle("盘点", h.getScope(), null), 0, h.getStartedAt(), h.getOperator()));
            }
        }
        if (kind == null || "TRANSFER".equals(kind)) {
            for (InvTransferOrder h : transferMapper.selectList(Wrappers.<InvTransferOrder>lambdaQuery()
                    .eq(InvTransferOrder::getOwnerId, ownerId)
                    .eq(hasNo, InvTransferOrder::getTransferNo, docNo)
                    .orderByDesc(InvTransferOrder::getId).last("LIMIT " + limit))) {
                out.add(new DocumentVO("TRANSFER", h.getTransferNo(), h.getStatus(),
                        subtitle("调拨", h.getFromLocationId(), h.getToLocationId()),
                        0, h.getShippedAt(), h.getOperator()));
            }
        }
        // 四类合成一个列表后统一按时间倒序 —— 端上拿到的就是它要显示的顺序
        return out.stream()
                .sorted(Comparator.comparing(DocumentVO::occurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit).toList();
    }

    // ────────────────────────────────────────────────────────────────────

    private static String subtitle(String a, String b, String c) {
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{a, b, c}) {
            if (s != null && !s.isBlank()) {
                sb.append(sb.isEmpty() ? "" : " · ").append(s);
            }
        }
        return sb.toString();
    }

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
            if (item != null && Integer.valueOf(0).equals(item.getSourceOnSale())) {
                flags.add(FLAG_OFF_SALE);
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
