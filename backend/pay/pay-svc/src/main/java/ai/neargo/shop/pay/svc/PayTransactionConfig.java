package ai.neargo.shop.pay.svc;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** Standalone pay has one physical database; both transaction names identify that same manager. */
@Configuration(proxyBeanMethods=false)
public class PayTransactionConfig {
    @Bean(name={"transactionManager","payTxManager"})
    public JdbcTransactionManager payTransactionManager(DataSource data) {
        return new JdbcTransactionManager(data);
    }
}
