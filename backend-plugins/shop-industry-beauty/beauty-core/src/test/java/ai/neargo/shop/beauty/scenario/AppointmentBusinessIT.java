package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.beauty.config.BeautyAppointmentConfiguration;
import ai.neargo.shop.beauty.config.BeautyCatalogConfiguration;
import ai.neargo.shop.beauty.config.BeautyWriteGuardConfiguration;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.dto.AppointmentQuery;
import ai.neargo.shop.beauty.dto.CancelAppointmentReq;
import ai.neargo.shop.beauty.dto.ChangeAppointmentReq;
import ai.neargo.shop.beauty.dto.CreateAppointmentReq;
import ai.neargo.shop.beauty.dto.CreateMyAppointmentReq;
import ai.neargo.shop.beauty.dto.PreviewAppointmentReq;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentChangeReq;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentReq;
import ai.neargo.shop.beauty.dto.RecordNoShowReq;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.beauty.service.impl.AppointmentRecovery;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Real private MySQL/source/fence/services. External catalog/personnel/resource owner are explicit controlled
 * fixtures.
 */
class AppointmentBusinessIT extends BeautyExecutionFixture {
    private final ObjectMapper json = new ObjectMapper();
    private final ControlledOwner owner = new ControlledOwner();
    private boolean allowed = true;
    private String revokedActor;
    private boolean occupied;
    private boolean otherOccupied;
    private boolean failOutbox;
    private long quotePrice = 1000;
    private Long goodsRevision = 7L;
    private final OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
            .truncatedTo(ChronoUnit.DAYS).plusHours(10);

