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
import ai.neargo.shop.fulfillment.entity.FulVerifyLog;
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

    public PickupServiceImpl(FulfillmentQueryPort orderPort, PickupQueryPort pickupPort,
                             VerifyLogMapper logMapper) {
        this.orderPort = orderPort;
        this.pickupPort = pickupPort;
        this.logMapper = logMapper;
    }

    @Override
    public PickupOverviewVO overview(String pickupNo) {
        String scope = requireScope(pickupNo);
        int pending = orderPort.ordersOfPickup(scope, "WAIT_FULFILL").size();
        String name = pickupPort.find(scope).map(PickupQueryPort.PickupBrief::name).orElse("");
        // 履约服务费口径未定（R15/B9），一期恒 0 —— 编一个数字比给 0 更糟，店主会拿它去对账
        return new PickupOverviewVO(scope, name, pending, 0, 0L);
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
        Map<String, PickingRowVO> agg = new LinkedHashMap<>();
        Map<String, Set<String>> buyers = new LinkedHashMap<>();

        for (PickupOrder o : orderPort.ordersOfPickup(requireScope(pickupNo), "WAIT_FULFILL")) {
            for (PickupOrder.Item item : o.items()) {
                String key = item.goodsNo() + "|" + item.spec();
                PickingRowVO cur = agg.get(key);
                int qty = (cur == null ? 0 : cur.totalQty()) + item.qty();
                buyers.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(o.subOrderNo());
                agg.put(key, new PickingRowVO(item.goodsNo(), item.title(), item.spec(),
                        qty, buyers.get(key).size()));
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
                                        String skuNo, String note) {
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
        String label = ("DAMAGE".equals(kind) ? "自提点上报破损："
                : "SHORTAGE".equals(kind) ? "自提点上报短少："
                // 兜底仍按短少：上报本身只留痕，不该因为一个词不认识就丢掉
                : "自提点上报短少：")
                + (note == null || note.isBlank() ? "无说明" : note.trim());
        orderPort.reportException(subOrderNo, SecurityUtils.currentUserNo(), label);
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
