package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.merchant.dto.AssignPersonnelReq;
import ai.neargo.shop.merchant.dto.CreatePersonnelReq;
import ai.neargo.shop.merchant.dto.PersonnelVO;
import ai.neargo.shop.merchant.dto.SaveScheduleRuleReq;
import ai.neargo.shop.merchant.service.PersonnelService;
import ai.neargo.shop.merchant.service.ScheduleService;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Real identity, reception and resource owners must agree across claim and atomic replacement. */
@Import(BeautyClaimedGuestResourceIT.ClockSelection.class)
@TestPropertySource(
        properties = {
            "shop.beauty.enabled=true",
            "shop.beauty.deployment=embedded",
            "shop.beauty.migration.mode=migrate",
            "shop.beauty.publication.archive-only=true",
            "shop.personnel.publication.archive-only=true",
            "shop.schedule.publication.archive-only=true",
            "shop.resource.publication.archive-only=true"
        })
@EnabledIfSystemProperty(named = "beauty.source.test.native", matches = "true")
class BeautyClaimedGuestResourceIT extends CheckoutFlowFixture {
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired PersonnelService personnel;
    @Autowired ScheduleService schedules;
    @Autowired MerchantOperatorAccessPort operators;
    private String managerUser;
    private String role;
    private String requirement;
    private PersonnelVO original;
    private PersonnelVO target;
    private OffsetDateTime start;

    @TestConfiguration
    static class ClockSelection {
        @Bean
        @Primary
        Clock resourceTestClock() {
            // Source permits are issued using actual UTC; a frozen fixture clock precedes issuedAt.
            return Clock.systemUTC();
        }
    }

    @BeforeEach
    void resourceFixture() {
        managerUser = "MU-" + sku;
        String account = "MA-" + sku;
        role = "MR-" + sku;
        jdbc.update(
                "INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at)"
                        + " VALUES (?,'resource manager','NORMAL',NOW(),NOW())",
                managerUser);
        jdbc.update(
                "INSERT INTO"
                    + " mch_account(entity_no,mch_account_no,user_no,is_owner,status,created_at,updated_at)"
                    + " VALUES (?,?,?,0,'ACTIVE',NOW(),NOW())",
                entity,
                account,
                managerUser);
        jdbc.update(
                "INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at)"
                        + " VALUES (?,?,'resource manager',?,NOW(),NOW())",
                entity,
                role,
                permissions(true));
        jdbc.update(
                "INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                        + " VALUES (?,?,?,NOW(),NOW())",
                account,
                store,
                role);
        start =
                OffsetDateTime.now(ZoneOffset.UTC)
                        .plusDays(1)
                        .withHour(10)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
        asMerchant(
                () ->
                        call(
                                bean("StorePolicyService"),
                                "publishPolicyVersion",
                                entity,
                                store,
                                actor,
                                dto(
                                        "PublishStorePolicyVersionReq",
                                        0L,
                                        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                                        dto(
                                                "PublishStorePolicyVersionReq$Policy",
                                                30,
                                                dto(
                                                        "PublishStorePolicyVersionReq$CancellationRule",
                                                        0,
                                                        true),
                                                Set.of("PREPAID_SINGLE"))),
                                "policy-" + sku));
        asMerchant(
                () ->
                        call(
                                bean("ServiceDefinitionService"),
                                "publishDefinitionVersion",
                                entity,
                                store,
                                actor,
                                dto(
                                        "PublishServiceDefinitionVersionReq",
                                        sku,
                                        0L,
                                        dto(
                                                "PublishServiceDefinitionVersionReq$Duration",
                                                30,
                                                0,
                                                0),
                                        List.of(
                                                dto(
                                                        "PublishServiceDefinitionVersionReq$Requirement",
                                                        "MAIN",
                                                        "STAFF",
                                                        goods,
                                                        1,
                                                        0,
                                                        0,
                                                        30,
                                                        "ANY_QUALIFIED"))),
                                "definition-" + sku));
        requirement =
                jdbc.queryForObject(
                        "SELECT requirement_no FROM bty_service_requirement WHERE entity_no=?",
                        String.class,
                        entity);
        var people =
                List.of(createPerson("one"), createPerson("two")).stream()
                        .sorted(Comparator.comparing(PersonnelVO::personnelNo))
                        .toList();
        original = people.getFirst();
        target = people.getLast();
        assertThat(original.personnelNo()).isLessThan(target.personnelNo());
        assertThat(allowed(BizPerms.BOOKING_MANAGE)).isTrue();
    }

