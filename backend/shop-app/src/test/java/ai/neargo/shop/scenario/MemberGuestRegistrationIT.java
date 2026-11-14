package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.config.MemberGuestCrypto;
import ai.neargo.shop.member.mapper.MemberGuestRegistrationMapper;
import ai.neargo.shop.member.service.impl.MemberGuestRegistrationServiceImpl;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.member.service.MemberGuestRegistrationService;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ClaimCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.RegisterCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real MySQL owner permission facts, member tickets and guest transactions, including staff handoff. */
@SpringBootTest(properties = {"shop.member-code.enabled=true",
        "shop.member.guest.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "member-guest.test.native", matches = "true")
class MemberGuestRegistrationIT {
    @Autowired JdbcTemplate sql;
    @Autowired MemberGuestRegistrationService guests;
    @Autowired MemberCodeResolveService codes;
    @Autowired MemberGuestRegistrationMapper mapper;
    @Autowired MerchantOperatorAccessPort access;
    @Autowired MerchantScopeFactsPort stores;
    @Autowired UserScopeFactsPort userFacts;
    @Autowired CustomerIdentityPort identities;
    @Autowired ApplicationContext context;
    @Autowired @Qualifier("transactionManager") PlatformTransactionManager transactions;
    private String entity;
    private String store;
    private String actor;
    private String manager;
    private String user;
    private String other;
    private Scope scope;
    private final RegisterCommand command = new RegisterCommand("secret-contact-17700000000", "Guest", true, "reg-1");

    @BeforeEach void fixture() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        entity = "GE-" + id; store = "GS-" + id; actor = "GA-" + id; manager = "GM-" + id;
        user = "GU-" + id; other = "GV-" + id;
        sql.update("INSERT INTO mch_entity(entity_no,name,status,created_at,updated_at) "
                + "VALUES (?,'guest owner','ACTIVE',NOW(),NOW())", entity);
        for (String s : List.of(store, store + "2")) {
            sql.update("INSERT INTO mch_store(entity_no,store_no,name,status,created_at,updated_at) "
                    + "VALUES (?,?,'guest store','ACTIVE',NOW(),NOW())", entity, s);
        }
        for (String a : List.of(actor, manager)) {
            sql.update("INSERT INTO mch_account(entity_no,mch_account_no,user_no,is_owner,status,"
                    + "created_at,updated_at) "
                    + "VALUES (?,?,?,1,'ACTIVE',NOW(),NOW())", entity, a, a);
        }
        for (String u : List.of(user, other)) {
            sql.update("INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at) "
                    + "VALUES (?,'real customer','NORMAL',NOW(),NOW())", u);
        }
        scope = scope(actor, "MCH", store);
    }
    @Test void registrationReplayIsDurableEncryptedAndHasNoAccountOrOrderSideEffects() {
        long accounts = count("usr_account"), members = count("mbr_member"), orders = count("ord_order");
        var result = as(scope, () -> guests.register(scope, command));
        assertThat(result.claimToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(result.state()).isEqualTo("REGISTERED");
        assertThat(result.revision()).isEqualTo(1);
        assertThat(as(scope, () -> guests.register(scope, command))).isEqualTo(result);
        assertThat(as(scope, () -> guests.get(scope, result.guestNo())).userNo()).isNull();
        assertThat(as(scope, () -> guests.verify(scope, result.guestNo(), result.claimToken())).state())
                .isEqualTo("REGISTERED");
        assertThat(sql.queryForList("SELECT * FROM mbr_guest_registration WHERE guest_no=?", result.guestNo())
                .toString()).doesNotContain(command.contact(), result.claimToken(), command.idempotencyKey());
        assertThat(result.toString() + command).doesNotContain(result.claimToken(), command.contact());
        assertThat(count("usr_account")).isEqualTo(accounts);
        assertThat(count("mbr_member")).isEqualTo(members);
        assertThat(count("ord_order")).isEqualTo(orders);
        rejected(() -> as(scope, () -> guests.register(scope, new RegisterCommand("changed", "Guest", true, "reg-1"))),
                ErrorCode.CONFLICT);
    }
    @Test void currentPermissionScopeAndNamespaceProtectEveryReplayAndRead() {
        var result = as(scope, () -> guests.register(scope, command));
        var usr = scope(actor, "USR", store);
        assertThat(as(usr, () -> guests.register(usr, command)).guestNo()).isNotEqualTo(result.guestNo());
        rejected(() -> as(usr, () -> guests.get(scope, result.guestNo())), ErrorCode.FORBIDDEN);
        var cross = scope(actor, "MCH", store + "2");
        rejected(() -> as(cross, () -> guests.get(cross, result.guestNo())), ErrorCode.NOT_FOUND);
        var stranger = scope("UNKNOWN", "MCH", store);
        rejected(() -> as(stranger, () -> guests.get(stranger, result.guestNo())), ErrorCode.FORBIDDEN);
        sql.update("UPDATE mch_account SET status='SUSPENDED' WHERE mch_account_no=?", actor);
        rejected(() -> as(scope, () -> guests.register(scope, command)), ErrorCode.FORBIDDEN);
        rejected(() -> as(scope, () -> guests.verify(scope, result.guestNo(), result.claimToken())),
                ErrorCode.FORBIDDEN);
    }
    @Test void sameStoreManagerHandoffRequiresTheirOwnCurrentReferenceAndOriginalGuestSecret() {
        var result = as(scope, () -> guests.register(scope, command));
        String oldRef = reference(scope, user);
        var next = scope(manager, "USR", store);
        sql.update("UPDATE mch_account SET status='SUSPENDED' WHERE mch_account_no=?", actor);
        assertThat(as(next, () -> guests.get(next, result.guestNo())).state()).isEqualTo("REGISTERED");
        rejected(() -> as(next, () -> guests.claim(next, result.guestNo(),
                new ClaimCommand(oldRef, result.claimToken(), "claim-1"))), ErrorCode.NOT_FOUND);
        String current = reference(next, user);
        rejected(() -> as(next, () -> guests.claim(next, result.guestNo(),
                new ClaimCommand(current, "wrong", "claim-1"))), ErrorCode.NOT_FOUND);
        var claim = new ClaimCommand(current, result.claimToken(), "claim-1");
        var claimed = as(next, () -> guests.claim(next, result.guestNo(), claim));
        assertThat(claimed.userNo()).isEqualTo(user);
        assertThat(claimed.revision()).isEqualTo(2);
        assertThat(as(next, () -> guests.claim(next, result.guestNo(), claim))).isEqualTo(claimed);
        rejected(() -> as(next, () -> guests.claim(next, result.guestNo(),
                new ClaimCommand(reference(next, other), result.claimToken(), "claim-1"))), ErrorCode.CONFLICT);
    }
    @Test void concurrentClaimsHaveOneRealUserWinnerAndFailureLeavesNoSideEffects() throws Exception {
        var result = as(scope, () -> guests.register(scope, command));
        String first = reference(scope, user), second = reference(scope, other);
        long accounts = count("usr_account"), orders = count("ord_order");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(ref -> pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    as(scope, () -> guests.claim(scope, result.guestNo(),
                            new ClaimCommand(ref, result.claimToken(), ref.equals(first) ? "one" : "two")));
                    return true;
                } catch (BizException failure) {
                    assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT); return false;
                }
            })).toList();
            start.countDown();
            int winners = 0;
            for (var f : futures) { if (f.get(15, TimeUnit.SECONDS)) { winners++; } }
            assertThat(winners).isEqualTo(1);
        }
        assertThat(as(scope, () -> guests.get(scope, result.guestNo())).userNo()).isIn(user, other);
        assertThat(count("usr_account")).isEqualTo(accounts); assertThat(count("ord_order")).isEqualTo(orders);
    }
    @Test void invalidInputsAndTamperedCiphertextFailClosed() {
        for (var invalid : List.of(new RegisterCommand(null, "Guest", false, "x"),
                new RegisterCommand("x".repeat(129), "Guest", true, "x"),
                new RegisterCommand(null, "x".repeat(81), true, "x"))) {
            rejected(() -> as(scope, () -> guests.register(scope, invalid)), ErrorCode.BAD_REQUEST);
        }
        var result = as(scope, () -> guests.register(scope, command));
        sql.update("UPDATE mbr_guest_registration SET claim_token_cipher='AAAA' WHERE guest_no=?", result.guestNo());
        assertThatThrownBy(() -> as(scope, () -> guests.register(scope, command))).isInstanceOf(BizException.class);
    }
    @Test void missingKeyDisablesOnlyGuestAndFreshOwnerReplaysPersistedSecret() {
        assertThat(context.getBeansOfType(GuestRegistrationPort.class)).hasSize(1);
        var unavailable = new MemberGuestRegistrationServiceImpl(mapper, access, stores, codes,
                new MemberGuestCrypto(""), userFacts, transactions);
        rejected(() -> as(scope, () -> unavailable.register(scope, command)), ErrorCode.MEMBER_GUEST_UNAVAILABLE);
        var result = as(scope, () -> guests.register(scope, command));
        var fresh = new MemberGuestRegistrationServiceImpl(mapper, access, stores, codes,
                new MemberGuestCrypto("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="), userFacts, transactions);
        assertThat(as(scope, () -> fresh.register(scope, command))).isEqualTo(result);
        assertThat(codes.issue("MAIN", user).memberCode()).startsWith("mc1_");
    }
    @Test void currentCustomerPermissionIsRecheckedAfterRoleRevocationAndWrongStoreAssignment() {
        sql.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no=?", manager);
        sql.update("INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at) "
                + "VALUES (?,'GUEST_ACCESS','Guest access','[\"biz:customer\"]',NOW(),NOW())", entity);
        sql.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at) "
                + "VALUES (?,?,'GUEST_ACCESS',NOW(),NOW())", manager, store);
        var next = scope(manager, "USR", store);
        var result = as(scope, () -> guests.register(scope, command));
        assertThat(as(next, () -> guests.verify(next, result.guestNo(), result.claimToken())).state())
                .isEqualTo("REGISTERED");
        var otherStore = scope(manager, "USR", store + "2");
        rejected(() -> as(otherStore, () -> guests.register(otherStore, command)), ErrorCode.FORBIDDEN);
        sql.update("UPDATE mch_role SET perms='[]' WHERE entity_no=? AND role_code='GUEST_ACCESS'", entity);
        rejected(() -> as(next, () -> guests.get(next, result.guestNo())), ErrorCode.FORBIDDEN);
        rejected(() -> as(next, () -> guests.claim(next, result.guestNo(),
                new ClaimCommand(reference(next, user), result.claimToken(), "claim"))), ErrorCode.FORBIDDEN);
    }
    @Test void nativeConsumerProofRejectsOtherGenuineUserExpiredForgedAndMerchantNamespaces() {
        String code = codes.issue("MAIN", user).memberCode();
        String different = codes.issue("MAIN", other).memberCode();
        var c = new EntitlementContext("MAIN", user, "proof", "a".repeat(64));
        var consumer = new DelegatedIdentity("MAIN", "CONSUMER", "USR", user);
        DelegatedIdentityContext.call(consumer, () -> {
            assertThat(identities.verifyConsumerProof(c, code).userNo()).isEqualTo(user);
            rejected(() -> identities.verifyConsumerProof(c, different), ErrorCode.MEMBER_CODE_INVALID);
            rejected(() -> identities.verifyConsumerProof(c, "login-token"), ErrorCode.MEMBER_CODE_INVALID);
            rejected(() -> identities.verifyConsumerProof(c, "mc1_" + "z".repeat(43)), ErrorCode.MEMBER_CODE_INVALID);
            return null;
        });
        rejected(() -> as(scope, () -> identities.verifyConsumerProof(c, code)), ErrorCode.FORBIDDEN);
        sql.update("UPDATE mbr_identity_ticket SET issued_at=DATE_SUB(NOW(), INTERVAL 2 MINUTE), "
                + "expires_at=DATE_SUB(NOW(), INTERVAL 1 SECOND) WHERE user_no=?", user);
        DelegatedIdentityContext.call(consumer, () -> {
            rejected(() -> identities.verifyConsumerProof(c, code), ErrorCode.MEMBER_CODE_INVALID); return null;
        });
    }
    @Test void duplicateConcurrentRegistrationReplaysOneCredential() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return as(scope, () -> guests.register(scope, command)); });
            var second = pool.submit(() -> { start.await(); return as(scope, () -> guests.register(scope, command)); });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mbr_guest_registration WHERE entity_no=?",
                    Long.class, entity)).isEqualTo(1);
        }
    }
    @Test void restartJvmReplaysOriginalKeyFromEncryptedStorage() throws Exception {
        String filename = System.getProperty("member-guest.test.restart-file");
        if (filename == null) { return; }
        Path file = Path.of(filename);
        var data = new Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) { data.load(in); }
            var saved = new Scope(data.getProperty("entity"), data.getProperty("store"),
                    new DelegatedIdentity("MAIN", "MERCHANT", "MCH", data.getProperty("actor")));
            var replay = as(saved, () -> guests.register(saved, command));
            assertThat(replay.guestNo()).isEqualTo(data.getProperty("guest"));
            assertThat(replay.claimToken()).isEqualTo(data.getProperty("token"));
            assertThat(data.getProperty("pid")).isNotEqualTo(Long.toString(ProcessHandle.current().pid()));
        } else {
            var result = as(scope, () -> guests.register(scope, command));
            data.setProperty("entity", entity); data.setProperty("store", store); data.setProperty("actor", actor);
            data.setProperty("guest", result.guestNo()); data.setProperty("token", result.claimToken());
            data.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
            try (var out = Files.newOutputStream(file)) { data.store(out, "Temporary restart fixture, never logs"); }
        }
    }

    @Test void guestClaimReferencesCannotCrossMerchantSubjectNamespaceEvenForSameActorNumber() {
        var usr = scope(actor, "USR", store);
        var registered = as(scope, () -> guests.register(scope, command));
        String mchReference = reference(scope, user);
        rejected(() -> as(usr, () -> guests.claim(usr, registered.guestNo(),
                new ClaimCommand(mchReference, registered.claimToken(), "namespace-claim"))), ErrorCode.NOT_FOUND);
        assertThat(as(scope, () -> guests.get(scope, registered.guestNo())).state()).isEqualTo("REGISTERED");
        String userReference = reference(usr, user);
        rejected(() -> as(scope, () -> guests.claim(scope, registered.guestNo(),
                new ClaimCommand(userReference, registered.claimToken(), "namespace-claim"))), ErrorCode.NOT_FOUND);
        String legacyReference = codes.resolve(new MemberCodeScope("MAIN", entity, store, actor),
                codes.issue("MAIN", user).memberCode()).customerRef();
        rejected(() -> as(scope, () -> guests.claim(scope, registered.guestNo(),
                new ClaimCommand(legacyReference, registered.claimToken(), "namespace-claim"))), ErrorCode.NOT_FOUND);
        assertThat(codes.requireReference(new MemberCodeScope("MAIN", entity, store, actor), legacyReference).userNo())
                .isEqualTo(user);
        assertThat(as(usr, () -> guests.claim(usr, registered.guestNo(),
                new ClaimCommand(userReference, registered.claimToken(), "namespace-claim"))).userNo()).isEqualTo(user);
    }

    @Test void registrationRequestFingerprintIsStableButProtectedByOwnerSecret() {
        var first = as(scope, () -> guests.register(scope, command));
        var anotherKey = new MemberGuestRegistrationServiceImpl(mapper, access, stores, codes,
                new MemberGuestCrypto("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="), userFacts, transactions);
        var second = as(scope, () -> anotherKey.register(scope,
                new RegisterCommand(command.contact(), command.displayName(), true, "another-key")));
        String firstHash = sql.queryForObject("SELECT request_hash FROM mbr_guest_registration WHERE guest_no=?",
                String.class, first.guestNo());
        String secondHash = sql.queryForObject("SELECT request_hash FROM mbr_guest_registration WHERE guest_no=?",
                String.class, second.guestNo());
        assertThat(firstHash).isNotEqualTo(secondHash);
        assertThat(as(scope, () -> guests.register(scope, command))).isEqualTo(first);
        rejected(() -> as(scope, () -> guests.register(scope,
                new RegisterCommand("different-contact", command.displayName(), true, command.idempotencyKey()))),
                ErrorCode.CONFLICT);
    }

    @Test void exactCommittedClaimReplaysAfterReferenceExpiryButCurrentPermissionAndIdentityStillApply() {
        var registered = as(scope, () -> guests.register(scope, command));
        String ref = reference(scope, user);
        var request = new ClaimCommand(ref, registered.claimToken(), "original-claim");
        var claimed = as(scope, () -> guests.claim(scope, registered.guestNo(), request));
        sql.update("UPDATE mbr_identity_ticket SET issued_at=DATE_SUB(NOW(), INTERVAL 3 MINUTE), "
                + "expires_at=DATE_SUB(NOW(), INTERVAL 1 SECOND) WHERE user_no=? "
                + "AND purpose='CUSTOMER_REFERENCE'", user);
        assertThat(as(scope, () -> guests.claim(scope, registered.guestNo(), request))).isEqualTo(claimed);
        assertThat(as(scope, () -> guests.register(scope, command))).isEqualTo(registered);
        var usr = scope(actor, "USR", store);
        rejected(() -> as(usr, () -> guests.claim(usr, registered.guestNo(), request)), ErrorCode.CONFLICT);
        rejected(() -> as(scope, () -> guests.claim(scope, registered.guestNo(),
                new ClaimCommand(ref, registered.claimToken(), "different-key"))), ErrorCode.CONFLICT);
        sql.update("UPDATE usr_account SET status='BANNED' WHERE user_no=?", user);
        rejected(() -> as(scope, () -> guests.claim(scope, registered.guestNo(), request)), ErrorCode.NOT_FOUND);
        sql.update("UPDATE usr_account SET status='NORMAL' WHERE user_no=?", user);
        sql.update("UPDATE mch_account SET status='SUSPENDED' WHERE mch_account_no=?", actor);
        rejected(() -> as(scope, () -> guests.claim(scope, registered.guestNo(), request)), ErrorCode.FORBIDDEN);
    }

    private String reference(Scope s, String u) {
        String code = codes.issue("MAIN", u).memberCode();
        return as(s, () -> codes.resolve(new MemberCodeScope("MAIN", s.entityNo(), s.storeNo(), s.identity().actorNo()),
                code).customerRef());
    }
    private Scope scope(String a, String kind, String s) {
        return new Scope(entity, s, new DelegatedIdentity("MAIN", "MERCHANT", kind, a));
    }
    private <T> T as(Scope s, Supplier<T> operation) { return DelegatedIdentityContext.call(s.identity(), operation); }
    private long count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private void rejected(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
}
