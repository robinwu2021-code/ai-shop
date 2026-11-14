package ai.neargo.shop.trade.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.RefundQuantityFactsPort;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Projects persisted successful plans through their owner using the caller's existing Core transaction. */
@Component
public final class RefundQuantityFactsPortImpl implements RefundQuantityFactsPort {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public RefundQuantityFactsPortImpl(@Qualifier("transactionManager") PlatformTransactionManager manager,
                                      ObjectMapper json) {
        if (!(manager instanceof DataSourceTransactionManager data)) {
            throw new IllegalStateException("Core JDBC transaction required");
        }
        this.db = new JdbcTemplate(Objects.requireNonNull(data.getDataSource()));
        this.json = json;
    }

    @Override
    public List<Fact> succeeded(String tenantNo, String subOrderNo, Instant through, List<String> processed) {
        var rows = db.queryForList("SELECT execution_no,plan_json,succeeded_at FROM ord_refund_execution "
                + "WHERE tenant_no=? AND sub_order_no=? AND status='SUCCEEDED' AND succeeded_at<=? "
                + "ORDER BY succeeded_at,id", tenantNo, subOrderNo, through.toEpochMilli());
        var facts = new ArrayList<Fact>();
        for (var row : rows) {
            String executionNo = (String) row.get("execution_no");
            if (processed.contains(executionNo)) continue;
            var plan = json.readValue((String) row.get("plan_json"), RefundPlan.class);
            if (!subOrderNo.equals(plan.subOrderNo())) throw BizException.of(ErrorCode.CONFLICT);
            var lines = plan.lines().stream().map(line -> new LineQuantity(line.itemId(), line.qty())).toList();
            facts.add(new Fact(executionNo, plan.subOrderNo(), lines,
                    ((Number) row.get("succeeded_at")).longValue()));
        }
        return List.copyOf(facts);
    }
}
