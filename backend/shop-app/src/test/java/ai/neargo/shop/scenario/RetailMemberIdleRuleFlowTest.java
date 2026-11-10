package ai.neargo.shop.scenario;

import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.service.MemberSegmentService;
import ai.neargo.shop.member.service.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RetailMemberIdleRuleFlowTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberService members;
    @Autowired MemberSegmentService segments;
    @Autowired ObjectMapper json;

    @Test
    void savedRollingRuleSurvivesNormalizationAndUsesThisStoreRatherThanRecentOtherStore() {
        String entity = "R04-" + UUID.randomUUID();
        String store = "S-" + entity;
        String idle = "IDLE-" + entity;
        String recent = "RECENT-" + entity;
        seed(entity, store, idle, 40);
        seed(entity, store, recent, 2);
        MemberQuery rule = json.readValue("{\"idleDays\":30,\"status\":\"ACTIVE\"}", MemberQuery.class);
        var saved = segments.save(entity, null, "Idle", store, rule);
        assertThat(segments.matchAll(entity, saved.segmentNo())).containsExactly(idle);
        assertThat(segments.resolve(entity, saved.segmentNo())).containsExactly(idle);
        assertThat(json.writeValueAsString(saved.rule())).contains("\"idleDays\":30");
        assertThat(segments.matchesSnapshot(entity, segments.snapshot(entity, saved.segmentNo()), idle)).isTrue();
        assertThat(members.match(entity, rule.unpaged())).isEmpty(); // Both shopped elsewhere yesterday.
    }

    private void seed(String entity, String store, String member, int localDays) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO mbr_member(member_no,entity_no,person_no,status,source,level,"
                + "last_order_at,joined_at,created_at,updated_at) VALUES (?,?,?,'ACTIVE','ORDER','REGULAR',"
                + "?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", member, entity, "P-" + member,
                now - TimeUnit.DAYS.toMillis(1), now);
        jdbc.update("INSERT INTO mbr_member_store(member_no,entity_no,store_no,level,last_order_at,"
                + "created_at,updated_at) VALUES (?,?,?,'REGULAR',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                member, entity, store, now - TimeUnit.DAYS.toMillis(localDays));
    }
}
