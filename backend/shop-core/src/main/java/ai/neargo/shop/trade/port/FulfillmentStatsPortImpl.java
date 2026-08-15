package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.FulfillmentStatsPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link FulfillmentStatsPort} 实现。
 *
 * <p><b>为什么整个类都在 {@code executeWithoutScope} 里</b>：{@code ord_sub_order} 是一表三维
 * （user / merchant / pickup），而平台调度看的恰恰是「一个点上**所有商家**的货」——
 * 拦截器按会话维度一过滤，看板就永远是空的。作用域由端点上的 {@code @PreAuthorize}
 * 与调用方传进来的 {@code pickupNos} 保证，不靠行级拦截器
 * （与 {@code FulfillmentQueryPortImpl} 同一个理由，那里写得更细）。
 *
 * <p><b>自提单的判据是 {@code pickup_no} 非空</b>，不是 {@code fulfillment} 那一列：
 * 履约方式有四种取值而自提有两种（STORE_PICKUP / NEIGHBOR_PICKUP），
 * 按取值枚举迟早漏掉新加的一种，而漏掉的表现是那个点在看板上凭空少了一批货。
 */
@Component
public class FulfillmentStatsPortImpl implements FulfillmentStatsPort {

    /** 「还没取走」= 待履约 + 已到货待取。终态与已退款的不算在待办里 */
    private static final List<String> OPEN = List.of(OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING);

    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper itemMapper;
    private final StatusLogMapper statusLogMapper;

    public FulfillmentStatsPortImpl(SubOrderMapper subOrderMapper, OrderItemMapper itemMapper,
                                    StatusLogMapper statusLogMapper) {
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
        this.statusLogMapper = statusLogMapper;
    }

    @Override
    public List<PickupDay> pickupDays() {
        List<OrdSubOrder> open = openPickupOrders(null);
        if (open.isEmpty()) {
            return List.of();
        }
        Map<String, List<OrdSubOrder>> byKey = new LinkedHashMap<>();
        for (OrdSubOrder s : open) {
            byKey.computeIfAbsent(s.getPickupNo() + "|" + dayOf(s), k -> new ArrayList<>()).add(s);
        }
        Map<String, Integer> qty = qtyBySubOrder(open.stream().map(OrdSubOrder::getSubOrderNo).toList());

        List<PickupDay> out = new ArrayList<>();
        for (var e : byKey.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            int items = e.getValue().stream()
                    .mapToInt(s -> qty.getOrDefault(s.getSubOrderNo(), 0)).sum();
            int merchants = (int) e.getValue().stream()
                    .map(OrdSubOrder::getEntityNo).distinct().count();
            out.add(new PickupDay(parts[0], parts[1], items, merchants));
        }
        return List.copyOf(out);
    }

    @Override
    public List<SortingItem> sortingItems(Collection<String> pickupNos) {
        if (pickupNos == null || pickupNos.isEmpty()) {
            // 空集合 = 没有已签收的批次，返回空。**不是「不过滤」** ——
            // 那会把全平台的待分拣明细倒出来，而调用方要的是「这几个点的」
            return List.of();
        }
        List<OrdSubOrder> open = openPickupOrders(Set.copyOf(pickupNos));
        if (open.isEmpty()) {
            return List.of();
        }
        Map<String, OrdSubOrder> byNo = new HashMap<>();
        open.forEach(s -> byNo.put(s.getSubOrderNo(), s));

        List<SortingItem> out = new ArrayList<>();
        for (OrdItem i : itemsOf(byNo.keySet())) {
            OrdSubOrder s = byNo.get(i.getSubOrderNo());
            if (s == null) {
                continue;
            }
            out.add(new SortingItem(s.getPickupNo(), i.getSkuNo(), i.getTitle(),
                    s.getEntityName(), i.getQty() == null ? 0 : i.getQty()));
        }
        return List.copyOf(out);
    }

