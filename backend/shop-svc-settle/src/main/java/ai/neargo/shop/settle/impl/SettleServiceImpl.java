package ai.neargo.shop.settle.impl;

import ai.neargo.shop.settle.SettleService;
import ai.neargo.shop.settle.SplitGateway;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import ai.neargo.shop.spi.user.PickupQueryPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.settle.dto.RateCardVO;
import ai.neargo.shop.settle.dto.SettleBillVO;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.entity.StlSplitLog;
import ai.neargo.shop.settle.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.settle.mapper.SettleMappers.SplitLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 结算与分账（ADR-002）。**M7 起取代 {@code SettlePortStub}**。
 *
 * <p>金额口径（三列缺一不可）：
 * <pre>
 *   gross      = 用户实付 + 平台补贴的优惠   ← 平台券的钱最终要给商家
 *   commission = gross × 费率档              ← 自带客流为 0（R16）
 *   net        = gross - commission - 服务费  ← 商家实得
 * </pre>
 * 商家券的钱是商家自己让的利，**不补回**，所以不进 gross。
 *
 * <p>费率**落库快照**：费率是会调的，历史账不能跟着变 —— 否则去年的账今年一看就对不上。
 */
@Service
public class SettleServiceImpl implements SettleService {

    private static final Logger log = LoggerFactory.getLogger(SettleServiceImpl.class);

    /** 自带客流费率（万分比）。R16 建议一期零佣金 —— 他带来的客户在别家消费才是平台收益。 */
    @Value("${shop.settle.merchant-owned-rate:0}")
    private int merchantOwnedRate;

    /** 平台客流费率（万分比）。默认 5%。 */
    @Value("${shop.settle.platform-rate:500}")
    private int platformRate;

    private final BillMapper billMapper;
    private final SplitLogMapper splitLogMapper;
    private final SettleSourcePort sourcePort;
    private final SplitGateway gateway;
    /** 算履约服务费要知道该自提点谈定的口径（ADR-009） */
    private final PickupQueryPort pickupPort;

    public SettleServiceImpl(BillMapper billMapper, SplitLogMapper splitLogMapper,
                             SettleSourcePort sourcePort, SplitGateway gateway,
                             PickupQueryPort pickupPort) {
        this.billMapper = billMapper;
        this.splitLogMapper = splitLogMapper;
        this.sourcePort = sourcePort;
        this.gateway = gateway;
        this.pickupPort = pickupPort;
    }

    // ---------------------------------------------------------------- 生成

    /**
     * 履约服务费：平台提供的自提点才收，按该点谈定的口径算（ADR-009 / V18）。
     *
     * <p>此前这里恒为 0，注释写着「R15 口径未定」—— 现在定了：自提点分**商家自行解决**
     * 与**平台提供**两种，后者的费率线下逐点协商、由运营平台录入，口径可能是按件也可能是按率。
     *
     * <p>三条边界：
     * <ul>
     *   <li>不经自提点（快递 / 上门）→ 0。没有人替你分拣保管，自然没有这笔钱</li>
     *   <li>查不到该自提点 → 0 而不是抛错。**结算不能因为一条主数据缺失就整批卡住**，
     *       少算的钱可以补，卡住的结算会让所有商家当期都拿不到钱</li>
     *   <li>{@code STORE} / {@code NEIGHBOR} 由 PickupQueryPort 那侧恒返回 {@code NONE}，
     *       这里不重复判断 —— 判两遍等于两处都可能改错</li>
     * </ul>
     *
     * @param gross 成交额（含平台补贴），按率计费时的基数
     */
    private long serviceFeeOf(SettleSourcePort.SettleSource src, long gross) {
        if (src.pickupNo() == null || src.pickupNo().isBlank()) {
            return 0L;
        }
        var pickup = pickupPort.find(src.pickupNo()).orElse(null);
        if (pickup == null) {
            log.warn("结算：自提点 {} 查不到，本单履约服务费按 0 计 subOrder={}",
                    src.pickupNo(), src.subOrderNo());
            return 0L;
        }
        return switch (pickup.feeMode()) {
            case "PER_ITEM" -> pickup.serviceFeePerItemMinor() * Math.max(src.itemCount(), 0);
            case "RATE" -> gross * pickup.serviceFeeRate() / 10000;
            default -> 0L;
        };
    }

