package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.beauty.config.BeautyAppointmentConfiguration;
import ai.neargo.shop.beauty.config.BeautyCatalogConfiguration;
import ai.neargo.shop.beauty.config.BeautyWriteGuardConfiguration;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.dto.CheckInReq;
import ai.neargo.shop.beauty.dto.CreateMyAppointmentReq;
import ai.neargo.shop.beauty.dto.MyAppointmentVO;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentReq;
import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.ReceptionIdentityProof;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
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
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
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

/** Real private MySQL/source/fence/services. External catalog/personnel/resource owner are explicit controlled
 * fixtures.
 */
abstract class ReceptionFixture extends BeautyExecutionFixture {
    final ObjectMapper json = new ObjectMapper();
    final ControlledOwner owner = new ControlledOwner();
    boolean allowed = true;
    String proofUser = "USR1";
    volatile Instant clockAt;
    final Clock receptionClock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return clockAt == null ? Instant.now() : clockAt; }
    };
    boolean customerAllowed = true;
    String catalogEntity = "E";
    String catalogStatus = "ACTIVE";
    boolean guestClaimed;
    String revokedActor;
    boolean roomQuantities;
    boolean occupied;
    boolean otherOccupied;
    boolean failOutbox;
    long quotePrice = 1000;
    Long goodsRevision = 7L;
    final OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
            .truncatedTo(ChronoUnit.DAYS).plusHours(10);

    @Configuration(proxyBeanMethods = false)
    static class OutboxRoute {
        @Bean
        UnifiedOutboxFacade facade(DataSource source, PlatformTransactionManager manager, ObjectMapper mapper) {
            var facade = new UnifiedOutboxFacade(mapper);
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, manager, mapper), List.of());
            facade.setArchiveOnly(Set.of("PLATFORM:BEAUTY_APPOINTMENT_OPERATION_COMPLETED",
                    "PLATFORM:BEAUTY_RECEPTION_STATE_CHANGED",
                    "PLATFORM:BEAUTY_SERVICE_DEFINITION_VERSION_PUBLISHED"));
            return facade;
        }
    }

    @BeforeEach
    void database() throws Exception {
        initialize("BtyAppointmentPreparation", "BtyAppointmentOperation", "BtyReceptionOperation",
                "BtyArrivalChallenge");
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
                "steps_template", "[{\"stepCode\":\"SERVICE\",\"sequenceNo\":0}]", "notes", "[]",
                        "schema_version", 1, "snapshot_hash", "b".repeat(64));
        seed("bty_service_requirement", "requirement_no", "REQ", "definition_version_no", "DV", "role_code", "MAIN",
                "resource_type", "STAFF", "skill_goods_no", "G", "qty", 1, "sequence_no", 0,
                "offset_min", 0, "duration_min", 17, "selection_rule", "ANY_QUALIFIED");
        sql.update("UPDATE bty_service_definition_version SET snapshot_hash=?", IdempotencyScope.requestHash("SKU",
                json.writeValueAsString(new PublishServiceDefinitionVersionReq.Duration(17, 3, 4)),
                json.writeValueAsString(List.of(new PublishServiceDefinitionVersionReq.Requirement("MAIN", "STAFF",
                        "G", 1, 0, 0, 17, "ANY_QUALIFIED")))));
        sql.update("UPDATE bty_store_policy_version SET snapshot_hash=?", IdempotencyScope.requestHash(
                T0.minusYears(1).atOffset(ZoneOffset.UTC).toString(), "30", "60", "true", "PREPAID_SINGLE"));
    }
    void login(LoginUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
                List.of()));
    }
    ApplicationContextRunner business() {
        return runner().withUserConfiguration(BeautyCatalogConfiguration.class, BeautyAppointmentConfiguration.class,
                        BeautyWriteGuardConfiguration.class, OutboxRoute.class)
                .withBean(ObjectMapper.class, () -> json)
                .withBean(ResourceBookingPort.class, () -> owner)
                .withBean(CustomerIdentityPort.class, () -> new CustomerIdentityPort() {
                    @Override public ResolvedCustomer resolveReference(
                            EntitlementContext context, ReferenceQuery q) {
                        throw new AssertionError("legacy reference cannot authorize reception");
                    }
                    @Override public ResolvedCustomer verifyConsumerProof(EntitlementContext context, String code) {
                        outside(); return new ResolvedCustomer(proofUser);
                    }
                    @Override public ResolvedCustomer resolveClaimReference(
                            EntitlementContext context, ReferenceQuery q) {
                        outside(); return new ResolvedCustomer("USR1");
                    }
                })
                .withBean(MerchantOperatorAccessPort.class, () -> q -> allowed && !q.operatorNo().equals(revokedActor)
                        && Set.of("biz:booking:manage", "biz:customer").contains(q.permission())
                        && (customerAllowed || !"biz:customer".equals(q.permission())))
                .withBean(ServiceCatalogFactsPort.class, () -> new ServiceCatalogFactsPort() {
                    @Override public Store store(String number) {
                        outside();
                        return new Store(catalogEntity, number, "Store", null, catalogStatus, "always", "address",
                                new StoreSalesCatalogPort.Snapshot(catalogEntity, number, null, 0, null, 0, null,
                                        false, null,
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
                        outside();
                        if (roomQuantities) {
                            return List.of(new ResourceFactsPort.ResourceFact("R1", ResourceFactsPort.Type.STAFF,
                                    null, "P1", "Technician", 1, false),
                                    new ResourceFactsPort.ResourceFact("ROOM1", ResourceFactsPort.Type.ROOM,
                                            null, null, "Shared room", 2, false),
                                    new ResourceFactsPort.ResourceFact("ROOM2", ResourceFactsPort.Type.ROOM,
                                            null, null, "Other room", 2, false));
                        }
                        return List.of(new ResourceFactsPort.ResourceFact("R1", ResourceFactsPort.Type.STAFF,
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
                }).withInitializer(application -> application.getBeanFactory().addBeanPostProcessor(
                        new BeanPostProcessor() {
                            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                                if ("beautyReceptionClock".equals(name)) { return receptionClock; }
                                if (!"beautyOutboxPublisher".equals(name)) { return bean; }
                                OutboxPublisher delegate = (OutboxPublisher) bean;
                                return (OutboxPublisher) (event, options) -> {
                                    var receipt = delegate.publish(event, options);
                                    if (failOutbox) { throw new IllegalStateException("reception-outbox-rollback"); }
                                    return receipt;
                                };
                            }
                        }));
    }
    void outside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    PreviewMyAppointmentReq preview(int qty) {
        return new PreviewMyAppointmentReq("S", List.of(new AppointmentItemReq("SKU", qty)), start, "P1");
    }
    MyAppointmentVO create(
            AssertableApplicationContext c) {
        login(LoginUser.consumer("USR1", "customer"));
        owner.authority = c.getBean(ResourceSourceAuthorityPort.class);
        var service = c.getBean(AppointmentService.class);
        var p = service.previewMyAppointment("USR1", preview(1));
        return service.createMyAppointment("USR1", new CreateMyAppointmentReq(p.previewNo(), List.of(), null),
                "CREATE");
    }
    CheckInReq request(String number, long revision) {
        return new CheckInReq(number, new ReceptionIdentityProof("cr1_" + "c".repeat(43), null, null), revision);
    }
    final class ControlledOwner implements ResourceBookingPort {
        ResourceSourceAuthorityPort authority;
        final Map<String, Accept> accepts = new LinkedHashMap<>();
        final Map<String, Receipt> receipts = new LinkedHashMap<>();
        final Map<String, ResourceSourceAuthorityPort.Decision> decisions = new LinkedHashMap<>();
        final List<Accept> replayed = new ArrayList<>();
        final List<String> actors = new ArrayList<>();
        final List<String> reconciled = new ArrayList<>();
        final AtomicInteger sequence = new AtomicInteger();
        boolean loseAccept; boolean loseExecute; boolean loseQuery; boolean freezeOnly; String reject;
        CountDownLatch entered; CountDownLatch proceed;
        Hold hold; Hold queryHoldOverride;
        Runnable onQueryHold = () -> { };
        Consumer<ResourceSourceAuthorityPort.Binding> onReconcile = binding -> { };
        String brokenSource;
        String loseExecuteAction;
        final Map<String, Hold> allHolds = new LinkedHashMap<>();
        @Override public Receipt accept(Actor actor, Accept req) {
            outside(); actors.add(actor.operatorNo());
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
            if (loseExecute || b.action().equals(loseExecuteAction)) {
                loseExecuteAction = null; loseExecute = false; loseQuery = true; throw new
                    IllegalStateException("controlled lost execute response"); }
            return terminal;
        }
        @Override public Hold queryHold(Actor actor, ResourceSourceAuthorityPort.Binding binding, String no) {
            outside(); onQueryHold.run(); if (queryHoldOverride != null) { return queryHoldOverride; }
            assertThat(hold.holdNo()).isEqualTo(no); return hold;
        }
        @Override public Optional<Receipt> reconcile(ResourceSourceAuthorityPort.Binding binding) {
            outside(); reconciled.add(binding.sourceNo()); onReconcile.accept(binding);
            if (binding.sourceNo().equals(brokenSource)) { throw new IllegalStateException("unavailable original"); }
            return receipts.values().stream().filter(r -> r.binding().equals(binding)).findFirst();
        }
        @Override public Hold reconcileHold(ResourceSourceAuthorityPort.Binding binding, String no) {
            outside(); return allHolds.get(no);
        }
    }
}
