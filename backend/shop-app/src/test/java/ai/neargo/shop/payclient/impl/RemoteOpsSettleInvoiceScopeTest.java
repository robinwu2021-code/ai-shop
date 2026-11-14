package ai.neargo.shop.payclient.impl;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.common.data.scope.DataScopeSpec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F10 · Review R10：独立形态下发票列表的主体范围由主应用算好带过去（TDD-支付服务-I1存量缺陷）。
 * 宁严勿宽：有范围但不是「商户」维度时，一个都不给看，而不是退化成不限。
 */
class RemoteOpsSettleInvoiceScopeTest {

    @Test
    @DisplayName("★★★ F10 受限运营只带自己能看的主体；不限才是不限")
    void scopeFollowsTheCurrentAccount() {
        assertThat(RemoteOpsSettleInvoiceAppService.merchantScope(DataScopeSpec.of("MERCHANT", Set.of("M1", "M2"))))
                .containsExactlyInAnyOrder("M1", "M2");
        assertThat(RemoteOpsSettleInvoiceAppService.merchantScope(DataScopeSpec.ALL)).isNull();
        assertThat(RemoteOpsSettleInvoiceAppService.merchantScope(null))
                .as("没有上下文 = 与内嵌形态下拦截器一致，不收窄").isNull();
    }

    @Test
    @DisplayName("★★ F10 有范围但没有商户维度（例如只限社区）→ 一个都不给看，不退化成不限")
    void nonMerchantScopeFailsClosed() {
        var communityOnly = new DataScopeSpec(false, List.of(new DataScopeSpec.Rule("COMMUNITY", Set.of("C1"))));
        assertThat(RemoteOpsSettleInvoiceAppService.merchantScope(communityOnly)).isEmpty();
        assertThat(RemoteOpsSettleInvoiceAppService.merchantScope(new DataScopeSpec(false, List.of()))).isEmpty();
    }
}
