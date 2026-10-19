package ai.neargo.shop.scenario;

import ai.neargo.shop.support.BasicRefundFixture;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundDispatchResult.DispatchStatus;
import ai.neargo.shop.trade.order.refund.model.*;
import ai.neargo.shop.trade.order.refund.service.RefundResultService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:refund-closure-boundary;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class BasicRefundClosureBoundaryFlowTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired RefundResultService results;
    @Autowired ObjectMapper json;

    @Test void partialFrozenPlanCannotClaimFullGoodsClosure() throws Exception {
        try (var f = new BasicRefundFixture(context)) {
            var p=f.purchase("13207072101");
            f.prepare(p);
            String as=f.apply(p,"REFUND_ONLY");
            f.approve(p,as);
            var plan=json.readValue(jdbc.queryForObject("select plan_json from ord_refund_execution where after_sale_no=?",String.class,as),RefundPlan.class);
            var line=plan.lines().getFirst();
            var partial=new RefundPlan(plan.subOrderNo(),List.of(new RefundLine(line.itemId(),0,line.amountMinor()/2)),0);
            jdbc.update("update ord_refund_execution set plan_json=? where after_sale_no=?",json.writeValueAsString(partial),as);
            String payment=jdbc.queryForObject("select payment_no from stl_payment where after_sale_no=? and direction='REFUND'",String.class,as);
            assertThatThrownBy(() -> results.apply(new RefundDispatchResult("RF-"+as,payment,DispatchStatus.SUCCEEDED,null,false,System.currentTimeMillis())))
                    .isInstanceOf(ai.neargo.shop.common.BizException.class);
            assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?",String.class,as)).isEqualTo("REFUNDING");
            assertThat(jdbc.queryForObject("select refunded_minor from ord_sub_order where sub_order_no=?",Long.class,p.subNo())).isZero();
        }
    }
}
