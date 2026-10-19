package ai.neargo.shop.scenario;

import ai.neargo.shop.support.BasicRefundFixture;
import ai.neargo.shop.portal.port.CouponPortRouter;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import ai.neargo.shop.pay.channel.TestPayGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.WebApplicationContext;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:refund-obligation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "shop.refund.closure.enabled=true"})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class RefundClosureObligationFlowTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired CouponService coupons;
    @Autowired BasicRefundExecutionService refunds;
    @MockitoSpyBean CouponPortRouter couponPort;
    @MockitoSpyBean TestPayGateway gateway;

    @Test void couponFailureRetainsCashSuccessAndRestartsOnlyBenefits() throws Exception {
        String coupon=coupons.save("M0001",new CouponSaveCmd(null,"退款结清", "CASH",300L,
                null,null,1000L,null,"ALL",List.of(),null,"RELATIVE",null,null,7,
                "CENTER","ORDER",1,10,1,null),"TEST").couponNo();
        try (var f=new BasicRefundFixture(context)) {
            String phone="13207072201";
            var received=f.request(f.login(phone),"/mp/coupon/"+coupon+"/receive","{}");
            assertThat(received.path("code").asInt()).isZero();
            String held=received.path("data").path("userCouponNo").asString();
            var p=f.purchase(phone,held);
            f.prepare(p);
            String as=f.apply(p,"REFUND_ONLY");
            f.approve(p,as);
            doThrow(new IllegalStateException("COUPON_TEMPORARILY_UNAVAILABLE")).when(couponPort).release(p.payNo());
            f.confirmCash(as);
            assertThat(value("status",as)).isEqualTo("SUCCEEDED");
            assertThat(value("closure_status",as)).isEqualTo("PENDING");
            assertThat(afterSale(as)).isEqualTo("REFUNDING");
            var view=context.getBean(ai.neargo.shop.trade.service.AfterSaleService.class).merchantList(f.merchant(p),null)
                    .stream().filter(v -> as.equals(v.afterSaleNo())).findFirst().orElseThrow();
            var progress=context.getBean(tools.jackson.databind.ObjectMapper.class)
                    .readTree(context.getBean(tools.jackson.databind.ObjectMapper.class).writeValueAsString(view)).path("refundExecution");
            assertThat(progress.path("closureStatus").asString()).isEqualTo("PENDING");
            assertThat(progress.path("cashStatus").asString()).isEqualTo("CONFIRMED");
            assertThat(progress.path("benefitsStatus").asString()).isEqualTo("PENDING");
            assertThat(value("resource_status",as)).isEqualTo("DONE");
            assertThat(refunds.recoverDue(System.currentTimeMillis()+300000,200)).isPositive();
            assertThat(afterSale(as)).isEqualTo("REFUNDING");
            assertThat(jdbc.queryForObject("select refunded_minor from ord_sub_order where sub_order_no=?",Long.class,p.subNo())).isPositive();
            doCallRealMethod().when(couponPort).release(p.payNo());
            refunds.recover("RF-"+as);
            refunds.recover("RF-"+as);
            assertThat(afterSale(as)).isEqualTo("REFUNDED");
            assertThat(value("closure_status",as)).isEqualTo("DONE");
            assertThat(jdbc.queryForObject("select status from pmt_user_coupon where user_coupon_no=?",String.class,held)).isEqualTo("UNUSED");
            assertThat(jdbc.queryForObject("select count(*) from ord_refund_obligation where execution_no=? and kind='COUPON' and state='SUCCEEDED'",Long.class,"RF-"+as)).isEqualTo(1);
            verify(gateway,times(1)).refund(any(),anyLong(),anyString(),anyString());
        }
    }

    @Test void noCouponRequiresNoExternalBenefitCallAndPersistsProtocolVersion() throws Exception {
        try(var f=new BasicRefundFixture(context)) {
            var p=f.purchase("13207072202");
            f.prepare(p);
            String as=f.apply(p,"REFUND_ONLY"); f.approve(p,as); f.confirmCash(as);
            assertThat(jdbc.queryForObject("select closure_version from ord_refund_execution where after_sale_no=?",Integer.class,as)).isEqualTo(2);
            assertThat(value("closure_status",as)).isEqualTo("DONE");
            assertThat(afterSale(as)).isEqualTo("REFUNDED");
            verify(couponPort,never()).release(p.payNo());
        }
    }

    private String value(String col,String as) {
        return jdbc.queryForObject("select "+col+" from ord_refund_execution where after_sale_no=?",String.class,as);
    }
    private String afterSale(String as) {return jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?",String.class,as);}
}
