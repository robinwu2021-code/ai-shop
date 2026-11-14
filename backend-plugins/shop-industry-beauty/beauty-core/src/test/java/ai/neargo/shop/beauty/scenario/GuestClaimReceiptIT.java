package ai.neargo.shop.beauty.scenario;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.BeautyAppointmentConfiguration;
import ai.neargo.shop.beauty.config.BeautyCatalogConfiguration;
import ai.neargo.shop.beauty.config.BeautyWriteGuardConfiguration;
import ai.neargo.shop.beauty.dto.ClaimGuestReq;
import ai.neargo.shop.beauty.dto.MyAppointmentQuery;
import ai.neargo.shop.beauty.dto.RegisterGuestReq;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.GuestRegistrationService;
import ai.neargo.shop.beauty.service.ReceptionService;
import ai.neargo.shop.beauty.service.impl.GuestRegistrationServiceImpl;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real MySQL/transactions/outbox; controlled member fixture. The host IT consumes real tickets. */
class GuestClaimReceiptIT extends BeautyExecutionFixture {
    private final ObjectMapper json = new ObjectMapper();
    private final ControlledGuest owner = new ControlledGuest();
    private final ClaimGuestReq claim = new ClaimGuestReq("cr1_" + "c".repeat(43), "t".repeat(43));
    private boolean allowed = true;
    private boolean proofValid = true;
    private boolean failOutbox;
    private String target = "U1";
    @Configuration(proxyBeanMethods = false)
    static class OutboxRoute {
        @Bean UnifiedOutboxFacade facade(DataSource source, PlatformTransactionManager manager, ObjectMapper json) {
            var facade = new UnifiedOutboxFacade(json);
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, manager, json), List.of());
            facade.setArchiveOnly(Set.of("PLATFORM:BEAUTY_RECEPTION_STATE_CHANGED"));
            return facade;
        }
    }
    @BeforeEach void database() throws Exception {
        initialize("BtyReceptionOperation", "BtyGuestClaimReceipt", "BtyArrivalChallenge", "BtyWorkOrderContext");
        new ResourceDatabasePopulator(new ClassPathResource("platform-write-fixture.sql")).execute(database.source);
        login("A");
    }
    private void login(String actor) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant(actor, "operator"), null, List.of()));
    }
    private void consumer(String user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.consumer(user, "customer"), null, List.of()));
    }
    private ApplicationContextRunner business() {
        return runner().withUserConfiguration(BeautyCatalogConfiguration.class, BeautyAppointmentConfiguration.class,
                        BeautyWriteGuardConfiguration.class, OutboxRoute.class)
                .withBean(ObjectMapper.class, () -> json)
                .withBean(GuestRegistrationPort.class, () -> owner)
                .withBean(MerchantOperatorAccessPort.class, () -> q -> {
                    outside(); return allowed && "E".equals(q.entityNo()) && "S".equals(q.storeNo());
                })
                .withBean(CustomerIdentityPort.class, () -> new CustomerIdentityPort() {
                    @Override public ResolvedCustomer resolveReference(EntitlementContext context, ReferenceQuery query) {
                        throw new AssertionError("legacy reference must not authorize a new claim");
                    }
                    @Override public ResolvedCustomer resolveClaimReference(EntitlementContext context,
                            ReferenceQuery query) {
                        outside(); if (!proofValid) { throw BizException.of(ErrorCode.NOT_FOUND); }
                        assertThat(query.customerRef()).isEqualTo(claim.verifiedIdentity());
                        return new ResolvedCustomer(target);
                    }
                }).withInitializer(application -> application.getBeanFactory().addBeanPostProcessor(
                        new BeanPostProcessor() {
                            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                                if (!"beautyOutboxPublisher".equals(name)) { return bean; }
                                OutboxPublisher delegate = (OutboxPublisher) bean;
                                return (OutboxPublisher) (event, options) -> {
                                    var receipt = delegate.publish(event, options);
                                    if (failOutbox) { throw new IllegalStateException("receipt-outbox-rollback"); }
                                    return receipt;
                                };
                            }
                        }));
    }
    private void outside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    @Test void sharedClaimCommitThenLocalRollbackRecoversReceiptWithCurrentEmployee() {
        business().run(c -> {
            var service = c.getBean(GuestRegistrationServiceImpl.class);
            failOutbox = true;
            assertThatThrownBy(() -> service.claimGuest("E", "S", "A", "GUE1", claim, "K"))
                    .hasMessage("receipt-outbox-rollback");
            assertThat(owner.fact.state()).isEqualTo("CLAIMED");
            assertThat(count("bty_reception_operation")).isOne();
            assertThat(count("bty_guest_claim_receipt")).isZero(); assertThat(count("sys_outbox")).isZero();
            String number = sql.queryForObject("SELECT operation_no FROM bty_reception_operation", String.class);
            String payload = sql.queryForObject("SELECT frozen_payload FROM bty_reception_operation", String.class);
            assertThat(payload).contains("GUE1", "U1").doesNotContain("cr1_", "tttt", "contact");
            failOutbox = false; proofValid = false; login("B");
            var result = service.recoverClaim("E", "S", "B", number);
            assertThat(result.operationNo()).isEqualTo(number); assertThat(result.userNo()).isEqualTo("U1");
            assertThat(owner.lastActor).isEqualTo("B"); assertThat(owner.claimCalls).isOne();
            assertThat(service.recoverClaim("E", "S", "B", number)).isEqualTo(result);
            assertThat(count("bty_guest_claim_receipt")).isOne(); assertThat(count("sys_outbox")).isOne();
            assertThat(sql.queryForObject("SELECT payload FROM sys_outbox", String.class))
                    .contains("GUE1").doesNotContain("U1", "cr1_", "claimToken", "contact", "frozenPlan");
            allowed = false;
            assertThatThrownBy(() -> service.recoverClaim("E", "S", "B", number)).isInstanceOf(BizException.class);
        });
    }
    @Test void unknownClaimKeepsOriginalIntentAndRegisteredNeedsFreshCurrentProof() {
        business().run(c -> {
            var service = c.getBean(GuestRegistrationServiceImpl.class);
            owner.unknownBeforeCommit = true;
            var pending = service.claimGuest("E", "S", "A", "GUE1", claim, "K");
            assertThat(pending.operationState()).isEqualTo("PENDING");
            login("B");
            assertThat(service.recoverClaim("E", "S", "B", pending.operationNo()).recoveryAction())
                    .isEqualTo("REQUIRES_FRESH_IDENTITY_PROOF");
            assertThat(owner.claimCalls).isOne(); assertThat(count("bty_guest_claim_receipt")).isZero();
            assertThatThrownBy(() -> service.recoverClaim("E", "OTHER", "B", pending.operationNo()))
                    .isInstanceOf(BizException.class);
            owner.fact = new GuestRegistrationPort.GuestFact("GUE1", "CLAIMED", "OTHER", 2);
            assertThatThrownBy(() -> service.recoverClaim("E", "S", "B", pending.operationNo()))
                    .isInstanceOf(BizException.class);
            assertThat(count("bty_guest_claim_receipt")).isZero();
        });
    }
    @Test void receiptCannotRebindAndSuccessReplayNeedsCurrentPermissionButNoFreshTicket() {
        business().run(c -> {
            var service = c.getBean(GuestRegistrationService.class);
            var first = service.claimGuest("E", "S", "A", "GUE1", claim, "K");
            proofValid = false;
            assertThat(service.claimGuest("E", "S", "A", "GUE1", claim, "K")).isEqualTo(first);
            proofValid = true; target = "U2";
            assertThatThrownBy(() -> service.claimGuest("E", "S", "A", "GUE1", claim, "OTHER"))
                    .isInstanceOf(BizException.class);
            owner.fact = new GuestRegistrationPort.GuestFact("GUE1", "CLAIMED", "U2", 2);
            assertThatThrownBy(() -> service.claimGuest("E", "S", "A", "GUE1", claim, "OTHER"))
                    .isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT user_no FROM bty_guest_claim_receipt", String.class)).isEqualTo("U1");
        });
    }
    @Test void receiptOwnershipIsAppliedBeforePagingAndRequiresCompleteScope() {
        business().run(c -> {
            c.getBean(GuestRegistrationService.class).claimGuest("E", "S", "A", "GUE1", claim, "K");
            appointment("DIRECT", "S"); appointment("GUEST", "S"); appointment("ANON", "S");
            appointment("FOREIGN_STORE", "OTHER"); appointment("DELETED", "S"); appointment("OTHER_USER", "S");
            appointment("FOREIGN_ENTITY", "S");
            sql.update("UPDATE bty_appointment SET entity_no='OTHER',guest_ref='GUE1' "
                    + "WHERE appointment_no='FOREIGN_ENTITY'");
            sql.update("UPDATE bty_appointment SET customer_ref='U1' WHERE appointment_no='DIRECT'");
            sql.update("UPDATE bty_appointment SET guest_ref='GUE1' "
                    + "WHERE appointment_no IN ('GUEST','FOREIGN_STORE','DELETED')");
            sql.update("UPDATE bty_appointment SET deleted=1 WHERE appointment_no='DELETED'");
            sql.update("UPDATE bty_appointment SET guest_ref='BTG-OLD' WHERE appointment_no='ANON'");
            sql.update("UPDATE bty_appointment SET customer_ref='U2' WHERE appointment_no='OTHER_USER'");
            consumer("U1"); var service = c.getBean(AppointmentService.class);
            var page = service.listMyAppointments("U1", new MyAppointmentQuery(null, null, null, 1, 1));
            assertThat(page.total()).isEqualTo(2); assertThat(page.records()).hasSize(1);
            var next = service.listMyAppointments("U1", new MyAppointmentQuery(null, null, null, 2, 1));
            assertThat(next.total()).isEqualTo(2);
            assertThat(List.of(page.records().getFirst().appointmentNo(), next.records().getFirst().appointmentNo()))
                    .containsExactlyInAnyOrder("DIRECT", "GUEST");
            assertThat(service.getMyAppointment("U1", "GUEST").appointment().appointmentNo()).isEqualTo("GUEST");
            for (String number : List.of("ANON", "FOREIGN_STORE", "FOREIGN_ENTITY", "DELETED", "OTHER_USER")) {
                assertThatThrownBy(() -> service.getMyAppointment("U1", number)).isInstanceOf(BizException.class);
            }
        });
    }
    @Test void arrivalCodeHasFixedTtlStableOriginalKeyAndNoAuditLeakage() {
        business().run(c -> {
            var service = c.getBean(ReceptionService.class);
            var first = service.issueArrivalChallenge("E", "S", "A", "K");
            assertThat(first.code()).matches("[A-Za-z0-9_-]{43}");
            assertThat(first.expiresAt()).isEqualTo(first.issuedAt().plusSeconds(90));
            assertThat(service.issueArrivalChallenge("E", "S", "A", "K")).isEqualTo(first);
            assertThat(service.issueArrivalChallenge("E", "S", "A", "OTHER").code()).isNotEqualTo(first.code());
            assertThat(count("bty_reception_operation")).isZero(); assertThat(count("bty_action_log")).isZero();
            assertThat(count("sys_outbox")).isZero();
            login("B"); assertThat(service.issueArrivalChallenge("E", "S", "B", "K").code()).isNotEqualTo(first.code());
            assertThatThrownBy(() -> service.issueArrivalChallenge("E", "OTHER", "B", "K"))
                    .isInstanceOf(BizException.class);
            allowed = false;
            assertThatThrownBy(() -> service.issueArrivalChallenge("E", "S", "B", "K"))
                    .isInstanceOf(BizException.class);
        });
    }
    @Test void explicitCapabilityFailureIsNeverRegistrationPending() {
        business().run(c -> {
            owner.unavailable = true;
            assertThatThrownBy(() -> c.getBean(GuestRegistrationService.class).registerGuest("E", "S", "A",
                    new RegisterGuestReq("contact", "name", true), "K"))
                    .isInstanceOfSatisfying(BizException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.MEMBER_GUEST_UNAVAILABLE));
            assertThat(count("bty_reception_operation")).isZero();
        });
    }
    private final class ControlledGuest implements GuestRegistrationPort {
        GuestFact fact = new GuestFact("GUE1", "REGISTERED", null, 1);
        boolean unavailable;
        boolean unknownBeforeCommit;
        int claimCalls;
        String lastActor;
        void current(Scope scope) {
            outside(); lastActor = scope.identity().actorNo();
            assertThat(lastActor).isEqualTo(SecurityUtils.requireUser().userNo());
            assertThat(scope.storeNo()).isEqualTo("S");
        }
        @Override public Registration register(Scope s, RegisterCommand command) {
            current(s); if (unavailable) { throw BizException.of(ErrorCode.MEMBER_GUEST_UNAVAILABLE); }
            return new Registration("GUE1", "t".repeat(43), "REGISTERED", 1);
        }
        @Override public GuestFact get(Scope s, String number) {
            current(s); if (!"GUE1".equals(number)) { throw BizException.of(ErrorCode.NOT_FOUND); } return fact;
        }
        @Override public GuestFact verify(Scope s, String number, String token) {
            assertThat(token).isEqualTo("t".repeat(43)); return get(s, number);
        }
        @Override public Claim claim(Scope s, String number, ClaimCommand command) {
            current(s); claimCalls++;
            assertThat(count("bty_reception_operation")).isOne();
            assertThat(command.idempotencyKey()).hasSize(64);
            if (unknownBeforeCommit) { throw new ResultUnknown(); }
            fact = new GuestFact(number, "CLAIMED", target, 2); return new Claim(number, target, 2);
        }
    }
}