    @Override
    @Transactional
    public int generateForOrder(String orderNo) {
        int created = 0;
        for (SettleSourcePort.SettleSource src : sourcePort.settleSourcesOf(orderNo)) {
            // 一个子单只能有一张结算单：重复生成 = 重复分账 = 给商家多打钱。
            // 靠先查再插 + DB 唯一索引双保险（事件重投时两条路径都可能撞上）
            if (findBySubOrder(src.subOrderNo()) != null) {
                continue;
            }
            int rate = rateOf(src.trafficSource());
            // ★ 平台补贴要补回给商家；商家自己让的利不补
            long gross = src.payAmount() + src.discountPlatform();
            long commission = gross * rate / 10000;
            long serviceFee = serviceFeeOf(src, gross);

            StlBill bill = new StlBill();
            bill.setSettleNo(BizKey.next(BizKey.SETTLE_BILL));
            bill.setSubOrderNo(src.subOrderNo());
            bill.setOrderNo(orderNo);
            bill.setMerchantNo(src.merchantNo());
            bill.setGrossMinor(gross);
            bill.setCommissionMinor(commission);
            bill.setServiceFeeMinor(serviceFee);
            bill.setNetMinor(gross - commission - serviceFee);
            bill.setTrafficSource(src.trafficSource());
            bill.setCommissionRate(rate);
            bill.setStatus(StlBill.PENDING);
            bill.setRetryCount(0);
            DataScopeContext.executeWithoutScope(() -> billMapper.insert(bill));
            created++;
        }
        return created;
    }

    private int rateOf(String trafficSource) {
        return "MERCHANT_OWNED".equals(trafficSource) ? merchantOwnedRate : platformRate;
    }

    // ---------------------------------------------------------------- 分账

    @Override
    @Transactional
    public void executeSplit(String settleNo) {
        StlBill bill = require(settleNo);
        if (StlBill.SPLIT.equals(bill.getStatus()) || StlBill.REVERSED.equals(bill.getStatus())) {
            return;   // 幂等：重复执行不会重复打款
        }

        bill.setStatus(StlBill.SPLITTING);
        update(bill);

        String requestNo = "SPL-" + settleNo;
        boolean ok = callProvider(StlSplitLog.SPLIT, bill, requestNo);
        if (!ok) {
            bill.setStatus(StlBill.RETRYING);
            bill.setRetryCount(nzi(bill.getRetryCount()) + 1);
            update(bill);
            return;
        }
        bill.setStatus(StlBill.SPLIT);
        bill.setSplitAt(System.currentTimeMillis());
        update(bill);
    }

    // ---------------------------------------------------------------- SettlePort（退款链路）

    @Override
    @Transactional
    public boolean reverseSplit(String subOrderNo) {
        StlBill bill = findBySubOrder(subOrderNo);
        if (bill == null) {
            // 没有结算单（例如未支付即取消）：没什么可回退的，视为成功
            return true;
        }
        if (StlBill.REVERSED.equals(bill.getStatus())) {
            return true;
        }
        if (!StlBill.SPLIT.equals(bill.getStatus())) {
            // **没分过账就不发回退指令** —— 发了只会收到「找不到分账单」的错误，
            // 徒增一条失败日志，还会让排查的人以为真出了问题
            bill.setStatus(StlBill.REVERSED);
            update(bill);
            return true;
        }

        boolean ok = callProvider(StlSplitLog.REVERSE, bill, "REV-" + bill.getSettleNo());
        if (!ok) {
            bill.setStatus(StlBill.MANUAL);
            bill.setLastError("分账回退失败，需人工处理");
            update(bill);
            return false;   // ★ 返回 false，调用方（售后）必须据此**停止退款**
        }
        bill.setStatus(StlBill.REVERSED);
        update(bill);
        return true;
    }

    @Override
    public String refund(String subOrderNo, long amountMinor, String reason) {
        // S4 接真实支付退款；当前记录意图。**顺序保证在调用方**（AfterSaleServiceImpl.doRefund）
        log.info("refund subOrder={} amount={} reason={}", subOrderNo, amountMinor, reason);
        return "REFUND-" + subOrderNo;
    }

    // ---------------------------------------------------------------- 查询

