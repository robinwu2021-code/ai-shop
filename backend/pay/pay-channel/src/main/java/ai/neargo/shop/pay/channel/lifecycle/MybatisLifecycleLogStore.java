package ai.neargo.shop.pay.channel.lifecycle;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.channel.entity.PayChannelLifecycleLog;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelLifecycleLogMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 生命周期日志落库（独立事务）。关闭阶段库可能已经不可用 —— 写不进去由调用方吞掉，日志里仍有一行 */
public final class MybatisLifecycleLogStore implements LifecycleLogStore {

    private final PayChannelLifecycleLogMapper mapper;
    private final TransactionTemplate tx;

    public MybatisLifecycleLogStore(PayChannelLifecycleLogMapper mapper, PlatformTransactionManager payTxManager) {
        this.mapper = mapper;
        this.tx = new TransactionTemplate(payTxManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void append(String payChannel, String fromState, String toState, String reason) {
        PayChannelLifecycleLog row = new PayChannelLifecycleLog();
        row.setPayChannel(payChannel);
        row.setFromState(fromState);
        row.setToState(toState);
        row.setReason(reason.length() <= 255 ? reason : reason.substring(0, 255));
        row.setOperatorNo("SYSTEM");
        row.setOccurredAt(LocalDateTime.now(ZoneOffset.UTC));
        tx.executeWithoutResult(s -> DataScopeContext.executeWithoutScope(() -> mapper.insert(row)));
    }
}
