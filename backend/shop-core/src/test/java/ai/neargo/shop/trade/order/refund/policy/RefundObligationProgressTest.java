package ai.neargo.shop.trade.order.refund.policy;

import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.trade.order.mapper.RefundObligationMapper;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionStore;
import ai.neargo.shop.trade.order.refund.service.impl.RefundObligationServiceImpl;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RefundObligationProgressTest {
    @Test void points_unknown_and_operator_required_are_not_reported_as_normal_processing() {
        var mapper = mock(RefundObligationMapper.class);
        var service = new RefundObligationServiceImpl(mapper, mock(RefundExecutionStore.class),
                mock(CouponPort.class), new ObjectMapper(), mock(PlatformTransactionManager.class));
        when(mapper.pointsSource("AS")).thenReturn(Map.of("closure_version", 2, "net_cash_minor", 50,
                "state", "CASH_PENDING", "phase", "CASH_PENDING", "after_sale_status", "REFUNDING"));
        assertThat(service.progress("PRF-AS").cashStatus()).isEqualTo("UNKNOWN");
        assertThat(service.progress("PRF-AS").financialState()).isEqualTo("UNKNOWN");

        when(mapper.pointsSource("AS")).thenReturn(Map.of("closure_version", 2, "net_cash_minor", 50,
                "state", "CASH_PENDING", "phase", "CASH_FAILED_PENDING_OPERATOR", "after_sale_status", "REFUNDING"));
        assertThat(service.progress("PRF-AS").financialState()).isEqualTo("MANUAL_REQUIRED");
    }
}
