package ai.neargo.shop.pay.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.merchant.MerchantMappers.PayMerchantBindingMapper;
import ai.neargo.shop.pay.mapper.merchant.MerchantMappers.PayMerchantMapper;
import ai.neargo.shop.pay.mapper.merchant.MerchantMappers.PayMerchantProductMapper;
import ai.neargo.shop.pay.merchant.entity.PayMerchant;
import ai.neargo.shop.pay.merchant.entity.PayMerchantBinding;
import ai.neargo.shop.pay.merchant.entity.PayMerchantProduct;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * {@link PayMerchantQueryPort} 的实现：读 {@code pay_merchant_binding} + {@code pay_merchant}。
 * <b>只按门店绑定解析，没有主体级兜底</b>（R01；TDD-支付服务-I4进件与收款商户 S2）。
 */
@Component
public class PayMerchantQueryPortImpl implements PayMerchantQueryPort {

    static final String ACTIVE = "ACTIVE";

    private final PayMerchantMapper merchants;
    private final PayMerchantBindingMapper bindings;
    private final PayMerchantProductMapper products;

    public PayMerchantQueryPortImpl(PayMerchantMapper merchants, PayMerchantBindingMapper bindings,
                                    PayMerchantProductMapper products) {
        this.merchants = merchants;
        this.bindings = bindings;
        this.products = products;
    }

    @Override
    public Optional<PayRoute> routeOf(Long storeId, String payChannel) {
        if (storeId == null || payChannel == null) {
            return Optional.empty();
        }
        PayMerchantBinding b = DataScopeContext.executeWithoutScope(() -> bindings.selectOne(
                Wrappers.<PayMerchantBinding>lambdaQuery().eq(PayMerchantBinding::getActiveKey, activeKey(storeId, payChannel))));
        if (b == null) {
            return Optional.empty();
        }
        PayMerchant m = DataScopeContext.executeWithoutScope(() -> merchants.selectById(b.getPayMerchantId()));
        return m == null || !ACTIVE.equals(m.getStatus()) ? Optional.empty() : Optional.of(route(storeId, m));
    }

    @Override
    public List<PayRoute> collectableRoutes(Long storeId, String market) {
        List<PayMerchantBinding> active = DataScopeContext.executeWithoutScope(() -> bindings.selectList(
                Wrappers.<PayMerchantBinding>lambdaQuery().eq(PayMerchantBinding::getStoreId, storeId)
                        .eq(PayMerchantBinding::getStatus, ACTIVE)));
        return active.stream()
                .map(b -> DataScopeContext.executeWithoutScope(() -> merchants.selectById(b.getPayMerchantId())))
                .filter(m -> m != null && ACTIVE.equals(m.getStatus()) && Objects.equals(m.getMarket(), market))
                .map(m -> route(storeId, m))
                .sorted(Comparator.comparingInt(PayRoute::priority).thenComparing(PayRoute::payMerchantId))
                .toList();
    }

    @Override
    public Optional<PayRoute> routeByPayMerchantId(Long payMerchantId, Integer keyVersion) {
        PayMerchant m = payMerchantId == null ? null
                : DataScopeContext.executeWithoutScope(() -> merchants.selectById(payMerchantId));
        if (m == null || "CLOSED".equals(m.getStatus())) {
            return Optional.empty();
        }
        PayRoute r = route(m.getStoreId(), m);
        // 交易冻结的密钥版本优先（密钥模块就绪前恒为 0）
        return Optional.of(keyVersion == null ? r : new PayRoute(r.storeId(), r.payMerchantId(), r.payMerchantNo(),
                r.payChannel(), r.market(), r.currency(), r.accessMode(), r.fundsPath(), r.channelMerchantId(),
                r.subMerchantId(), r.parentPayMerchantId(), keyVersion, r.priority()));
    }

    @Override
    public Optional<MerchantCapability> capabilityOf(Long payMerchantId) {
        PayMerchant m = payMerchantId == null ? null
                : DataScopeContext.executeWithoutScope(() -> merchants.selectById(payMerchantId));
        if (m == null) {
            return Optional.empty();
        }
        var open = DataScopeContext.executeWithoutScope(() -> products.selectList(Wrappers.<PayMerchantProduct>lambdaQuery()
                        .eq(PayMerchantProduct::getPayMerchantId, payMerchantId).eq(PayMerchantProduct::getStatus, "OPEN")))
                .stream().map(PayMerchantProduct::getPayChannelProductId).collect(Collectors.toSet());
        return Optional.of(new MerchantCapability(m.getId(), m.getStatus(), open, Boolean.TRUE.equals(m.getInvoiceCapable()),
                m.getFeeBearer(), m.getSettleCycle(), m.getSplitReversible()));
    }

    public static String activeKey(Long storeId, String payChannel) {
        return storeId + "|" + payChannel;
    }

    private static PayRoute route(Long storeId, PayMerchant m) {
        return new PayRoute(storeId, m.getId(), m.getPayMerchantNo(), m.getPayChannel(), m.getMarket(), m.getCurrency(),
                m.getAccessMode(), m.getFundsPath(), m.getChannelMerchantId(),
                m.getSubMerchantId() == null ? "" : m.getSubMerchantId(), m.getParentPayMerchantId(),
                m.getSecKeyVersion() == null ? 0 : m.getSecKeyVersion(), m.getPriority() == null ? 100 : m.getPriority());
    }
}
