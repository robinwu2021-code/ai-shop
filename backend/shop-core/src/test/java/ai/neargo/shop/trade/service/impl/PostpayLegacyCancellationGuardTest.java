package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.statemachine.Actor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Legacy prepayment closure cannot erase an independently accepted or issued unpaid reservation. */
class PostpayLegacyCancellationGuardTest {
    private final OrderServiceImpl service=mock(OrderServiceImpl.class,CALLS_REAL_METHODS);
    private final StoreOrderMapper children=mock(StoreOrderMapper.class);
    private final PaymentMapper payments=mock(PaymentMapper.class);
    private final StockPort stock=mock(StockPort.class);
    private final OrdOrder parent=new OrdOrder();
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service,"subOrderMapper",children);
        ReflectionTestUtils.setField(service,"orderMapper",payments);
        ReflectionTestUtils.setField(service,"stockPort",stock);
        parent.setOrderNo("ORDER"); parent.setStatus(OrdOrder.WAIT_PAY);
        var child=new OrdSubOrder(); child.setInventoryLifecycleVersion(3); child.setStatus(OrdSubOrder.COMPLETED);
        when(children.selectList(any())).thenReturn(List.of(child));
    }
    @Test void prepaymentTimeoutDoesNotCloseAnAcceptedPostpaidOrderOrReleaseItsStock() {
        assertThat(service.closeOne(parent,"expired")).isFalse();
        assertThat(parent.getStatus()).isEqualTo(OrdOrder.WAIT_PAY);
        assertThat(parent.getPaidAt()).isNull(); verifyNoInteractions(payments,stock);
    }
    @Test void legacyManualCancellationRejectsBeforeAnyMoneyStatusOrStockMutation() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service,"cancelPayment",parent,"cancel",
                Actor.SYSTEM,"SYSTEM","cancel"))
                .isInstanceOfSatisfying(BizException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL));
        assertThat(parent.getStatus()).isEqualTo(OrdOrder.WAIT_PAY); verifyNoInteractions(payments,stock);
    }
}
