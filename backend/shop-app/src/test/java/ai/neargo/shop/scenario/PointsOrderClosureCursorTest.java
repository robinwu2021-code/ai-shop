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
    @Autowired ai.neargo.shop.trade.order.points.OrderPointsPendingScan pending;
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

    @Test
    void failed_front_rows_do_not_starve_later_work_and_each_queue_wraps() {
        String suffix=UUID.randomUUID().toString().replace("-", "").substring(0,16);
        var refs=List.of("A"+suffix,"B"+suffix,"C"+suffix);
        for (int i=0;i<refs.size();i++) {
            String ref=refs.get(i);
            jdbc.update("INSERT INTO ord_points_placement(order_no,phase,payload_json) VALUES(?,'UNKNOWN','{}')",ref);
            jdbc.update("INSERT INTO ord_points_refund_binding(after_sale_no,sub_order_no,phase,source_version,command_json) VALUES(?,?,'STAGED',0,'{}')",ref,ref);
            jdbc.update("INSERT INTO ord_points_source_fact(event_no,type_code,source_sequence,store_no,sub_order_no,schema_version,normalized_hash,payload_json) VALUES(?,?,?,?,?,1,'HASH','{}')",ref,suffix,i,"ST",ref);
        }
        for(String queue:List.of("PLACEMENT","REFUND","EVENT")) {
            assertThat(pending.next(queue,2)).containsExactly(refs.get(0),refs.get(1));
            // Simulate repeated failure: leave those rows pending and unchanged.
            assertThat(pending.next(queue,2)).containsExactly(refs.get(2));
            assertThat(jdbc.queryForObject("SELECT after_reference FROM ord_points_work_cursor WHERE queue_name=?",String.class,queue)).isEqualTo(refs.get(2));
            assertThat(pending.next(queue,2)).containsExactly(refs.get(0),refs.get(1));
        }
    }
}
