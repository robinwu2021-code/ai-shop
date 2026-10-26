package ai.neargo.shop.pay.channel.http;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.channel.entity.PayRawPayload;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayRawPayloadMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 原文落库，<b>独立事务</b>：业务回滚不带走「我们发了什么、渠道回了什么」（同 {@code MybatisCallLogStore}） */
public final class MybatisRawPayloadStore implements RawPayloadStore {

    private final PayRawPayloadMapper mapper;
    private final TransactionTemplate tx;

    public MybatisRawPayloadStore(PayRawPayloadMapper mapper, PlatformTransactionManager payTxManager) {
        this.mapper = mapper;
        this.tx = new TransactionTemplate(payTxManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Long insert(PayRawPayload row) {
        return tx.execute(s -> {
            DataScopeContext.executeWithoutScope(() -> mapper.insert(row));
            return row.getId();
        });
    }
}