    @Test
    void claimedGuestReplacementUsesCanonicalUserAndPreservesReceipts() {
        var scenario = claimedArrival();
        long orders = count("ord_order"), payments = count("stl_payment");
        Object request = opening(scenario);
        Object opened = asManager(() -> open(request));
        String workOrder = (String) value(opened, "workOrderNo");
        assertThat(value(opened, "operationState")).isEqualTo("SUCCEEDED");
        assertThat(value(opened, "taskNos")).asList().hasSize(1);
        assertThat(row("SELECT customer_ref FROM bty_work_order WHERE work_order_no=?", workOrder))
                .containsEntry("customer_ref", "USER:" + buyer);
        assertThat(
                        row(
                                "SELECT identity_kind,identity_ref FROM bty_work_order_context"
                                        + " WHERE work_order_no=?",
                                workOrder))
                .containsEntry("identity_kind", "USER")
                .containsEntry("identity_ref", buyer);
        assertThat(ownedCount("bty_work_order")).isEqualTo(1);
        assertThat(ownedCount("bty_service_task")).isEqualTo(1);
        var command = replacement(scenario.appointment());
        assertThat(command)
                .containsEntry("action", "REPLACE")
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("target_hold_no", scenario.hold());
        String currentHold = currentHold(scenario.appointment());
        assertThat(currentHold).isNotEqualTo(scenario.hold());
        assertThat(
                        row(
                                "SELECT status,replaced_by_hold_no FROM mch_booking_hold WHERE"
                                        + " hold_no=?",
                                scenario.hold()))
                .containsEntry("status", "RELEASED")
                .containsEntry("replaced_by_hold_no", currentHold);
        assertThat(
                        row(
                                "SELECT status,replaces_hold_no FROM mch_booking_hold WHERE"
                                        + " hold_no=?",
                                currentHold))
                .containsEntry("status", "CONFIRMED")
                .containsEntry("replaces_hold_no", scenario.hold());
        assertThat(currentResources(scenario.appointment())).containsExactly(target.resourceNo());
        assertThat(activeUnits(scenario.hold())).isZero();
        assertThat(activeUnits(currentHold)).isEqualTo(6);
        assertThat(capacity(original.resourceNo())).isZero();
        assertThat(capacity(target.resourceNo())).isEqualTo(6);
        reconcileCommand(command, target.resourceNo(), 1);
        assertHistory(scenario);
        String facts = effectDigest();
        assertThat(digest(asManager(() -> open(request)))).isEqualTo(digest(opened));
        assertThat(effectDigest()).isEqualTo(facts);
        assertThat(digest(register("main"))).isEqualTo(scenario.registrationDigest());
        assertThat(digest(claim(scenario.guest(), scenario.claimRequest())))
                .isEqualTo(scenario.claimDigest());
        assertHistory(scenario);
        assertThat(count("ord_order")).isEqualTo(orders);
        assertThat(count("stl_payment")).isEqualTo(payments);
        System.out.println(
                "A7 positive: canonical USER; REPLACE SUCCEEDED; one work order/task; six target"
                    + " buckets; original guest/claim/history and all replay effects preserved.");
    }

    @Test
    void revokedUsrBookingGrantCannotReplaceClaimedGuestAppointment() {
        var scenario = claimedArrival();
        String before = preservedResourceDigest(scenario);
        jdbc.update(
                "UPDATE mch_role SET perms=? WHERE entity_no=? AND role_code=?",
                permissions(false),
                entity,
                role);
        assertThat(allowed(BizPerms.BOOKING_VIEW)).isTrue();
        assertThat(allowed(BizPerms.CUSTOMER)).isTrue();
        Object visible =
                asManager(
                        () ->
                                call(
                                        bean("AppointmentService"),
                                        "getAppointment",
                                        entity,
                                        store,
                                        managerUser,
                                        scenario.appointment()));
        assertThat(visible).isNotNull();
        Object request = opening(scenario);
        assertThatThrownBy(() -> asManager(() -> open(request)))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(allowed(BizPerms.BOOKING_MANAGE)).isFalse();
        assertThat(preservedResourceDigest(scenario)).isEqualTo(before);
        assertThat(ownedCount("bty_work_order")).isZero();
        assertThat(ownedCount("bty_service_task")).isZero();
        assertThat(replacementCount(scenario.appointment())).isZero();
        assertThat(currentHold(scenario.appointment())).isEqualTo(scenario.hold());
        assertHistory(scenario);
        System.out.println(
                "A7 revoked: actual MERCHANT/USR nonowner; booking view/customer remain; manage"
                        + " revoked; FORBIDDEN; original hold/units/capacity/arrangement and guest"
                        + " facts preserved.");
    }

