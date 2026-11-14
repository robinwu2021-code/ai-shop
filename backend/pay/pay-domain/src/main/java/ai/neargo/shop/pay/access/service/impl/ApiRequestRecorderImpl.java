package ai.neargo.shop.pay.access.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.access.entity.PayApiRequestLog;
import ai.neargo.shop.pay.access.service.ApiRequestRecorder;
import ai.neargo.shop.pay.mapper.access.AccessMappers.PayApiRequestLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link ApiRequestRecorder} 的实现 */
@Service
public class ApiRequestRecorderImpl implements ApiRequestRecorder {

    private static final Logger log = LoggerFactory.getLogger(ApiRequestRecorderImpl.class);

    private final PayApiRequestLogMapper logs;
    private final TransactionTemplate tx;

    public ApiRequestRecorderImpl(PayApiRequestLogMapper logs,
                                  @Qualifier("payTxManager") PlatformTransactionManager payTxManager) {
        this.logs = logs;
        this.tx = new TransactionTemplate(payTxManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void record(PayApiRequestLog row) {
        try {
            tx.executeWithoutResult(s -> DataScopeContext.executeWithoutScope(() -> logs.insert(row)));
        } catch (RuntimeException e) {
            log.warn("[pay-request-log] 记录失败（不影响请求）：{} {}", row.getApi(), e.toString());
        }
    }
}
