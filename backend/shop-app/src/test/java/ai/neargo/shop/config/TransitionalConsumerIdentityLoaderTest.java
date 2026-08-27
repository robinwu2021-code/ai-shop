package ai.neargo.shop.config;

import ai.neargo.auth.store.IdentityLoader;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 过渡期回落。**这个类存在的唯一理由，是不让所有商家店员在切换那天集体 401。**
 *
 * <p>店员的会话主体是 {@code mch_account_no}，却装在 C 端池里
 * （{@code MerchantStaffServiceImpl} 签发的是 {@code LoginUser.consumer(mchAccountNo, ...)}）。
 * 会话外置之前没有症状 —— 整份身份都在会话里；外置之后身份改为从用户表现读，
 * 而那个号<b>不在 {@code usr_account} 里</b>。
 */
class TransitionalConsumerIdentityLoaderTest {

    private static IdentityLoader<LoginUser> loaderOf(String no, LoginUser value) {
        return userNo -> no.equals(userNo) ? Optional.of(value) : Optional.empty();
    }

    private static final LoginUser CONSUMER = LoginUser.consumer("U1", "小王");
    private static final LoginUser STAFF = LoginUser.merchant("SF-M0001", "老王超市");

    private TransitionalConsumerIdentityLoader loader() {
        return new TransitionalConsumerIdentityLoader(
                loaderOf("U1", CONSUMER), loaderOf("SF-M0001", STAFF));
    }

    @Test
    @DisplayName("C 端用户照常从 usr_account 还原")
    void consumerFirst() {
        LoginUser u = loader().load("U1").orElseThrow();
        assertEquals("U1", u.userNo());
        assertEquals(Realm.CONSUMER, u.realm());
    }

    @Test
    @DisplayName("★★ 店员回落到 mch_account —— 没有这一条，切换当天所有店员 401")
    void merchantStaffFallsBack() {
        LoginUser u = loader().load("SF-M0001").orElseThrow();
        assertEquals("SF-M0001", u.userNo());
        assertEquals("老王超市", u.nickname());
    }

    @Test
    @DisplayName("★ 回落回来的身份必须是 CONSUMER 形态 —— 否则每个店员请求都会撞上 store 的 realm 校验")
    void fallbackIsDowngradedToConsumerRealm() {
        LoginUser u = loader().load("SF-M0001").orElseThrow();
        assertEquals(Realm.CONSUMER, u.realm(),
                "它此刻装在 C 端池里。返回 MERCHANT 身份会让 DbTokenStore 那道"
                + "「会话 realm 与本 store 一致」的校验在每个店员请求上炸 —— "
                + "而那道校验本身是对的，不该为过渡期放宽");
    }

    @Test
    @DisplayName("两边都查不到就是空 —— 不能给幽灵身份放行")
    void neitherMeansEmpty() {
        assertTrue(loader().load("UNKNOWN").isEmpty());
    }
}
