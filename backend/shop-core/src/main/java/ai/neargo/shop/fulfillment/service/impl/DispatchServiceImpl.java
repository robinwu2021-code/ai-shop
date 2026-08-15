package ai.neargo.shop.fulfillment.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.fulfillment.dto.ArrivalBatchVO;
import ai.neargo.shop.fulfillment.dto.OverdueRuleVO;
import ai.neargo.shop.fulfillment.dto.RedeemStatVO;
import ai.neargo.shop.fulfillment.dto.SortingRowVO;
import ai.neargo.shop.fulfillment.entity.FulBatch;
import ai.neargo.shop.fulfillment.entity.FulShortageReport;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.BatchMapper;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.ShortageReportMapper;
import ai.neargo.shop.fulfillment.service.DispatchService;
import ai.neargo.shop.spi.platform.SettingPort;
import ai.neargo.shop.spi.trade.FulfillmentStatsPort;
import ai.neargo.shop.spi.user.PickupDirectoryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link DispatchService} 实现。
 *
 * <p>跨域一律走 Port：自提点名录走 {@link PickupDirectoryPort}（community 域），
 * 订单聚合走 {@link FulfillmentStatsPort}（trade 域），逾期规则走 {@link SettingPort}（platform 域）。
 * 直连别的域的 Mapper 会让 ArchUnit 当场红，而那条规则拦的正是「三个月后拆不动的单体」。
 */
@Service
public class DispatchServiceImpl implements DispatchService {

    /** 逾期规则在 sys_setting 里的键。 */
    static final String KEY_OVERDUE_RULE = "fulfillment.overdue-rule";

    /**
     * 默认宽限 24 小时 = 矩阵 §七之二 {@code pickupGraceDays}（1 天）的等价小时数。
     *
     * <p><b>没有直接读那个常量</b>：它在 {@code packages/shared} 里，是端上的常量表，
     * 后端读不到；而它标的两个消费方（C 取货提醒 / B 自提点清点）今天一行代码都没有。
     * 口径对齐写在这里与 TDD §4.4，等那两侧做出来时一起读这份 sys_setting。
     */
    private static final int DEFAULT_GRACE_HOURS = 24;

    private static final String DEFAULT_RULE =
            "{\"action\":\"POSTPONE\",\"graceHours\":24,\"maxPostpone\":2}";

    /** 宽限期下限。**到点即作废必产生客诉** —— 这是规则不是建议，两侧都校验。 */
    private static final int MIN_GRACE_HOURS = 1;

    private static final String PENDING_VEHICLE = "待派";

    private final BatchMapper batchMapper;
    private final ShortageReportMapper shortageMapper;
    private final PickupDirectoryPort pickupDirectory;
    private final FulfillmentStatsPort statsPort;
    private final SettingPort settingPort;
    private final ObjectMapper json;

    public DispatchServiceImpl(BatchMapper batchMapper, ShortageReportMapper shortageMapper,
                               PickupDirectoryPort pickupDirectory, FulfillmentStatsPort statsPort,
                               SettingPort settingPort, ObjectMapper json) {
        this.batchMapper = batchMapper;
        this.shortageMapper = shortageMapper;
        this.pickupDirectory = pickupDirectory;
        this.statsPort = statsPort;
        this.settingPort = settingPort;
        this.json = json;
    }

    // ---------------------------------------------------------------- 到货批次（5.1.1）

