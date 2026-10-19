package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.support.BasicRefundFixture;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Cash success survives a resource error; the existing case recovers without another cash request. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:refund-resource;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class RefundResourceRecoveryFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private TestPayGateway gateway;

    @Test
    void opsRecoversResourcesOfAlreadyRefundedCaseWithoutRedispatchingCash() throws Exception {
        try (var fixture = new BasicRefundFixture(context)) {
            var purchase = fixture.purchase("13207071701");
            String no = fixture.apply(purchase, "REFUND_ONLY");
            fixture.approve(purchase, no);
            jdbc.update("update ord_refund_execution set stock_authority='INVENTORY' where after_sale_no=?", no);
            fixture.confirmCash(no);
            assertThat(value("status", no)).isEqualTo("SUCCEEDED");
            assertThat(value("resource_status", no)).isEqualTo("PENDING");
            assertThat(value("resource_error_code", no)).isEqualTo("STOCK_SOURCE_AUTHORITY_MISMATCH");
            assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?",
                    String.class, no)).isEqualTo("REFUNDED");
            jdbc.update("update ord_refund_execution set stock_authority='PLATFORM' where after_sale_no=?", no);
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            String admin = TestLogin.admin(mvc, json);
            var response = fixture.request(admin, "/ops/after-sales/" + no + "/recover", "{}");
            assertThat(response.path("code").asInt()).as(response.toString()).isZero();
            assertThat(response.path("data").path("refundExecution").path("resourceStatus").asString())
                    .isEqualTo("DONE");
            fixture.request(admin, "/ops/after-sales/" + no + "/recover", "{}");
            assertThat(value("resource_status", no)).isEqualTo("DONE");
            assertThat(jdbc.queryForObject("select sum(qty) from prd_stock_restore where purpose_no=?",
                    Long.class, "RF-" + no)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select count(*) from stl_payment where after_sale_no=?"
                    + " and direction='REFUND' and status='SUCCESS'", Long.class, no)).isEqualTo(1L);
            verify(gateway, times(1)).refund(any(), anyLong(), anyString(), anyString());
        }
    }

    private String value(String column, String no) {
        return jdbc.queryForObject("select " + column + " from ord_refund_execution where after_sale_no=?",
                String.class, no);
    }
}
