package ai.neargo.shop.commercebridge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 门店准入表能被读出来，与网关的资源行放在一起看（TDD-权限统一到网关层-修复方案 第 1 步）。 */
@DisplayName("门店准入 · 路由表可读")
class StoreAdmissionRoutesTest {

    @Test
    @DisplayName("★★ 只描述 B 端入口；路径变量、通配方法都能匹配")
    void describesMerchantRoutesOnly() {
        assertThat(StoreAdmissionRoutes.describe("GET", "/ops/auth/me")).isNull();
        assertThat(StoreAdmissionRoutes.describe("GET", "/mp/goods")).isNull();
        assertThat(StoreAdmissionRoutes.describe("GET", null)).isNull();
        // 登录、建店这类账户级入口不要求选门店
        assertThat(StoreAdmissionRoutes.describe("POST", "/biz/merchant/quick-start")).isNotBlank();
        assertThat(StoreAdmissionRoutes.describe("*", "/biz/goods")).isNotBlank();
        assertThat(StoreAdmissionRoutes.describe("GET", "/biz/stores/{storeNo}/features")).isNotBlank();
    }

    @Test
    @DisplayName("★★★ 没登记进准入表的门店路由默认关闭：它在这里显示为 DENY，而不是悄悄没有")
    void unknownStoreRouteIsShownAsDenied() {
        assertThat(StoreAdmissionRoutes.describe("GET", "/biz/stores/{storeNo}/no-such-feature")).startsWith("DENY");
    }
}