    @Override
    @Transactional
    public List<ArrivalBatchVO> batches(String communityNo, String pickupNo, String status,
                                        String keyword) {
        Map<String, PickupDirectoryPort.PickupRow> directory = pickupDirectory.list(communityNo)
                .stream().collect(Collectors.toMap(PickupDirectoryPort.PickupRow::pickupNo,
                        r -> r, (a, b) -> a, LinkedHashMap::new));

        // 现算的件数/商家数。key = pickupNo|arriveDate
        Map<String, FulfillmentStatsPort.PickupDay> live = statsPort.pickupDays().stream()
                .filter(d -> directory.containsKey(d.pickupNo()))
                .collect(Collectors.toMap(d -> d.pickupNo() + "|" + d.arriveDate(), d -> d, (a, b) -> a));

        ensureRows(live, directory);

        List<FulBatch> rows = DataScopeContext.executeWithoutScope(() ->
                batchMapper.selectList(Wrappers.<FulBatch>lambdaQuery()
                        .in(!directory.isEmpty(), FulBatch::getPickupNo, directory.keySet())
                        .orderByDesc(FulBatch::getArriveDate).orderByDesc(FulBatch::getId)));

        List<ArrivalBatchVO> out = new ArrayList<>();
        for (FulBatch b : rows) {
            PickupDirectoryPort.PickupRow p = directory.get(b.getPickupNo());
            if (p == null) {
                continue;
            }
            FulfillmentStatsPort.PickupDay d = live.get(b.getPickupNo() + "|" + b.getArriveDate());
            ArrivalBatchVO vo = new ArrivalBatchVO(b.getBatchNo(), b.getStatus(),
                    p.communityNo(), p.communityName(), p.pickupNo(), p.name(),
                    iso(b.getPlanArriveAt()),
                    b.getVehicle() == null || b.getVehicle().isBlank() ? PENDING_VEHICLE : b.getVehicle(),
                    // 这一批的货已经全部取走时没有 live 行 —— 件数是 0，而批次本身还在
                    d == null ? 0 : d.itemCount(), d == null ? 0 : d.merchantCount());
            if (hit(vo, pickupNo, status, keyword)) {
                out.add(vo);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 读时补齐：有货、还没有批次行的「点 × 日」就地建一条。
     *
     * <p>幂等：按 (pickup_no, arrive_date) 先查后建。并发下最坏是插入失败一次，
     * 下一次列表刷新时那一行已经在了 —— 而 batch_no 上有唯一键，重复不会落库两条。
     */
    private void ensureRows(Map<String, FulfillmentStatsPort.PickupDay> live,
                            Map<String, PickupDirectoryPort.PickupRow> directory) {
        if (live.isEmpty()) {
            return;
        }
        Set<String> existing = DataScopeContext.executeWithoutScope(() ->
                        batchMapper.selectList(Wrappers.<FulBatch>lambdaQuery()
                                .in(FulBatch::getPickupNo, live.values().stream()
                                        .map(FulfillmentStatsPort.PickupDay::pickupNo).distinct().toList())))
                .stream().map(b -> b.getPickupNo() + "|" + b.getArriveDate())
                .collect(Collectors.toSet());

        for (var e : live.entrySet()) {
            if (existing.contains(e.getKey())) {
                continue;
            }
            FulfillmentStatsPort.PickupDay d = e.getValue();
            PickupDirectoryPort.PickupRow p = directory.get(d.pickupNo());
            FulBatch b = new FulBatch();
            b.setBatchNo(BizKey.next(BizKey.ARRIVAL_BATCH));
            b.setPickupNo(d.pickupNo());
            b.setCommunityNo(p == null ? null : p.communityNo());
            b.setArriveDate(d.arriveDate());
            // 计划到货时间默认「到货日 08:00」。**不是编数字**：它是给运营改的初值，
            // 车次同理（「待派」）。给 null 的话看板上是一列空白，运营看不出「还没排」与「没这一列」
            b.setPlanArriveAt(planArriveAt(d.arriveDate()));
            b.setVehicle(PENDING_VEHICLE);
            b.setTotalQty(0);
            b.setStatus(FulBatch.STATUS_PLANNED);
            b.setTenantNo("MAIN");
            b.setCreatedAt(LocalDateTime.now());
            b.setCreatedBy("SYSTEM");
            b.setUpdatedAt(LocalDateTime.now());
            b.setUpdatedBy("SYSTEM");
            b.setVersion(0L);
            b.setDeleted(0);
            DataScopeContext.executeWithoutScope(() -> batchMapper.insert(b));
        }
    }

    @Override
    @Transactional
    public ArrivalBatchVO setBatchStatus(String batchNo, String status, String operatorNo) {
        FulBatch b = DataScopeContext.executeWithoutScope(() ->
                batchMapper.selectOne(Wrappers.<FulBatch>lambdaQuery()
                        .eq(FulBatch::getBatchNo, batchNo).last("limit 1")));
        if (b == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * 跳步拒绝。**不是「幂等地放过」**：把 PLANNED 直接推到 SIGNED，
         * 等于宣称「车发过、货到过、站长签过」三件事都发生了 ——
         * 而其中至少两件没有。责任判定的依据就是这三步各自的时刻。
         */
        String next = FulBatch.NEXT.get(b.getStatus());
        if (next == null || !next.equals(status)) {
            throw BizException.of(ErrorCode.BATCH_TRANSITION_ILLEGAL);
        }
        b.setStatus(status);
        if (FulBatch.STATUS_SIGNED.equals(status)) {
            // 签收人与时刻：少收了货要能追到是谁签的
            b.setReceivedAt(System.currentTimeMillis());
            b.setReceivedBy(operatorNo);
        }
        b.setUpdatedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> batchMapper.updateById(b));

        return batches(null, b.getPickupNo(), null, null).stream()
                .filter(v -> v.batchNo().equals(batchNo)).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    // ---------------------------------------------------------------- 分拣汇总（5.1.2）

    @Override
    public List<SortingRowVO> sorting(String pickupNo) {
        Map<String, PickupDirectoryPort.PickupRow> directory = pickupDirectory.list(null)
                .stream().collect(Collectors.toMap(PickupDirectoryPort.PickupRow::pickupNo, r -> r, (a, b) -> a));

        /*
         * **只看已签收批次覆盖到的点。**
         * 没签收就分拣，等于把「货到底交没交到点上」这条判据跳过去 ——
         * 而分拣时发现少了东西，第一个要回答的问题正是「签收时是不是就少了」。
         */
        Set<String> signed = DataScopeContext.executeWithoutScope(() ->
                        batchMapper.selectList(Wrappers.<FulBatch>lambdaQuery()
                                .eq(FulBatch::getStatus, FulBatch.STATUS_SIGNED)))
                .stream().map(FulBatch::getPickupNo)
                .filter(p -> pickupNo == null || pickupNo.isBlank() || pickupNo.equals(p))
                .filter(directory::containsKey)
                .collect(Collectors.toSet());
        if (signed.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> shortQty = shortageBySku(signed);

        // 按 自提点 × SKU × 供货商家 聚合。多一维商家正是平台视角存在的理由：
        // 一个批次混装多家的货，缺件要能指到具体哪一家
        Map<String, SortingRowVO> agg = new LinkedHashMap<>();
        for (FulfillmentStatsPort.SortingItem it : statsPort.sortingItems(signed)) {
            String key = it.pickupNo() + "|" + it.skuNo() + "|" + it.merchantName();
            SortingRowVO cur = agg.get(key);
            int qty = (cur == null ? 0 : cur.qty()) + it.qty();
            PickupDirectoryPort.PickupRow p = directory.get(it.pickupNo());
            agg.put(key, new SortingRowVO(it.pickupNo(), p == null ? "" : p.name(),
                    it.skuNo(), it.title(), it.merchantName(), qty,
                    shortQty.getOrDefault(it.pickupNo() + "|" + it.skuNo(), 0)));
        }
        return List.copyOf(agg.values());
    }

    private Map<String, Integer> shortageBySku(Set<String> pickupNos) {
        Map<String, Integer> out = new HashMap<>();
        List<FulShortageReport> rows = DataScopeContext.executeWithoutScope(() ->
                shortageMapper.selectList(Wrappers.<FulShortageReport>lambdaQuery()
                        .in(FulShortageReport::getPickupNo, pickupNos)));
        for (FulShortageReport r : rows) {
            if (r.getSkuNo() == null) {
                // SKU 未知的上报进不了分拣行 —— 它仍然留在订单时间线上，
                // 但汇总只能按 SKU 归堆，硬归到某个 SKU 上会让那个 SKU 的数字变成假的
                continue;
            }
            out.merge(r.getPickupNo() + "|" + r.getSkuNo(), r.getQty() == null ? 1 : r.getQty(),
                    Integer::sum);
        }
        return out;
    }

    // ---------------------------------------------------------------- 核销监控（5.1.3）

    @Override
    public List<RedeemStatVO> redeemStats(String pickupNo) {
        List<PickupDirectoryPort.PickupRow> directory = pickupDirectory.list(null).stream()
                .filter(r -> pickupNo == null || pickupNo.isBlank() || pickupNo.equals(r.pickupNo()))
                .toList();
        if (directory.isEmpty()) {
            return List.of();
        }
        /*
         * **逾期规则真正被消费的地方就是这一行。**
         * 宽限期从 24 改成 1，同一批订单里逾期数立刻变多 ——
         * 撤掉这一行，那条配置就退化成「存了没人读」的一张表。
         */
        long overdueBefore = System.currentTimeMillis()
                - overdueRule().graceHours() * 3600_000L;

        Map<String, FulfillmentStatsPort.PickupRedeem> stats = statsPort
                .redeemStats(directory.stream().map(PickupDirectoryPort.PickupRow::pickupNo).toList(),
                        overdueBefore)
                .stream().collect(Collectors.toMap(FulfillmentStatsPort.PickupRedeem::pickupNo,
                        s -> s, (a, b) -> a));

        List<RedeemStatVO> out = new ArrayList<>();
        for (PickupDirectoryPort.PickupRow p : directory) {
            FulfillmentStatsPort.PickupRedeem s = stats.get(p.pickupNo());
            int pending = s == null ? 0 : s.pending();
            int redeemed = s == null ? 0 : s.redeemed();
            int overdue = s == null ? 0 : s.overdue();
            int total = pending + redeemed + overdue;
            // 一单都没有的点 rate 给 0 而不是 1：「100% 核销率」会让它排在健康度榜首，
            // 而它其实什么都没发生
            double rate = total == 0 ? 0d : Math.round(redeemed * 10000d / total) / 10000d;
            out.add(new RedeemStatVO(p.pickupNo(), p.name(), p.communityName(),
                    pending, redeemed, overdue, rate));
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- 逾期规则（5.1.4）

    @Override
    public OverdueRuleVO overdueRule() {
        Map<String, Object> m = readRule();
        String action = str(m, "action");
        return new OverdueRuleVO(
                action.isBlank() ? OverdueRuleVO.POSTPONE : action,
                intOf(m, "graceHours", DEFAULT_GRACE_HOURS),
                intOf(m, "maxPostpone", 2),
                str(m, "updatedAt"), str(m, "updatedBy"));
    }

    @Override
    @Transactional
    public OverdueRuleVO saveOverdueRule(String action, int graceHours, int maxPostpone,
                                         String operatorNo) {
        if (!OverdueRuleVO.POSTPONE.equals(action) && !OverdueRuleVO.VOID.equals(action)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 到点即作废必产生客诉 —— VOID 也要留宽限期
        if (graceHours < MIN_GRACE_HOURS) {
            throw BizException.of(ErrorCode.OVERDUE_GRACE_TOO_SHORT);
        }
        // 「顺延 0 次」名为顺延实为作废：配置界面上写着顺延，行为上是作废，最坏的一种不一致
        if (OverdueRuleVO.POSTPONE.equals(action) && maxPostpone < 1) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("graceHours", graceHours);
        m.put("maxPostpone", maxPostpone);
        m.put("updatedAt", Instant.now().toString());
        m.put("updatedBy", operatorNo);
        settingPort.put(KEY_OVERDUE_RULE, json.writeValueAsString(m), operatorNo);
        return overdueRule();
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> readRule() {
        String raw = settingPort.get(KEY_OVERDUE_RULE, DEFAULT_RULE);
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (RuntimeException e) {
            // 存坏了的一行不该让整个履约看板打不开：回落到默认值，
            // 而不是把一个 JSON 解析异常抛给运营
            return json.readValue(DEFAULT_RULE, new TypeReference<Map<String, Object>>() {
            });
        }
    }

    private static boolean hit(ArrivalBatchVO b, String pickupNo, String status, String keyword) {
        if (pickupNo != null && !pickupNo.isBlank() && !pickupNo.equals(b.pickupNo())) {
            return false;
        }
        if (status != null && !status.isBlank() && !status.equals(b.status())) {
            return false;
        }
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String k = keyword.trim().toLowerCase();
        return contains(b.batchNo(), k) || contains(b.pickupName(), k)
                || contains(b.communityName(), k) || contains(b.vehicle(), k);
    }

    private static boolean contains(String v, String k) {
        return v != null && v.toLowerCase().contains(k);
    }

    /** 到货日 08:00 的时间戳。日期不合法（历史脏数据）时给 null，不让一行坏数据炸掉整页。 */
    private static Long planArriveAt(String arriveDate) {
        try {
            return LocalDate.parse(arriveDate).atTime(8, 0)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String iso(Long at) {
        return at == null ? null : Instant.ofEpochMilli(at).toString();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static int intOf(Map<String, Object> m, String k, int fallback) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : fallback;
    }
}
