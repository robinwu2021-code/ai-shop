package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementExceptionService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ops-exception-list;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class OpsEntitlementExceptionListFlowTest {
    @Autowired EntitlementExceptionService exceptions;
    @Autowired JdbcTemplate jdbc;
    private void row(String tenant, String entity) {
        var no = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO pay_ent_exception(tenant_no,exception_no,entity_no,kind,biz_no,status,evidence,"
                + "created_at,updated_at,created_by,updated_by) VALUES(?,?,?,'GRANT_DELIVERY',?,'OPEN','{}',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'TEST','TEST')", tenant, no, entity, no);
    }
    @Test void actualPaginationFiltersByAuthorizedEntitiesBeforeCountingOrReadingRows() {
        row("MAIN", "M1"); row("MAIN", "M2"); row("MAIN", "M3"); row("OTHER", "M1");
        var allowed = new OperatorEntityScope("MAIN", "OP1", Set.of("M1", "M2"));
        var first = exceptions.listAcross(allowed, "GRANT_DELIVERY", "OPEN", 1, 1);
        var second = exceptions.listAcross(allowed, "GRANT_DELIVERY", "OPEN", 2, 1);
        assertThat(first.total()).isEqualTo(2);
        assertThat(second.total()).isEqualTo(2);
        assertThat(first.records().getFirst().exceptionNo()).isNotEqualTo(second.records().getFirst().exceptionNo());
        assertThat(first.records().getFirst().entityNo()).isIn("M1", "M2");
        assertThat(second.records().getFirst().entityNo()).isIn("M1", "M2");
        assertThat(exceptions.listAcross(new OperatorEntityScope("MAIN", "OP1", Set.of()), null, null, 1, 20).total())
                .isZero();
        assertThat(exceptions.listAcross(new OperatorEntityScope("MAIN", "OP1", null), null, null, 1, 20).total())
                .isEqualTo(3);
        assertThat(exceptions.listAcross(allowed, null, "RESOLVED", 1, 20).total()).isZero();
        assertThatThrownBy(() -> exceptions.listAcross(new OperatorEntityScope("OTHER", "OP1", null),
                null, null, 1, 20)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> exceptions.listAcross(allowed, null, null, 1, 101)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance", Integer.class)).isZero();
    }
}
