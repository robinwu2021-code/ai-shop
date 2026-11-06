package ai.neargo.shop.portal.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.payclient.PayServiceApplyRequests;
import ai.neargo.shop.payclient.PaySourceLinkApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TDD-运营端申请支付服务 A6。 */
class OpsPayServiceControllerTest {

    @Test
    @DisplayName("★★ 内嵌部署没有独立支付服务：明确报依赖不可用，不碰主体、不假装成功")
    void embeddedDeploymentReportsUnavailable() {
        var reader = mock(StoreSnapshotReader.class);
        var none = new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(PaySourceLinkApi.class);
        var c = new OpsPayServiceController(reader, mock(StoreSyncProperties.class), none);
        assertThatThrownBy(() -> c.apply("M1", new PayServiceApplyRequests.Form("x", "1", null, null, null)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE));
        org.mockito.Mockito.verifyNoInteractions(reader);
    }
}
