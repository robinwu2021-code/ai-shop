package ai.neargo.shop.printing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.printing.policy.PrintDocumentPolicy;
import ai.neargo.shop.spi.printing.PrintPort.Document;
import ai.neargo.shop.spi.printing.PrintPort.Scope;
import ai.neargo.shop.spi.printing.PrintPort.Line;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Text protocol rejects executable templates and raw printer-control bytes before a job exists. */
class PrintDocumentPolicyTest {
    @Test void ownerScopeKeepsCanonicalSixtyFourCharacterKeysWithoutExpandingPrintKeys() {
        PrintDocumentPolicy.scope(new Scope(
                "E".repeat(64), "S".repeat(64), "A".repeat(64)));
        assertThatThrownBy(() -> PrintDocumentPolicy.scope(new Scope(
                "E".repeat(65), "S", "ACTOR"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> PrintDocumentPolicy.key("D".repeat(33))).isInstanceOf(BizException.class);
    }

    @Test void rejectExpressionAndPrinterCommandInjection() {
        assertThatThrownBy(() -> PrintDocumentPolicy.template("{{document}} ${runtime.exec('x')}"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> PrintDocumentPolicy.template("{{document}}\u001b@"))
                .isInstanceOf(BizException.class);
        var unsafe=new Document(1,"ORDER","BATCH","title",null,
                List.of(new Line("sku\u001b@","1",null,null)),"notice");
        assertThatThrownBy(() -> PrintDocumentPolicy.document(unsafe)).isInstanceOf(BizException.class);
    }
    @Test void renderRespectsCurrencyScaleAndKeepsReprintMarkEvenIfTemplateOmitsIt() {
        var yen=new Document(1,"PREBILL","CHECK","预结","JPY",
                List.of(new Line("SKU","1",123L,null)),"不代表收款");
        assertThat(PrintDocumentPolicy.render(yen,"{{document}}","PJ1","损坏"))
                .startsWith("REPRINT / 补打 PJ1 / 损坏").contains("JPY 123").doesNotContain("1.23");
        var dinar=new Document(1,"PREBILL","CHECK","预结","KWD",
                List.of(new Line("SKU","1",123L,null)),"不代表收款");
        assertThat(PrintDocumentPolicy.render(dinar,"{{document}}",null,null)).contains("KWD 0.123");
    }
    @Test void sourceLabelsAndReprintReasonsCannotBecomeTemplateInstructions() {
        var source=new Document(1,"ORDER","BATCH","title",null,
                List.of(new Line("literal {{reprint}} $5","1",null,null)),"notice");
        assertThat(PrintDocumentPolicy.render(source,"{{reprint}}{{document}}","PJ1","literal {{document}}"))
                .startsWith("REPRINT / 补打 PJ1 / literal {{document}}")
                .contains("literal {{reprint}} $5");
    }
    @Test void rejectMoneyReceiptScenesAndNegativeAmounts() {
        var paid=new Document(1,"RECEIPT","CHECK","receipt","CNY",
                List.of(new Line("SKU","1",100L,null)),"paid");
        assertThatThrownBy(() -> PrintDocumentPolicy.document(paid)).isInstanceOf(BizException.class);
        var negative=new Document(1,"PREBILL","CHECK","预结","CNY",
                List.of(new Line("SKU","1",-1L,null)),"不代表收款");
        assertThatThrownBy(() -> PrintDocumentPolicy.document(negative)).isInstanceOf(BizException.class);
    }
}
