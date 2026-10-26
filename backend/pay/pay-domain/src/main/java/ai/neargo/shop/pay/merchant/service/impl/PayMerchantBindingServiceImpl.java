package ai.neargo.shop.pay.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.mapper.merchant.MerchantMappers.PayMerchantBindingMapper;
import ai.neargo.shop.pay.mapper.merchant.MerchantMappers.PayMerchantMapper;
import ai.neargo.shop.pay.merchant.entity.PayMerchant;
import ai.neargo.shop.pay.merchant.entity.PayMerchantBinding;
import ai.neargo.shop.pay.merchant.port.PayMerchantQueryPortImpl;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantQueryPort.PayStore;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link PayMerchantBindingService} 的实现（TDD-支付服务-I4进件与收款商户 S2）。门店属于哪个主体、哪个市场经核心系统 Port 判断 */
@Service
public class PayMerchantBindingServiceImpl implements PayMerchantBindingService {

    private final PayMerchantMapper merchants;
    private final PayMerchantBindingMapper bindings;
    private final MerchantQueryPort stores;
    private final TransactionTemplate tx;

    public PayMerchantBindingServiceImpl(PayMerchantMapper merchants, PayMerchantBindingMapper bindings,
                                         MerchantQueryPort stores,
                                         @Qualifier("payTxManager") PlatformTransactionManager payTxManager) {
        this.merchants = merchants;
        this.bindings = bindings;
        this.stores = stores;
        this.tx = new TransactionTemplate(payTxManager);
    }

    @Override
    public Optional<StoreRef> storeByNo(String storeNo) {
        return stores.payStoreOf(storeNo).map(s -> new StoreRef(s.storeId(), s.storeNo(), s.storeName(), s.entityName(),
                s.market() == null ? "CN" : s.market(), s.fundsPath()));
    }

    @Override
    public Long bind(Long storeId, String payChannel, Long payMerchantId, String operatorNo) {
        PayMerchant m = DataScopeContext.executeWithoutScope(() -> merchants.selectById(payMerchantId));
        if (m == null || !"ACTIVE".equals(m.getStatus())) {
            throw invalid("收款商户不是 ACTIVE");
        }
        if (!Objects.equals(m.getPayChannel(), payChannel)) {
            throw invalid("渠道不符：" + m.getPayChannel() + " ≠ " + payChannel);
        }
        PayStore store = stores.payStoreById(storeId).orElseThrow(() -> invalid("门店不存在"));
        if (!Objects.equals(store.market(), m.getMarket())) {
            throw invalid("市场不符：" + store.market() + " ≠ " + m.getMarket());
        }
        if (m.getStoreId() != null) {
            PayStore opening = stores.payStoreById(m.getStoreId()).orElseThrow(() -> invalid("开户门店不存在"));
            if (!Objects.equals(opening.entityNo(), store.entityNo())) {
                // R03 / R26：A 店主体的号不能绑给别的主体的店
                throw invalid("开户门店与本门店不属于同一主体");
            }
        }
        return tx.execute(s -> {
            release(storeId, payChannel);
            PayMerchantBinding b = new PayMerchantBinding();
            b.setStoreId(storeId);
            b.setPayChannel(payChannel);
            b.setPayMerchantId(payMerchantId);
            b.setStatus("ACTIVE");
            b.setActiveKey(PayMerchantQueryPortImpl.activeKey(storeId, payChannel));
            b.setBoundBy(operatorNo == null || operatorNo.isBlank() ? "SYSTEM" : operatorNo);
            b.setBoundAt(LocalDateTime.now(ZoneOffset.UTC));
            DataScopeContext.executeWithoutScope(() -> bindings.insert(b));
            return b.getId();
        });
    }

    @Override
    public void unbind(Long storeId, String payChannel, String operatorNo) {
        tx.executeWithoutResult(s -> release(storeId, payChannel));
    }

    private void release(Long storeId, String payChannel) {
        DataScopeContext.executeWithoutScope(() -> bindings.update(null, Wrappers.<PayMerchantBinding>lambdaUpdate()
                .eq(PayMerchantBinding::getActiveKey, PayMerchantQueryPortImpl.activeKey(storeId, payChannel))
                .set(PayMerchantBinding::getStatus, "INACTIVE").set(PayMerchantBinding::getActiveKey, null)
                .set(PayMerchantBinding::getUnboundAt, LocalDateTime.now(ZoneOffset.UTC))));
    }

    private static BizException invalid(String why) {
        return BizException.of(ErrorCode.PAY_BINDING_INVALID, why);
    }
}
