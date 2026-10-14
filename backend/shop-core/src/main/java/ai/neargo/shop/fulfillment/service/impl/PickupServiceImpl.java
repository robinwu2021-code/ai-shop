package ai.neargo.shop.fulfillment.service.impl;

import ai.neargo.shop.fulfillment.service.PickupService;

import ai.neargo.shop.spi.trade.FulfillmentQueryPort;
import ai.neargo.shop.spi.trade.FulfillmentQueryPort.PickupOrder;
import ai.neargo.shop.spi.user.PickupQueryPort;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.fulfillment.dto.PickingRowVO;
import ai.neargo.shop.fulfillment.dto.PickupOrderVO;
import ai.neargo.shop.fulfillment.dto.PickupOverviewVO;
import ai.neargo.shop.fulfillment.dto.VerifyResultVO;
import ai.neargo.shop.fulfillment.entity.FulShortageReport;
import ai.neargo.shop.fulfillment.entity.FulVerifyLog;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.ShortageReportMapper;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.VerifyLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PickupServiceImpl implements PickupService {

    private final FulfillmentQueryPort orderPort;
    private final PickupQueryPort pickupPort;
    private final VerifyLogMapper logMapper;
    private final ShortageReportMapper shortageMapper;

    public PickupServiceImpl(FulfillmentQueryPort orderPort, PickupQueryPort pickupPort,
                             VerifyLogMapper logMapper, ShortageReportMapper shortageMapper) {
        this.orderPort = orderPort;
        this.pickupPort = pickupPort;
        this.logMapper = logMapper;
        this.shortageMapper = shortageMapper;
    }

    @Override
    public PickupOverviewVO overview(String pickupNo) {
        String scope = requireScope(pickupNo);
        /*
         * **此前这里算错了**：`pendingVerify`（字段名与文档都写着「到货了还没人来取的」）
         * 拿的却是 `WAIT_FULFILL`（备货中，还没到货）的数量 —— 与 `FULFILLING`
         * （已到点、真正待核销）刚好是流水线上前后相邻的两段。核销页自己没敢信这个数
         * （改成了拿列表现算），但字段本身一直是错的，谁直接用它就会被误导。
         */
        int pendingVerify = orderPort.ordersOfPickup(scope, "FULFILLING").size();
        String name = pickupPort.find(scope).map(PickupQueryPort.PickupBrief::name).orElse("");
        // 「今日到货批次」「履约服务费」口径未定（R15/B9），一期恒 0 —— 编一个数字比给 0 更糟，
        // 店主会拿它去对账；端上据此把这两格隐藏，而不是常驻显示一个看起来像真数据的 0
        return new PickupOverviewVO(scope, name, pendingVerify, 0, 0L);
    }

    @Override
    @Transactional
    public VerifyResultVO verify(String verifyCode, boolean onBehalf) {
        String operator = SecurityUtils.currentUserNo();
        VerifyResultVO result = doVerify(verifyCode);
        // **失败也记日志**：「顾客说扫不了」这类纠纷，能查的只有失败记录，只记成功等于没记
        log(verifyCode, result, operator,
                onBehalf ? FulVerifyLog.TYPE_ON_BEHALF : FulVerifyLog.TYPE_SCAN);
        return result;
    }

    @Override
    @Transactional
    public BatchResult verifyBatch(List<String> verifyCodes) {
        int success = 0;
        List<VerifyResultVO> failed = new ArrayList<>();
        for (String code : verifyCodes == null ? List.<String>of() : verifyCodes) {
            VerifyResultVO r = doVerify(code);
            log(code, r, SecurityUtils.currentUserNo(), FulVerifyLog.TYPE_BATCH);
            if (r.success()) {
                success++;
            } else {
                // 批量里的失败要逐条回报：店主需要知道**哪一单**没成，而不是「3 成功 2 失败」
                failed.add(r);
            }
        }
        return new BatchResult(success, failed);
    }

    private VerifyResultVO doVerify(String verifyCode) {
        var found = orderPort.findByVerifyCode(verifyCode);
        if (found.isEmpty()) {
            return VerifyResultVO.fail(VerifyResultVO.NOT_FOUND);
        }
        PickupOrder order = found.get();

        BizContext ctx = BizContext.current();
        if (!ctx.pickupNos().contains(order.pickupNo())) {
            // 非本点：明确告诉店主「这单不在你这儿」，他才能让顾客去对的点
            return VerifyResultVO.fail(VerifyResultVO.NOT_THIS_PICKUP, order.subOrderNo());
        }
        switch (order.status()) {
            case "COMPLETED" -> {
                return VerifyResultVO.fail(VerifyResultVO.ALREADY_VERIFIED, order.subOrderNo());
            }
            case "REFUNDED", "CANCELLED" -> {
                return VerifyResultVO.fail(VerifyResultVO.REFUNDED, order.subOrderNo());
            }
            case "WAIT_PAY" -> {
                return VerifyResultVO.fail(VerifyResultVO.NOT_PAID, order.subOrderNo());
            }
            case "WAIT_FULFILL" -> {
                /*
                 * **货还没到这个点上。**
                 *
                 * 此前这一支落进下面的 default「继续核销」，于是未到货的码核销成功 ——
                 * 邻居代收点上，货还在路上就被记成「已取货」，
                 * 而「已取货」是终态：之后没有任何人会去追它到底到没到。
                 *
                 * 拦住的代价是「货到了但没人点到货登记」时店员会被卡一下（先去点一次到货），
                 * 那是一次多点一下；放行的代价是一件货不知去向。
                 */
                return VerifyResultVO.fail(VerifyResultVO.NOT_ARRIVED, order.subOrderNo());
            }
            default -> {
                // 继续核销
            }
        }

        boolean ok = orderPort.complete(order.subOrderNo(), SecurityUtils.currentUserNo(), "已取货");
        return ok ? VerifyResultVO.ok(order.subOrderNo())
                : VerifyResultVO.fail(VerifyResultVO.ALREADY_VERIFIED, order.subOrderNo());
    }

    @Override
    public List<PickupOrderVO> orders(String pickupNo, String status) {
        return orderPort.ordersOfPickup(requireScope(pickupNo), status).stream()
                .map(this::mask).toList();
    }

    @Override
    public List<PickupOrderVO> searchByCode(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        // 在本点范围内按码模糊匹配：扫码失败时店主让顾客报后几位
        return BizContext.current().pickupNos().stream()
                .flatMap(p -> orderPort.ordersOfPickup(p, null).stream())
                .filter(o -> o.verifyCode() != null && o.verifyCode().contains(keyword))
                .map(this::mask).toList();
    }

    @Override
    public List<PickingRowVO> picking(String pickupNo) {
        // **按 SKU 聚合**（此前是 goodsNo|spec）：分拣是按规格分堆的，
        // 而端上上报短少也要带 skuNo —— 聚合键与那件事对齐才不会两处再各推一遍
        Map<String, PickingRowVO> agg = new LinkedHashMap<>();
        Map<String, List<PickingRowVO.Buyer>> buyers = new LinkedHashMap<>();

        for (PickupOrder o : orderPort.ordersOfPickup(requireScope(pickupNo), "WAIT_FULFILL")) {
            for (PickupOrder.Item item : o.items()) {
                String key = item.skuNo() == null ? item.goodsNo() + "|" + item.spec()
                        : item.skuNo();
                PickingRowVO cur = agg.get(key);
                int qty = (cur == null ? 0 : cur.totalQty()) + item.qty();
                /*
                 * **明细而不是计数**：分拣单上真正要做的是照着名字分堆。
                 * 只给「3 个人」，店主分不出哪几件是谁的；而端上点某个人
                 * 还要能上报短少（要 subOrderNo）。
                 */
                buyers.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new PickingRowVO.Buyer(o.buyerNickname(), item.qty(), o.subOrderNo()));
                List<PickingRowVO.Buyer> bs = buyers.get(key);
                agg.put(key, new PickingRowVO(item.goodsNo(), item.skuNo(), item.title(),
                        item.cover(), item.spec(), qty,
                        (int) bs.stream().map(PickingRowVO.Buyer::orderNo).distinct().count(),
                        List.copyOf(bs)));
            }
        }
        return List.copyOf(agg.values());
    }

    /**
     * 作用域校验：不传就用当前身份的第一个点；传了必须在自己的点里。
     * <b>403 而不是空列表</b> —— 空列表会让店主以为「今天没单」，而不是「你查错点了」。
     */
    @Override
    @Transactional
    public List<PickupOrderVO> markArrived(String pickupNo, List<String> subOrderNos) {
        String scope = requireScope(pickupNo);
        if (subOrderNos == null || subOrderNos.isEmpty()) {
            // 空批次不是错误：端上「全选」时可能一单都没有，报错会挡住一次正常操作
            return List.of();
        }
        List<String> moved = orderPort.markArrived(subOrderNos, scope, SecurityUtils.currentUserNo());
        if (moved.isEmpty()) {
            return List.of();
        }
        // 返回推进后的最新状态：端上直接拿它刷新列表，不用再拉一次
        return orderPort.ordersOfPickup(scope, null).stream()
                .filter(o -> moved.contains(o.subOrderNo()))
                .map(this::mask)
                .toList();
    }

    @Override
    @Transactional
    public PickupOrderVO reportShortage(String pickupNo, String subOrderNo, String kind,
                                        String skuNo, int qty, String note) {
        String scope = requireScope(pickupNo);
        PickupOrder target = ofThisPickup(scope, subOrderNo, null);
        if (target == null) {
            /*
             * 不在「还没取走」那批里，再按已核销找一次 —— 只为了报一个说得清的错。
             *
             * `ordersOfPickup(_, null)` 默认只给未取走的单（核销台关心待办不关心历史），
             * 所以已核销的单在这里表现为「找不到」。直接 404 的话，店主看到的是
             * 「这单不存在」，而它明明就在本点刚核销过 —— 他会以为系统丢单。
             */
            if (ofThisPickup(scope, subOrderNo, "COMPLETED") != null) {
                throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
            }
            // 真不在本点：404。区分「不存在」与「不是本点的」等于一个订单归属探测器
            throw BizException.of(ErrorCode.NOT_FOUND);
        }

        /*
         * 两个词都显式认一次。此前只判 `DAMAGE`、其余全当短少 ——
         * 行为上没错，但 `SHORTAGE` 这个词在后端<b>一次都没出现过</b>，
         * 于是端上声明了它、后端「不认识」它，枚举对账那条守卫据此报警：
         * 契约上写着的取值，后端代码里查无此词，谁也说不清是不是漏实现了。
         */
        String normalizedKind = "DAMAGE".equals(kind)
                ? FulShortageReport.KIND_DAMAGE : FulShortageReport.KIND_SHORTAGE;
        String label = (FulShortageReport.KIND_DAMAGE.equals(normalizedKind)
                ? "自提点上报破损：" : "自提点上报短少：")
                // 兜底仍按短少：上报本身只留痕，不该因为一个词不认识就丢掉
                + (note == null || note.isBlank() ? "无说明" : note.trim());
        orderPort.reportException(subOrderNo, SecurityUtils.currentUserNo(), label);

        /*
         * **结构化留一份**（V131）。此前这里只往订单时间线追加上面那句话，
         * 收下的 {@code skuNo} 原地丢掉 —— 买家看得到，而平台分拣汇总里的
         * 「哪个 SKU 缺了几件」无从算起：一句自由文本没法聚合。
         *
         * 后果不是少一个数字，是 {@code DispatchService.sorting()} 的 shortQty **恒为 0**，
         * 页面上那个红色徽标永远不亮 —— 而看的人会把它读成「今天没缺件」。
         * 表与读的那一侧都已经在了，缺的一直是这一段。
         */
        FulShortageReport report = new FulShortageReport();
        report.setSubOrderNo(subOrderNo);
        report.setPickupNo(scope);
        report.setSkuNo(skuNo == null || skuNo.isBlank() ? null : skuNo.trim());
        report.setKind(normalizedKind);
        // 端上现在会报具体数量；兜个底防止传 0 或负数把汇总算错
        report.setQty(Math.max(1, qty));
        report.setNote(note == null || note.isBlank() ? null : note.trim());
        report.setReporterNo(SecurityUtils.currentUserNo());
        report.setAt(System.currentTimeMillis());
        report.setTenantNo("MAIN");
        shortageMapper.insert(report);
        return mask(target);
    }

    /** 在本点的某个状态里找这一单；找不到返回 null。 */
    private PickupOrder ofThisPickup(String pickupNo, String subOrderNo, String status) {
        return orderPort.ordersOfPickup(pickupNo, status).stream()
                .filter(o -> o.subOrderNo().equals(subOrderNo))
                .findFirst().orElse(null);
    }

    /**
     * 这次操作作用在哪个自提点。
     *
     * <p><b>不传时取「当前门店的点」，不是集合里的第一个。</b>
     *
     * <p>原先取 {@code pickupNos().iterator().next()}，在一个商家只有一个点时没问题；
     * 自提点归属到门店（V16）之后「两家店两个点」成了常态，那句话的后果是：
     * 另一家店的货**永远登记不上**、待核销列表**永远是空的** ——
     * 而到货登记对「不在本点的单」是静默跳过的，商家看到的只是一个空列表，
     * 没有任何提示说「你在给另一个点操作」。
     *
     * <p>当前门店没有自提点时**直接拒绝**，不回落到别的点：
     * 回落的表现是「以为在给 A 点登记，其实登记到了 B 点」——
     * 数字都是真的，只是不是他要的那个点的。
     */
    private String requireScope(String pickupNo) {
        BizContext ctx = BizContext.current();
        if (ctx.pickupNos().isEmpty()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (pickupNo != null && !pickupNo.isBlank()) {
            if (!ctx.pickupNos().contains(pickupNo)) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            return pickupNo;
        }
        String storeNo = ctx.currentStoreNo();
        if (storeNo == null || storeNo.isBlank()) {
            // 没有门店上下文（存量单店）：行为与改造前逐字相同
            return ctx.pickupNos().iterator().next();
        }
        return ctx.pickupNos().stream()
                .filter(no -> storeNo.equals(pickupPort.find(no)
                        .map(ai.neargo.shop.spi.user.PickupQueryPort.PickupBrief::ownerStoreNo)
                        .orElse(null)))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
    }

    /** Port 已经裁剪过一次，这里再映射成对外 VO —— 两层都不含金额与完整手机号。 */
    private PickupOrderVO mask(PickupOrder o) {
        return new PickupOrderVO(o.subOrderNo(), o.verifyCode(), o.buyerNickname(),
                o.buyerPhoneTail(), o.merchantName(), o.status(), o.pickupNo(),
                o.items().stream()
                        .map(i -> new PickupOrderVO.Item(i.goodsNo(), i.title(), i.spec(), i.qty()))
                        .toList());
    }

    private void log(String verifyCode, VerifyResultVO result, String operator, String type) {
        FulVerifyLog entry = new FulVerifyLog();
        entry.setSubOrderNo(result.subOrderNo());
        entry.setPickupNo(BizContext.current().pickupNos().stream().findFirst().orElse(""));
        entry.setVerifyCode(verifyCode);
        entry.setVerifyType(type);
        entry.setOperatorNo(operator);
        entry.setResult(result.success() ? "SUCCESS" : result.reason());
        entry.setAt(System.currentTimeMillis());
        entry.setTenantNo("MAIN");
        entry.setCreatedAt(LocalDateTime.now());
        logMapper.insert(entry);
    }
}
