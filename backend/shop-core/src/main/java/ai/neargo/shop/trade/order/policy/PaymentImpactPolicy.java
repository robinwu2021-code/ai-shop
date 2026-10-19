package ai.neargo.shop.trade.order.policy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.contract.CancellationImpact;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.projection.OrderStatusProjector;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 真实影响集合先不带域读取，再借现有行级权限逐对象校验。 */
@Component
public class PaymentImpactPolicy {
    private final PaymentMapper payments;
    private final StoreOrderMapper orders;
    private final MerchantQueryPort merchants;

    public PaymentImpactPolicy(PaymentMapper payments, StoreOrderMapper orders, MerchantQueryPort merchants) {
        this.payments = payments; this.orders = orders; this.merchants = merchants;
    }

    public CancellationImpact preview(String requestedNo) {
        PaymentSet set = load(requestedNo);
        if (!authorized(set.orders())) return CancellationImpact.blocked(ErrorCode.FORBIDDEN.name());
        if (!cancellable(set)) return CancellationImpact.blocked(ErrorCode.ORDER_STATE_ILLEGAL.name());
        Map<String, MerchantQueryPort.MerchantBrief> merchantNames = merchants.findAll(
                set.orders().stream().map(OrdSubOrder::getEntityNo).distinct().toList());
        Map<String, String> storeNames = merchants.storeNames(set.orders().stream()
                .map(OrdSubOrder::getStoreNo).filter(no -> no != null).distinct().toList());
        List<CancellationImpact.AffectedOrder> affected = set.orders().stream().map(sub -> {
            var merchant = merchantNames.get(sub.getEntityNo());
            return new CancellationImpact.AffectedOrder(sub.getSubOrderNo(), sub.getEntityNo(),
                    merchant == null ? sub.getEntityNo() : merchant.merchantName(), sub.getStoreNo(),
                    sub.getStoreNo() == null ? null : storeNames.get(sub.getStoreNo()), sub.getPayAmount(),
                    OrderStatusProjector.contract(sub.getStatus(), set.payment().getStatus()));
        }).toList();
        return new CancellationImpact(true, null, requestedNo, set.payment().getOrderNo(),
                set.payment().getPayAmount(), set.payment().getCurrency(), affected);
    }

    public PaymentSet requireCancellable(String requestedNo) {
        PaymentSet set = load(requestedNo);
        if (!authorized(set.orders())) throw BizException.of(ErrorCode.FORBIDDEN);
        if (!cancellable(set)) throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        return set;
    }

    private PaymentSet load(String requestedNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            OrdSubOrder sub = orders.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                    .eq(OrdSubOrder::getSubOrderNo, requestedNo));
            String no = sub == null ? requestedNo : sub.getOrderNo();
            OrdOrder payment = payments.selectOne(Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getOrderNo, no));
            if (payment == null) throw BizException.of(ErrorCode.NOT_FOUND);
            List<OrdSubOrder> children = orders.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                    .eq(OrdSubOrder::getOrderNo, no).orderByAsc(OrdSubOrder::getId));
            return new PaymentSet(payment, children);
        });
    }

    private boolean authorized(List<OrdSubOrder> all) {
        if (all.isEmpty()) return false;
        List<String> nos = all.stream().map(OrdSubOrder::getSubOrderNo).toList();
        Set<String> allowed = orders.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                .in(OrdSubOrder::getSubOrderNo, nos)).stream()
                .map(OrdSubOrder::getSubOrderNo).collect(Collectors.toSet());
        return allowed.containsAll(nos);
    }

    private boolean cancellable(PaymentSet set) {
        String status = set.payment().getStatus();
        return (OrdOrder.WAIT_PAY.equals(status) || OrdOrder.WAIT_OFFLINE_PAY.equals(status))
                && set.orders().stream().allMatch(sub -> OrdSubOrder.WAIT_PAY.equals(sub.getStatus()));
    }

    public static boolean singleStore(Collection<String> stores) {
        return !stores.isEmpty() && stores.stream().allMatch(no -> no != null && !no.isBlank())
                && stores.stream().distinct().count() == 1;
    }

    public static void requireSingleStore(Collection<String> stores) {
        if (!singleStore(stores)) throw BizException.of(ErrorCode.PAY_MODE_NOT_SUPPORTED);
    }

    public record PaymentSet(OrdOrder payment, List<OrdSubOrder> orders) { }
}
