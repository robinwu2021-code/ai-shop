package ai.neargo.shop.spi.report;

import java.util.List;
import java.util.Set;
import ai.neargo.shop.spi.report.ReportContracts.Aggregation;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.ValueType;

public interface ReportMetricCatalogPort {
    record MetricDefinition(String code,SourceKey source,ValueType valueType,Aggregation aggregation,
            Set<String> aggregationSets,Set<String> dimensions,boolean negativeAllowed,String availability) {}
    List<MetricDefinition> metrics();
}
