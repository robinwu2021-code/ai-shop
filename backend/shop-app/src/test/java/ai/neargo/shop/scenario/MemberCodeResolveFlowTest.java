package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.config.MemberCodeProperties;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.member.service.impl.MemberCodeResolveServiceImpl;
import ai.neargo.shop.member.mapper.MemberIdentityTicketMapper;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.CustomerIdentityPort.ReferenceQuery;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real database facts, including actor isolation; removing its predicate must break referenceBinding. */
@SpringBootTest(properties = {"shop.member-code.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:member-code-resolve;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class MemberCodeResolveFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ai.neargo.shop.spi.user.PersonPort people;
    @Autowired private MemberCodeResolveService service;
    @Autowired private CustomerIdentityPort identity;
    @Autowired private MemberCodeProperties properties;
    @Autowired private MemberIdentityTicketMapper ticketMapper;
    @Autowired private MerchantScopeFactsPort merchantFacts;
    @Autowired private UserScopeFactsPort userFacts;
    private String user;
    private String entity;
    private String store;
    private String secondStore;
    private String otherEntity;
    private String otherStore;
    private MemberCodeScope scope;

    @BeforeEach
    void fixture() {
        properties.setEnabled(true);
        user = "MC-U-" + UUID.randomUUID();
        entity = "MC-M-" + UUID.randomUUID();
        store = "MC-S-" + UUID.randomUUID();
        secondStore = "MC-S-" + UUID.randomUUID();
        otherEntity = "MC-M-" + UUID.randomUUID();
        otherStore = "MC-S-" + UUID.randomUUID();
        scope = new MemberCodeScope("MAIN", entity, store, "B-ACTOR");
        jdbc.update("INSERT INTO usr_account(user_no,nickname,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,'李小美','NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", user);
        jdbc.update("INSERT INTO mch_entity(entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,'code merchant','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity);
        jdbc.update("INSERT INTO mch_entity(entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,'other merchant','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", otherEntity);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'second store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                secondStore, entity);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'other store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                otherStore, otherEntity);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'code store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
    }

    @AfterEach
    void cleanup() {
        properties.setEnabled(true);
        DataScopeContext.clear();
        jdbc.update("DELETE FROM mbr_identity_ticket WHERE user_no=?", user);
        jdbc.update("DELETE FROM mch_store WHERE store_no=?", store);
        jdbc.update("DELETE FROM mch_store WHERE store_no=?", secondStore);
        jdbc.update("DELETE FROM mch_store WHERE store_no=?", otherStore);
        jdbc.update("DELETE FROM mch_entity WHERE entity_no=?", otherEntity);
        jdbc.update("DELETE FROM mch_entity WHERE entity_no=?", entity);
        jdbc.update("DELETE FROM usr_account WHERE user_no=?", user);
    }

    @Test void selectsUnregisteredPersonWithoutFakeCodeAndKeepsActorBinding() {
        String phone="139"+String.format("%08d",Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),100000000));
        var person=people.resolveOrCreateByPhone(phone);
        var ref=service.selectCustomer(scope,person.personNo(),null);
        var resolved=service.requireReference(scope,ref.customerRef());
        assertThat(resolved.userNo()).isNull();assertThat(resolved.customerNo()).isEqualTo(person.personNo());
        assertThat(jdbc.queryForObject("SELECT purpose FROM mbr_identity_ticket WHERE customer_no=?",String.class,person.personNo())).isEqualTo("CUSTOMER_SELECTION");
        assertThatThrownBy(()->service.requireReference(new MemberCodeScope(scope.tenantNo(),scope.entityNo(),scope.storeNo(),"OTHER_ACTOR"),ref.customerRef())).isInstanceOf(BizException.class);
    }

    @Test
    void issuesUnifiedCodeAndResolvesDurableReferenceWithOnlyMaskedIdentity() {
        Instant before = Instant.now();
        var code = service.issue("MAIN", user);
        var ref = service.resolve(scope, code.memberCode());
        assertThat(code.memberCode()).matches("mc1_[A-Za-z0-9_-]{43}");
        assertThat(ref.customerRef()).matches("cr1_[A-Za-z0-9_-]{43}");
        assertThat(Duration.between(before, code.expiresAt()).getSeconds()).isBetween(59L, 61L);
        assertThat(Duration.between(before, ref.expiresAt()).getSeconds()).isBetween(119L, 121L);
        assertThat(ref.maskedName()).isEqualTo("李**");
        assertThat(service.requireReference(scope, ref.customerRef()).userNo()).isEqualTo(user);
        var ctx = new EntitlementContext("MAIN", "B-ACTOR", "query-1", "a".repeat(64));
        assertThat(identity.resolveReference(ctx, new ReferenceQuery(entity, store, ref.customerRef())).userNo())
                .isEqualTo(user);
        var rows = jdbc.queryForList("SELECT * FROM mbr_identity_ticket WHERE user_no=?", user);
        assertThat(rows).hasSize(2);
        // A fresh service object can resolve it: no process-local map or original bearer token is needed.
        var fresh = new MemberCodeResolveServiceImpl(ticketMapper, userFacts, merchantFacts, properties);
        assertThat(fresh.requireReference(scope, ref.customerRef()).userNo()).isEqualTo(user);
        assertThat(rows.toString()).doesNotContain(code.memberCode(), ref.customerRef());
        assertThat(code.toString()).doesNotContain(code.memberCode());
        assertThat(ref.toString()).doesNotContain(ref.customerRef());
        assertThat(new ReferenceQuery(entity, store, ref.customerRef()).toString()).doesNotContain(ref.customerRef());
    }

    @Test
    void referenceBindingIncludesTenantEntityStoreAndActor() {
        var ref = service.resolve(scope, service.issue("MAIN", user).memberCode()).customerRef();
        rejected(() -> service.requireReference(new MemberCodeScope("OTHER", entity, store, "B-ACTOR"), ref));
        rejected(() -> service.requireReference(new MemberCodeScope("MAIN", otherEntity, otherStore, "B-ACTOR"), ref));
        rejected(() -> service.requireReference(new MemberCodeScope("MAIN", entity, secondStore, "B-ACTOR"), ref));
        rejected(() -> service.requireReference(new MemberCodeScope("MAIN", entity, store, "OTHER"), ref));
        assertThat(service.requireReference(scope, ref).userNo()).isEqualTo(user);
    }

    @Test
    void rejectsForgedCodesUserNumbersReferencesAndExpiredCodes() {
        rejected(() -> service.resolve(scope, user));
        rejected(() -> service.resolve(scope, "ctk-not-a-member-code"));
        rejected(() -> service.resolve(scope, "mc1_" + "a".repeat(43)));
        var code = service.issue("MAIN", user).memberCode();
        var ref = service.resolve(scope, code).customerRef();
        rejected(() -> service.resolve(scope, ref));
        rejected(() -> service.requireReference(scope, code));
        jdbc.update("UPDATE mbr_identity_ticket SET issued_at=?,expires_at=? "
                + "WHERE user_no=? AND purpose='MEMBER_CODE'", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(120),
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), user);
        assertThatThrownBy(() -> service.resolve(scope, code)).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.MEMBER_CODE_INVALID));
        // A reference has its own deadline; expiration of the parent code does not silently change it.
        assertThat(service.requireReference(scope, ref).userNo()).isEqualTo(user);
    }

    @Test
    void expiredReferencesFailEvenWithValidScope() {
        var ref = service.resolve(scope, service.issue("MAIN", user).memberCode()).customerRef();
        jdbc.update("UPDATE mbr_identity_ticket SET issued_at=?,expires_at=? WHERE user_no=?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(180),
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), user);
        rejected(() -> service.requireReference(scope, ref));
    }

    @Test
    void rechecksLiveUserForIssueScanAndReferenceReads() {
        var code = service.issue("MAIN", user).memberCode();
        var ref = service.resolve(scope, code).customerRef();
        jdbc.update("UPDATE usr_account SET status='BANNED' WHERE user_no=?", user);
        rejected(() -> service.issue("MAIN", user));
        rejected(() -> service.resolve(scope, code));
        rejected(() -> service.requireReference(scope, ref));
        jdbc.update("UPDATE usr_account SET status='RISK_LIMITED' WHERE user_no=?", user);
        assertThat(service.requireReference(scope, ref).userNo()).isEqualTo(user);
        jdbc.update("UPDATE usr_account SET deleted=1 WHERE user_no=?", user);
        rejected(() -> service.requireReference(scope, ref));
    }

    @Test
    void verifiesMerchantOwnershipAndTenantBeforeMintingReference() {
        var code = service.issue("MAIN", user).memberCode();
        rejected(() -> service.resolve(new MemberCodeScope("OTHER", entity, store, "B-ACTOR"), code));
        jdbc.update("UPDATE mch_store SET entity_no='OTHER' WHERE store_no=?", store);
        rejected(() -> service.resolve(scope, code));
        jdbc.update("UPDATE mch_store SET entity_no=?,status='SUSPENDED' WHERE store_no=?", entity, store);
        rejected(() -> service.resolve(scope, code));
        jdbc.update("UPDATE mch_store SET status='ACTIVE' WHERE store_no=?", store);
        jdbc.update("UPDATE mch_entity SET status='SUSPENDED' WHERE entity_no=?", entity);
        rejected(() -> service.resolve(scope, code));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mbr_identity_ticket WHERE user_no=? "
                + "AND purpose='CUSTOMER_REFERENCE'", Integer.class, user)).isZero();
    }

    @Test
    void disabledFeatureBlocksNewCredentialsButAllowsExistingReferenceQuery() {
        assertThat(new MemberCodeProperties().isEnabled()).isFalse();
        var code = service.issue("MAIN", user).memberCode();
        var ref = service.resolve(scope, code).customerRef();
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.issue("MAIN", user)).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        rejected(() -> service.resolve(scope, code));
        assertThat(service.requireReference(scope, ref).userNo()).isEqualTo(user);
    }

    private static void rejected(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BizException.class);
    }
}
