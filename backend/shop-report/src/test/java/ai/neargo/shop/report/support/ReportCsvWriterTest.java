package ai.neargo.shop.report.support;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ai.neargo.shop.spi.report.ReportContracts.Attribution;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportExportPort.Snapshot;
import ai.neargo.shop.spi.report.ReportQueryPort.Mode;
import ai.neargo.shop.spi.report.ReportQueryPort.Query;
import ai.neargo.shop.spi.report.ReportQueryPort.Result;
import ai.neargo.shop.spi.report.ReportQueryPort.Row;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReportCsvWriterTest {
    @Test void preventsTextFormulaInjectionAndPreservesSignedMinorAmounts() {
        var scope=new Scope("MAIN","E1","=1+1");var date=LocalDate.of(2020,1,1);
        var fact=new MetricFact("ORDER_TRANSACTED_AMOUNT","OPERATING_ORDER_DAY",Attribution.ORIGINAL_ORDER,
                Map.of(),Map.of(),MetricValue.money(-123456789012345678L,"AED"));
        var query=new Query(List.of(scope),date,date.plusDays(1),1,"OPERATING_ORDER_DAY",Set.of(fact.metricCode()),Map.of(),Mode.DAILY,100,"ACTOR");
        var result=new Result(1,List.of(new Row(scope,date,fact)),List.of(),List.of(),true,List.of(scope),date,date.plusDays(1),"DAILY","BUSINESS_DATE",List.of());
        var bytes=ReportCsvWriter.write(new Snapshot(query,result));
        String text=new String(bytes,StandardCharsets.UTF_8);
        assertThat(text).startsWith("\uFEFF").contains("\"'=1+1\"").contains("\"-123456789012345678\"");
        assertThat(text).doesNotContain("\"'-123456789012345678\"");
        assertThat(ReportCsvWriter.checksum(bytes)).hasSize(64);
    }
}
