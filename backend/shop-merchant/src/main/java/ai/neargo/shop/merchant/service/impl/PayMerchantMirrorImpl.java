package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.merchant.service.PayMerchantMirror;
import ai.neargo.shop.spi.pay.PayMerchantMirrorPort;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** {@link PayMerchantMirror} 的实现 */
@Service
public class PayMerchantMirrorImpl implements PayMerchantMirror {

    private static final Logger log = LoggerFactory.getLogger(PayMerchantMirrorImpl.class);
    private static final Object PENDING = PayMerchantMirrorImpl.class.getName() + ".pending";

    private final ObjectProvider<PayMerchantMirrorPort> port;

    public PayMerchantMirrorImpl(ObjectProvider<PayMerchantMirrorPort> port) {
        this.port = port;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void changed(String entityNo) {
        if (entityNo == null || entityNo.isBlank()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            fire(entityNo);
            return;
        }
        Set<String> pending = (Set<String>) TransactionSynchronizationManager.getResource(PENDING);
        if (pending == null) {
            Set<String> batch = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(PENDING, batch);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    batch.forEach(PayMerchantMirrorImpl.this::fire);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING);
                }
            });
            pending = batch;
        }
        pending.add(entityNo);
    }

    /** 旧表已提交：新表写不进去只记日志，差异由比对（S5）报出来 */
    private void fire(String entityNo) {
        try {
            port.ifAvailable(p -> p.legacyChanged(entityNo));
        } catch (RuntimeException ex) {
            log.warn("[pay-merchant-dual-write] 主体 {} 同步新表失败，旧路径不受影响：{}", entityNo, ex.toString());
        }
    }
}
