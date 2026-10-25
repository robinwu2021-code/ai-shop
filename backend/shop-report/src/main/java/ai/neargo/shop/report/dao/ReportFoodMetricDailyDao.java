package ai.neargo.shop.report.dao;

import java.util.List;

import ai.neargo.shop.report.support.ReportEvidenceCodec;
import ai.neargo.shop.report.support.ReportDimensionKey;
import ai.neargo.shop.spi.report.ReportContracts.Attribution;
import ai.neargo.shop.spi.report.ReportContracts.BuildLease;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.ValueType;
import org.springframework.jdbc.core.simple.JdbcClient;

public class ReportFoodMetricDailyDao extends ReportDaoSupport {
    public ReportFoodMetricDailyDao(JdbcClient jdbc) { super(jdbc); }
    public static String dimensionKey(MetricFact fact) {
        return ReportDimensionKey.of(fact);
    }
    public void insert(BuildLease lease, MetricFact fact, String family) {
        var v = fact.value();
        partitioned("""
                INSERT INTO rpt_food_metric_daily(tenant_no,entity_no,store_no,business_date,date_basis,metric_version,
                  build_no,attempt_no,aggregation_set,metric_family,metric_code,attribution_basis,dimension_key,
                  dimensions,display_snapshot,value_type,count_value,quantity_value,amount_minor,currency,unit,
                  completeness,unknown_reason,known_count_value,known_quantity_value,known_amount_minor,
                  sku_no,menu_no,channel_code,station_no,material_sku_no,actor_no,created_at,updated_at)
                VALUES(:tenant,:entity,:store,:day,'BUSINESS_DATE',:version,:build,:attempt,:set,:family,:metric,:basis,:key,
                  :dimensions,:display,:type,:count,:quantity,:amount,:currency,:unit,:coverage,:reason,:knownCount,
                  :knownQuantity,:knownAmount,:sku,:menu,:channel,:station,:material,:actor,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """,lease.partition()).param("build",lease.buildNo()).param("attempt",lease.attemptNo())
                .param("set",fact.aggregationSet()).param("family",family).param("metric",fact.metricCode()).param("basis",fact.attribution().name())
                .param("key",dimensionKey(fact)).param("dimensions",ReportEvidenceCodec.json(fact.dimensions()))
                .param("display",ReportEvidenceCodec.json(fact.labels())).param("type",v.type().name()).param("count",v.integerValue())
                .param("quantity",v.quantityValue()).param("amount",v.amountMinor()).param("currency",v.currency()).param("unit",v.unit())
                .param("coverage",v.coverage().name()).param("reason",v.unknownReason()).param("knownCount",v.knownInteger())
                .param("knownQuantity",v.knownQuantity()).param("knownAmount",v.knownAmount()).param("sku",fact.dimensions().get("skuNo"))
                .param("menu",fact.dimensions().get("menuNo")).param("channel",fact.dimensions().get("channel"))
                .param("station",fact.dimensions().get("stationNo")).param("material",fact.dimensions().get("materialSkuNo"))
                .param("actor",fact.dimensions().get("actorNo")).update();
    }
    public List<MetricFact> find(Partition p, String build, long attempt) {
        return scoped("SELECT * FROM rpt_food_metric_daily WHERE "+SCOPE+" AND build_no=:build AND attempt_no=:attempt ORDER BY metric_code,dimension_key",p.scope())
                .param("build",build).param("attempt",attempt).query((rs,n) -> new MetricFact(rs.getString("metric_code"),rs.getString("aggregation_set"),
                        Attribution.valueOf(rs.getString("attribution_basis")),ReportEvidenceCodec.map(rs.getString("dimensions")),
                        ReportEvidenceCodec.map(rs.getString("display_snapshot")),new MetricValue(ValueType.valueOf(rs.getString("value_type")),
                        rs.getObject("count_value",Long.class),rs.getBigDecimal("quantity_value"),rs.getObject("amount_minor",Long.class),
                        rs.getString("currency"),rs.getString("unit"),Coverage.valueOf(rs.getString("completeness")),rs.getString("unknown_reason"),
                        rs.getObject("known_count_value",Long.class),rs.getBigDecimal("known_quantity_value"),rs.getObject("known_amount_minor",Long.class)))).list();
    }
}