    @Override
    public List<PickupRedeem> redeemStats(Collection<String> pickupNos, long overdueBefore) {
        if (pickupNos == null || pickupNos.isEmpty()) {
            return List.of();
        }
        Set<String> scope = Set.copyOf(pickupNos);
        List<OrdSubOrder> rows = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .in(OrdSubOrder::getPickupNo, scope)
                        .in(OrdSubOrder::getStatus, List.of(OrdSubOrder.WAIT_FULFILL,
                                OrdSubOrder.FULFILLING, OrdSubOrder.COMPLETED))));

        // 到货时刻：状态日志里推进到 FULFILLING 的那一条。
        // **不用 updated_at** —— 那一列任何一次写都会动（改备注、回填快递单号），
        // 于是「这单在点上放了多久」会随便一次无关操作被清零，逾期数悄悄变小
        Map<String, Long> arrivedAt = arrivedAt(rows.stream()
                .filter(s -> OrdSubOrder.FULFILLING.equals(s.getStatus()))
                .map(OrdSubOrder::getSubOrderNo).toList());

        Map<String, int[]> agg = new LinkedHashMap<>();
        for (String p : scope) {
            agg.put(p, new int[3]);
        }
        for (OrdSubOrder s : rows) {
            int[] a = agg.get(s.getPickupNo());
            if (a == null) {
                continue;
            }
            switch (s.getStatus()) {
                case OrdSubOrder.COMPLETED -> a[1]++;
                case OrdSubOrder.FULFILLING -> {
                    Long at = arrivedAt.get(s.getSubOrderNo());
                    // 查不到到货日志的按「还在宽限期内」算：宁可少报一个逾期，
                    // 也不要凭一条缺失的日志去催买家 —— 催错的代价是客诉
                    if (at != null && at < overdueBefore) {
                        a[2]++;
                    } else {
                        a[0]++;
                    }
                }
                // WAIT_FULFILL：货还没到点，不可能逾期，算待核销
                default -> a[0]++;
            }
        }
        return agg.entrySet().stream()
                .map(e -> new PickupRedeem(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .toList();
    }

    @Override
    public List<ExpressOrder> expressOrders() {
        // ★ 接数据域（批④），理由同 openPickupOrders：运营端的快递单列表
        List<OrdSubOrder> rows =
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getFulfillment, OrdSubOrder.EXPRESS)
                        .isNotNull(OrdSubOrder::getExpressNo)
                        .ne(OrdSubOrder::getExpressNo, "")
                        .orderByDesc(OrdSubOrder::getId));
        return rows.stream().map(s -> new ExpressOrder(
                s.getSubOrderNo(), s.getExpressNo(), s.getStatus(),
                s.getReceiverName() == null ? "" : s.getReceiverName(),
                regionOf(s.getReceiverAddress()),
                s.getCreatedAt() == null ? 0L
                        : s.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
                .toList();
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 待履约的自提单。
     *
     * <p>★ <b>接数据域</b>（批④）：这个 Port 只被履约调度与物流服务用，
     * 而它们只有 {@code /ops/**} 的出口 —— 配了自提点域的社区运营
     * 只该看到自己那几个点的分拣与批次。此前这里 {@code executeWithoutScope}，
     * 于是「给社区运营配了城西片区，他照样看到全平台的分拣单」。
     *
     * <p>{@code ord_sub_order} 上 PICKUP / COMMUNITY / MERCHANT 三个锚点都在
     * （批① 与 V137），所以这里不会 fail-closed 成空白。
     */
    private List<OrdSubOrder> openPickupOrders(Set<String> pickupNos) {
        var w = Wrappers.<OrdSubOrder>lambdaQuery()
                .isNotNull(OrdSubOrder::getPickupNo)
                .ne(OrdSubOrder::getPickupNo, "")
                .in(OrdSubOrder::getStatus, OPEN);
        if (pickupNos != null) {
            w.in(OrdSubOrder::getPickupNo, pickupNos);
        }
        return subOrderMapper.selectList(w.orderByAsc(OrdSubOrder::getId));
    }

    private List<OrdItem> itemsOf(Collection<String> subOrderNos) {
        if (subOrderNos.isEmpty()) {
            return List.of();
        }
        return DataScopeContext.executeWithoutScope(() -> itemMapper.selectList(
                Wrappers.<OrdItem>lambdaQuery().in(OrdItem::getSubOrderNo, subOrderNos)));
    }

    private Map<String, Integer> qtyBySubOrder(Collection<String> subOrderNos) {
        Map<String, Integer> out = new HashMap<>();
        for (OrdItem i : itemsOf(subOrderNos)) {
            out.merge(i.getSubOrderNo(), i.getQty() == null ? 0 : i.getQty(), Integer::sum);
        }
        return out;
    }

    /** 每单**最早**一次推进到「已到货」的时刻 —— 重复登记不该让逾期计时重来。 */
    private Map<String, Long> arrivedAt(Collection<String> subOrderNos) {
        if (subOrderNos.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new HashMap<>();
        List<OrdStatusLog> logs = DataScopeContext.executeWithoutScope(() ->
                statusLogMapper.selectList(Wrappers.<OrdStatusLog>lambdaQuery()
                        .in(OrdStatusLog::getSubOrderNo, subOrderNos)
                        .eq(OrdStatusLog::getStatus, OrdSubOrder.FULFILLING)));
        for (OrdStatusLog l : logs) {
            if (l.getAt() == null) {
                continue;
            }
            out.merge(l.getSubOrderNo(), l.getAt(), Math::min);
        }
        return out;
    }

    /** 下单日 YYYY-MM-DD。到货日一期就取它（TDD-运营端履约调度 §4.3）。 */
    private static String dayOf(OrdSubOrder s) {
        return s.getCreatedAt() == null ? "" : s.getCreatedAt().toLocalDate().toString();
    }

    /**
     * 收件地区（省 市）。地址快照的格式是「省 市 区 详细」，取前两段。
     *
     * <p>解析放在 trade 而不是让 fulfillment 自己切：地址的格式是下单时这一侧写进去的，
     * 格式变了该由这里跟着变 —— 让下游按约定去切，等于把一个隐式契约散到两个域。
     */
    private static String regionOf(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        String[] parts = address.trim().split("\\s+");
        return parts.length >= 2 ? parts[0] + " " + parts[1] : parts[0];
    }
}
