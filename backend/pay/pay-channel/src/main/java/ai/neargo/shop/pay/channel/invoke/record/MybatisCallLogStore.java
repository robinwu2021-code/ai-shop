package ai.neargo.shop.pay.channel.invoke.record;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.channel.entity.PayChannelCallLog;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelCallLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 调用记录落库，<b>独立事务</b>：调用方的业务事务回滚时（比如退款记账失败），「我们到底调没调渠道」这件事不能跟着消失 ——
 * 那正是事后对账最需要的一条（渠道接入统一封装设计 §11）。
 */
public final class MybatisCallLogStore implements CallLogStore {

    private final PayChannelCallLogMapper mapper;
    private final TransactionTemplate tx;

    public MybatisCallLogStore(PayChannelCallLogMapper mapper, PlatformTransactionManager payTxManager) {
        this.mapper = mapper;
        this.tx = new TransactionTemplate(payTxManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Long insert(PayChannelCallLog row) {
        return tx.execute(s -> {
            DataScopeContext.executeWithoutScope(() -> mapper.insert(row));
            return row.getId();
        });
    }

    @Override
    public void update(PayChannelCallLog row) {
        tx.executeWithoutResult(s -> DataScopeContext.executeWithoutScope(() -> mapper.updateById(row)));
    }

    @Override
    public Optional<PayChannelCallLog> lastSucceeded(String payChannel, String operation, String outNo) {
        return Optional.ofNullable(DataScopeContext.executeWithoutScope(() -> mapper.selectOne(
                Wrappers.<PayChannelCallLog>lambdaQuery()
                        .eq(PayChannelCallLog::getPayChannel, payChannel)
                        .eq(PayChannelCallLog::getOperation, operation)
                        .eq(PayChannelCallLog::getOutTradeNo, outNo)
                        .eq(PayChannelCallLog::getOutcomeClass, "SUCCEEDED")
                        .orderByDesc(PayChannelCallLog::getId)
                        .last("LIMIT 1"))));
    }
}
