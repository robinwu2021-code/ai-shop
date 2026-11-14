package ai.neargo.shop.spi.report;

import java.time.LocalDate;
import java.time.Instant;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.BuildProblem;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Publication;
import ai.neargo.shop.spi.report.ReportContracts.Scope;

/** Trusted internal boundary. The industry adapter must authorize every supplied store. */
public interface ReportQueryPort {
    record MeasureFilter(String currency,String unit) {
        public MeasureFilter {
            if(currency!=null&&!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("REPORT_INVALID_CURRENCY");
            if(unit!=null) ReportContracts.identifier(unit,32);
        }
        public Map<String,String> filters() {
            var filters=new LinkedHashMap<String,String>();
            if(currency!=null) filters.put("currency",currency);if(unit!=null) filters.put("unit",unit);return Map.copyOf(filters);
        }
    }
    enum Mode { DAILY, PERIOD, RANKING, WEEKLY, MONTHLY, HEALTH }
    record Query(List<Scope> scopes, LocalDate from, LocalDate to, Integer metricVersion,
            String aggregationSet, Set<String> metricCodes, Map<String,String> filters, Mode mode, int limit,String actorNo) {
        public Query {
            ReportContracts.identifier(actorNo,64);
            scopes=List.copyOf(scopes); filters=Map.copyOf(filters);
            // Sorted, not Set.copyOf: the export request/snapshot hash keeps array order, and
            // Set.copyOf iterates in a per-JVM random order, so a task created on one instance
            // would read back as REPORT_EXPORT_SNAPSHOT_CORRUPT after a restart or on another.
            metricCodes=Collections.unmodifiableSortedSet(new TreeSet<>(metricCodes));
            if (scopes.isEmpty() || scopes.size()>30 || Set.copyOf(scopes).size()!=scopes.size()
                    || from==null || to==null || !from.isBefore(to) || from.plusDays(366).isBefore(to)
                    || metricCodes.isEmpty() || metricCodes.size()>64 || filters.size()>20 || mode==null
                    || limit<1 || limit>1000 || metricVersion!=null && metricVersion<1)
                throw new IllegalArgumentException("REPORT_INVALID_QUERY");
            ReportContracts.identifier(aggregationSet,48);
            String tenant=scopes.getFirst().tenantNo(); String entity=scopes.getFirst().entityNo();
            if (scopes.stream().anyMatch(s->!s.tenantNo().equals(tenant)||!s.entityNo().equals(entity)))
                throw new IllegalArgumentException("REPORT_CROSS_ENTITY_QUERY");
        }
    }
    record Row(Scope scope, LocalDate businessDate, MetricFact fact) {}
    record SourceWatermark(Partition partition,SourceKey source,Instant asOfAt,Coverage coverage,String reason) {}
    record Result(int metricVersion, List<Row> rows, List<Publication> builds,
            List<Partition> missingDays, boolean complete,List<Scope> scopes,LocalDate from,LocalDate to,
            String dataMode,String dateBasis,List<SourceWatermark> sourceWatermarks,List<BuildProblem> buildProblems) {
        public Result(int metricVersion,List<Row> rows,List<Publication> builds,List<Partition> missingDays,boolean complete,
                List<Scope> scopes,LocalDate from,LocalDate to,String dataMode,String dateBasis,List<SourceWatermark> sourceWatermarks) {
            this(metricVersion,rows,builds,missingDays,complete,scopes,from,to,dataMode,dateBasis,sourceWatermarks,List.of());
        }
        public Result { rows=List.copyOf(rows); builds=List.copyOf(builds); missingDays=List.copyOf(missingDays);
            scopes=List.copyOf(scopes); sourceWatermarks=List.copyOf(sourceWatermarks);buildProblems=buildProblems==null?List.of():List.copyOf(buildProblems); }
    }
    Result query(Query query);
}
