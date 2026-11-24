package ai.neargo.shop.pay.acquiring.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayOrderMapper;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.spi.pay.PaidCollectionPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import java.util.Set;

/**
 * {@link PaidCollectionPort} 的内嵌实现：读支付单本身（{@code biz_type=BIZ_COLLECT}、已成功），门店号按支付单的门店主键换。
 * 装配在 shop-gateway {@code OrdinaryPaymentRouting}（只在内嵌形态）。
 */
public class PaidCollectionPortImpl implements PaidCollectionPort {

    /** 与 CheckoutServiceImpl.COLLECT_BIZ_TYPE 同值：门店代客收款（收款码 / 付款链接） */
    static final String COLLECT_BIZ_TYPE = "BIZ_COLLECT";
    private static final Set<String> SUCCEEDED = Set.of("SUCCEEDED", "PARTIALLY_REFUNDED", "REFUNDED");

    private final PayOrderMapper orders;
    private final PayMerchantBindingService stores;

    public PaidCollectionPortImpl(PayOrderMapper orders, PayMerchantBindingService stores) {
        this.orders = orders;
        this.stores = stores;
    }

    @Override
    public Optional<PaidCollection> paidCollection(String payOrderNo) {
        PayOrder o = DataScopeContext.executeWithoutScope(() -> orders.selectOne(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getPayOrderNo, payOrderNo).last("LIMIT 1")));
        if (o == null || !COLLECT_BIZ_TYPE.equals(o.getBizType()) || !SUCCEEDED.contains(o.getStatus())
                || o.getPaidMinor() == null || o.getPaidMinor() <= 0) return Optional.empty();
        return stores.storeById(o.getStoreId()).map(store -> new PaidCollection(o.getPayOrderNo(), store.storeNo(),
                o.getBizNo(), o.getCurrency(), o.getPaidMinor(), o.getPayChannel()));
    }
}
