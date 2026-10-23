package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore.SessionData;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.trade.policy.CheckoutAccessPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.function.Supplier;

/**
 * Cash authority is current, store-scoped and resolves the actual merchant account in either login
 * namespace.
 */
@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class CheckoutCashPermissionFlowTest extends CheckoutFlowFixture {
    @Autowired private CheckoutAccessPolicy access;

    @Test
    void ownerWildcardResolvesActualAccountButDelegationIsNotCurrentCashAuthorization() {
        var actual =
                asCashier(
                        LoginUser.merchant(actor, "cash owner"),
                        () -> access.requireCurrentCashier(scope));
        assertThat(actual.accountNo()).isEqualTo(actor);
        assertThat(actual.actorNo()).isEqualTo(actor);
        assertThatThrownBy(() -> asMerchant(() -> access.requireCurrentCashier(scope)))
                .isInstanceOf(BizException.class);
        MerchantOperatorAccessPort oldProvider = query -> true;
        assertThat(
                        oldProvider.resolveAuthorizedOperator(
                                new MerchantOperatorAccessPort.Query(
                                        "MAIN",
                                        entity,
                                        store,
                                        actor,
                                        "MERCHANT",
                                        "MCH",
                                        "pay:cash:receive")))
                .isEmpty();
    }

    @Test
    void receiveAndViewDoNotGrantCashAndGrantRevocationIsImmediateWithinOneSession() {
        jdbc.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no=?", actor);
        jdbc.update(
                "INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at) VALUES"
                    + " (?,'CASHIER','cashier','[\"biz:receive\",\"biz:order:view\"]',NOW(),NOW())",
                entity);
        jdbc.update(
                "INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                        + " VALUES (?,?,'CASHIER',NOW(),NOW())",
                actor,
                store);
        asCashier(
                LoginUser.merchant(actor, "cashier"),
                () -> {
                    assertThatThrownBy(() -> access.requireCurrentCashier(scope))
                            .isInstanceOf(BizException.class);
                    jdbc.update(
                            "UPDATE mch_role SET perms='[\"pay:cash:receive\",\"biz:order:view\"]'"
                                    + " WHERE entity_no=? AND role_code='CASHIER'",
                            entity);
                    assertThat(access.requireCurrentCashier(scope).accountNo()).isEqualTo(actor);
                    jdbc.update(
                            "UPDATE mch_role SET perms='[\"biz:receive\",\"biz:order:view\"]'"
                                    + " WHERE entity_no=? AND role_code='CASHIER'",
                            entity);
                    assertThatThrownBy(() -> access.requireCurrentCashier(scope))
                            .isInstanceOf(BizException.class);
                    return null;
                });
    }

    @Test
    void userLoginResolvesLinkedAccountAndCannotBorrowAnotherStoreOrNamespace() {
        String user = actor + "-user";
        jdbc.update(
                "INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at)"
                        + " VALUES (?,'cash owner','NORMAL',NOW(),NOW())",
                user);
        var userScope = new CheckoutScope("MAIN", entity, store, user);
        var actual =
                asCashier(
                        LoginUser.merchantByUser(user, "cash owner"),
                        () -> access.requireCurrentCashier(userScope));
        assertThat(actual.accountNo()).isEqualTo(actor);
        assertThat(actual.actorNo()).isEqualTo(user);
        assertThat(actual.subjectKind()).isEqualTo("USR");
        assertThatThrownBy(
                        () ->
                                asCashier(
                                        LoginUser.merchant(actor, "cash owner"),
                                        () ->
                                                access.requireCurrentCashier(
                                                        new CheckoutScope(
                                                                "MAIN",
                                                                entity,
                                                                "other-store",
                                                                actor))))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(
                        () ->
                                asCashier(
                                        LoginUser.merchantByUser(actor, "wrong namespace"),
                                        () -> access.requireCurrentCashier(scope)))
                .isInstanceOf(BizException.class);
    }

    private <T> T asCashier(LoginUser user, Supplier<T> action) {
        var pool = tokens.of(Realm.MERCHANT);
        String token = pool.issue(new SessionData(user));
        var original = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
        auth.setDetails(token);
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(original);
            pool.revoke(token);
        }
    }
}
