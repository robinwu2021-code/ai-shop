package ai.neargo.shop.pay.openapp;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayApiNonceMapper;
import ai.neargo.shop.pay.openapp.entity.PayApiNonce;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 落库的 nonce 存储（无 Redis 时）：插 {@code pay_api_nonce}，唯一键撞上即重放。过期行由 {@link #purgeExpired} 清理。
 */
@Component
public class DbNonceStore implements NonceStore {

    private final PayApiNonceMapper nonces;
    private final TransactionTemplate tx;

    public DbNonceStore(PayApiNonceMapper nonces, @Qualifier("payTxManager") PlatformTransactionManager payTxManager) {
        this.nonces = nonces;
        this.tx = new TransactionTemplate(payTxManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean claim(String callerType, Long callerId, String nonce, Duration ttl) {
        PayApiNonce row = new PayApiNonce();
        row.setCallerType(callerType);
        row.setCallerId(callerId);
        row.setNonce(nonce);
        row.setExpireAt(LocalDateTime.now(ZoneOffset.UTC).plus(ttl));
        try {
            tx.executeWithoutResult(s -> DataScopeContext.executeWithoutScope(() -> nonces.insert(row)));
            return true;
        } catch (DuplicateKeyException replay) {
            return false;
        }
    }

    /** 删过期 nonce（逻辑删除表也物理删：防重放凭证不是业务事实） */
    public int purgeExpired() {
        return DataScopeContext.executeWithoutScope(() -> nonces.delete(Wrappers.<PayApiNonce>lambdaQuery()
                .lt(PayApiNonce::getExpireAt, LocalDateTime.now(ZoneOffset.UTC))));
    }
}
