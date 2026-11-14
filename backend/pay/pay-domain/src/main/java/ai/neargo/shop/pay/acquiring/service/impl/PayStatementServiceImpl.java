package ai.neargo.shop.pay.acquiring.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayAction;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.access.policy.CallerPolicy;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.acquiring.service.PayStatementService;
import ai.neargo.shop.pay.funds.BizDates;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayOrderMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayRefundMapper;
import ai.neargo.shop.pay.merchant.MerchantDefaults;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** {@link PayStatementService} 的实现（TDD-支付服务-I5接入层 S8） */
@Service
public class PayStatementServiceImpl implements PayStatementService {

    /** 一天最多列多少行（单店一天的笔数；超出时合计照算、明细截断） */
    private static final int MAX_ITEMS = 1000;

    private final PayOrderMapper orders;
    private final PayRefundMapper refunds;
    private final CallerPolicy policy;
    private final PayMerchantBindingService stores;

    public PayStatementServiceImpl(PayOrderMapper orders, PayRefundMapper refunds, CallerPolicy policy,
                                   PayMerchantBindingService stores) {
        this.orders = orders;
        this.refunds = refunds;
        this.policy = policy;
        this.stores = stores;
    }

    @Override
    public DailyStatement daily(PayCaller caller, Long storeId, LocalDate bizDate) {
        policy.check(caller, PayAction.READ_LEDGER, storeId);
        String market = stores.storeById(storeId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND)).market();
        LocalDateTime from = BizDates.startUtc(market, bizDate);
        LocalDateTime to = BizDates.startUtc(market, bizDate.plusDays(1));
        List<PayOrder> paid = DataScopeContext.executeWithoutScope(() -> orders.selectList(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getStoreId, storeId).isNotNull(PayOrder::getSucceededAt)
                .ge(PayOrder::getSucceededAt, from).lt(PayOrder::getSucceededAt, to)));
        List<PayRefund> refunded = DataScopeContext.executeWithoutScope(() -> refunds.selectList(
                Wrappers.<PayRefund>lambdaQuery().eq(PayRefund::getStoreId, storeId).eq(PayRefund::getStatus, "SUCCEEDED")
                        .ge(PayRefund::getSucceededAt, from).lt(PayRefund::getSucceededAt, to)));
        Map<Long, PayOrder> byId = paid.stream().collect(Collectors.toMap(PayOrder::getId, Function.identity()));
        List<Item> items = new ArrayList<>();
        for (PayOrder o : paid) {
            items.add(new Item("PAYMENT", o.getPayOrderNo(), o.getPayOrderNo(), nz(o.getPaidMinor()), o.getPayChannel(),
                    o.getPayMethod(), o.getStatus(), epoch(o.getSucceededAt())));
        }
        for (PayRefund r : refunded) {
            PayOrder o = byId.containsKey(r.getPayOrderId()) ? byId.get(r.getPayOrderId())
                    : DataScopeContext.executeWithoutScope(() -> orders.selectById(r.getPayOrderId()));
            items.add(new Item("REFUND", r.getPayRefundNo(), o == null ? null : o.getPayOrderNo(), nz(r.getAmountMinor()),
                    o == null ? null : o.getPayChannel(), o == null ? null : o.getPayMethod(), r.getStatus(),
                    epoch(r.getSucceededAt())));
        }
        items.sort(Comparator.comparing(Item::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return new DailyStatement(bizDate, MerchantDefaults.currencyOf(market), paid.size(),
                paid.stream().mapToLong(o -> nz(o.getPaidMinor())).sum(), refunded.size(),
                refunded.stream().mapToLong(r -> nz(r.getAmountMinor())).sum(),
                items.size() > MAX_ITEMS ? items.subList(0, MAX_ITEMS) : items);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static Long epoch(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
