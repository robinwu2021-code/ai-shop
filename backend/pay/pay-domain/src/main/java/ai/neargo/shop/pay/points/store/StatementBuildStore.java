package ai.neargo.shop.pay.points.store;

import ai.neargo.shop.pay.points.statement.StatementBuildService.Build;
import java.util.List;
import java.util.Map;

public interface StatementBuildStore {
    java.util.List<Map<String,Object>> lines(String statementNo,String buildNo,String routeKey,long cutoff,long after,int limit);
    java.util.List<String> routes(String buildNo);
    java.util.List<Build> pending(String typeCode,String after,int limit);
    void published(String buildNo);
    Build find(String buildNo);
    Build lock(String buildNo);
    long highWater(String typeCode);
    void create(Build build, String actor);
    List<Map<String, Object>> page(Build build, int limit);
    void append(Build build, Map<String, Object> source, String routeKey, long remaining);
    void advance(Build before, Build after);
    void bind(String sourceNo, String typeCode, String storeNo, String statementNo);
    Map<String,Object> totals(String statementNo);
    Map<String,Object> firstSource(String statementNo);

}