    @Test
    void failedClaimedGuestReplacementPreservesOriginalResourceHold() throws Exception {
        var scenario = claimedArrival();
        String before = preservedResourceDigest(scenario);
        Object request = opening(scenario);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Object> opening = null;
        String peerAppointment;
        try (Connection gate = dataSource.getConnection()) {
            gate.setAutoCommit(false);
            try {
                try (var statement =
                        gate.prepareStatement(
                                "SELECT personnel_no FROM mch_personnel WHERE tenant_no='MAIN' AND"
                                        + " entity_no=? AND personnel_no=? FOR UPDATE")) {
                    statement.setString(1, entity);
                    statement.setString(2, original.personnelNo());
                    try (var locked = statement.executeQuery()) {
                        assertThat(locked.next()).isTrue();
                        assertThat(locked.getString(1)).isEqualTo(original.personnelNo());
                    }
                }
                opening = executor.submit(() -> asManager(() -> open(request)));
                await(
                        () ->
                                number(
                                                "SELECT COUNT(*) FROM mch_resource_command c JOIN"
                                                    + " mch_booking_owner o ON"
                                                    + " o.owner_no=c.owner_no AND"
                                                    + " o.entity_no=c.entity_no JOIN"
                                                    + " bty_appointment_resource_intent i ON"
                                                    + " i.command_no=c.command_no AND"
                                                    + " i.entity_no=c.entity_no WHERE c.entity_no=?"
                                                    + " AND o.source_no=? AND c.action='REPLACE'"
                                                    + " AND c.status='ACCEPTED' AND i.phase_code=1",
                                                entity,
                                                scenario.appointment())
                                        == 1,
                        "authorized REPLACE before peer reservation");
                assertThat(opening.isDone()).isFalse();
                Object peer = register("peer");
                Object reserved = appointment((String) value(peer, "guestNo"), target, "peer");
                peerAppointment = (String) value(reserved, "appointmentNo");
                assertThat(value(reserved, "status")).isEqualTo("CONFIRMED");
                assertThat(capacity(target.resourceNo())).isEqualTo(6);
            } finally {
                gate.rollback();
            }
            Future<Object> result = opening;
            assertThatThrownBy(() -> result.get(30, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BizException.class)
                    .satisfies(
                            failure ->
                                    assertThat(((BizException) failure.getCause()).errorCode())
                                            .isEqualTo(ErrorCode.CONFLICT));
        } finally {
            if (opening != null && !opening.isDone()) opening.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(preservedResourceDigest(scenario)).isEqualTo(before);
        assertThat(currentHold(scenario.appointment())).isEqualTo(scenario.hold());
        assertThat(currentResources(scenario.appointment())).containsExactly(original.resourceNo());
        assertThat(currentResources(peerAppointment)).containsExactly(target.resourceNo());
        assertThat(activeUnits(scenario.hold())).isEqualTo(6);
        assertThat(activeUnits(currentHold(peerAppointment))).isEqualTo(6);
        assertThat(capacity(original.resourceNo())).isEqualTo(6);
        assertThat(capacity(target.resourceNo())).isEqualTo(6);
        var command = replacement(scenario.appointment());
        assertThat(command)
                .containsEntry("status", "REJECTED")
                .containsEntry("rejection_code", "CAPACITY_UNAVAILABLE")
                .containsEntry("target_hold_no", scenario.hold());
        reconcileCommand(command, target.resourceNo(), 2);
        assertThat(
                        row(
                                "SELECT phase FROM bty_reception_operation WHERE entity_no=? AND"
                                        + " appointment_no=? AND endpoint='BAPI-052'",
                                entity,
                                scenario.appointment()))
                .containsEntry("phase", "REJECTED");
        assertThat(ownedCount("bty_work_order")).isZero();
        assertThat(ownedCount("bty_service_task")).isZero();
        assertThat(number("SELECT COUNT(*) FROM mch_booking_hold WHERE entity_no=?", entity))
                .isEqualTo(2);
        assertHistory(scenario);
        System.out.println(
                "A7 capacity: personnel A gate; actual REPLACE ACCEPTED/AUTHORIZED before real peer"
                        + " B; CAPACITY_UNAVAILABLE/CONFLICT; both original A and peer B confirmed"
                        + " holds preserved.");
    }

    private PersonnelVO createPerson(String suffix) {
        var assignment =
                new AssignPersonnelReq(
                        0L,
                        null,
                        store,
                        start.minusDays(2),
                        start.plusDays(40),
                        true,
                        null,
                        0,
                        List.of("TECHNICIAN"),
                        List.of(new AssignPersonnelReq.Skill(goods, sku, null)));
        var person =
                asMerchant(
                        () ->
                                personnel.createPersonnel(
                                        entity,
                                        store,
                                        actor,
                                        new CreatePersonnelReq(
                                                "qualified " + suffix, null, assignment),
                                        "person-" + suffix + sku));
        Long revision =
                jdbc.queryForObject(
                        "SELECT version FROM mch_resource WHERE entity_no=? AND resource_no=?",
                        Long.class,
                        entity,
                        person.resourceNo());
        asMerchant(
                () ->
                        schedules.saveScheduleRule(
                                entity,
                                store,
                                actor,
                                new SaveScheduleRuleReq(
                                        null,
                                        person.resourceNo(),
                                        revision,
                                        "UTC",
                                        127,
                                        0,
                                        1440,
                                        0,
                                        start.toLocalDate().minusDays(1),
                                        start.toLocalDate().plusDays(30),
                                        "ACTIVE",
                                        "A7 real schedule"),
                                "schedule-" + suffix + sku));
        assertThat(
                        row(
                                "SELECT resource_type,personnel_no,capacity FROM mch_resource WHERE"
                                        + " resource_no=?",
                                person.resourceNo()))
                .containsEntry("resource_type", "STAFF")
                .containsEntry("personnel_no", person.personnelNo())
                .containsEntry("capacity", 1);
        return person;
    }

    private Scenario claimedArrival() {
        Object registered = register("main");
        String guest = (String) value(registered, "guestNo");
        String token = (String) value(registered, "claimToken");
        Object reserved = appointment(guest, original, "main");
        String appointment = (String) value(reserved, "appointmentNo");
        String hold = currentHold(appointment);
        assertThat(row("SELECT status FROM mch_booking_hold WHERE hold_no=?", hold))
                .containsEntry("status", "CONFIRMED");
        String history = appointmentHistory(appointment, hold);
        String currentReference =
                asManager(
                        () ->
                                identities
                                        .resolve(
                                                new MemberCodeScope(
                                                        "MAIN", entity, store, managerUser),
                                                identities.issue("MAIN", buyer).memberCode())
                                        .customerRef());
        Object claimRequest = dto("ClaimGuestReq", currentReference, token);
        Object claimed = claim(guest, claimRequest);
        assertThat(value(claimed, "userNo")).isEqualTo(buyer);
        assertThat(
                        row(
                                "SELECT"
                                    + " state,claimed_user_no,claim_actor_kind,claim_actor_no,version"
                                    + " FROM mbr_guest_registration WHERE guest_no=?",
                                guest))
                .containsEntry("state", "CLAIMED")
                .containsEntry("claimed_user_no", buyer)
                .containsEntry("claim_actor_kind", "USR")
                .containsEntry("claim_actor_no", managerUser)
                .containsEntry("version", 2L);
        // Arrival uses a new current merchant-scoped reference after the owner has committed claim.
        String arrivalReference =
                asManager(
                        () ->
                                identities
                                        .resolve(
                                                new MemberCodeScope(
                                                        "MAIN", entity, store, managerUser),
                                                identities.issue("MAIN", buyer).memberCode())
                                        .customerRef());
        Object arrived =
                asManager(
                        () ->
                                call(
                                        bean("ReceptionService"),
                                        "checkIn",
                                        entity,
                                        store,
                                        managerUser,
                                        dto(
                                                "CheckInReq",
                                                appointment,
                                                dto(
                                                        "ReceptionIdentityProof",
                                                        arrivalReference,
                                                        null,
                                                        null),
                                                value(reserved, "revision")),
                                        "arrival-" + sku));
        assertThat(value(arrived, "status")).isEqualTo("CHECKED_IN");
        long revision =
                number("SELECT version FROM bty_appointment WHERE appointment_no=?", appointment);
        return new Scenario(
                guest,
                appointment,
                hold,
                revision,
                claimRequest,
                digest(registered),
                digest(claimed),
                guestHistory(guest),
                history);
    }

    private Object register(String suffix) {
        return asManager(
                () ->
                        call(
                                bean("GuestRegistrationService"),
                                "registerGuest",
                                entity,
                                store,
                                managerUser,
                                dto(
                                        "RegisterGuestReq",
                                        "private-contact-" + suffix + sku,
                                        "guest",
                                        true),
                                "register-" + suffix + sku));
    }

    private Object claim(String guest, Object request) {
        return asManager(
                () ->
                        call(
                                bean("GuestRegistrationService"),
                                "claimGuest",
                                entity,
                                store,
                                managerUser,
                                guest,
                                request,
                                "claim-" + sku));
    }

    private Object appointment(String guest, PersonnelVO person, String suffix) {
        Object preview =
                asManager(
                        () ->
                                call(
                                        bean("AppointmentService"),
                                        "previewAppointment",
                                        entity,
                                        store,
                                        managerUser,
                                        dto(
                                                "PreviewAppointmentReq",
                                                store,
                                                null,
                                                List.of(dto("AppointmentItemReq", sku, 1)),
                                                assignments(person),
                                                start,
                                                guest)));
        Object created =
                asManager(
                        () ->
                                call(
                                        bean("AppointmentService"),
                                        "createAppointment",
                                        entity,
                                        store,
                                        managerUser,
                                        dto(
                                                "CreateAppointmentReq",
                                                value(preview, "previewNo"),
                                                null,
                                                List.of(),
                                                null,
                                                guest),
                                        "appointment-" + suffix + sku));
        assertThat(value(created, "status"))
                .as(
                        "Actual owner commands=%s; source intents=%s; operation=%s",
                        jdbc.queryForList(
                                "SELECT action,status,rejection_code,wait_reason FROM"
                                        + " mch_resource_command WHERE entity_no=? ORDER BY id",
                                entity),
                        jdbc.queryForList(
                                "SELECT action,phase_code,reason_code,terminal_code FROM"
                                    + " bty_appointment_resource_intent WHERE entity_no=? ORDER BY"
                                    + " id",
                                entity),
                        value(created, "operationState"))
                .isEqualTo("CONFIRMED");
        assertThat(value(created, "operationState")).isEqualTo("SUCCEEDED");
        return created;
    }

    private List<Object> assignments(PersonnelVO person) {
        return List.of(
                dto(
                        "AppointmentAssignmentReq",
                        0,
                        requirement,
                        person.resourceNo(),
                        person.personnelNo()));
    }

    private Object opening(Scenario scenario) {
        return dto(
                "OpenWorkOrderReq",
                null,
                store,
                List.of(dto("AppointmentItemReq", sku, 1)),
                scenario.appointment(),
                scenario.guest(),
                scenario.revision(),
                assignments(target),
                start);
    }

    private Object open(Object request) {
        return call(
                bean("WorkOrderService"),
                "openWorkOrder",
                entity,
                store,
                managerUser,
                request,
                "open-" + sku);
    }

    private void assertHistory(Scenario scenario) {
        assertThat(guestHistory(scenario.guest())).isEqualTo(scenario.guestHistory());
        assertThat(appointmentHistory(scenario.appointment(), scenario.hold()))
                .isEqualTo(scenario.appointmentHistory());
    }

    private String guestHistory(String guest) {
        return digest(
                List.of(
                        jdbc.queryForList(
                                "SELECT * FROM mbr_guest_registration WHERE guest_no=?", guest),
                        jdbc.queryForList(
                                "SELECT * FROM bty_guest_claim_receipt WHERE guest_no=?", guest),
                        jdbc.queryForList(
                                "SELECT * FROM bty_reception_operation WHERE entity_no=? AND"
                                        + " endpoint='BAPI-032'",
                                entity)));
    }

    private String appointmentHistory(String appointment, String hold) {
        var identity =
                row(
                        "SELECT customer_ref,guest_ref,source FROM bty_appointment WHERE"
                                + " appointment_no=?",
                        appointment);
        assertThat(identity.get("guest_ref")).isNotNull();
        assertThat(identity.get("customer_ref")).isNull();
        return digest(
                List.of(
                        identity,
                        jdbc.queryForList(
                                "SELECT * FROM bty_appointment_arrangement WHERE appointment_no=?"
                                        + " AND hold_no=?",
                                appointment,
                                hold),
                        jdbc.queryForList(
                                "SELECT * FROM bty_appointment_assignment WHERE entity_no=? AND"
                                        + " hold_no=? ORDER BY id",
                                entity,
                                hold)));
    }

    private String preservedResourceDigest(Scenario scenario) {
        // Source root/fence versions may advance through authorization and reconciliation; facts
        // stay intact.
        return digest(
                List.of(
                        jdbc.queryForList(
                                "SELECT * FROM mch_booking_hold WHERE hold_no=?", scenario.hold()),
                        jdbc.queryForList(
                                "SELECT * FROM mch_booking_hold_unit WHERE hold_no=? ORDER BY id",
                                scenario.hold()),
                        jdbc.queryForList(
                                "SELECT arrangement_revision FROM bty_appointment_current WHERE"
                                        + " appointment_no=?",
                                scenario.appointment()),
                        jdbc.queryForList(
                                "SELECT * FROM mch_resource_bucket WHERE entity_no=? AND"
                                        + " resource_no=? ORDER BY id",
                                entity,
                                original.resourceNo())));
    }

    private Map<String, Object> replacement(String appointment) {
        assertThat(replacementCount(appointment)).isEqualTo(1);
        return row(
                "SELECT c.* FROM mch_resource_command c JOIN mch_booking_owner o ON"
                    + " o.owner_no=c.owner_no AND o.entity_no=c.entity_no WHERE c.entity_no=? AND"
                    + " o.source_no=? AND c.action='REPLACE'",
                entity,
                appointment);
    }

    private long replacementCount(String appointment) {
        return number(
                "SELECT COUNT(*) FROM mch_resource_command c JOIN mch_booking_owner o ON"
                    + " o.owner_no=c.owner_no AND o.entity_no=c.entity_no WHERE c.entity_no=? AND"
                    + " o.source_no=? AND c.action='REPLACE'",
                entity,
                appointment);
    }

    private void reconcileCommand(Map<String, Object> command, String resource, int terminal) {
        String commandNo = (String) command.get("command_no");
        var intent =
                row("SELECT * FROM bty_appointment_resource_intent WHERE command_no=?", commandNo);
        String frozen = (String) intent.get("frozen_plan");
        assertThat(ResourcePlanContract.hash(frozen)).isEqualTo(intent.get("plan_hash"));
        assertThat(command.get("frozen_payload")).isEqualTo(frozen);
        assertThat(ResourcePlanContract.decode(frozen).demands())
                .hasSize(1)
                .allSatisfy(demand -> assertThat(demand.resourceNo()).isEqualTo(resource));
        assertThat(intent).containsEntry("phase_code", 3).containsEntry("terminal_code", terminal);
        assertThat(intent.get("source_revision")).isEqualTo(command.get("source_revision"));
        assertThat(
                        number(
                                "SELECT COUNT(*) FROM bty_appointment_resource_decision WHERE"
                                        + " command_no=? AND decision_no=?",
                                commandNo,
                                intent.get("decision_no")))
                .isEqualTo(1);
        assertThat(
                        number(
                                "SELECT COUNT(*) FROM bty_appointment_resource_fence WHERE"
                                        + " appointment_no=? AND active_intent_no IS NULL",
                                intent.get("appointment_no")))
                .isEqualTo(1);
    }

    private String currentHold(String appointment) {
        return (String)
                row(
                                "SELECT current_hold_no FROM mch_booking_owner WHERE entity_no=?"
                                        + " AND source_no=?",
                                entity,
                                appointment)
                        .get("current_hold_no");
    }

    private List<String> currentResources(String appointment) {
        return jdbc.queryForList(
                "SELECT DISTINCT a.resource_no FROM bty_appointment_assignment a JOIN"
                    + " bty_appointment_item i ON i.appointment_item_no=a.appointment_item_no AND"
                    + " i.entity_no=a.entity_no JOIN bty_appointment_current c ON"
                    + " c.appointment_no=i.appointment_no AND c.entity_no=i.entity_no AND"
                    + " c.arrangement_revision=a.appointment_revision WHERE c.appointment_no=?",
                String.class,
                appointment);
    }

    private long activeUnits(String hold) {
        return number(
                "SELECT COUNT(*) FROM mch_booking_hold_unit WHERE hold_no=? AND released_at IS"
                        + " NULL",
                hold);
    }

    private long capacity(String resource) {
        return number(
                "SELECT COALESCE(SUM(held_qty+confirmed_qty),0) FROM mch_resource_bucket"
                        + " WHERE entity_no=? AND resource_no=?",
                entity,
                resource);
    }

    private String effectDigest() {
        return digest(
                List.of(
                        ownedCount("bty_work_order"),
                        ownedCount("bty_service_task"),
                        ownedCount("mch_resource_command"),
                        ownedCount("bty_reception_operation"),
                        jdbc.queryForList(
                                "SELECT * FROM mch_booking_owner WHERE entity_no=? ORDER BY id",
                                entity),
                        jdbc.queryForList(
                                "SELECT * FROM mch_booking_hold WHERE entity_no=? ORDER BY id",
                                entity),
                        jdbc.queryForList(
                                "SELECT * FROM mch_booking_hold_unit WHERE entity_no=? ORDER BY id",
                                entity)));
    }

    private long ownedCount(String table) {
        return number("SELECT COUNT(*) FROM " + table + " WHERE entity_no=?", entity);
    }

    private long number(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private Map<String, Object> row(String sql, Object... arguments) {
        return jdbc.queryForMap(sql, arguments);
    }

    private String permissions(boolean manage) {
        return json.writeValueAsString(
                manage
                        ? List.of(BizPerms.BOOKING_VIEW, BizPerms.BOOKING_MANAGE, BizPerms.CUSTOMER)
                        : List.of(BizPerms.BOOKING_VIEW, BizPerms.CUSTOMER));
    }

    private boolean allowed(String permission) {
        return asManager(
                () ->
                        operators.allowed(
                                new MerchantOperatorAccessPort.Query(
                                        "MAIN",
                                        entity,
                                        store,
                                        managerUser,
                                        "MERCHANT",
                                        "USR",
                                        permission)));
    }

    private <T> T asManager(Supplier<T> action) {
        return DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "MERCHANT", "USR", managerUser), action);
    }

    private String digest(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            json.writeValueAsString(value)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(BooleanSupplier ready, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(ready.getAsBoolean()).as(description).isTrue();
    }

    private Object bean(String name) {
        return context.getBean(type("service." + name));
    }

    private Class<?> type(String name) {
        try {
            return Class.forName("ai.neargo.shop.beauty." + name);
        } catch (ClassNotFoundException absent) {
            throw new AssertionError(absent);
        }
    }

    private Object dto(String name, Object... arguments) {
        try {
            return Arrays.stream(type("dto." + name).getConstructors())
                    .filter(candidate -> candidate.getParameterCount() == arguments.length)
                    .findFirst()
                    .orElseThrow()
                    .newInstance(arguments);
        } catch (InvocationTargetException failure) {
            throw cause(failure);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private Object value(Object record, String name) {
        return call(record, name);
    }

    private Object call(Object receiver, String name, Object... arguments) {
        try {
            return Arrays.stream(receiver.getClass().getMethods())
                    .filter(
                            candidate ->
                                    candidate.getName().equals(name)
                                            && candidate.getParameterCount() == arguments.length)
                    .findFirst()
                    .orElseThrow()
                    .invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            throw cause(failure);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private RuntimeException cause(InvocationTargetException failure) {
        if (failure.getCause() instanceof RuntimeException runtime) return runtime;
        if (failure.getCause() instanceof Error error) throw error;
        return new IllegalStateException(failure.getCause());
    }

    private record Scenario(
            String guest,
            String appointment,
            String hold,
            long revision,
            Object claimRequest,
            String registrationDigest,
            String claimDigest,
            String guestHistory,
            String appointmentHistory) {}
}
