package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.trade.policy.CheckoutAccessPolicy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

@SpringBootTest(
        properties =
                "spring.datasource.url=${CHECKOUT_NATIVE_MYSQL_URL:jdbc:h2:mem:checkout-owner;"
                        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}")
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class CheckoutPermissionFlowTest {
    private static final String PERMISSION = "trade:checkout:prepare";
    @Autowired private CheckoutAccessPolicy policy;
    @Autowired private JdbcTemplate jdbc;
    private String entity;
    private String store;
    private String actor;
    private CheckoutScope scope;

    @BeforeEach
    void fixture() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        entity = "CE-" + id;
        store = "CS-" + id;
        actor = "CA-" + id;
        jdbc.update(
                "INSERT INTO mch_entity(entity_no,name,status,created_at,updated_at) "
                        + "VALUES (?,'checkout entity','ACTIVE',NOW(),NOW())",
                entity);
        jdbc.update(
                "INSERT INTO mch_store(entity_no,store_no,name,status,created_at,updated_at) "
                        + "VALUES (?,?,'checkout store','ACTIVE',NOW(),NOW())",
                entity,
                store);
        jdbc.update(
                "INSERT INTO"
                        + " mch_account(entity_no,mch_account_no,user_no,is_owner,status,created_at,updated_at)"
                        + " VALUES (?,?,?,1,'ACTIVE',NOW(),NOW())",
                entity,
                actor,
                actor + "-linked-user");
        scope = new CheckoutScope("MAIN", entity, store, actor);
    }

    @Test
    void ownerHasNewPermissionByExistingWildcard() {
        assertThat(as(scope).actorNo()).isEqualTo(actor);
    }

    @Test
    void clerkWithoutExplicitGrantCannotPrepareCheckout() {
        jdbc.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no=?", actor);
        assertThatThrownBy(() -> as(scope)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void currentOwnerRevocationTakesEffect() {
        as(scope);
        jdbc.update("UPDATE mch_account SET status='DISABLED' WHERE mch_account_no=?", actor);
        assertThatThrownBy(() -> as(scope)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void crossStoreCannotUseAnOwnedEntityAsAuthority() {
        var other = new CheckoutScope("MAIN", entity, "not-owned-store", actor);
        assertThatThrownBy(() -> as(other)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void delegatedNamespaceCannotTurnMerchantAccountIntoUserActor() {
        assertThatThrownBy(
                        () ->
                                DelegatedIdentityContext.call(
                                        new DelegatedIdentity("MAIN", "MERCHANT", "USR", actor),
                                        () -> policy.requireMerchant(scope, PERMISSION)))
                .isInstanceOf(RuntimeException.class);
    }

    private CheckoutAccessPolicy.MerchantActor as(CheckoutScope requested) {
        return DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor),
                () -> policy.requireMerchant(requested, PERMISSION));
    }

    @Test
    void explicitGrantAndRevocationTakeEffectWithoutNewLogin() {
        role("CUSTOM_CHECKOUT", "[\"trade:checkout:prepare\",\"biz:order:view\"]");
        assertThat(as(scope).actorNo()).isEqualTo(actor);
        jdbc.update(
                "UPDATE mch_role SET perms='[]' WHERE entity_no=? AND role_code='CUSTOM_CHECKOUT'",
                entity);
        assertThatThrownBy(() -> as(scope)).isInstanceOf(BizException.class);
    }

    @Test
    void pickerAndCourierCannotReadCheckoutMoneyOrPrepare() {
        for (String role : List.of("PICKER", "COURIER")) {
            jdbc.update("DELETE FROM mch_store_role WHERE mch_account_no=?", actor);
            role(role, "[\"biz:order:view\"]");
            assertThatThrownBy(() -> as(scope)).isInstanceOf(BizException.class);
            if ("COURIER".equals(role)) {
                assertThatThrownBy(
                                () ->
                                        DelegatedIdentityContext.call(
                                                new DelegatedIdentity(
                                                        "MAIN", "MERCHANT", "MCH", actor),
                                                () ->
                                                        policy.requireMerchant(
                                                                scope, "biz:order:view")))
                        .isInstanceOf(BizException.class);
            }
        }
    }

    private void role(String role, String permissions) {
        jdbc.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no=?", actor);
        jdbc.update(
                "INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at)"
                        + " VALUES (?,?,'checkout role',?,NOW(),NOW())",
                entity,
                role,
                permissions);
        jdbc.update(
                "INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                        + " VALUES (?,?,?,NOW(),NOW())",
                actor,
                store,
                role);
    }
}