    @Configuration(proxyBeanMethods = false)
    static class OutboxRoute {
        @Bean
        UnifiedOutboxFacade facade(DataSource source, PlatformTransactionManager manager, ObjectMapper mapper) {
            var facade = new UnifiedOutboxFacade(mapper);
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, manager, mapper), List.of());
            facade.setArchiveOnly(Set.of("PLATFORM:BEAUTY_APPOINTMENT_OPERATION_COMPLETED"));
            return facade;
        }
    }

    @BeforeEach
    void database() throws Exception {
        initialize("BtyAppointmentPreparation", "BtyAppointmentOperation");
        new ResourceDatabasePopulator(new ClassPathResource("platform-write-fixture.sql")).execute(database.source);
        login(LoginUser.consumer("USR1", "customer"));
        seed("bty_store_policy", "policy_no", "P", "store_no", "S", "current_policy_version_no", "PV",
                "status", "ACTIVE", "version", 1L);
        seed("bty_store_policy_version", "policy_version_no", "PV", "policy_no", "P", "revision_index", 1,
                "booking_ahead_days", 30, "cancel_rule", "{\"freeCancelBeforeMinutes\":60,"
                        + "\"allowCustomerCancel\":true}", "allowed_modes", "[\"PREPAID_SINGLE\"]",
                "effective_at", T0.minusYears(1), "schema_version", 1, "snapshot_hash", "a".repeat(64));
        seed("bty_service_definition", "definition_no", "D", "store_no", "S", "sku_no", "SKU",
                "current_definition_version_no", "DV", "status", "ACTIVE", "version", 1L);
        seed("bty_service_definition_version", "definition_no", "D", "definition_version_no", "DV",
                "revision_index", 1, "duration_min_snapshot", 17, "buffer_before_min", 3, "buffer_after_min", 4,
                "steps_template", "[]", "notes", "[]", "schema_version", 1, "snapshot_hash", "b".repeat(64));
        seed("bty_service_requirement", "requirement_no", "REQ", "definition_version_no", "DV", "role_code", "MAIN",
                "resource_type", "STAFF", "skill_goods_no", "G", "qty", 1, "sequence_no", 0,
                "offset_min", 0, "duration_min", 17, "selection_rule", "ANY_QUALIFIED");
    }
    private void login(LoginUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
                List.of()));
    }
    private ApplicationContextRunner business() {
        return runner().withUserConfiguration(BeautyCatalogConfiguration.class, BeautyAppointmentConfiguration.class,
                        BeautyWriteGuardConfiguration.class, OutboxRoute.class)
                .withBean(ObjectMapper.class, () -> json)
                .withBean(ResourceBookingPort.class, () -> owner)
                .withBean(CustomerIdentityPort.class, () -> (context, q) -> {
                    outside();
                    assertThat(q.customerRef()).isEqualTo("MEMBER_TICKET");
                    return new CustomerIdentityPort.ResolvedCustomer("USR1");
                })
                .withBean(MerchantOperatorAccessPort.class, () -> q -> allowed && !q.operatorNo().equals(revokedActor)
                        && "biz:booking:manage".equals(q.permission()))
                .withBean(ServiceCatalogFactsPort.class, () -> new ServiceCatalogFactsPort() {
                    @Override public Store store(String number) {
                        outside();
                        return new Store("E", number, "Store", null, "ACTIVE", "always", "address",
                                new StoreSalesCatalogPort.Snapshot("E", number, null, 0, null, 0, null, false, null,
                                        null));
                    }
                    @Override public Service service(String store, String sku) {
                        outside();
                        return new Service(store(store), sku, "G", "Service", null, quotePrice, 17,
                                goodsRevision, 0L);
                    }
                })
                .withBean(ResourceCandidatesPort.class, () -> new ResourceCandidatesPort() {
                    @Override public List<ResourceFactsPort.ResourceFact> candidates(Query query) {
                        outside(); return List.of(new ResourceFactsPort.ResourceFact("R1", ResourceFactsPort.Type.STAFF,
                                null, "P1", "Technician", 1, false),
                                new ResourceFactsPort.ResourceFact("R2", ResourceFactsPort.Type.STAFF,
                                null, "P2", "Second", 1, false));
                    }
                    @Override public List<Capacity> capacity(CapacityQuery query) {
                        outside();
                        if (!occupied) { return List.of(); }
                        boolean excluded = query.replacement() != null;
                        if (excluded) {
                            var proof = query.replacement();
                            assertThat(proof.binding().sourceType()).isEqualTo("BEAUTY_APPOINTMENT");
                            assertThat(owner.receipts.values()).anySatisfy(receipt ->
                                    assertThat(receipt.binding()).isEqualTo(proof.binding()));
                            assertThat(owner.allHolds.get(proof.target().holdNo()).status()).isEqualTo("CONFIRMED");
                        }
                        assertThat(query.factAt()).isAfter(query.from());
                        int used = (excluded ? 0 : 1) + (otherOccupied ? 1 : 0);
                        return List.of(new Capacity("R1", start.minusMinutes(5).toInstant(),
                                start.plusMinutes(25).toInstant(), 1, used));
                    }
                })
                .withBean(PersonnelFactsPort.class, () -> q -> {
                    outside(); return List.of("P1", "P2").stream().map(p -> new PersonnelFactsPort.PersonnelFact(p,
                            "A" + p, "S", p, "L", true, Instant.EPOCH, null, List.of("TECH"),
                            List.of(new PersonnelFactsPort.Qualification("Q" + p, "S", "G", "SKU", "L",
                                    Instant.EPOCH, null)))).toList();
                })
                .withBean(ScheduleWindowsPort.class, () -> q -> {
                    outside(); return q.resourceNos().stream().map(no -> new ScheduleWindowsPort.Window(no, "S",
                            "A", q.startAt(), q.endAt(), List.of("RULE"))).toList();
                });
    }
    private void outside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private PreviewMyAppointmentReq preview(int qty) {
        return new PreviewMyAppointmentReq("S", List.of(new AppointmentItemReq("SKU", qty)), start, "P1");
    }
    @Test
    void createsOnlyAfterIndependentConfirmAndKeepsSeparateArrangementRevision() {
        business().run(context -> {
            owner.authority = context.getBean(ResourceSourceAuthorityPort.class);
            var service = context.getBean(AppointmentService.class);
            var p = service.previewMyAppointment("USR1", preview(2));
            assertThat(p.policyVersionNo()).isEqualTo("PV");
            assertThat(p.items()).hasSize(2);
            assertThat(p.assignments().get(1).startAt()).isAfterOrEqualTo(p.assignments().getFirst().endAt());
            var result = service.createMyAppointment("USR1", new CreateMyAppointmentReq(p.previewNo(), List.of(),
                    null), "CREATE");
            assertThat(result.status()).isEqualTo("CONFIRMED");
            assertThat(owner.accepts.values().stream().map(ResourceBookingPort.Accept::action))
                    .containsExactly("RESERVE", "CONFIRM");
            var detail = service.getMyAppointment("USR1", result.appointmentNo());
            assertThat(detail.appointment().arrangementRevision()).isEqualTo(1);
            assertThat(detail.appointment().revision()).isGreaterThan(1);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment_resource_intent WHERE phase_code=3",
                    Integer.class)).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment_resource_fence "
                    + "WHERE active_intent_no IS NOT NULL", Integer.class)).isZero();
            for (var original : owner.decisions.values()) {
                assertThat(owner.authority.query(new ResourceSourceAuthorityPort.Request(original.binding(), "USR1",
                        "CONSUMER", "USR"))).isEqualTo(original);
            }
            assertThatThrownBy(() -> service.createMyAppointment("USR1",
                    new CreateMyAppointmentReq(p.previewNo(), List.of(), null), "REUSE"))
                    .isInstanceOf(BizException.class);
        });
    }
    @Test
    void lostAcceptResponseReplaysIdenticalPersistedKeyAndPayload() {
        business().run(context -> {
            owner.authority = context.getBean(ResourceSourceAuthorityPort.class);
            var service = context.getBean(AppointmentService.class);
            var p = service.previewMyAppointment("USR1", preview(1));
            var req = new CreateMyAppointmentReq(p.previewNo(), List.of(), null);
            owner.loseAccept = true;
            var first = service.createMyAppointment("USR1", req, "LOST");
            assertThat(first.status()).isEqualTo("HELD"); assertThat(first.operationState()).isEqualTo("PENDING");
            var accepted = owner.accepts.values().iterator().next();
            var next = service.createMyAppointment("USR1", req, "LOST");
            assertThat(next.appointmentNo()).isEqualTo(first.appointmentNo());
            assertThat(next.status()).isEqualTo("CONFIRMED");
            assertThat(owner.replayed).contains(accepted);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment", Integer.class)).isEqualTo(1);
        });
    }
    @Test
    void recoveryReconcilesOriginalWithoutMintingUserThenAwaitsAuthenticatedConfirm() {
        business().run(context -> {
            owner.authority = context.getBean(ResourceSourceAuthorityPort.class);
            var service = context.getBean(AppointmentService.class);
            var p = service.previewMyAppointment("USR1", preview(1));
            var req = new CreateMyAppointmentReq(p.previewNo(), List.of(), null);
            owner.loseExecute = true;
            var pending = service.createMyAppointment("USR1", req, "UNKNOWN");
            assertThat(pending.operationState()).isEqualTo("PENDING");
            SecurityContextHolder.clearContext();
            sql.update("UPDATE bty_appointment_operation SET next_retry_at=?", T0);
            context.getBean(AppointmentRecovery.class).scan();
            assertThat(owner.accepts).hasSize(1);
            assertThat(sql.queryForObject("SELECT status FROM bty_appointment", String.class)).isEqualTo("HELD");
            login(LoginUser.consumer("USR1", "customer"));
            assertThat(service.createMyAppointment("USR1", req, "UNKNOWN").status()).isEqualTo("CONFIRMED");
        });
    }
    @Test
    void failedReplaceKeepsOriginalArrangementAndSuccessfulChangePreservesHistory() {
        business().run(context -> {
            owner.authority = context.getBean(ResourceSourceAuthorityPort.class);
            var service = context.getBean(AppointmentService.class);
            var p = service.previewMyAppointment("USR1", preview(1));
            var root = service.createMyAppointment("USR1", new CreateMyAppointmentReq(p.previewNo(), List.of(),
                    null), "FIRST");
            var change = service.previewMyAppointmentChange("USR1", root.appointmentNo(), new
                    PreviewMyAppointmentChangeReq(
                    root.revision(), start.plusHours(2), List.of(new AppointmentItemReq("SKU", 1)), "P1"));
            owner.reject = "REPLACE";
            var rejected = service.changeMyAppointment("USR1", root.appointmentNo(),
                    new ChangeAppointmentReq(change.previewNo(), root.revision(), "change", null), "REJECT");
            assertThat(rejected.operationState()).isEqualTo("REJECTED");
            assertThat(service.getMyAppointment("USR1", root.appointmentNo()).appointment().startAt()).isEqualTo(start);
            owner.reject = null;
            var accepted = service.changeMyAppointment("USR1", root.appointmentNo(),
                    new ChangeAppointmentReq(change.previewNo(), root.revision(), "change", null), "CHANGE");
            assertThat(accepted.status()).isEqualTo("CONFIRMED");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment_arrangement",
                    Integer.class)).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment_item", Integer.class)).isEqualTo(2);
            assertThatThrownBy(() -> service.cancelMyAppointment("USR1", root.appointmentNo(),
                    new CancelAppointmentReq(root.revision(), "old"), "STALE")).isInstanceOf(BizException.class);
        });
    }
    @Test
    void exactConsumerNamespaceSourceSpoofAndCurrentMerchantRevocationAreRejected() {
        business().run(context -> {
            owner.authority = context.getBean(ResourceSourceAuthorityPort.class);
            var service = context.getBean(AppointmentService.class);
            var p = service.previewMyAppointment("USR1", preview(1));
            var root = service.createMyAppointment("USR1", new CreateMyAppointmentReq(p.previewNo(), List.of(),
                    null), "FIRST");
            login(LoginUser.consumer("OTHER", "other"));
            assertThatThrownBy(() -> service.getMyAppointment("OTHER",
                    root.appointmentNo())).isInstanceOf(BizException.class);
            login(LoginUser.merchantByUser("USR1", "merchant"));
            assertThatThrownBy(() -> service.getMyAppointment("USR1",
                    root.appointmentNo())).isInstanceOf(BizException.class);
            allowed = false;
            assertThatThrownBy(() -> service.getAppointment("E", "S", "USR1",
                    root.appointmentNo())).isInstanceOf(BizException.class);
            login(LoginUser.consumer("USR1", "customer"));
            var b = owner.receipts.values().iterator().next().binding();
            var forged = new ResourceSourceAuthorityPort.Binding(b.tenantNo(), b.entityNo(), b.storeNo(), "FAKE",
                    b.sourceType(), b.sourceNo(), b.sourceRevision(), b.action(), b.commandHash(), b.planHash());
            assertThat(owner.authority.freeze(new ResourceSourceAuthorityPort.Request(forged, "USR1", "CONSUMER",
                    "USR"))
                    .state()).isEqualTo(ResourceSourceAuthorityPort.State.UNKNOWN);
        });
    }
    @Test
    void confirmRejectionRequiresActualReleaseAndNoMoneySourceMayBypassProof() {
        business().run(context -> {
            owner.authority = context.getBean(ResourceSourceAuthorityPort.class);
            var service = context.getBean(AppointmentService.class);
            var p = service.previewMyAppointment("USR1", preview(1));
            assertThatThrownBy(() -> service.createMyAppointment("USR1", new CreateMyAppointmentReq(p.previewNo(),
                    List.of("FAKE_PAYMENT"), null), "MONEY")).isInstanceOf(BizException.class);
            owner.reject = "CONFIRM";
            var held = service.createMyAppointment("USR1", new CreateMyAppointmentReq(p.previewNo(), List.of(),
                    null), "HELD");
            assertThat(held.status()).isEqualTo("HELD");
                    assertThat(held.operationState()).isEqualTo("RELEASE_REQUIRED");
            owner.reject = null;
            var cancelled = service.cancelMyAppointment("USR1", held.appointmentNo(),
                    new CancelAppointmentReq(held.revision(), "cleanup"), "RELEASE");
            assertThat(cancelled.status()).isEqualTo("CANCELLED");
            assertThat(owner.hold.status()).isEqualTo("RELEASED");
        });
    }
    @Test
    void immutableExpiredPermissionDoesNotReleaseFenceOrRenewOnQuery() {
        business().run(context -> {
            owner.authority = context.getBean(ResourceSourceAuthorityPort.class);
            var service = context.getBean(AppointmentService.class);
            var p = service.previewMyAppointment("USR1", preview(1));
            var req = new CreateMyAppointmentReq(p.previewNo(), List.of(), null);
            owner.freezeOnly = true;
            var pending = service.createMyAppointment("USR1", req, "EXPIRED");
            assertThat(pending.operationState()).isEqualTo("PENDING");
            var binding = owner.receipts.values().iterator().next().binding();
            sql.update("UPDATE bty_appointment_resource_intent SET issued_at=?,valid_until=?", T0.minusDays(2),
                    T0.minusDays(1));
            var request = new ResourceSourceAuthorityPort.Request(binding, "USR1", "CONSUMER", "USR");
            var first = owner.authority.query(request);
            var second = owner.authority.freeze(request);
            assertThat(second).isEqualTo(first);
            assertThat(second.validUntil()).isBefore(Instant.now());
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment_resource_fence "
                    + "WHERE active_intent_no IS NOT NULL", Integer.class)).isOne();
            owner.freezeOnly = false;
            assertThat(service.createMyAppointment("USR1", req, "EXPIRED").operationState()).isEqualTo("REJECTED");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment_resource_fence "
                    + "WHERE active_intent_no IS NOT NULL", Integer.class)).isZero();
        });
    }
    @Test
    void concurrentlyPreparedDifferentChangeKeysSerializeOnDurableRootBeforeOwnerIo() {
        business().run(context -> {
            owner.authority = context.getBean(ResourceSourceAuthorityPort.class);
            var service = context.getBean(AppointmentService.class);
            var p = service.previewMyAppointment("USR1", preview(1));
            var root = service.createMyAppointment("USR1", new CreateMyAppointmentReq(p.previewNo(), List.of(),
                    null), "BASE");
            var a = service.previewMyAppointmentChange("USR1", root.appointmentNo(), new PreviewMyAppointmentChangeReq(
                    root.revision(), start.plusHours(1), List.of(new AppointmentItemReq("SKU", 1)), "P1"));
            var b = service.previewMyAppointmentChange("USR1", root.appointmentNo(), new PreviewMyAppointmentChangeReq(
                    root.revision(), start.plusHours(2), List.of(new AppointmentItemReq("SKU", 1)), "P1"));
            owner.entered = new CountDownLatch(1); owner.proceed = new CountDownLatch(1); owner.freezeOnly = true;
            try (var executor = Executors.newSingleThreadExecutor()) {
                var first = executor.submit(() -> {
                    login(LoginUser.consumer("USR1", "customer"));
                    return service.changeMyAppointment("USR1", root.appointmentNo(),
                            new ChangeAppointmentReq(a.previewNo(), root.revision(), "first", null), "ONE");
                });
                assertThat(owner.entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.changeMyAppointment("USR1", root.appointmentNo(),
                        new ChangeAppointmentReq(b.previewNo(), root.revision(), "second", null), "TWO"))
                        .isInstanceOf(BizException.class);
                owner.proceed.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS).operationState()).isEqualTo("PENDING");
            } finally { owner.proceed.countDown(); }
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment_operation WHERE action='CHANGE'",
                    Integer.class)).isOne();
            assertThat(service.getMyAppointment("USR1", root.appointmentNo()).appointment().startAt()).isEqualTo(start);
        });
    }
    @Test
    void requirementQuantitySelectsDistinctQualifiedPeopleAndOldPolicyChangesDoNotRewritePreview() {
        business().run(context -> {
            var service = context.getBean(AppointmentService.class);
            sql.update("UPDATE bty_service_requirement SET qty=2");
            var p = service.previewMyAppointment("USR1", new PreviewMyAppointmentReq("S",
                    List.of(new AppointmentItemReq("SKU", 1)), start, null));
            assertThat(p.assignments()).extracting(a -> a.personnelNo()).containsExactlyInAnyOrder("P1", "P2");
            String bytes = sql.queryForObject("SELECT frozen_plan FROM bty_appointment_preparation WHERE preview_no=?",
                    String.class, p.previewNo());
            assertThat(ResourcePlanContract.decode(bytes).demands()).hasSize(2);
            assertThatThrownBy(() -> service.previewMyAppointment("USR1", preview(1))).isInstanceOf(BizException.class);
        });
    }

    @Test
    void merchantCustomerResolutionGuestReferencesReadAndNoShowUseCurrentBookingPermissions() {
        business().run(context -> {
            owner.authority = context.getBean(ResourceSourceAuthorityPort.class);
            var service = context.getBean(AppointmentService.class);
            login(LoginUser.merchantByUser("OWNER_USER", "owner"));
            var p = service.previewAppointment("E", "S", "OWNER_USER", new PreviewAppointmentReq("S", "MEMBER_TICKET",
                    List.of(new AppointmentItemReq("SKU", 1)), List.of(), start));
            assertThat(sql.queryForObject("SELECT customer_ref FROM bty_appointment_preparation WHERE preview_no=?",
                    String.class, p.previewNo())).isEqualTo("USR1");
            var guest = service.previewAppointment("E", "S", "OWNER_USER", new PreviewAppointmentReq("S", null,
                    List.of(new AppointmentItemReq("SKU", 1)), List.of(), start));
            assertThat(sql.queryForObject("SELECT guest_ref FROM bty_appointment_preparation WHERE preview_no=?",
                    String.class, guest.previewNo())).startsWith("BTG");
            var root = service.createAppointment("E", "S", "OWNER_USER",
                    new CreateAppointmentReq(p.previewNo(), "MEMBER_TICKET", List.of(), null), "STAFF_CREATE");
            assertThat(service.listAppointments("E", "S", "OWNER_USER", new AppointmentQuery("S", null, null,
                    "CONFIRMED", "P1", 1, 20, "ASSIGNED")).total()).isEqualTo(1);
            allowed = false;
            assertThatThrownBy(() -> service.cancelAppointment("E", "S", "OWNER_USER", root.appointmentNo(),
                    new CancelAppointmentReq(root.revision(), "revoked"), "REVOKED")).isInstanceOf(BizException.class);
            allowed = true;
            sql.update("UPDATE bty_appointment SET start_at=?", T0.minusDays(1));
            var noShow = service.recordNoShow("E", "S", "OWNER_USER", root.appointmentNo(),
                    new RecordNoShowReq(root.revision(), "not arrived", "EVIDENCE"), "NO_SHOW");
            assertThat(noShow.status()).isEqualTo("NO_SHOW");
            assertThat(sql.queryForObject("SELECT safe_snapshot FROM bty_action_log "
                    + "WHERE action='APPOINTMENT_NO_SHOW_PREPARED'", String.class))
                    .contains("not arrived", "EVIDENCE");
            assertThat(owner.hold.status()).isEqualTo("RELEASED");
        });
    }

    @Test
    void anotherCurrentlyAuthorizedManagerRecoversRevokedInitiatorsExactCommand() {
        business().run(context -> {
            owner.authority = context.getBean(ResourceSourceAuthorityPort.class);
            var service = context.getBean(AppointmentService.class);
            login(LoginUser.merchant("M1", "first"));
            var preview = service.previewAppointment("E", "S", "M1", new PreviewAppointmentReq("S", null,
                    List.of(new AppointmentItemReq("SKU", 1)), List.of(), start));
            owner.freezeOnly = true;
            var pending = service.createAppointment("E", "S", "M1",
                    new CreateAppointmentReq(preview.previewNo(), null, List.of(), null), "ORIGINAL");
            var original = owner.receipts.values().iterator().next().binding();
            revokedActor = "M1";
            var recovery = context.getBean(ServiceOperationRecoveryService.class);
            assertThatThrownBy(() -> recovery.recoverOperation("E", "S", "M1", pending.operationNo()))
                    .isInstanceOf(BizException.class);
            login(LoginUser.merchantByUser("M2", "second"));
            owner.freezeOnly = false;
            assertThat(recovery.recoverOperation("E", "S", "M2", pending.operationNo()).state()).isEqualTo("SUCCEEDED");
            assertThat(owner.receipts.values()).anySatisfy(receipt -> {
                assertThat(receipt.binding()).isEqualTo(original); assertThat(receipt.status()).isEqualTo("SUCCEEDED");
            });
            assertThat(sql.queryForObject("SELECT actor_no FROM bty_appointment_operation WHERE operation_no=?",
                    String.class, pending.operationNo())).isEqualTo("M1");
            assertThat(owner.accepts).hasSize(2);
        });
    }

    @Test
    void bookingRequiresObservedProductRevisionsAndRevalidatesQuoteAtSubmission() {
        business().run(context -> {
            var service = context.getBean(AppointmentService.class);
            goodsRevision = null;
            assertThatThrownBy(() -> service.previewMyAppointment("USR1", preview(1))).isInstanceOf(BizException.class);
            goodsRevision = 7L;
            var p = service.previewMyAppointment("USR1", preview(1));
            assertThat(p.items().getFirst().goodsRevision()).isEqualTo(7);
            assertThat(p.items().getFirst().skuRevision()).isZero();
            quotePrice = 1500;
            assertThatThrownBy(() -> service.createMyAppointment("USR1",
                    new CreateMyAppointmentReq(p.previewNo(), List.of(), null), "PRICE_CHANGED"))
                    .isInstanceOf(BizException.class);
            assertThat(owner.accepts).isEmpty();
        });
    }


    @Test
    void terminalSourceOutboxRollsBackWithFenceAndReplaysExactlyOnce() {
        business().withInitializer(application -> application.getBeanFactory().addBeanPostProcessor(
                new BeanPostProcessor() {
                    @Override
                    public Object postProcessAfterInitialization(Object bean, String name) {
                        if (!name.equals("beautyOutboxPublisher")) { return bean; }
                        OutboxPublisher delegate = (OutboxPublisher) bean;
                        return (OutboxPublisher) (event, options) -> {
                            var receipt = delegate.publish(event, options);
                            if (failOutbox) {
                                TransactionSynchronizationManager.registerSynchronization(
                                        new TransactionSynchronization() {
                                            @Override public void beforeCommit(boolean readOnly) {
                                                throw new IllegalStateException("source-outbox-rollback");
                                            }
                                        });
                            }
                            return receipt;
                        };
                    }
                })).run(context -> {
            owner.authority = context.getBean(ResourceSourceAuthorityPort.class);
            var service = context.getBean(AppointmentService.class);
            var p = service.previewMyAppointment("USR1", preview(1));
            var req = new CreateMyAppointmentReq(p.previewNo(), List.of(), null);
            failOutbox = true;
            assertThatThrownBy(() -> service.createMyAppointment("USR1", req, "OUTBOX"))
                    .hasMessage("source-outbox-rollback");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT status FROM bty_appointment", String.class)).isEqualTo("HELD");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment_resource_fence "
                    + "WHERE active_intent_no IS NOT NULL", Integer.class)).isOne();
            failOutbox = false;
            var done = service.createMyAppointment("USR1", req, "OUTBOX");
            assertThat(done.status()).isEqualTo("CONFIRMED");
            service.createMyAppointment("USR1", req, "OUTBOX");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT payload FROM sys_outbox", String.class))
                    .contains(done.operationNo()).doesNotContain("USR1", "MEMBER_TICKET", "demands", "priceMinor");
        });
    }

    @Test
    void overlappingReplacementExcludesOnlyItsVerifiedCurrentHold() {
        business().run(context -> {
            owner.authority = context.getBean(ResourceSourceAuthorityPort.class);
            var service = context.getBean(AppointmentService.class);
            var p = service.previewMyAppointment("USR1", preview(1));
            var root = service.createMyAppointment("USR1", new CreateMyAppointmentReq(p.previewNo(), List.of(), null),
                    "OVERLAP");
            occupied = true;
            var req = new PreviewMyAppointmentChangeReq(root.revision(), start.plusMinutes(5),
                    List.of(new AppointmentItemReq("SKU", 1)), "P1");
            assertThat(service.previewMyAppointmentChange("USR1", root.appointmentNo(), req).previewNo()).isNotBlank();
            otherOccupied = true;
            assertThatThrownBy(() -> service.previewMyAppointmentChange("USR1", root.appointmentNo(), req))
                    .isInstanceOf(BizException.class);
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void recoveryFailureDoesNotStarveLaterOriginalTerminal(CapturedOutput output) {
        business().run(context -> {
            owner.authority = context.getBean(ResourceSourceAuthorityPort.class);
            var service = context.getBean(AppointmentService.class);
            for (String key : List.of("BROKEN", "LATER")) {
                var p = service.previewMyAppointment("USR1", preview(1));
                owner.loseExecute = true;
                service.createMyAppointment("USR1", new CreateMyAppointmentReq(p.previewNo(), List.of(), null), key);
            }
            owner.brokenSource = owner.receipts.values().iterator().next().binding().sourceNo();
            sql.update("UPDATE bty_appointment_operation SET next_retry_at='2020-01-01'");
            SecurityContextHolder.clearContext();
            assertThat(context.getBean(AppointmentRecovery.class).scan()).isEqualTo(1);
            assertThat(output).contains("Appointment recovery deferred operation=", "failure=IllegalStateException")
                    .doesNotContain("unavailable original");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment_operation "
                    + "WHERE appointment_no=? AND lease_until IS NULL "
                    + "AND next_retry_at>UTC_TIMESTAMP()+INTERVAL 40 SECOND",
                    Integer.class, owner.brokenSource)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment_operation "
                    + "WHERE receipt IS NULL AND accept_payload LIKE '%CONFIRM%'", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment_resource_fence "
                    + "WHERE active_intent_no IS NOT NULL", Integer.class)).isOne();
        });
    }

    @Test
    void preservedLegacyRootReadsWithoutInventingArrangementEvidence() throws Exception {
        database = new BeautySchemaMigrationIT();
        database.isolatedDatabase();
        sql = new JdbcTemplate(database.source);
        Flyway.configure().dataSource(database.source).locations("classpath:db/industry/beauty")
                .table("bty_flyway_history").target("2").baselineVersion("0").baselineOnMigrate(true)
                .createSchemas(false).placeholderReplacement(false).load().migrate();
        appointment("LEGACY", "S");
        sql.update("UPDATE bty_appointment SET customer_ref='USR1'");
        database.initialize("migrate");
        new ResourceDatabasePopulator(new ClassPathResource("platform-write-fixture.sql")).execute(database.source);
        business().run(context -> {
            login(LoginUser.merchantByUser("OWNER_USER", "owner"));
            var service = context.getBean(AppointmentService.class);
            var page = service.listAppointments("E", "S", "OWNER_USER",
                    new AppointmentQuery("S", null, null, null, null, 1, 20, null));
            assertThat(page.total()).isOne();
            var detail = service.getAppointment("E", "S", "OWNER_USER", "LEGACY");
            assertThat(detail.appointment().status()).isEqualTo("CONFIRMED");
            assertThat((Object) detail.appointment().arrangementRevision()).isNull();
            assertThat(detail.items()).isEmpty();
            assertThat(detail.availableActions()).isEmpty();
            assertThat(sql.queryForObject("SELECT hold_no FROM bty_appointment", String.class)).isEqualTo("HOLD");
            login(LoginUser.consumer("USR1", "customer"));
            assertThat(service.getMyAppointment("USR1", "LEGACY").availableActions()).isEmpty();
            assertThatThrownBy(() -> service.cancelMyAppointment("USR1", "LEGACY",
                    new CancelAppointmentReq(0L, "legacy"), "LEGACY-CANCEL")).isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment_current", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_appointment_operation", Integer.class)).isZero();
        });
    }

    /** An explicit external-owner fixture. It is never installed as a production provider or admission proof. */
    private final class ControlledOwner implements ResourceBookingPort {
        ResourceSourceAuthorityPort authority;
        final Map<String, Accept> accepts = new LinkedHashMap<>();
        final Map<String, Receipt> receipts = new LinkedHashMap<>();
        final Map<String, ResourceSourceAuthorityPort.Decision> decisions = new LinkedHashMap<>();
        final List<Accept> replayed = new ArrayList<>();
        final AtomicInteger sequence = new AtomicInteger();
        boolean loseAccept; boolean loseExecute; boolean loseQuery; boolean freezeOnly; String reject;
        CountDownLatch entered; CountDownLatch proceed;
        Hold hold;
        String brokenSource;
        final Map<String, Hold> allHolds = new LinkedHashMap<>();
        @Override public Receipt accept(Actor actor, Accept req) {
            outside();
            if (entered != null) {
                entered.countDown();
                try { if (!proceed.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("fixture timeout"); } }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new
                        IllegalStateException(failure); }
            }
            var previous = accepts.putIfAbsent(req.key(), req);
            if (previous != null) { assertThat(req).isEqualTo(previous); replayed.add(req); }
            var receipt = receipts.computeIfAbsent(req.key(), key -> {
                var b = new ResourceSourceAuthorityPort.Binding("MAIN", "E", "S", "CMD" + sequence.incrementAndGet(),
                        req.sourceType(), req.sourceNo(), req.sourceRevision(), req.action(),
                        IdempotencyScope.requestHash(json.writeValueAsString(req)),
                                ResourcePlanContract.hash(req.frozenPayload()));
                return new Receipt(b, "OWNER", "ACCEPTED", 0, req.expectedOwnerVersion(), req.target(),
                        req.frozenPayload(), null, null);
            });
            if (loseAccept) { loseAccept = false; throw new IllegalStateException("controlled lost accept response"); }
            return receipt;
        }
        @Override public Optional<Receipt> query(Actor actor, Scope scope, String command) {
            outside();
            if (loseQuery) { loseQuery = false; throw new IllegalStateException("controlled unavailable query"); }
            return receipts.values().stream().filter(r -> r.binding().commandNo().equals(command)).findFirst();
        }
        @Override public Receipt execute(Actor actor, Scope scope, String command) {
            outside();
            var original = query(actor, scope, command).orElseThrow();
            if (!"ACCEPTED".equals(original.status())) { return original; }
            var b = original.binding();
            var decision = authority.freeze(new ResourceSourceAuthorityPort.Request(b, actor.operatorNo(),
                    actor.realm(), actor.subjectKind()));
            assertThat(decision.state()).isEqualTo(ResourceSourceAuthorityPort.State.AUTHORIZED);
            decisions.put(b.commandNo(), decision);
            if (freezeOnly) { throw new IllegalStateException("controlled pending original command"); }
            boolean rejected = b.action().equals(reject) || !decision.validUntil().isAfter(Instant.now());
            String result;
            if (rejected) { result = "{}"; }
            else {
                String state = "RESERVE".equals(b.action()) ? "HELD" : "RELEASE".equals(b.action()) ? "RELEASED" :
                        "CONFIRMED";
                String no = hold == null || "REPLACE".equals(b.action()) ? "H" + sequence.incrementAndGet() :
                        hold.holdNo();
                hold = new Hold(no, state, hold == null ? 0 : hold.version() + 1, 1, b.sourceRevision(),
                        original.expectedOwnerVersion() + 1, b.planHash());
                allHolds.put(no, hold);
                result = json.writeValueAsString(Map.of("holdNo", no, "status", state, "ownerRevision",
                        hold.ownerVersion()));
            }
            var terminal = new Receipt(b, "OWNER", rejected ? "REJECTED" : "SUCCEEDED", 1,
                    original.expectedOwnerVersion(), original.target(), original.frozenPayload(), result,
                    rejected ? "CAPACITY_UNAVAILABLE" : null);
            receipts.replaceAll((key, value) -> value.binding().equals(b) ? terminal : value);
            if (loseExecute) { loseExecute = false; loseQuery = true; throw new
                    IllegalStateException("controlled lost execute response"); }
            return terminal;
        }
        @Override public Hold queryHold(Actor actor, ResourceSourceAuthorityPort.Binding binding, String no) {
            outside(); assertThat(hold.holdNo()).isEqualTo(no); return hold;
        }
        @Override public Optional<Receipt> reconcile(ResourceSourceAuthorityPort.Binding binding) {
            outside();
            if (binding.sourceNo().equals(brokenSource)) { throw new IllegalStateException("unavailable original"); }
            return receipts.values().stream().filter(r -> r.binding().equals(binding)).findFirst();
        }
        @Override public Hold reconcileHold(ResourceSourceAuthorityPort.Binding binding, String no) {
            outside(); return allHolds.get(no);
        }
    }
}
