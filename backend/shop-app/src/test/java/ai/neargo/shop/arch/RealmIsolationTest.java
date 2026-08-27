package ai.neargo.shop.arch;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三端令牌池的隔离 —— **前缀是最便宜也最靠前的一道闸**。
 *
 * <p>这组断言存在的理由：曾经 {@code newToken} 写的是
 * {@code realm == OPERATOR ? "otk_" : "ctk_"}。加第三个池时，
 * 那个三元表达式会**静默地**把新池归进 {@code ctk_} —— 编译过、测试过，
 * 只是 B 端和 C 端从此共用一个池，而这正是本次改造要消灭的状态。
 */
@DisplayName("令牌池隔离")
class RealmIsolationTest {

    @Test
    @DisplayName("★ 每个池的前缀都不一样 —— 撞了第一道闸就没了")
    void everyRealmHasItsOwnPrefix() {
        Set<String> prefixes = new HashSet<>();
        for (Realm r : Realm.values()) {
            assertThat(r.tokenPrefix())
                    .as("%s 的前缀不能为空", r)
                    .isNotBlank();
            assertThat(prefixes.add(r.tokenPrefix()))
                    .as("前缀 %s 被两个池共用 —— 那正是这次改造要消灭的状态", r.tokenPrefix())
                    .isTrue();
        }
        assertThat(prefixes).hasSameSizeAs(Set.of(Realm.values()));
    }

    @Test
    @DisplayName("★ 签发的令牌带对前缀（三个池逐一验，不靠三元表达式的默认分支）")
    void issuedTokenCarriesItsOwnPrefix() {
        for (Realm r : Realm.values()) {
            String token = TokenStore.newToken(r);
            assertThat(token)
                    .as("%s 签发的令牌前缀不对 —— 它会被当成另一个池的", r)
                    .startsWith(r.tokenPrefix());
            assertThat(Realm.ofToken(token))
                    .as("%s 的令牌认不回自己", r)
                    .isEqualTo(r);
        }
    }

    @Test
    @DisplayName("跨池令牌认不出来 —— 认不出就该直接 401，不必查库")
    void foreignAndMalformedTokensResolveToNothing() {
        assertThat(Realm.ofToken("xyz_deadbeef")).isNull();
        assertThat(Realm.ofToken("")).isNull();
        assertThat(Realm.ofToken(null)).isNull();
        // C 端令牌不能被认成 B 端
        assertThat(Realm.ofToken(TokenStore.newToken(Realm.CONSUMER)))
                .isNotEqualTo(Realm.MERCHANT);
    }

    @Test
    @DisplayName("三个池齐了：CONSUMER / MERCHANT / OPERATOR")
    void threeRealms() {
        assertThat(Realm.values())
                .as("B 端曾经没有自己的池，userNo 一个字段里塞着两张表的主键")
                .containsExactlyInAnyOrder(Realm.CONSUMER, Realm.MERCHANT, Realm.OPERATOR);
    }
}
