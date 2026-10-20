package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementOutboxRelay;
import ai.neargo.shop.pay.entitlement.service.EntitlementEventDeliveryService;
import ai.neargo.shop.pay.entitlement.service.EntitlementExceptionService;
import ai.neargo.shop.pay.entitlement.service.EntitlementExceptionRecoveryService;
import ai.neargo.shop.pay.mapper.entitlement.PayEntOutboxMapper;
import ai.neargo.shop.spi.entitlement.EntitlementEvents;
import ai.neargo.shop.spi.entitlement.PayEntitlementEvent;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventBridgePort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ent-dead-letter;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.pay.entitlement.cursor-key=dead-letter-test-key-at-least-thirty-two-characters"})
@ActiveProfiles("test")
class EntitlementDeadLetterFlowTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean PayEntitlementOutboxRelay backgroundRelay;
    @Autowired EntitlementEventDeliveryService delivery;
    @Autowired EntitlementExceptionService exceptions;
    @Autowired EntitlementExceptionRecoveryService recovery;
    @Autowired PayEntOutboxMapper outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private final CatalogScope scope=new CatalogScope("MAIN","M1","OP1",true);

    private PayEntitlementEvent seed(boolean exceptionEvent) {
        String suffix=UUID.randomUUID().toString();
        PayEntitlementEvent event=exceptionEvent
                ? new EntitlementEvents.ExceptionOpened("E"+suffix,"EX"+suffix,"M1","GRANT_DELIVERY","G1",1797000000000L)
                : new EntitlementEvents.EntitlementGranted("E"+suffix,"G"+suffix,"U1","M1","V1","TARGETED","B1",1797000000000L);
        // Durable publisher without an after-commit relay: drive the failure deterministically.
        var provider=new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(PayEntitlementOutboxRelay.class);
        new PayEntitlementEventPublisher(outbox,json,provider).publish(event);
        jdbc.update("UPDATE pay_ent_outbox SET attempts=9 WHERE event_no=?",event.eventNo());
        return event;
    }

    @Test void exhaustedDeliveryCanRequeueOnlyTheOriginalEventAndResolveAfterActualSend() {
        var event=seed(false);
        var bridge=mock(PayEntitlementEventBridgePort.class);
        doThrow(new IllegalStateException("core unavailable")).when(bridge).publish(event.eventNo(),event);
        var relay=new PayEntitlementOutboxRelay(outbox,bridge,json,delivery);
        try {
            relay.relay(event.eventNo());
            assertThat(outbox.findByEventNo("MAIN",event.eventNo()).getStatus()).isEqualTo("DEAD");
            var issue=delivery.captureDead(event.eventNo());
            assertThat(issue.kind()).isEqualTo("OUTBOX_DELIVERY");
            assertThat(issue.evidence().delivery().attempts()).isEqualTo(10);
            assertThat(delivery.captureDead(event.eventNo()).revision()).isEqualTo(issue.revision());
            assertThatThrownBy(() -> recovery.retryGrant(scope,issue.exceptionNo(),
                    new RecoveryCommand(issue.revision(),"wrong operation"),"WRONG")).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> delivery.retryEvent(new CatalogScope("MAIN","M2","OP1",true),issue.exceptionNo(),
                    new RecoveryCommand(issue.revision(),"wrong entity"),"FOREIGN")).isInstanceOf(BizException.class);
            var command=new RecoveryCommand(issue.revision(),"restore original message delivery");
            String key="K"+UUID.randomUUID();
            var queued=recovery.retryEvent(scope,issue.exceptionNo(),command,key);
            assertThat(queued.status()).isEqualTo("PROCESSING");
            assertThat(outbox.findByEventNo("MAIN",event.eventNo()).getAttempts()).isZero();
            assertThat(recovery.retryEvent(scope,issue.exceptionNo(),command,key)).isEqualTo(queued);
            assertThatThrownBy(() -> recovery.retryEvent(scope,issue.exceptionNo(),
                    new RecoveryCommand(issue.revision(),"changed reason"),key)).isInstanceOf(BizException.class);
            doNothing().when(bridge).publish(event.eventNo(),event);
            relay.relay(event.eventNo());
            assertThat(outbox.findByEventNo("MAIN",event.eventNo()).getStatus()).isEqualTo("SENT");
            var resolved=exceptions.get(scope,issue.exceptionNo());
            assertThat(resolved.status()).isEqualTo("RESOLVED");
            assertThat(resolved.evidence().delivery().status()).isEqualTo("SENT");
            assertThat(recovery.retryEvent(scope,issue.exceptionNo(),command,key)).isEqualTo(queued);
            assertThat(exceptions.actions(scope,issue.exceptionNo(),1,20).total()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_grant",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox WHERE event_no=?",Integer.class,event.eventNo()))
                    .isEqualTo(1);
        } finally { relay.shutdown(); }
    }

    @Test void restartScanRepairsDeadCaptureAndSentResolutionGapsWithoutStarvation() {
        var bad=seed(false);
        jdbc.update("UPDATE pay_ent_outbox SET status='DEAD',payload_hash='bad' WHERE event_no=?",bad.eventNo());
        var event=seed(false);
        jdbc.update("UPDATE pay_ent_outbox SET status='DEAD' WHERE event_no=?",event.eventNo());
        var relay=new PayEntitlementOutboxRelay(outbox,mock(PayEntitlementEventBridgePort.class),json,delivery);
        try {
            assertThat(relay.recoverDeliveryFailures()).isPositive();
            var issue=delivery.captureDead(event.eventNo());
            var queued=recovery.retryEvent(scope,issue.exceptionNo(),new RecoveryCommand(issue.revision(),"recover"),"K"+UUID.randomUUID());
            var pending=recovery.querySource(scope,issue.exceptionNo(),new RecoveryCommand(queued.revision(),"check pending"),"Q"+UUID.randomUUID());
            assertThat(pending.status()).isEqualTo("PROCESSING");
            var stale=outbox.findByEventNo("MAIN",event.eventNo());
            assertThat(outbox.markDead(stale.getId(),"MAIN",java.time.LocalDateTime.now(),stale.getVersion()-1)).isZero();
            // Simulate process death after successful bridge acknowledgement and before exception resolution.
            outbox.markForwarded(stale.getId(),"MAIN",java.time.LocalDateTime.now());
            relay.recoverDeliveryFailures(); // Reach end and rotate the bounded keyset.
            relay.recoverDeliveryFailures();
            assertThat(exceptions.get(scope,issue.exceptionNo()).status()).isEqualTo("RESOLVED");
        } finally { relay.shutdown(); }
    }

    @Test void corruptEventIsNotAttributedToAnInventedMerchantAndExceptionFailureDoesNotRecurse() {
        var event=seed(true);
        jdbc.update("UPDATE pay_ent_outbox SET status='DEAD',attempts=10 WHERE event_no=?",event.eventNo());
        var issue=delivery.captureDead(event.eventNo());
        assertThat(delivery.captureDead(event.eventNo()).exceptionNo()).isEqualTo(issue.exceptionNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox WHERE aggregate_no=?",Integer.class,issue.exceptionNo()))
                .isZero(); // No ExceptionOpened for an outbox-delivery failure.
        var corrupt=seed(false);
        jdbc.update("UPDATE pay_ent_outbox SET status='DEAD',payload_hash='bad' WHERE event_no=?",corrupt.eventNo());
        assertThatThrownBy(() -> delivery.captureDead(corrupt.eventNo())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_exception WHERE kind='OUTBOX_DELIVERY' AND biz_no=?",
                Integer.class,corrupt.eventNo())).isZero();
        var all=new ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope("MAIN","OP1",null);
        var page=delivery.deadLetters(all,null,100);
        assertThat(page.records()).anyMatch(r -> corrupt.eventNo().equals(r.eventNo()));
        assertThat(json.writeValueAsString(page)).doesNotContain("payloadHash","payload","commandSnapshot");
        var first=delivery.deadLetters(all,null,1);
        assertThat(first.nextCursor()).isNotBlank();
        var second=delivery.deadLetters(all,first.nextCursor(),1);
        assertThat(second.records().getFirst().eventNo()).isNotEqualTo(first.records().getFirst().eventNo());
        assertThatThrownBy(() -> delivery.deadLetters(new ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope(
                "MAIN","OP2",null),first.nextCursor(),1)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> delivery.deadLetters(all,first.nextCursor()+"bad",1)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> delivery.deadLetters(new ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope(
                "MAIN","OP1",java.util.Set.of("M1")),null,20)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> delivery.deadLetters(all,null,101)).isInstanceOf(BizException.class);
    }
}
