package ai.neargo.shop.scenario;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ai.neargo.shop.pay.service.impl.*;
import ai.neargo.shop.pay.service.ReconService;
import ai.neargo.shop.pay.numbering.PayDocumentNumberConfiguration;
import ai.neargo.shop.pay.numbering.PayOperationsDocuments;
import ai.neargo.shop.spi.pay.*;
import ai.neargo.shop.spi.settle.RefundRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import java.util.regex.Pattern;

@SpringJUnitConfig(RefundReconPersistenceTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:refund-recon-followup;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver"})
class RefundReconPersistenceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired RefundPaymentStore refunds;
    @Autowired PayQueryPort query;
    @MockitoSpyBean ReconServiceImpl recon;
    @BeforeAll void schema() throws Exception {
        String ddl=new ClassPathResource("schema-test.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        for(String table:List.of("stl_payment","stl_recon_diff","pay_document_sequence","pay_document_number")) {
            var matcher=Pattern.compile("CREATE TABLE IF NOT EXISTS "+table+"\\b.*?;",Pattern.DOTALL).matcher(ddl);
            assertThat(matcher.find()).isTrue();jdbc.execute(matcher.group());
        }
    }
    @BeforeEach void clear() { jdbc.update("delete from stl_recon_diff");jdbc.update("delete from stl_payment");reset(query);clearInvocations(recon); }
    String refund() {
        String no="R"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("insert into stl_payment(payment_no,direction,order_no,user_no,amount_minor,currency,status,pay_channel,trade_no,out_trade_no,created_at,updated_at) values(?,'PAY',?,'U',100,'CNY','SUCCESS','TEST',?,?,NOW(),NOW())",no,no,no,no);
        var row=refunds.prepare(new RefundRequest("E:"+no,"AS:"+no,"SUB:"+no,no,100,"CNY","refund"));
        jdbc.update("update stl_payment set status='PENDING',created_at='2020-01-01 00:00:00' where payment_no=?",row.getPaymentNo());
        return row.getPaymentNo();
    }
    void answer(String payment,RefundQueryResult.Status status) {
        String out=jdbc.queryForObject("select out_trade_no from stl_payment where payment_no=?",String.class,payment);
        when(query.queryRefundOutcome("TEST",out)).thenReturn(new RefundQueryResult(status,status.name(),out,100,"BANK","CNY",1));
    }
    @Test void failedTerminalArchiveIsRetriedWithoutAnotherChannelQuery() {
        String payment=refund();answer(payment,RefundQueryResult.Status.TERMINAL_FAILED);
        doThrow(new IllegalStateException("archive unavailable")).doCallRealMethod()
                .when(recon).recordFinding(any(),anyString(),anyString());
        recon.checkStalePayments(System.currentTimeMillis());
        recon.checkStalePayments(System.currentTimeMillis());
        assertThat(jdbc.queryForObject("select count(*) from stl_recon_diff where payment_no=?",Integer.class,payment)).isEqualTo(1);
        verify(query,times(1)).queryRefundOutcome(anyString(),anyString());
    }
    @Test void blankTerminalProofCanBeRepairedByAClosedReceipt() {
        String payment=refund();answer(payment,RefundQueryResult.Status.TERMINAL_FAILED);
        jdbc.update("update stl_payment set status='FAILED',refund_terminal_proof='' where payment_no=?",payment);
        recon.checkStalePayments(System.currentTimeMillis());
        assertThat(jdbc.queryForObject("select refund_terminal_proof from stl_payment where payment_no=?",String.class,payment)).contains("TERMINAL_FAILED");
    }
    @Test void lateSuccessHasItsOwnPendingIssueAfterAnEarlierIssueWasResolved() {
        String payment=refund();answer(payment,RefundQueryResult.Status.UNKNOWN);
        recon.checkStalePayments(System.currentTimeMillis());
        jdbc.update("update stl_recon_diff set status='RESOLVED' where payment_no=?",payment);
        jdbc.update("update stl_payment set status='FAILED',refund_query_state='LATE_SUCCESS' where payment_no=?",payment);
        recon.checkStalePayments(System.currentTimeMillis());
        assertThat(jdbc.queryForObject("select count(*) from stl_recon_diff where payment_no=? and status='PENDING' and resolution like '%LATE_SUCCESS%'",Integer.class,payment)).isEqualTo(1);
    }
    @Configuration @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass=true)
    @Import({RefundCapacityDatabaseTest.Config.class,ReconServiceImpl.class,
            PayDocumentNumberConfiguration.class,PayOperationsDocuments.class})
    static class Config { @Bean PayQueryPort query() { return mock(PayQueryPort.class); } }
}
