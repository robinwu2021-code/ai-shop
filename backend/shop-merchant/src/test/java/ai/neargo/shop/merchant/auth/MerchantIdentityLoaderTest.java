package ai.neargo.shop.merchant.auth;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** B 端身份加载。 */
class MerchantIdentityLoaderTest {

    private MerchantIdentityLoader loader(MchAccount stored) {
        MerchantMappers.MchAccountMapper mapper = mock(MerchantMappers.MchAccountMapper.class);
        when(mapper.selectOne(ArgumentMatchers.any())).thenReturn(stored);
        return new MerchantIdentityLoader(mapper);
    }

    private static MchAccount account(String no, String name, String status) {
        MchAccount a = new MchAccount();
        a.setMchAccountNo(no);
        a.setDisplayName(name);
        a.setStatus(status);
        a.setEntityNo("ENT-9");
        return a;
    }

    @Test
    @DisplayName("★ 商家身份的 realm 是 MERCHANT，主体是商家账号号")
    void merchantRealmAndSubject() {
        LoginUser u = loader(account("SF-M0001", "老王超市", "ACTIVE"))
                .load("SF-M0001").orElseThrow();

        assertEquals(Realm.MERCHANT, u.realm(),
                "签成 CONSUMER 的话，userNo 一个字段里又会同时装着两张表的主键");
        assertEquals("SF-M0001", u.userNo());
        assertEquals("老王超市", u.nickname());
    }

    @Test
    @DisplayName("★ 身份里不含实体与门店 —— 那两样由 X-Store-No 每请求现算")
    void identityCarriesNoStoreOrEntity() {
        LoginUser u = loader(account("SF-M0001", "老王超市", "ACTIVE"))
                .load("SF-M0001").orElseThrow();

        assertTrue(u.roles().isEmpty(), "B 端不是 RBAC，归属由 BizContext 表达");
        assertTrue(u.perms().isEmpty());
        assertFalse(u.dataScope().all(), "仍限定到自己");
        // 实体号在账号行上有（ENT-9），但**刻意没有**进身份：
        // 进了就有第二个真源，过期那份会让「切了门店但权限还是上一个店的」。
        // 用与账号号不相交的实体号，否则子串断言会被账号号本身命中 —— 第一版就栽在这
        assertFalse(u.toString().contains("ENT-9"),
                "实体号不该出现在身份里 —— 门店/实体由 BizIdentityResolver 现算并校验归属");
    }

    @Test
    @DisplayName("★ 停用的员工加载不到 —— 「停用后立即无法操作」多一道保险")
    void disabledAccountCannotLoad() {
        assertTrue(loader(account("SF-M0001", "老王超市", "DISABLED"))
                .load("SF-M0001").isEmpty());
    }

    @Test
    @DisplayName("查不到就是空，不给幽灵身份放行")
    void missingAccount() {
        assertTrue(loader(null).load("SF-GONE").isEmpty());
    }
}
