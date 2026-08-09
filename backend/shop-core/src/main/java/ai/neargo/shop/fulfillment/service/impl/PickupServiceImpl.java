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
    private String requireScope(String pickupNo) {
        BizContext ctx = BizContext.current();
        if (ctx.pickupNos().isEmpty()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (pickupNo == null || pickupNo.isBlank()) {
            return ctx.pickupNos().iterator().next();
        }
        if (!ctx.pickupNos().contains(pickupNo)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return pickupNo;
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
