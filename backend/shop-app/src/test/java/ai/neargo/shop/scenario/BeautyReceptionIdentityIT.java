package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.config.MemberGuestCrypto;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.mapper.MemberGuestRegistrationMapper;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.member.service.MemberGuestRegistrationService;
import ai.neargo.shop.member.service.impl.MemberGuestRegistrationServiceImpl;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
import org.springframework.util.ClassUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Native current platform fixture + official V537/V538 + beauty V1–V5; real mc1/cr1 and guest owner.
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
        faults.delegate = owner; faults.dropRegister = false; faults.dropClaim = false; faults.claimCalls = 0;
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
        try { return type("dto." + name).getConstructors()[0].newInstance(args); }
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
        @Bean @Primary FaultPort faultPort(MemberGuestRegistrationService owner) { return new FaultPort(owner); }
    }
    static class FaultPort implements GuestRegistrationPort {
        MemberGuestRegistrationService delegate;
        boolean dropRegister, dropClaim;
        int claimCalls;
        String lastActor;
        FaultPort(MemberGuestRegistrationService delegate) { this.delegate = delegate; }
        @Override public Registration register(Scope scope, RegisterCommand command) {
            var result = delegate.register(scope, command);
            if (dropRegister) { dropRegister = false; throw new ResultUnknown(); } return result;
        }
        @Override public GuestFact get(Scope scope, String guest) {
            lastActor = scope.identity().actorNo(); return delegate.get(scope, guest);
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
