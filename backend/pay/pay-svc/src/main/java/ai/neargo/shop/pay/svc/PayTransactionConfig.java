package ai.neargo.shop.pay.svc;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

/**
 * Standalone pay has one physical database; every transaction name identifies that same manager,
 * including the pay-merchant module handle (ADR-066), which has no separate database here.
 */
@Configuration(proxyBeanMethods=false)
public class PayTransactionConfig {
    @Bean(name={"transactionManager","payTxManager","payMerchantTxManager"})
    public JdbcTransactionManager payTransactionManager(DataSource data) {
        var manager = new JdbcTransactionManager(data);
        manager.setTransactionSynchronization(
                AbstractPlatformTransactionManager.SYNCHRONIZATION_ON_ACTUAL_TRANSACTION);
        return manager;
    }
}
