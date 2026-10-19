package ai.neargo.shop.support;

import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreditRecharge;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Unit-level authoritative receipt rows; this does not assert the still-pending public/manual receipt integration. */
public final class StoredValueCreditFixture {
    private StoredValueCreditFixture() { }
    private static String id() { return UUID.randomUUID().toString(); }
    public static CreditRecharge received(JdbcTemplate jdbc, ObjectMapper json, String user, String entity, String store, long principal, long bonus) {
        return received(jdbc, json, user, entity, store, principal, bonus, "CNY");
    }
    public static CreditRecharge received(JdbcTemplate jdbc, ObjectMapper json, String user, String entity,
                                          String store, long principal, long bonus, String currency) {
        String no = "RC" + id();
        var source = new SourceRef("CORE", "RECHARGE", "RI" + id(), null, null, null, 0);
        var pay = new SourceRef("PAY", "RECHARGE", no, null, null, null, 0);
        Instant at = Instant.ofEpochMilli(System.currentTimeMillis() - 1000);
        var decision = new DecisionRef("RD" + id(), at, 0, DecisionRef.DecisionType.RECHARGE_RECEIVED);
        var permit = new ParticipationPermit("AR" + id(), source, "A1", "AV1", "T1", user, entity, store,
                currency, principal, bonus, List.of(), new RedemptionScope("STORES", List.of(store)), "b".repeat(64), at);
        var time = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
        jdbc.update("INSERT INTO sv_recharge(tenant_no,recharge_no,request_id,payload_hash,reservation_no,activity_no,"
                + "activity_version_no,user_no,entity_no,store_no,store_name,currency,principal_minor,bonus_minor,status,"
                + "collection_method,actual_amount_minor,receipt_ref,receipt_digest,operator_no,received_by,received_at,"
                + "expires_at,permit_snapshot,source_snapshot,received_decision_snapshot,created_at,updated_at,"
                + "created_by,updated_by) VALUES ('MAIN',?,?,?,?,'A1','AV1',?,?,?,'beauty store',?,?,?,'RECEIVED',"
                + "'CASH',?,'PRIVATE-RECEIPT',?,'OPERATOR','CONFIRMER',?,?,?,?,?,?,?,'OPERATOR','CONFIRMER')",
                no, id(), "c".repeat(64), permit.reservationNo(), user, entity, store, currency, principal, bonus, principal,
                "d".repeat(64), time, time.plusMinutes(15), json.writeValueAsString(permit),
                json.writeValueAsString(source), json.writeValueAsString(decision), time, time);
        return new CreditRecharge(no, pay, decision);
    }
}
