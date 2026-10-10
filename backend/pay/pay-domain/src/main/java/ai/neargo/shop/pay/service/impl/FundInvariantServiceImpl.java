package ai.neargo.shop.pay.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.entity.PtsUserLedger;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.service.FundInvariantService;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class FundInvariantServiceImpl implements FundInvariantService {

    private static final Logger log = LoggerFactory.getLogger(FundInvariantServiceImpl.class);

    private final BillMapper billMapper;
    private final SettleMappers.PaymentMapper paymentMapper;
    private final SettleMappers.PointsLedgerMapper ledgerMapper;

    public FundInvariantServiceImpl(BillMapper billMapper,
                                    SettleMappers.PointsLedgerMapper ledgerMapper,
                                    ai.neargo.shop.pay.PointsService pointsService,
                                    SettleMappers.PaymentMapper paymentMapper) {
        this.billMapper = billMapper;
        this.paymentMapper = paymentMapper;
        this.ledgerMapper = ledgerMapper;
    }

    /**
     * 只读，且<b>只挑有订单号的</b>。
     *
     * <p>提现（PAYOUT）、补贴（SUBSIDY）这类流水的 {@code orderNo} 是空的 ——
     * 它们成功与订单状态无关，混进来会让 I8 每轮都在比对一批永远对不上的东西，
     * 而那种噪声最后的效果是没人再看这个任务的结果。
     */
    @Override
    public List<SuccessPayment> successPaymentsSince(long since, int limit) {
        return DataScopeContext.executeWithoutScope(() -> paymentMapper.selectList(
                        Wrappers.<StlPayment>lambdaQuery()
                                .eq(StlPayment::getDirection, StlPayment.PAY)
                                .eq(StlPayment::getStatus, StlPayment.SUCCESS)
                                .isNotNull(StlPayment::getOrderNo)
                                .ge(StlPayment::getSucceededAt, since)
                                .orderByAsc(StlPayment::getSucceededAt)
                                .last("LIMIT " + limit)))
                .stream()
                .map(p -> new SuccessPayment(p.getPaymentNo(), p.getOrderNo(),
                        p.getSucceededAt() == null ? 0L : p.getSucceededAt()))
                .toList();
    }

    @Override
    public java.util.Set<String> subOrdersWithBill(java.util.Collection<String> subOrderNos) {
        return subOrderNos.isEmpty() ? Set.of() : billSubOrderNos(List.copyOf(subOrderNos));
    }

    @Override
    public java.util.Set<String> subOrdersWithEarnLedger(java.util.Collection<String> subOrderNos) {
        return subOrderNos.isEmpty() ? Set.of() : earnedSubOrderNos(List.copyOf(subOrderNos));
    }

    @Override
    public List<String> billSubOrderNosSince(long since, int limit) {
        return DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .ge(StlBill::getAccruedAt, since)
                        .orderByAsc(StlBill::getAccruedAt)
                        .last("limit " + Math.max(1, limit))))
                .stream().map(StlBill::getSubOrderNo).toList();
    }


    @Override
    public List<String> pendingHoldSubOrders(long olderThan, int limit) {
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(olderThan), java.time.ZoneId.systemDefault());
        return DataScopeContext.executeWithoutScope(() ->
                ledgerMapper.selectList(Wrappers.<PtsUserLedger>lambdaQuery()
                        .eq(PtsUserLedger::getBizType, PtsUserLedger.USE)
                        .eq(PtsUserLedger::getStatus, PtsUserLedger.PENDING)
                        .lt(PtsUserLedger::getCreatedAt, cutoff)
                        .orderByAsc(PtsUserLedger::getCreatedAt)
                        .last("limit " + Math.max(1, limit))))
                .stream().map(PtsUserLedger::getSubOrderNo).distinct().toList();
    }


    /** 这批子单里**已经有发分流水**的。一次查回来，不逐个 exists */
    private Set<String> earnedSubOrderNos(List<String> subOrderNos) {
        List<PtsUserLedger> rows = DataScopeContext.executeWithoutScope(() ->
                ledgerMapper.selectList(Wrappers.<PtsUserLedger>lambdaQuery()
                        .eq(PtsUserLedger::getBizType, PtsUserLedger.EARN)
                        .in(PtsUserLedger::getSubOrderNo, subOrderNos)));
        return rows.stream().map(PtsUserLedger::getSubOrderNo)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** 这批子单里**已经有结算单**的。一次查回来，不逐个 exists —— 那是 N 次往返 */
    private Set<String> billSubOrderNos(List<String> subOrderNos) {
        List<StlBill> rows = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .in(StlBill::getSubOrderNo, subOrderNos)));
        return rows.stream().map(StlBill::getSubOrderNo)
                .collect(java.util.stream.Collectors.toSet());
    }
}
