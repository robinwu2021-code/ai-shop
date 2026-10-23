package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.member.config.MemberGuestCrypto;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.mapper.MemberGuestRegistrationMapper;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.member.service.MemberGuestRegistrationService;
import ai.neargo.shop.member.service.impl.MemberGuestRegistrationServiceImpl;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import java.lang.reflect.InvocationTargetException;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.ObjectMapper;

/** Native current platform fixture + official V537/V538 + beauty V1–V6; real mc1/cr1 and guest owner.
 * Reflection keeps default retail compilation independent of the optional beauty module.
 * The only fault injection drops a response after the real owner returns from its committed transaction. */
@SpringBootTest(properties = {"shop.member-code.enabled=true", "shop.beauty.enabled=true",
        "shop.beauty.deployment=embedded", "shop.beauty.migration.mode=migrate",
        "shop.beauty.publication.archive-only=true",
        "shop.member.guest.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "beauty.reception.test.native", matches = "true")
@Import(BeautyReceptionIdentityIT.FaultConfiguration.class)
class BeautyReceptionIdentityIT {
    @Autowired JdbcTemplate sql;
    @Autowired ApplicationContext context;
    @Autowired MemberCodeResolveService codes;
    @Autowired MemberGuestRegistrationService owner;
    @Autowired FaultPort faults;
    @Autowired ArrivalHoldFixture holds;
    @Autowired ObjectMapper json;
    @Autowired CustomerIdentityPort identities;
    @Autowired MemberGuestRegistrationMapper mapper;
    @Autowired MerchantOperatorAccessPort access;
    @Autowired MerchantScopeFactsPort stores;
    @Autowired UserScopeFactsPort users;
    @Autowired @Qualifier("transactionManager") PlatformTransactionManager transactions;
    private String entity, store, actor, manager, user;
    private Object service;
    @BeforeEach void fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        entity = "RE-" + suffix; store = "RS-" + suffix; actor = "RA-" + suffix;
        manager = "RM-" + suffix; user = "RU-" + suffix;
        sql.update("INSERT INTO mch_entity(entity_no,name,status,created_at,updated_at) "
                + "VALUES (?,'reception','ACTIVE',NOW(),NOW())", entity);
        sql.update("INSERT INTO mch_store(entity_no,store_no,name,status,created_at,updated_at) "
                + "VALUES (?,?,'reception','ACTIVE',NOW(),NOW())", entity, store);
        for (String account : List.of(actor, manager)) {
            sql.update("INSERT INTO mch_account(entity_no,mch_account_no,user_no,is_owner,status,created_at,updated_at) "
                    + "VALUES (?,?,?,1,'ACTIVE',NOW(),NOW())", entity, account, account);
        }
        sql.update("INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at) "
                + "VALUES (?,'reception customer','NORMAL',NOW(),NOW())", user);
        service = context.getBean(type("service.GuestRegistrationService"));
        faults.delegate = owner; faults.dropRegister = false; faults.dropClaim = false;
        faults.claimCalls = 0; faults.getCalls = 0;
    }
    @Test void registrationUnknownHasNoLocalOracleAndOnlyOriginalActorReplaysOwnerKey() {
        faults.dropRegister = true;
        Object pending = as(actor, () -> register("private-contact", "original"));
        assertThat(value(pending, "operationState")).isEqualTo("PENDING");
        assertThat(value(pending, "operationNo")).isNull();
        assertThat(value(pending, "recoveryAction")).isEqualTo("REQUIRES_ORIGINAL_ACTOR");
        assertThat(count("bty_reception_operation")).isZero();
        Object recovered = as(actor, () -> register("private-contact", "original"));
        assertThat(value(recovered, "operationState")).isEqualTo("SUCCEEDED");
        assertThat(count("mbr_guest_registration")).isOne();
        rejected(() -> as(actor, () -> register("changed-contact", "original")), ErrorCode.CONFLICT);
        Object second = as(manager, () -> register("private-contact", "original"));
        assertThat(value(second, "guestNo")).isNotEqualTo(value(recovered, "guestNo"));
        assertThat(count("mbr_guest_registration")).isEqualTo(2);
        assertThat(count("bty_reception_operation")).isZero();
        sql.update("UPDATE mch_account SET status='SUSPENDED' WHERE mch_account_no=?", actor);
        rejected(() -> as(actor, () -> register("private-contact", "original")), ErrorCode.FORBIDDEN);
    }
    @Test void realClaimUnknownRecoversImmutableReceiptUnderCurrentManagerWithoutOldProofOrImpersonation() {
        Object registration = as(actor, () -> register("contact", "registration"));
        String guest = (String) value(registration, "guestNo");
        String token = (String) value(registration, "claimToken");
        String ref = reference(actor);
        faults.dropClaim = true;
        Object pending = as(actor, () -> call(service, "claimGuest", entity, store, actor, guest,
                dto("ClaimGuestReq", ref, token), "claim"));
        String operation = (String) value(pending, "operationNo");
        assertThat(value(pending, "operationState")).isEqualTo("PENDING");
        assertThat(count("bty_guest_claim_receipt")).isZero();
        assertThat(sql.queryForObject("SELECT frozen_payload FROM bty_reception_operation WHERE entity_no=?",
                String.class, entity)).contains(user, guest).doesNotContain(ref, token, "contact");
        sql.update("UPDATE mch_account SET status='SUSPENDED' WHERE mch_account_no=?", actor);
        sql.update("UPDATE mbr_identity_ticket SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND), "
                + "issued_at=DATE_SUB(NOW(),INTERVAL 3 MINUTE) WHERE user_no=?", user);
        Object result = as(manager, () -> call(service, "recoverClaim", entity, store, manager, operation));
        assertThat(value(result, "userNo")).isEqualTo(user);
        assertThat(value(result, "operationState")).isEqualTo("SUCCEEDED");
        assertThat(faults.lastActor).isEqualTo(manager); assertThat(faults.claimCalls).isOne();
        assertThat(count("bty_guest_claim_receipt")).isOne();
        assertThat(as(manager, () -> call(service, "recoverClaim", entity, store, manager, operation)))
                .isEqualTo(result);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id=?", Integer.class, guest))
                .isOne();
        sql.update("UPDATE mch_account SET status='SUSPENDED' WHERE mch_account_no=?", manager);
        rejected(() -> as(manager, () -> call(service, "recoverClaim", entity, store, manager, operation)),
                ErrorCode.FORBIDDEN);
    }
    @Test void realCurrentReferenceScopeAndMissingOwnerCapabilityStayFailClosed() {
        Object registration = as(actor, () -> register("contact", "registration"));
        String guest = (String) value(registration, "guestNo"), token = (String) value(registration, "claimToken");
        String wrongActorRef = reference(actor);
        rejected(() -> as(manager, () -> call(service, "claimGuest", entity, store, manager, guest,
                dto("ClaimGuestReq", wrongActorRef, token), "claim")), ErrorCode.NOT_FOUND);
        String current = reference(manager);
        Object claimed = as(manager, () -> call(service, "claimGuest", entity, store, manager, guest,
                dto("ClaimGuestReq", current, token), "claim"));
        assertThat(value(claimed, "userNo")).isEqualTo(user);
        faults.delegate = new MemberGuestRegistrationServiceImpl(mapper, access, stores, codes,
                new MemberGuestCrypto(""), users, transactions);
        rejected(() -> as(manager, () -> register("contact", "unavailable")), ErrorCode.MEMBER_GUEST_UNAVAILABLE);
        assertThat(count("mbr_guest_registration")).isOne();
        assertThat(count("bty_reception_operation")).isOne();
    }
    @ParameterizedTest
    @CsvSource({"MCH,USR,false", "USR,MCH,false", "MCH,USR,true", "USR,MCH,true"})
    void sameActorNumberDifferentKindCannotCreateClaimIntent(String originalKind, String currentKind,
            boolean alreadyClaimed) {
        Object registration = as(actor, () -> register("contact", "registration"));
        String guest = (String) value(registration, "guestNo"), token = (String) value(registration, "claimToken");
        var original = new DelegatedIdentity("MAIN", "MERCHANT", originalKind, actor);
        var current = new DelegatedIdentity("MAIN", "MERCHANT", currentKind, actor);
        for (var identity : List.of(original, current)) {
            assertThat(DelegatedIdentityContext.call(identity, () -> access.allowed(
                    new MerchantOperatorAccessPort.Query("MAIN", entity, store, actor, "MERCHANT",
                            identity.subjectKind(), "biz:customer")))).isTrue();
        }
        String code = codes.issue("MAIN", user).memberCode();
        String ref = DelegatedIdentityContext.call(original, () -> codes.resolve(
                new MemberCodeScope("MAIN", entity, store, actor), code).customerRef());
        var identityContext = new EntitlementContext("MAIN", actor, "strict-test", "c".repeat(64));
        var query = new CustomerIdentityPort.ReferenceQuery(entity, store, ref);
        assertThat(DelegatedIdentityContext.call(current, () -> identities.resolveReference(identityContext, query))
                .userNo()).isEqualTo(user);
        if (alreadyClaimed) {
            DelegatedIdentityContext.call(original, () -> owner.claim(new GuestRegistrationPort.Scope(entity, store,
                    original), guest, new GuestRegistrationPort.ClaimCommand(ref, token, "owner-fixture")));
        }
        rejected(() -> DelegatedIdentityContext.call(current, () -> call(service, "claimGuest", entity, store,
                actor, guest, dto("ClaimGuestReq", ref, token), "new-claim")), ErrorCode.NOT_FOUND);
        assertThat(count("bty_reception_operation")).isZero();
        assertThat(count("bty_guest_claim_receipt")).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id=?", Integer.class, guest))
                .isZero();
    }
    @Test void strictOwnerCapabilityChecksCurrentCustomerGrantAndContextBeforeTicket() {
        String ref = reference(actor);
        var c = new EntitlementContext("MAIN", actor, "strict", "b".repeat(64));
        var query = new CustomerIdentityPort.ReferenceQuery(entity, store, ref);
        assertThat(as(actor, () -> identities.resolveClaimReference(c, query)).userNo()).isEqualTo(user);
        rejected(() -> as(manager, () -> identities.resolveClaimReference(c, query)), ErrorCode.FORBIDDEN);
        rejected(() -> as(actor, () -> identities.resolveClaimReference(c,
                new CustomerIdentityPort.ReferenceQuery(entity, "FOREIGN", ref))), ErrorCode.FORBIDDEN);
        sql.update("UPDATE mch_account SET status='SUSPENDED' WHERE mch_account_no=?", actor);
        rejected(() -> as(actor, () -> identities.resolveClaimReference(c, query)), ErrorCode.FORBIDDEN);
    }
    @Test void receptionCheckInConsumesStrictCurrentReferenceAndRealGuestToken() {
        Object reception = context.getBean(type("service.ReceptionService"));
        String appointment = arrivalRoot(user, null);
        String ref = reference(actor);
        Object req = dto("CheckInReq", appointment, dto("ReceptionIdentityProof", ref, null, null), 7L);
        rejected(() -> as(manager, () -> call(reception, "checkIn", entity, store, manager, req, "ARRIVAL")),
                ErrorCode.NOT_FOUND);
        var merchantUser = new DelegatedIdentity("MAIN", "MERCHANT", "USR", actor);
        rejected(() -> DelegatedIdentityContext.call(merchantUser,
                () -> call(reception, "checkIn", entity, store, actor, req, "ARRIVAL")), ErrorCode.NOT_FOUND);
        assertThat(count("bty_reception_operation")).isZero();
        Object arrived = as(actor, () -> call(reception, "checkIn", entity, store, actor, req, "ARRIVAL"));
        assertThat(value(arrived, "status")).isEqualTo("CHECKED_IN");
        Object registration = as(actor, () -> register("arrival-contact", "guest-registration"));
        String guest = (String) value(registration, "guestNo");
        String guestAppointment = arrivalRoot(null, guest);
        Object forged = dto("CheckInReq", guestAppointment,
                dto("ReceptionIdentityProof", null, guest, "x".repeat(43)), 7L);
        rejected(() -> as(actor, () -> call(reception, "checkIn", entity, store, actor, forged, "GUEST")),
                ErrorCode.NOT_FOUND);
        Object proof = dto("CheckInReq", guestAppointment,
                dto("ReceptionIdentityProof", null, guest, value(registration, "claimToken")), 7L);
        assertThat(value(as(actor, () -> call(reception, "checkIn", entity, store, actor, proof, "GUEST")), "status"))
                .isEqualTo("CHECKED_IN");
        assertThat(count("bty_work_order")).isZero(); assertThat(count("bty_service_task")).isZero();
        sql.update("UPDATE mch_account SET status='SUSPENDED' WHERE mch_account_no=?", actor);
        rejected(() -> as(actor, () -> call(reception, "checkIn", entity, store, actor, req, "ARRIVAL")),
                ErrorCode.FORBIDDEN);
    }
    @Test void consumerArrivalConsumesRealOwnMc1AndLiveStoreChallenge() {
        Object reception = context.getBean(type("service.ReceptionService"));
        String appointment = arrivalRoot(user, null);
        Object challenge = as(actor, () -> call(reception, "issueArrivalChallenge", entity, store, actor, "CODE"));
        String code = codes.issue("MAIN", user).memberCode();
        var consumer = new DelegatedIdentity("MAIN", "CONSUMER", "USR", user);
        Object forged = dto("MyCheckInReq", appointment, dto("CheckInProof", "mc1_" + "x".repeat(43),
                value(challenge, "code")), 7L);
        rejected(() -> DelegatedIdentityContext.call(consumer,
                () -> call(reception, "checkInMyAppointment", user, forged, "MY")), ErrorCode.MEMBER_CODE_INVALID);
        String other = "OTHER-" + UUID.randomUUID().toString().substring(0, 8);
        sql.update("INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at) "
                + "VALUES (?,'other','NORMAL',NOW(),NOW())", other);
        Object wrongOwner = dto("MyCheckInReq", appointment,
                dto("CheckInProof", codes.issue("MAIN", other).memberCode(), value(challenge, "code")), 7L);
        rejected(() -> DelegatedIdentityContext.call(consumer,
                () -> call(reception, "checkInMyAppointment", user, wrongOwner, "MY")), ErrorCode.MEMBER_CODE_INVALID);
        Object req = dto("MyCheckInReq", appointment, dto("CheckInProof", code, value(challenge, "code")), 7L);
        rejected(() -> as(actor, () -> call(reception, "checkInMyAppointment", user, req, "MY")), ErrorCode.FORBIDDEN);
        Object arrived = DelegatedIdentityContext.call(consumer,
                () -> call(reception, "checkInMyAppointment", user, req, "MY"));
        assertThat(value(arrived, "status")).isEqualTo("CHECKED_IN");
        assertThat(value(arrived, "timing")).isEqualTo("EARLY");
        assertThat(count("bty_reception_operation")).isOne();
        assertThat(count("bty_work_order")).isZero(); assertThat(count("bty_service_task")).isZero();
    }
    @Test void workOrderOpeningUsesRealCustomerAndGuestFacts() {
        Object reception = context.getBean(type("service.ReceptionService"));
        Object orders = context.getBean(type("service.WorkOrderService"));
        String appointment = arrivalRoot(user, null);
        workOrderFacts(appointment);
        String ref = reference(actor);
        Object arrived = as(actor, () -> call(reception, "checkIn", entity, store, actor,
                dto("CheckInReq", appointment, dto("ReceptionIdentityProof", ref, null, null), 7L), "ARRIVE"));
        Object forged = dto("OpenWorkOrderReq", "cr1_" + "x".repeat(43), store, List.of(), appointment, null,
                value(arrived, "revision"), List.of(), null);
        rejected(() -> as(actor, () -> call(orders, "openWorkOrder", entity, store, actor, forged, "OPEN")),
                ErrorCode.NOT_FOUND);
        Object req = dto("OpenWorkOrderReq", ref, store, List.of(), appointment, null,
                value(arrived, "revision"), List.of(), null);
        Object opened = as(actor, () -> call(orders, "openWorkOrder", entity, store, actor, req, "OPEN"));
        String number = (String) value(opened, "workOrderNo");
        assertThat(value(opened, "status")).isEqualTo("OPEN");
        assertThat(sql.queryForObject("SELECT customer_ref FROM bty_work_order WHERE work_order_no=?", String.class,
                number)).isEqualTo("USER:" + user);
        sql.update("UPDATE mbr_identity_ticket SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND), "
                + "issued_at=DATE_SUB(NOW(),INTERVAL 3 MINUTE) WHERE user_no=?", user);
        assertThat(as(actor, () -> call(orders, "openWorkOrder", entity, store, actor, req, "OPEN"))).isEqualTo(opened);
        String expiredAppointment = arrivalRoot(user, null); workOrderItem(expiredAppointment);
        sql.update("UPDATE bty_appointment SET status='CHECKED_IN',checked_in_at=NOW(),version=8 WHERE appointment_no=?",
                expiredAppointment);
        Object expiredNew = dto("OpenWorkOrderReq", ref, store, List.of(), expiredAppointment, null,
                8L, List.of(), null);
        rejected(() -> as(actor, () -> call(orders, "openWorkOrder", entity, store, actor, expiredNew, "NEW")),
                ErrorCode.NOT_FOUND);
        var consumer = new DelegatedIdentity("MAIN", "CONSUMER", "USR", user);
        assertThat(DelegatedIdentityContext.call(consumer, () -> call(orders, "getMyWorkOrder", user, number)))
                .isNotNull();
        Object registration = as(actor, () -> register("scope-contact", "scope-guest"));
        String guest = (String) value(registration, "guestNo");
        String guestAppointment = arrivalRoot(null, guest); workOrderItem(guestAppointment);
        Object guestArrived = as(actor, () -> call(reception, "checkIn", entity, store, actor,
                dto("CheckInReq", guestAppointment, dto("ReceptionIdentityProof", null, guest,
                        value(registration, "claimToken")), 7L), "GUEST-ARRIVE"));
        Object legacyGuest = dto("OpenWorkOrderReq", null, store, List.of(), guestAppointment, "BTG_LEGACY",
                value(guestArrived, "revision"), List.of(), null);
        rejected(() -> as(actor, () -> call(orders, "openWorkOrder", entity, store, actor, legacyGuest, "LEGACY")),
                ErrorCode.NOT_FOUND);
        Object guestReq = dto("OpenWorkOrderReq", null, store, List.of(), guestAppointment, guest,
                value(guestArrived, "revision"), List.of(), null);
        Object guestOpened = as(actor, () -> call(orders, "openWorkOrder", entity, store, actor, guestReq,
                "GUEST-OPEN"));
        String guestOrder = (String) value(guestOpened, "workOrderNo");
        rejected(() -> DelegatedIdentityContext.call(consumer,
                () -> call(orders, "getMyWorkOrder", user, guestOrder)), ErrorCode.NOT_FOUND);
        String claimRef = reference(actor);
        as(actor, () -> call(service, "claimGuest", entity, store, actor, guest,
                dto("ClaimGuestReq", claimRef, value(registration, "claimToken")), "SCOPE-CLAIM"));
        assertThat(DelegatedIdentityContext.call(consumer,
                () -> call(orders, "getMyWorkOrder", user, guestOrder))).isNotNull();
        // A CLAIMED guest fact normalizes to USER when checked against an existing USER appointment scope.
        String claimedAppointment = arrivalRoot(user, null); workOrderItem(claimedAppointment);
        Object claimedArrived = as(actor, () -> call(reception, "checkIn", entity, store, actor,
                dto("CheckInReq", claimedAppointment, dto("ReceptionIdentityProof", claimRef, null, null), 7L),
                        "CLAIMED-ARRIVE"));
        Object claimedReq = dto("OpenWorkOrderReq", null, store, List.of(), claimedAppointment, guest,
                value(claimedArrived, "revision"), List.of(), null);
        Object claimedOrder = as(actor, () -> call(orders, "openWorkOrder", entity, store, actor, claimedReq,
                "CLAIMED-OPEN"));
        assertThat(sql.queryForObject("SELECT customer_ref FROM bty_work_order WHERE work_order_no=?", String.class,
                value(claimedOrder, "workOrderNo"))).isEqualTo("USER:" + user);
        sql.update("UPDATE mch_account SET status='SUSPENDED' WHERE mch_account_no=?", actor);
        rejected(() -> as(actor, () -> call(orders, "openWorkOrder", entity, store, actor, req, "OPEN")),
                ErrorCode.FORBIDDEN);
        assertThat(count("bty_work_order")).isEqualTo(3);
    }
    @ParameterizedTest @CsvSource({"false", "true"})
    void omittedGuestIdentityUsesCurrentOwnerAndScopedReceipt(boolean full) {
        Object registration = as(actor, () -> register("guest-owner", "OWNER"));
        String guest = (String) value(registration, "guestNo");
        String first = arrivalRoot(null, guest); workOrderFacts(first);
        Object arrived = guestArrival(first, registration, "FIRST");
        int before = faults.getCalls;
        Object opened = omittedGuestOpen(full, first, value(arrived, "revision"), "FIRST-OPEN");
        assertThat(faults.getCalls).isEqualTo(before + 1);
        assertThat(sql.queryForObject("SELECT customer_ref FROM bty_work_order WHERE work_order_no=?", String.class,
                value(opened, "workOrderNo"))).isEqualTo("GUEST:" + guest);
        String bridged = arrivalRoot(null, guest); workOrderItem(bridged);
        Object bridgedArrival = guestArrival(bridged, registration, "BRIDGED");
        String foreignReceipt = arrivalRoot(null, guest); workOrderItem(foreignReceipt);
        Object foreignArrival = guestArrival(foreignReceipt, registration, "FOREIGN-RECEIPT");
        String ref = reference(actor);
        as(actor, () -> call(service, "claimGuest", entity, store, actor, guest,
                dto("ClaimGuestReq", ref, value(registration, "claimToken")), "CLAIM"));
        before = faults.getCalls;
        assertThat(omittedGuestOpen(full, first, value(arrived, "revision"), "FIRST-OPEN")).isEqualTo(opened);
        assertThat(faults.getCalls).isEqualTo(before);
        Object normalized = omittedGuestOpen(full, bridged, value(bridgedArrival, "revision"), "BRIDGED-OPEN");
        assertThat(sql.queryForObject("SELECT customer_ref FROM bty_work_order WHERE work_order_no=?", String.class,
                value(normalized, "workOrderNo"))).isEqualTo("USER:" + user);
        assertThat(sql.queryForObject("SELECT guest_ref FROM bty_appointment WHERE appointment_no=?", String.class,
                bridged)).isEqualTo(guest);
        assertThat(sql.queryForObject("SELECT customer_ref FROM bty_appointment WHERE appointment_no=?", String.class,
                bridged)).isNull();
        sql.update("UPDATE bty_guest_claim_receipt SET store_no='FOREIGN' WHERE guest_no=? AND entity_no=?",
                guest, entity);
        rejected(() -> omittedGuestOpen(full, foreignReceipt, value(foreignArrival, "revision"), "FOREIGN-OPEN"),
                ErrorCode.CONFLICT);
        sql.update("UPDATE bty_guest_claim_receipt SET store_no=? WHERE guest_no=? AND entity_no=?",
                store, guest, entity);
        Object wrong = as(actor, () -> register("wrong-scope", "WRONG"));
        String wrongGuest = (String) value(wrong, "guestNo");
        String wrongRoot = arrivalRoot(null, wrongGuest); workOrderItem(wrongRoot);
        Object wrongArrival = guestArrival(wrongRoot, wrong, "WRONG-ARRIVE");
        sql.update("UPDATE mbr_guest_registration SET store_no='FOREIGN' WHERE guest_no=? AND entity_no=?",
                wrongGuest, entity);
        rejected(() -> omittedGuestOpen(full, wrongRoot, value(wrongArrival, "revision"), "WRONG-OPEN"),
                ErrorCode.NOT_FOUND);
        sql.update("UPDATE mbr_guest_registration SET store_no=? WHERE guest_no=? AND entity_no=?",
                store, wrongGuest, entity);
        Object unbridged = as(actor, () -> register("no-local-receipt", "UNBRIDGED"));
        String noReceiptGuest = (String) value(unbridged, "guestNo");
        String noReceiptRoot = arrivalRoot(null, noReceiptGuest); workOrderItem(noReceiptRoot);
        Object noReceiptArrival = guestArrival(noReceiptRoot, unbridged, "NO-RECEIPT-ARRIVE");
        var actorIdentity = new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor);
        as(actor, () -> owner.claim(new GuestRegistrationPort.Scope(entity, store, actorIdentity), noReceiptGuest,
                new GuestRegistrationPort.ClaimCommand(reference(actor), (String) value(unbridged, "claimToken"),
                        "DIRECT-OWNER-CLAIM")));
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_guest_claim_receipt WHERE guest_no=?", Integer.class,
                noReceiptGuest)).isZero();
        rejected(() -> omittedGuestOpen(full, noReceiptRoot, value(noReceiptArrival, "revision"), "NO-RECEIPT-OPEN"),
                ErrorCode.CONFLICT);
        assertThat(count("bty_work_order")).isEqualTo(2);
        assertThat(count("bty_service_submission")).isEqualTo(2);
    }
    private Object guestArrival(String appointment, Object registration, String key) {
        return as(actor, () -> call(context.getBean(type("service.ReceptionService")), "checkIn", entity, store, actor,
                dto("CheckInReq", appointment, dto("ReceptionIdentityProof", null, value(registration, "guestNo"),
                        value(registration, "claimToken")), 7L), key));
    }
    private Object omittedGuestOpen(boolean full, String appointment, Object revision, String key) {
        return as(actor, () -> full
                ? call(context.getBean(type("service.WorkOrderService")), "openWorkOrder", entity, store, actor,
                        dto("OpenWorkOrderReq", null, store, List.of(), appointment, null, revision,
                                List.of(), null), key)
                : call(context.getBean(type("service.AppointmentService")), "openWorkOrderFromAppointment",
                        entity, store,
                        actor, appointment, dto("OpenAppointmentWorkOrderReq", revision, List.of()), key));
    }
    private void workOrderFacts(String appointment) {
        var effective = LocalDateTime.of(2025, 1, 1, 0, 0);
        String cancel = "{\"freeCancelBeforeMinutes\":60,\"allowCustomerCancel\":true}";
        row("bty_store_policy", "policy_no", "P", "store_no", store, "current_policy_version_no", "PV", "status",
                "ACTIVE");
        row("bty_store_policy_version", "policy_version_no", "PV", "policy_no", "P", "revision_index", 1,
                "booking_ahead_days", 30, "cancel_rule", cancel, "allowed_modes", "[\"PREPAID_SINGLE\"]",
                "effective_at", effective, "schema_version", 1, "snapshot_hash", IdempotencyScope.requestHash(
                        effective.atOffset(ZoneOffset.UTC).toString(), "30", "60", "true", "PREPAID_SINGLE"));
        row("bty_service_definition", "definition_no", "D", "store_no", store, "sku_no", "SKU",
                "current_definition_version_no", "DV", "status", "ACTIVE");
        Object duration = dto("PublishServiceDefinitionVersionReq$Duration", 20, 0, 0);
        Object requirement = dto("PublishServiceDefinitionVersionReq$Requirement", "MAIN", "STAFF", "G", 1, 0, 0, 20,
                "ANY_QUALIFIED");
        row("bty_service_definition_version", "definition_no", "D", "definition_version_no", "DV", "revision_index", 1,
                "duration_min_snapshot", 20, "buffer_before_min", 0, "buffer_after_min", 0, "steps_template",
                "[{\"stepCode\":\"SERVICE\",\"sequenceNo\":0}]", "notes", "[]", "schema_version", 1,
                "snapshot_hash", IdempotencyScope.requestHash("SKU", json.writeValueAsString(duration),
                        json.writeValueAsString(List.of(requirement))));
        row("bty_service_requirement", "requirement_no", "REQ", "definition_version_no", "DV", "role_code", "MAIN",
                "resource_type", "STAFF", "skill_goods_no", "G", "qty", 1, "sequence_no", 0, "offset_min", 0,
                "duration_min", 20, "selection_rule", "ANY_QUALIFIED");
        workOrderItem(appointment);
    }
    private void workOrderItem(String appointment) {
        row("bty_appointment_item", "appointment_item_no", "BL-" + appointment, "appointment_no", appointment,
                "sku_no", "SKU", "definition_version_no", "DV", "qty", 1, "uom", "COUNT", "sequence_no", 0,
                "execution_snapshot", "{}");
    }
    /** Only resource admission is fixture evidence here; member tickets, grants and beauty writes are real. */
    private String arrivalRoot(String customer, String guest) {
        String number = "BA-" + UUID.randomUUID().toString().substring(0, 8);
        String preview = "BP-" + number, holdNo = "BH-" + number, line = "BL-" + number;
        var start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2).truncatedTo(ChronoUnit.SECONDS);
        var end = start.plusMinutes(20);
        String group = line + ":REQ:0";
        String plan = ResourcePlanContract.encode(List.of(new ResourcePlanContract.Demand("R", group, 1,
                List.of(new ResourcePlanContract.Interval(start.toInstant(), end.toInstant())))),
                List.of(new ResourcePlanContract.Qualification("R", group, "G", "SKU", true)));
        String snapshot = json.writeValueAsString(Map.of("items", List.of(dto("AppointmentPlanItemVO", line, "SKU",
                "G", "DV", 1, 1000L, 20, start, end, 1L, 1L)), "assignments", List.of(dto("AppointmentAssignmentVO",
                line, "REQ", "P", "R", start, end)), "priceMinor", 1000L,
                "cancelRule", Map.of("freeCancelBeforeMinutes", 60, "allowCustomerCancel", true)));
        var binding = new ResourceSourceAuthorityPort.Binding("MAIN", entity, store, "BC-" + number,
                "BEAUTY_APPOINTMENT", number, 4, "CONFIRM", "d".repeat(64), ResourcePlanContract.hash(plan));
        var hold = new ResourceBookingPort.Hold(holdNo, "CONFIRMED", 2, 1, 4, 2, binding.planHash());
        holds.holds.put(holdNo, hold);
        var receipt = new ResourceBookingPort.Receipt(binding, "OWNER", "SUCCEEDED", 1, 1,
                new ResourceBookingPort.Target(holdNo, 1, 1), plan,
                json.writeValueAsString(Map.of("holdNo", holdNo, "status", "CONFIRMED", "ownerRevision", 2)), null);
        row("bty_appointment", "appointment_no", number, "store_no", store, "customer_ref", customer,
                "guest_ref", guest, "source", "STAFF", "status", "CONFIRMED", "start_at", start.toLocalDateTime(),
                "end_at", end.toLocalDateTime(), "timezone", "UTC", "policy_version_no", "PV", "hold_no", holdNo,
                "version", 7);
        row("bty_appointment_preparation", "preview_no", preview, "store_no", store, "actor_no", actor,
                "actor_realm", "MERCHANT", "actor_kind", "MCH", "expected_revision", 0, "policy_version_no", "PV",
                "start_at", start.toLocalDateTime(), "end_at", end.toLocalDateTime(), "timezone", "UTC",
                "frozen_plan", plan, "plan_hash", binding.planHash(), "snapshot", snapshot,
                "expires_at", end.toLocalDateTime());
        row("bty_appointment_current", "appointment_no", number, "arrangement_revision", 1);
        row("bty_appointment_resource_fence", "appointment_no", number, "store_no", store, "fence_no", "BF-" + number);
        row("bty_appointment_arrangement", "appointment_no", number, "arrangement_revision", 1,
                "preview_no", preview, "hold_no", holdNo, "snapshot", snapshot);
        row("bty_appointment_operation", "operation_no", "BO-" + number, "store_no", store, "actor_no", actor,
                "actor_realm", "MERCHANT", "actor_kind", "MCH", "key_hash", IdempotencyScope.requestHash(number),
                "request_hash", "a".repeat(64), "appointment_no", number, "preview_no", preview, "action", "CREATE",
                "state", "SUCCEEDED", "accept_payload", "{}", "receipt", json.writeValueAsString(receipt),
                "next_retry_at", start.toLocalDateTime());
        return number;
    }
    private void row(String table, Object... pairs) {
        var columns = new StringBuilder("tenant_no,entity_no,created_at,updated_at");
        var values = new StringBuilder("'MAIN',?,NOW(),NOW()");
        var args = new ArrayList<Object>(); args.add(entity);
        for (int i = 0; i < pairs.length; i += 2) {
            columns.append(',').append(pairs[i]); values.append(",?"); args.add(pairs[i + 1]);
        }
        sql.update("INSERT INTO " + table + "(" + columns + ") VALUES (" + values + ")", args.toArray());
    }
    private String reference(String account) {
        String mc1 = codes.issue("MAIN", user).memberCode(); assertThat(mc1).startsWith("mc1_");
        String cr1 = as(account, () -> codes.resolve(new MemberCodeScope("MAIN", entity, store, account), mc1)
                .customerRef()); assertThat(cr1).startsWith("cr1_"); return cr1;
    }
    private Object register(String contact, String key) {
        return call(service, "registerGuest", entity, store, DelegatedIdentityContext.current().actorNo(),
                dto("RegisterGuestReq", contact, "Guest", true), key);
    }
    private long count(String table) {
        return sql.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE entity_no=?", Long.class, entity);
    }
    private <T> T as(String account, Supplier<T> action) {
        return DelegatedIdentityContext.call(new DelegatedIdentity("MAIN", "MERCHANT", "MCH", account), action);
    }
    private Class<?> type(String name) {
        return ClassUtils.resolveClassName("ai.neargo.shop.beauty." + name, getClass().getClassLoader());
    }
    private Object dto(String name, Object... args) {
        try { return Arrays.stream(type("dto." + name).getConstructors())
                .filter(constructor -> constructor
                        .getParameterCount() == args.length).findFirst().orElseThrow().newInstance(args); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private Object value(Object object, String field) { return call(object, field); }
    private Object call(Object target, String method, Object... args) {
        try {
            return Arrays.stream(target.getClass().getMethods()).filter(m -> m.getName().equals(method)
                    && m.getParameterCount() == args.length).findFirst().orElseThrow().invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private void rejected(Runnable action, ErrorCode error) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(error));
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class FaultConfiguration {
        @Bean @Primary ArrivalHoldFixture arrivalHoldFixture() { return new ArrivalHoldFixture(); }
        @Bean @Primary FaultPort faultPort(MemberGuestRegistrationService owner) { return new FaultPort(owner); }
    }
    static class ArrivalHoldFixture implements ResourceBookingPort {
        final Map<String, Hold> holds = new LinkedHashMap<>();
        @Override public Hold queryHold(Actor actor, ResourceSourceAuthorityPort.Binding binding, String number) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var hold = holds.get(number); assertThat(hold.planHash()).isEqualTo(binding.planHash()); return hold;
        }
        @Override public Receipt accept(Actor actor, Accept req) { throw new AssertionError("Arrival cannot accept"); }
        @Override public Optional<Receipt> query(Actor actor, Scope scope,
                String command) { throw new AssertionError(); }
        @Override public Receipt execute(Actor actor, Scope scope, String command) { throw new AssertionError(); }
        @Override public Optional<Receipt> reconcile(ResourceSourceAuthorityPort.Binding binding) {
            throw new AssertionError();
        }
        @Override public Hold reconcileHold(ResourceSourceAuthorityPort.Binding binding, String number) {
            throw new AssertionError();
        }
    }
    static class FaultPort implements GuestRegistrationPort {
        MemberGuestRegistrationService delegate;
        boolean dropRegister, dropClaim;
        int claimCalls, getCalls;
        String lastActor;
        FaultPort(MemberGuestRegistrationService delegate) { this.delegate = delegate; }
        @Override public Registration register(Scope scope, RegisterCommand command) {
            var result = delegate.register(scope, command);
            if (dropRegister) { dropRegister = false; throw new ResultUnknown(); } return result;
        }
        @Override public GuestFact get(Scope scope, String guest) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            getCalls++; lastActor = scope.identity().actorNo(); return delegate.get(scope, guest);
        }
        @Override public GuestFact verify(Scope scope, String guest, String token) {
            return delegate.verify(scope, guest, token);
        }
        @Override public Claim claim(Scope scope, String guest, ClaimCommand command) {
            claimCalls++; var result = delegate.claim(scope, guest, command);
            if (dropClaim) { dropClaim = false; throw new ResultUnknown(); } return result;
        }
    }
}
