package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.approval.LightApprovalPort;
import ai.neargo.shop.spi.approval.LightApprovalPort.Decision;
import ai.neargo.shop.spi.approval.LightApprovalPort.Submit;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 轻量审批 sys_approval（TDD-基础能力库对齐 §2.2 1e，Q13、Q14）：真表上的送审、批准、幂等与拒绝。 */
@SpringBootTest
@ActiveProfiles("test")
class LightApprovalFlowTest {

    @Autowired private LightApprovalPort approvals;
    @Autowired private JdbcTemplate jdbc;

    private static Submit submit(String ref, long revision, String by, boolean auto, Instant expires) {
        return new Submit("MAIN", "E1", "S1", "PRICE_ADJUST", "TEST_SUBJECT", ref, revision, 101L, "CNY",
                "PRICE_CORRECTION", "改价", by, auto, LightApprovalPort.VIA_APP, expires);
    }

    @Test
    void submitIsIdempotentPerRevisionAndOnlyAnotherPersonApprovesOnce() {
        String ref = "ADJ-" + UUID.randomUUID();
        var first = approvals.submit(submit(ref, 0, "STAFF", false, Instant.now().plusSeconds(120)));
        assertThat(approvals.submit(submit(ref, 0, "STAFF", false, null)).approvalNo()).isEqualTo(first.approvalNo());
        assertThat(first.status()).isEqualTo(LightApprovalPort.PENDING);

        assertThatThrownBy(() -> approvals.decide(first.approvalNo(), new Decision("STAFF", true, null, "APP")))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        var approved = approvals.decide(first.approvalNo(), new Decision("OWNER", true, "ok", "APP"));
        assertThat(approved.status()).isEqualTo(LightApprovalPort.APPROVED);
        assertThat(approvals.decide(first.approvalNo(), new Decision("OWNER", true, "ok", "APP")).version())
                .as("同一人同一结果重放").isEqualTo(approved.version());
        assertThatThrownBy(() -> approvals.decide(first.approvalNo(), new Decision("OWNER2", false, "晚了", "APP")))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);

        var next = approvals.submit(submit(ref, 1, "STAFF", false, null));
        assertThat(approvals.latest("MAIN", "TEST_SUBJECT", ref).orElseThrow().approvalNo()).isEqualTo(next.approvalNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_approval WHERE subject_ref=?", Integer.class, ref))
                .isEqualTo(2);
    }

    @Test
    void autoApprovalIsApprovedBySubmitterAndExpiredRequestsCannotBeApproved() {
        String ref = "ADJ-" + UUID.randomUUID();
        var auto = approvals.submit(submit(ref, 0, "OWNER", true, null));
        assertThat(auto.approved()).isTrue();
        assertThat(auto.approvalMode()).isEqualTo(LightApprovalPort.MODE_AUTO);
        assertThat(auto.approverNo()).isEqualTo("OWNER");

        var expired = approvals.submit(submit("ADJ-" + UUID.randomUUID(), 0, "STAFF", false,
                Instant.now().minusSeconds(1)));
        assertThatThrownBy(() -> approvals.decide(expired.approvalNo(), new Decision("OWNER", true, null, "APP")))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);
    }
}