    @Override
    public List<SettleBillVO> merchantBills(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                        billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                                .eq(StlBill::getMerchantNo, merchantNo)
                                .orderByDesc(StlBill::getId))).stream()
                .map(this::toVO).toList();
    }

    @Override
    public SettleBillVO merchantBill(String merchantNo, String settleNo) {
        StlBill bill = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectOne(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getSettleNo, settleNo)
                        .eq(StlBill::getMerchantNo, merchantNo)
                        .last("limit 1")));
        if (bill == null) {
            // 属主校验写进查询条件：settleNo 可猜，不能先查出来再比对
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return toVO(bill);
    }

    @Override
    public RateCardVO rateCard() {
        return new RateCardVO(merchantOwnedRate, platformRate,
                "自带客流（扫店铺码进店）零佣金；平台客流按 "
                        + (platformRate / 100.0) + "% 收取。费率以下单时快照为准，调整不影响历史订单。");
    }

    @Override
    public long splitLogCount(String settleNo, String action) {
        return DataScopeContext.executeWithoutScope(() ->
                splitLogMapper.selectCount(Wrappers.<StlSplitLog>lambdaQuery()
                        .eq(StlSplitLog::getSettleNo, settleNo)
                        .eq(StlSplitLog::getSplitAction, action)
                        .eq(StlSplitLog::getResult, "SUCCESS")));
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 调分账通道并留痕。**幂等在这一层**：靠 {@code uk_request_no} 挡住重复指令，
     * 不依赖支付服务商的幂等实现是否可靠 —— 换真实通道时这段不用改。
     *
     * <p>**失败也写日志**：「发过但失败了」和「压根没发」是完全不同的排查方向。
     */
    private boolean callProvider(String action, StlBill bill, String requestNo) {
        boolean duplicated = DataScopeContext.executeWithoutScope(() ->
                splitLogMapper.selectCount(Wrappers.<StlSplitLog>lambdaQuery()
                        .eq(StlSplitLog::getRequestNo, requestNo)
                        .eq(StlSplitLog::getResult, "SUCCESS"))) > 0;
        if (duplicated) {
            return true;
        }

        long amount = nz(bill.getNetMinor());
        SplitGateway.Result result = StlSplitLog.REVERSE.equals(action)
                ? gateway.reverse(bill.getSubOrderNo(), amount, requestNo)
                : gateway.split(bill.getSubOrderNo(), amount, requestNo);

        StlSplitLog entry = new StlSplitLog();
        entry.setSettleNo(bill.getSettleNo());
        entry.setSubOrderNo(bill.getSubOrderNo());
        entry.setSplitAction(action);
        entry.setAmountMinor(amount);
        // 失败的指令也要有唯一 requestNo，但不能挡住重试 —— 加时间戳后缀
        entry.setRequestNo(result.success() ? requestNo
                : requestNo + "-F" + System.currentTimeMillis());
        entry.setResult(result.success() ? "SUCCESS" : "FAILED");
        entry.setProviderNo(result.providerNo());
        entry.setMessage(result.message());
        entry.setAt(System.currentTimeMillis());
        entry.setTenantNo("MAIN");
        entry.setCreatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> splitLogMapper.insert(entry));
        return result.success();
    }

    private StlBill findBySubOrder(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                billMapper.selectOne(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getSubOrderNo, subOrderNo).last("limit 1")));
    }

    private StlBill require(String settleNo) {
        StlBill bill = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectOne(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getSettleNo, settleNo).last("limit 1")));
        if (bill == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return bill;
    }

    private void update(StlBill bill) {
        DataScopeContext.executeWithoutScope(() -> billMapper.updateById(bill));
    }

    private SettleBillVO toVO(StlBill b) {
        return new SettleBillVO(b.getSettleNo(), b.getSubOrderNo(), b.getOrderNo(), b.getMerchantNo(),
                nz(b.getGrossMinor()), nz(b.getCommissionMinor()), nz(b.getServiceFeeMinor()),
                nz(b.getNetMinor()), b.getTrafficSource(), nzi(b.getCommissionRate()),
                b.getStatus(),
                b.getCreatedAt() == null ? 0L
                        : b.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                b.getSplitAt());
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nzi(Integer v) {
        return v == null ? 0 : v;
    }
}
