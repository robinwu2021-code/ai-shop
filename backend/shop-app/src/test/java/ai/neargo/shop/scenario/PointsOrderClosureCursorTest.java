package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.trade.order.points.OrderPointsClosureScan;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:points-closure-cursor;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PointsOrderClosureCursorTest {
    @Autowired OrderPointsClosureScan scan;
    @Autowired JdbcTemplate jdbc;

    @Test
    void closure_scan_persists_position_and_wraps_after_last_binding() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0,16);
        String first = "S-CURSOR-A-" + suffix, second = "S-CURSOR-B-" + suffix;
        for (String sub : List.of(first, second)) {
            jdbc.update(
                    "INSERT INTO"
                        + " ord_points_binding(binding_no,request_id,order_no,sub_order_no,store_no,phase,payload_json)"
                        + " VALUES(?,?,?,?,?,'BOUND','{}')",
                    "B:" + sub,
                    "REQ:" + sub,
                    "O:" + sub,
                    sub,
                    "ST");
        }
        assertThat(scan.next(1)).containsExactly(first);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT after_sub_order_no FROM ord_points_recovery_cursor"
                                        + " WHERE queue_name='CLOSURE'",
                                String.class))
                .isEqualTo(first);
        assertThat(scan.next(1)).containsExactly(second);
        assertThat(scan.next(1)).containsExactly(first);
    }
}
