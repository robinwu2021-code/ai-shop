package ai.neargo.shop.event.outbox;

import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxMaintenanceTest extends OutboxTestFixture {
    @Test
    void auditedReplayRetainsAttemptsAndOnlyFailedTargetIsReset() {
        append("E1");
        var ref=store.findDue(now,1).getFirst();
        var claim=store.tryClaim(ref,"c",now,Duration.ofSeconds(120)).orElseThrow();
        store.complete(claim,DeliveryResult.failed("REJECTED","known rejection"),now,RetryPolicy.defaults());
        store.operate("REQ1","E1",claim.handlerId(),"OPS1","verified outcome",false,now);
        store.operate("REQ1","E1",claim.handlerId(),"OPS1","verified outcome",false,now);
        var row=jdbc.queryForMap("SELECT * FROM sys_outbox_delivery WHERE id=?",ref.id());
        assertThat(((Number)row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(((Number)row.get("round_attempt_count")).intValue()).isZero();
        assertThat(((Number)row.get("replay_count")).intValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox_operation",Long.class)).isEqualTo(1);
        var replayed = store.tryClaim(ref,"replay",now,Duration.ofSeconds(120)).orElseThrow();
        store.complete(replayed,DeliveryResult.sent(),now,RetryPolicy.defaults());
        var audit = jdbc.queryForMap("SELECT * FROM sys_outbox_operation");
        assertThat(audit.get("original_error_code")).isEqualTo("REJECTED");
        assertThat(((Number) audit.get("original_attempt_count")).intValue()).isEqualTo(1);

    }
    @Test
    void legacyAdoptionRequiresCompleteReviewedManifestAndOfflineConfirmation() {
        jdbc.update("INSERT INTO sys_outbox (event_no,aggregate_type,aggregate_id,event_type,payload,status,"
                + "retry_count,created_at) VALUES ('OLD1','ORDER','O1','ORDER_PAID','{}','PENDING',0,?)",
                java.sql.Timestamp.from(now));
        var old=store.inspectLegacy(100).getFirst();
        var decision=new JdbcOutboxStore.LegacyDecision(old,"ORDER:O1",Map.of("a","SENT","b","PENDING"),
                Map.of("a",false,"b",false),"confirmed existing business receipts");
        assertThatThrownBy(() -> store.adoptLegacy(List.of(decision),false,now)).isInstanceOf(IllegalStateException.class);
        store.adoptLegacy(List.of(decision),true,now);
        assertThat(store.findDue(now,100)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox_operation",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox_delivery WHERE handler_id='a'",String.class))
                .isEqualTo("SENT");
    }

    @Test
    void healthSeparatesFailuresBlockedFollowersAndUnknownResults() {
        append("E1"); append("E2");
        var ref=store.findDue(now,1).getFirst();
        var claim=store.tryClaim(ref,"x",now,Duration.ofSeconds(120)).orElseThrow();
        store.complete(claim,DeliveryResult.unknown("verify channel receipt"),now,RetryPolicy.defaults());
        var health=store.health(now);
        assertThat(health.get("failed")).isEqualTo(1L);
        assertThat(health.get("unknown")).isEqualTo(1L);
        assertThat(health.get("blocked")).isEqualTo(2L);
        assertThat(health.get("due")).isEqualTo(1L);
    }
}
