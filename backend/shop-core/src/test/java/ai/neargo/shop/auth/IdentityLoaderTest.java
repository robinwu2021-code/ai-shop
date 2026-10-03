package ai.neargo.shop.auth;

import ai.neargo.shop.platform.auth.OperatorIdentityLoader;
import ai.neargo.shop.platform.entity.SysOpsStaff;
import ai.neargo.shop.platform.mapper.PlatformMappers;
import ai.neargo.shop.user.auth.ConsumerIdentityLoader;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.mapper.UserMappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 身份加载器。**每一条断言都对应一种「不加载就会出事」的情况。**
 */
class IdentityLoaderTest {

    // ── C 端 ────────────────────────────────────────────────────────────

    private ConsumerIdentityLoader consumerLoader(UsrAccount stored) {
        UserMappers.UserMapper mapper = mock(UserMappers.UserMapper.class);
        when(mapper.selectOne(ArgumentMatchers.any())).thenReturn(stored);
        return new ConsumerIdentityLoader(mapper);
    }

    private static UsrAccount account(String userNo, String nickname, String status) {
        UsrAccount a = new UsrAccount();
        a.setUserNo(userNo);
        a.setNickname(nickname);
        a.setStatus(status);
        return a;
    }

    @Test
    @DisplayName("C 端：正常账号还原成 CONSUMER 身份")
    void consumerNormal() {
        LoginUser u = consumerLoader(account("U1", "小王", "NORMAL")).load("U1").orElseThrow();
        assertEquals(Realm.CONSUMER, u.realm());
        assertEquals("U1", u.userNo());
        assertEquals("小王", u.nickname());
    }

    @Test
    @DisplayName("★ C 端：封禁的账号加载不到 —— 不必等会话过期")
    void consumerBanned() {
        assertTrue(consumerLoader(account("U1", "小王", "BANNED")).load("U1").isEmpty());
    }

    @Test
    @DisplayName("★ C 端：查不到就是空，**不能返回一个空身份放行**")
    void consumerMissing() {
        assertTrue(consumerLoader(null).load("U-GONE").isEmpty(),
                "放行的话，那是没有任何权限的幽灵身份在系统里游走 —— "
                + "多数接口会挡住它所以不报错，直到碰上一个只判「登录了没」的接口");
    }

    // ── 运营端 ──────────────────────────────────────────────────────────

    private static SysOpsStaff staff(String no, String roles, String status,
                                     String merchantNo) {
        SysOpsStaff s = new SysOpsStaff();
        s.setStaffNo(no);
        s.setRealName("管理员");
        s.setRoles(roles);
        s.setStatus(status);
        s.setMerchantNo(merchantNo);
        return s;
    }

    private OperatorIdentityLoader operatorLoader(SysOpsStaff stored, List<String> resolved) {
        PlatformMappers.StaffMapper mapper = mock(PlatformMappers.StaffMapper.class);
        when(mapper.selectOne(ArgumentMatchers.any())).thenReturn(stored);
        LivePermResolver perms = roles -> resolved;
        return new OperatorIdentityLoader(mapper, perms);
    }

    @Test
    @DisplayName("★ 运营端：角色现读、权限现算 —— 改了角色下一个请求就生效")
    void operatorResolvesRolesAndPermsLive() {
        LoginUser u = operatorLoader(staff("S1", "[\"GOODS_OPS\"]", "ACTIVE", null),
                List.of("product:sku:read")).load("S1").orElseThrow();

        assertEquals(Realm.OPERATOR, u.realm());
        assertEquals(List.of("GOODS_OPS"), u.roles());
        assertEquals(List.of("product:sku:read"), u.perms(),
                "权限是现算的，不是会话里那份快照");
    }

    @Test
    @DisplayName("★ 运营端：数据域也现算，且与登录路径共用 StaffScopes")
    void operatorScopeIsComputed() {
        LoginUser limited = operatorLoader(staff("S1", "[\"GOODS_OPS\"]", "ACTIVE", "M0001"),
                List.of("product:sku:read")).load("S1").orElseThrow();
        assertFalse(limited.dataScope().all(), "配了归属键就该受限");

        LoginUser superAdmin = operatorLoader(staff("S2", "[\"SUPER_ADMIN\"]", "ACTIVE", "M0001"),
                List.of("*")).load("S2").orElseThrow();
        assertTrue(superAdmin.dataScope().all(),
                "全量角色一律不受限 —— 与 setStaffScope 拒绝给全量角色配数据域是同一条规矩");
    }

    @Test
    @DisplayName("★ 运营端：权限解析不出时给空权限，不能抛")
    void operatorPermResolutionFailureDegrades() {
        LoginUser u = operatorLoader(staff("S1", "[\"GOODS_OPS\"]", "ACTIVE", null), null)
                .load("S1").orElseThrow();
        assertTrue(u.perms().isEmpty(),
                "抛出去会让整个运营端 500；空权限至少让报错指向「权限」");
    }

    @Test
    @DisplayName("运营端：停用的账号加载不到")
    void operatorDisabled() {
        assertTrue(operatorLoader(staff("S1", "[]", "DISABLED", null), List.of())
                .load("S1").isEmpty());
    }

    @Test
    @DisplayName("运营端：roles 是坏 JSON 时当作空，不炸")
    void operatorBadRolesJson() {
        LoginUser u = operatorLoader(staff("S1", "不是JSON", "ACTIVE", null), List.of())
                .load("S1").orElseThrow();
        assertTrue(u.roles().isEmpty());
    }
}
