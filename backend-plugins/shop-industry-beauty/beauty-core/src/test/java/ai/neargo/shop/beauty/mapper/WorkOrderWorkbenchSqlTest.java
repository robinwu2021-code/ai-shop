package ai.neargo.shop.beauty.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.beauty.dto.WorkOrderQueryReq;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.IntStream;

class WorkOrderWorkbenchSqlTest {
    protected DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:svc_queue_"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @Test
    void realSqlPaginatesStableRootsAndKeepsTenantStoreAndContextBoundaries() throws Exception {
        var source = dataSource();
        var sql = new JdbcTemplate(source);
        sql.execute(
                "CREATE TABLE bty_work_order(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no"
                    + " VARCHAR(32),entity_no VARCHAR(32),store_no VARCHAR(32),work_order_no"
                    + " VARCHAR(32),appointment_no VARCHAR(32),customer_ref VARCHAR(64),status"
                    + " VARCHAR(24),scope_revision BIGINT,version BIGINT,deleted INT,created_at"
                    + " TIMESTAMP,started_at TIMESTAMP,finished_at TIMESTAMP,closed_at TIMESTAMP)");
        sql.execute(
                "CREATE TABLE bty_work_order_context(tenant_no VARCHAR(32),entity_no"
                    + " VARCHAR(32),store_no VARCHAR(32),work_order_no VARCHAR(32),identity_kind"
                    + " VARCHAR(16),identity_ref VARCHAR(32),deleted INT)");
        for (int index = 0; index < 54; index++)
            sql.update(
                    "INSERT INTO"
                        + " bty_work_order(tenant_no,entity_no,store_no,work_order_no,appointment_no,customer_ref,status,scope_revision,version,deleted,created_at)"
                        + " VALUES(?,?,?,?,?,'USER:U','OPEN',1,0,0,TIMESTAMP '2026-11-10"
                        + " 00:00:00')",
                    index == 51 ? "OTHER" : "MAIN",
                    index == 52 ? "FOREIGN" : "E",
                    index == 53 ? "OTHER" : "S",
                    "W" + index,
                    "AP" + index);
        var config = new MybatisConfiguration();
        config.addMapper(WorkOrderWorkbenchMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        try (var session = factory.getObject().openSession(true)) {
            var mapper = session.getMapper(WorkOrderWorkbenchMapper.class);
            var q = new WorkOrderQueryReq(null, null, null, null, null, 1, 20);
            assertThat(mapper.count("E", "S", q, null, null)).isEqualTo(51);
            var first = mapper.list("E", "S", q, null, null, 20, 0);
            var second = mapper.list("E", "S", q, null, null, 20, 20);
            assertThat(first)
                    .extracting(r -> r.getWorkOrderNo())
                    .containsExactlyElementsOf(
                            IntStream.range(0, 20).mapToObj(i -> "W" + (50 - i)).toList());
            assertThat(second).hasSize(20);
            assertThat(first)
                    .extracting(r -> r.getWorkOrderNo())
                    .doesNotContainAnyElementsOf(
                            second.stream().map(r -> r.getWorkOrderNo()).toList());
            assertThat(mapper.list("E", "S", q, null, null, 20, 40)).hasSize(11);
            sql.update(
                    "INSERT INTO bty_work_order_context"
                        + " VALUES('MAIN','E','OTHER','W50','USER','U',0)");
            session.clearCache();
            assertThat(mapper.count("E", "S", q, null, null)).isEqualTo(50);
            var exact = new WorkOrderQueryReq(null, "OPEN", "AP49", null, null, 1, 20);
            assertThat(mapper.list("E", "S", exact, null, null, 20, 0))
                    .extracting(r -> r.getWorkOrderNo())
                    .containsExactly("W49");
            var boundary = LocalDateTime.parse("2026-11-10T00:00:00");
            assertThat(mapper.count("E", "S", q, boundary, boundary.plusSeconds(1))).isEqualTo(50);
            assertThat(mapper.count("E", "S", q, null, boundary)).isZero();
            assertThat(
                            mapper.count(
                                    "E",
                                    "S",
                                    new WorkOrderQueryReq(null, "CLOSED", null, null, null, 1, 20),
                                    null,
                                    null))
                    .isZero();
            sql.update(
                    "UPDATE bty_work_order SET status='SERVICE_FINISHED' WHERE"
                        + " work_order_no='W49'");
            session.clearCache();
            assertThat(
                            mapper.list(
                                    "E",
                                    "S",
                                    new WorkOrderQueryReq(
                                            null, "SERVICE_FINISHED", null, null, null, 1, 20),
                                    null,
                                    null,
                                    20,
                                    0))
                    .extracting(r -> r.getWorkOrderNo())
                    .containsExactly("W49");
        }
    }
}
