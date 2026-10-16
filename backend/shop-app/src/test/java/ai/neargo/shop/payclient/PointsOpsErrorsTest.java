package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService;
import ai.neargo.shop.payclient.impl.PointsTreasuryAppServiceImpl;

import org.junit.jupiter.api.Test;

class PointsOpsErrorsTest {
    @Test
    void expected_points_rejections_are_business_errors_and_internal_failures_stay_internal() {
        var domain = mock(PointsTreasuryService.class);
        var app = new PointsTreasuryAppServiceImpl(domain);
        when(domain.requests("STALE", 0, 50))
                .thenThrow(new IllegalArgumentException("POINTS_REQUEST_REVISION_CONFLICT"));
        assertThatThrownBy(() -> app.requests("STALE", 0, 50))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode().code()).isEqualTo(50041));
        var unexpected = new IllegalStateException("POINTS_LEDGER_INVARIANT");
        when(domain.requests("BROKEN", 0, 50)).thenThrow(unexpected);
        assertThatThrownBy(() -> app.requests("BROKEN", 0, 50)).isSameAs(unexpected);
        var noMessage = new IllegalStateException();
        when(domain.requests("NO_MESSAGE", 0, 50)).thenThrow(noMessage);
        assertThatThrownBy(() -> app.requests("NO_MESSAGE", 0, 50)).isSameAs(noMessage);
    }
}
