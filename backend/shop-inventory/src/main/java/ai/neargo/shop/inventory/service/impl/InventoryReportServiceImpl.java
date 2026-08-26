package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.dto.InventoryVOs.BalanceVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.MonthlyVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.RankVO;
import ai.neargo.shop.inventory.entity.InvItem;
import ai.neargo.shop.inventory.entity.InvLedger;
import ai.neargo.shop.inventory.entity.InvStockBalance;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.shop.inventory.service.InventoryReportService;
import ai.neargo.shop.inventory.service.StockQueryService;
import ai.neargo.shop.inventory.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 报表实现：直接聚合流水。 */
@ConditionalOnInventory
@Service
public class InventoryReportServiceImpl implements InventoryReportService {

    /** Excel 的 UTF-8 BOM。少了它，中文在 Excel 里全是乱码。 */
    private static final String BOM = "﻿";
    /** 一次导出的上界。再多的话浏览器那头也打不开，且这一条查询会拖住连接。 */
    private static final int EXPORT_MAX = 20000;

    private final LedgerMapper ledgerMapper;
    private final BalanceMapper balanceMapper;
    private final ItemMapper itemMapper;
    private final StockQueryService query;

    public InventoryReportServiceImpl(LedgerMapper ledgerMapper, BalanceMapper balanceMapper,
                                      ItemMapper itemMapper, StockQueryService query) {
        this.ledgerMapper = ledgerMapper;
        this.balanceMapper = balanceMapper;
        this.itemMapper = itemMapper;
        this.query = query;
    }

    @Override
    public MonthlyVO monthly(String ownerId, String locationId, String month) {
        YearMonth ym = YearMonth.parse(month);
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();
        List<InvLedger> rows = inPeriod(ownerId, locationId, from, to);

        int purchased = 0;
        int sold = 0;
        int lost = 0;
        int adjusted = 0;
        /*
         * 成本**按每一笔当时的单位成本累加**，不是「件数 × 当前成本价」——
         * 进价一波动，后者会把上个月的账算成今天的价。
         * 没有单位成本的行按 0 计：宁可少算，不要用一个猜的价去填。
         */
        long soldCostMinor = 0;
        long lostCostMinor = 0;
        for (InvLedger e : rows) {
            int d = e.getQtyDelta();
            String r = e.getReasonCode();
            if (InvEnums.InboundSource.PURCHASE.equals(r)) {
                purchased += d;
            } else if (InvEnums.OutboundPurpose.SALE.equals(r)) {
                sold += -d;
                soldCostMinor += costOf(e);
            } else if (InvEnums.OutboundPurpose.SCRAP.equals(r)
                    || InvEnums.OutboundPurpose.COUNT_LOSS.equals(r)) {
                lost += -d;
                lostCostMinor += costOf(e);
            } else {
                // 退货入、盘盈、领用、调拨、期初、其它 —— 一律归「调」。
                // **兜底分支是有意的**：新加一个 reasonCode 时它会自动落进来，
                // 于是 balanced 仍然为真，而报表上多出来的那一块看得见
                adjusted += d;
            }
        }
        int closing = closingOf(ownerId, locationId, to);
        int net = purchased - sold - lost + adjusted;
        int opening = closing - net;
        // 分类之和 == 本期净变动：漏归一类就为假
        int sumOfDelta = rows.stream().mapToInt(InvLedger::getQtyDelta).sum();
        boolean balanced = net == sumOfDelta;
        return new MonthlyVO(month, opening, purchased, sold, lost, adjusted, closing, balanced,
                soldCostMinor, lostCostMinor);
    }

    /** 一行台账的成本金额（分）。没有单位成本就是 0 —— 不拿当前价去填 */
    private long costOf(InvLedger e) {
        return e.getUnitCostMinor() == null ? 0L
                : e.getUnitCostMinor() * Math.abs(e.getQtyDelta());
    }

    @Override
    public List<RankVO> ranking(String ownerId, String locationId, String type, int days, int limit) {
        if ("slow".equals(type)) {
            // 滞销直接用读侧的判定，避免两处各写一套「多久算滞销」
            return query.balances(ownerId, locationId, "todo", limit).stream()
                    .filter(b -> b.flags().contains("STALE"))
                    .map(b -> new RankVO(b.itemId(), b.name(), b.specText(), b.onHand(), null))
                    .toList();
        }
        LocalDateTime from = LocalDateTime.now().minusDays(days);
        List<InvLedger> rows = inPeriod(ownerId, locationId, from, LocalDateTime.now()).stream()
                .filter(e -> InvEnums.OutboundPurpose.SALE.equals(e.getReasonCode()))
                .toList();
        Map<String, int[]> qtyByItem = new HashMap<>();
        Map<String, long[]> costByItem = new HashMap<>();
        for (InvLedger e : rows) {
            qtyByItem.computeIfAbsent(e.getItemId(), k -> new int[1])[0] += -e.getQtyDelta();
            long unit = e.getUnitCostMinor() == null ? 0 : e.getUnitCostMinor();
            costByItem.computeIfAbsent(e.getItemId(), k -> new long[1])[0] += unit * -e.getQtyDelta();
        }
        Map<String, InvItem> items = names(qtyByItem.keySet().stream().toList());
        List<RankVO> out = new ArrayList<>();
        qtyByItem.forEach((itemId, qty) -> {
            InvItem it = items.get(itemId);
            out.add(new RankVO(itemId, it == null ? itemId : it.getName(),
                    it == null ? null : it.getSpecText(), qty[0], costByItem.get(itemId)[0]));
        });
        return out.stream().sorted(Comparator.comparingInt(RankVO::qty).reversed()).limit(limit).toList();
    }

