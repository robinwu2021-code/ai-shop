package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B 端权限模型（纯计算，不起 Spring）。
 *
 * <p>守的是这套模型里最容易写错的四件事：并集、零权限默认、切店换角色、
 * 以及「店长碰不了钱」这条产品判断 —— 最后一条会被反复问，钉死它比在文档里写十遍有用。
 */
class BizPermsTest {

    @Test
    @DisplayName("★★ 多角色取并集 —— 店员 + 配送员 = 两样都能干")
    void multipleRolesUnion() {
        Set<String> both = Set.of(BizPerms.CLERK, BizPerms.COURIER);

        assertThat(BizPerms.can(both, BizPerms.VERIFY)).as("店员带来的核销").isTrue();
        assertThat(BizPerms.can(both, BizPerms.SHIP)).as("配送员带来的发货").isTrue();
        assertThat(BizPerms.can(both, BizPerms.STOCK)).isTrue();

        // 并集不该凭空长出谁都没有的权限
        assertThat(BizPerms.can(both, BizPerms.GOODS)).as("两个角色都没有改价").isFalse();
        assertThat(BizPerms.can(both, BizPerms.FINANCE)).isFalse();
    }

    @Test
    @DisplayName("★★ 空角色 = 零权限，不是「默认店员」")
    void noRoleMeansNoPermission() {
        for (String code : Set.of(BizPerms.VERIFY, BizPerms.RECEIVE, BizPerms.ORDER_VIEW,
                BizPerms.STOCK, BizPerms.FINANCE)) {
            assertThat(BizPerms.can(Set.of(), code))
                    .as("认不出角色时给权限，是这类判定最坏的失败方式")
                    .isFalse();
            assertThat(BizPerms.can(null, code)).isFalse();
        }
    }

    @Test
    @DisplayName("★ 认不出的角色码不放行 —— 库里出现一个新词不能等于开门")
    void unknownRoleGrantsNothing() {
        assertThat(BizPerms.can(Set.of("SOMETHING_NEW"), BizPerms.ORDER_VIEW)).isFalse();
        // 但它与已知角色并存时，已知的那份仍然生效
        assertThat(BizPerms.can(Set.of("SOMETHING_NEW", BizPerms.CLERK), BizPerms.VERIFY)).isTrue();
    }

    @Test
    @DisplayName("★★ 店长碰不了钱、也管不了员工 —— 这两条会被反复问")
    void managerTouchesNeitherMoneyNorStaff() {
        Set<String> mgr = Set.of(BizPerms.MANAGER);

        assertThat(BizPerms.can(mgr, BizPerms.FINANCE))
                .as("店长不是主体负责人，结算账户绑的是执照")
                .isFalse();
        assertThat(BizPerms.can(mgr, BizPerms.STORE_ADMIN))
                .as("授权别人 = 扩散权限；建店停店改的是主体结构")
                .isFalse();

        // 但经营面是全的
        assertThat(BizPerms.can(mgr, BizPerms.GOODS)).isTrue();
        assertThat(BizPerms.can(mgr, BizPerms.STORE)).as("改自己店的装修可以").isTrue();
    }

    @Test
    @DisplayName("★ 履约三档各归各：理货不核销、配送不进货、店员两样都做")
    void fulfillmentSplitsThreeWays() {
        assertThat(BizPerms.can(Set.of(BizPerms.PICKER), BizPerms.RECEIVE)).isTrue();
        assertThat(BizPerms.can(Set.of(BizPerms.PICKER), BizPerms.VERIFY))
                .as("核销要面对顾客，理货员只对货").isFalse();
        assertThat(BizPerms.can(Set.of(BizPerms.PICKER), BizPerms.ORDER_VIEW))
                .as("分拣单本身够用且不含金额，给订单权限是多给").isFalse();

        assertThat(BizPerms.can(Set.of(BizPerms.COURIER), BizPerms.SHIP)).isTrue();
        assertThat(BizPerms.can(Set.of(BizPerms.COURIER), BizPerms.RECEIVE)).isFalse();

        assertThat(BizPerms.can(Set.of(BizPerms.CLERK), BizPerms.RECEIVE)).isTrue();
        assertThat(BizPerms.can(Set.of(BizPerms.CLERK), BizPerms.VERIFY)).isTrue();
    }

    @Test
    @DisplayName("★ 客服只对着顾客说话：评价/售后/看单，不碰货与钱")
    void csOnlyTalksToCustomers() {
        Set<String> cs = Set.of(BizPerms.CS);
        assertThat(BizPerms.can(cs, BizPerms.REVIEW)).isTrue();
        assertThat(BizPerms.can(cs, BizPerms.AFTERSALE)).isTrue();
        assertThat(BizPerms.can(cs, BizPerms.ORDER_VIEW)).isTrue();

        assertThat(BizPerms.can(cs, BizPerms.VERIFY)).isFalse();
        assertThat(BizPerms.can(cs, BizPerms.STOCK)).isFalse();
        assertThat(BizPerms.can(cs, BizPerms.FINANCE)).isFalse();
        assertThat(BizPerms.can(cs, BizPerms.CUSTOMER))
                .as("处理售后要的是订单历史，不是累计消费额排行")
                .isFalse();
    }

    @Test
    @DisplayName("★ 老板通配一切")
    void ownerHasEverything() {
        for (String code : Set.of(BizPerms.FINANCE, BizPerms.STORE_ADMIN, BizPerms.GOODS,
                BizPerms.VERIFY, BizPerms.CUSTOMER)) {
            assertThat(BizPerms.can(Set.of(BizPerms.OWNER), code)).isTrue();
        }
        assertThat(BizPerms.of(Set.of(BizPerms.OWNER))).containsExactly("*");
    }

    @Test
    @DisplayName("★★ 切门店要换角色 —— 同一个人可能 A 店店长、B 店店员")
    void rolesFollowCurrentStore() {
        BizContext ctx = new BizContext("M1", Set.of(), Set.of(), Set.of("ST-A", "ST-B"),
                "ST-A", false,
                Map.of("ST-A", Set.of(BizPerms.MANAGER), "ST-B", Set.of(BizPerms.CLERK)));

        assertThat(ctx.staffRoles()).containsExactly(BizPerms.MANAGER);
        assertThat(ctx.can(BizPerms.GOODS)).as("在 A 店是店长，能改价").isTrue();

        BizContext atB = ctx.withStore("ST-B");
        assertThat(atB.staffRoles()).containsExactly(BizPerms.CLERK);
        assertThat(atB.can(BizPerms.GOODS))
                .as("切到 B 店他只是店员 —— 角色跟着门店走，不跟着人走")
                .isFalse();
        assertThat(atB.can(BizPerms.VERIFY)).isTrue();

        // 没被授权的第三家店：零权限
        assertThat(ctx.withStore("ST-C").staffRoles()).isEmpty();
        assertThat(ctx.withStore("ST-C").can(BizPerms.VERIFY)).isFalse();
    }

    @Test
    @DisplayName("★ 老板不受门店授权限制 —— 他不在 mch_store_role 里")
    void ownerIgnoresStoreGrants() {
        BizContext owner = new BizContext("M1", Set.of(), Set.of(), Set.of("ST-A"),
                "ST-A", true, Map.of());
        assertThat(owner.staffRoles()).containsExactly(BizPerms.OWNER);
        assertThat(owner.can(BizPerms.FINANCE)).isTrue();
        // 切到任何一家店都还是老板
        assertThat(owner.withStore("ST-X").can(BizPerms.FINANCE)).isTrue();
    }
}
