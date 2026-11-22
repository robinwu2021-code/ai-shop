package ai.neargo.shop.invoice.channel.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt.Outcome;
import org.junit.jupiter.api.Test;

/** 码表：按报文种类优先、再查通用码；不分大小写；未登记一律 FINAL；同码重复登记启动即失败（TDD-发票模块 §19）。 */
class ResultCodeTableTest {

    final ResultCodeTable table = ResultCodeTable.builder()
            .acceptedFor("VOID", "C0").acceptedFor("ALLOWANCE_VOID", "C0", "C4")
            .accountProblem("invalid").retryable("error").build();

    @Test
    void ac41TypedCodesWinThenCommonCodesThenFinal() {
        assertEquals(Outcome.ACCEPTED, table.classify("ALLOWANCE_VOID", "c4"));
        assertEquals(Outcome.FINAL, table.classify("VOID", "C4"), "C4 只在折让作废里算送达");
        assertEquals(Outcome.ACCOUNT_PROBLEM, table.classify("ISSUE", " InValid "));
        assertEquals(Outcome.RETRYABLE, table.classify("ISSUE", "ERROR"));
        assertEquals(Outcome.FINAL, table.classify("ISSUE", "M:CarrierId1"), "不认识的码不重发");
        assertEquals(Outcome.FINAL, table.classify("ISSUE", null));
    }

    @Test
    void ac41DuplicateCodeIsAConfigurationError() {
        assertThrows(IllegalStateException.class,
                () -> ResultCodeTable.builder().retryable("error").accountProblem("ERROR"));
    }
}
