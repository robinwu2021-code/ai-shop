package ai.neargo.shop.paybridge;

import static ai.neargo.shop.common.config.ModuleDataSources.MEMBER_MARKETING_BINDING;
import static ai.neargo.shop.common.config.ModuleDataSources.TRADE_DATA_SOURCE;
import static ai.neargo.shop.common.config.ModuleDataSources.TRADE_TX_MANAGER;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The shared trade database serializes cutover with each outstanding refund recovery call. */
@Component
public class StoredValueRefundRecoveryOwner {
    private static final String OWNER = "SELECT scan_owner FROM ord_service_refund_recovery_owner WHERE tenant_no=?";
    private final JdbcTemplate jdbc;
    private final String worker;
    private final EntitlementServiceUseCoordinator coordinator;

    public StoredValueRefundRecoveryOwner(@Qualifier(TRADE_DATA_SOURCE) DataSource trade,
            @Value("${" + MEMBER_MARKETING_BINDING + ":}") String target,
            EntitlementServiceUseCoordinator coordinator) {
        this.jdbc = new JdbcTemplate(trade);
        this.worker = target.isBlank() ? "PAY" : "TARGET";
        this.coordinator = coordinator;
    }

    public boolean owns(String tenant) {
        return worker.equals(jdbc.queryForObject(OWNER, String.class, tenant));
    }

    @Transactional(transactionManager = TRADE_TX_MANAGER)
    public String recover(EntitlementContext context, String entity, String operation) {
        String owner = jdbc.queryForObject(OWNER + " FOR UPDATE", String.class, context.tenantNo());
        if (!worker.equals(owner)) return "PENDING";
        return coordinator.recoverRefund(context, entity, operation).status();
    }
}