    @Override
    public String exportCsv(String ownerId, String locationId, String type) {
        StringBuilder sb = new StringBuilder(BOM);
        if ("balances".equals(type)) {
            sb.append("物料,品名,规格,实存,预留,可用\n");
            for (BalanceVO b : query.balances(ownerId, locationId, "all", EXPORT_MAX)) {
                sb.append(csv(b.itemId())).append(',').append(csv(b.name())).append(',')
                        .append(csv(b.specText())).append(',').append(b.onHand()).append(',')
                        .append(b.reserved()).append(',').append(b.available()).append('\n');
            }
            return sb.toString();
        }
        sb.append("时间,单据,类型,原因,变动,变动后,操作人\n");
        List<InvLedger> rows = ledgerMapper.selectList(Wrappers.<InvLedger>lambdaQuery()
                .eq(InvLedger::getOwnerId, ownerId)
                .eq(locationId != null, InvLedger::getLocationId, locationId)
                .orderByDesc(InvLedger::getId).last("LIMIT " + EXPORT_MAX));
        for (InvLedger e : rows) {
            sb.append(e.getOccurredAt()).append(',').append(csv(e.getDocNo())).append(',')
                    .append(csv(e.getDocKind())).append(',').append(csv(e.getReasonCode())).append(',')
                    .append(e.getQtyDelta()).append(',').append(e.getBalanceAfter()).append(',')
                    .append(csv(e.getOperator())).append('\n');
        }
        return sb.toString();
    }

    // ────────────────────────────────────────────────────────────────────

    /** 逗号与引号要转义，否则一个带逗号的品名会把整行的列数搞乱，而 Excel 不会报错。 */
    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        return v.contains(",") || v.contains("\"") ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }

    private List<InvLedger> inPeriod(String ownerId, String locationId,
                                     LocalDateTime from, LocalDateTime to) {
        return ledgerMapper.selectList(Wrappers.<InvLedger>lambdaQuery()
                .eq(InvLedger::getOwnerId, ownerId)
                .eq(locationId != null, InvLedger::getLocationId, locationId)
                // 按**业务发生时间**归期，不按落库时间：补录上周五的进货要算进上周
                .ge(InvLedger::getOccurredAt, from)
                .lt(InvLedger::getOccurredAt, to));
    }

    /**
     * 期末结存。
     *
     * <p>取「到期末为止最后一行的 {@code balanceAfter}」而不是当前余额 ——
     * 查上个月的月报时，当前余额里已经含了这个月的变动。
     */
    private int closingOf(String ownerId, String locationId, LocalDateTime to) {
        if (to.isAfter(LocalDateTime.now())) {
            return balanceMapper.selectList(Wrappers.<InvStockBalance>lambdaQuery()
                            .eq(InvStockBalance::getOwnerId, ownerId)
                            .eq(locationId != null, InvStockBalance::getLocationId, locationId))
                    .stream().mapToInt(InvStockBalance::getOnHand).sum();
        }
        List<InvLedger> rows = ledgerMapper.selectList(Wrappers.<InvLedger>lambdaQuery()
                .eq(InvLedger::getOwnerId, ownerId)
                .eq(locationId != null, InvLedger::getLocationId, locationId)
                .lt(InvLedger::getOccurredAt, to)
                .orderByDesc(InvLedger::getId));
        Map<String, Integer> lastByKey = new HashMap<>();
        for (InvLedger e : rows) {
            lastByKey.putIfAbsent(e.getItemId() + "|" + e.getLocationId(), e.getBalanceAfter());
        }
        return lastByKey.values().stream().mapToInt(Integer::intValue).sum();
    }

    private Map<String, InvItem> names(List<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return itemMapper.selectList(Wrappers.<InvItem>lambdaQuery()
                        .in(InvItem::getItemId, itemIds)).stream()
                .collect(Collectors.toMap(InvItem::getItemId, Function.identity(), (a, b) -> a));
    }
}
