package ai.neargo.shop.config;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.auth.store.SubjectKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A7 之后，三个池各装各的主体。
 *
 * <p>这个文件接替 {@code TransitionalConsumerIdentityLoaderTest} ——
 * 那个类存在的唯一理由是「不让所有商家店员在切换那天集体 401」，
 * 而它自己的注释写着「<b>A7 落地那天，这个类整个删掉</b>，
 * 判据是 usr_session 里不再出现 mch_account_no 形状的主体」。
 *
 * <p>删掉容易，把那条判据留下才是要紧的 —— 所以在这里换个形式钉住：
 * <b>谁签发的会话，主体属于哪张表，必须由工厂方法本身决定，不留第二种可能。</b>
 */
class PoolIsolationAfterA7Test {

    @Test
    @DisplayName("★★★ C 端池只装 usr_account 的号 —— 商家账号号不该再出现在这里")
    void consumerPoolCarriesOnlyUserAccounts() {
        LoginUser u = LoginUser.consumer("U202608181350550001913", "买家");
        assertThat(u.realm()).isEqualTo(Realm.CONSUMER);
        assertThat(u.subjectKind())
                .as("过渡期这里装过 mch_account_no —— 那正是 A7 要消灭的状态")
                .isEqualTo(SubjectKind.USR);
    }

    @Test
    @DisplayName("★★★ B 端池装两类：店员是 MCH，还没开店的人是 USR")
    void merchantPoolCarriesBothKinds() {
        // 店员：他可能根本没有 C 端账号（生产实测 9 个里 8 个如此）
        LoginUser staff = LoginUser.merchant("SF-M0001", "店员甲");
        assertThat(staff.realm()).isEqualTo(Realm.MERCHANT);
        assertThat(staff.subjectKind()).isEqualTo(SubjectKind.MCH);

        // 还没开店的人：btk_ 表示「这是 B 端的会话」，不表示「这个人是商家」
        LoginUser applicant = LoginUser.merchantByUser("U202608221744550003915", "老王");
        assertThat(applicant.realm())
                .as("他也在 B 端池里 —— 否则他登进 b-app 之后连入驻申请都提交不了")
                .isEqualTo(Realm.MERCHANT);
        assertThat(applicant.subjectKind()).isEqualTo(SubjectKind.USR);
    }

    @Test
    @DisplayName("★ 运营端只装 sys_ops_staff 的号")
    void operatorPoolCarriesOnlyStaff() {
        LoginUser ops = LoginUser.operator("ST-ADMIN", "超级管理员", java.util.List.of(), java.util.List.of());
        assertThat(ops.realm()).isEqualTo(Realm.OPERATOR);
        assertThat(ops.subjectKind()).isEqualTo(SubjectKind.OPS);
    }

    @Test
    @DisplayName("★★ 令牌前缀三端互不相同 —— 跨端令牌在第一道就被拒的前提")
    void tokenPrefixesAreDistinct() {
        assertThat(Realm.CONSUMER.tokenPrefix()).isEqualTo("ctk_");
        assertThat(Realm.MERCHANT.tokenPrefix()).isEqualTo("btk_");
        assertThat(Realm.OPERATOR.tokenPrefix()).isEqualTo("otk_");
    }
}
