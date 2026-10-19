package ai.neargo.shop.pay.points.store.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.points.PointsConfigurationMapper;
import ai.neargo.shop.pay.points.statement.StatementBuildService.Build;
import ai.neargo.shop.pay.points.store.StatementBuildStore;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

@Repository
public class MybatisStatementBuildStore implements StatementBuildStore {
    private final PointsConfigurationMapper mapper;
    public MybatisStatementBuildStore(PointsConfigurationMapper mapper) { this.mapper=mapper; }
    @Override public List<Map<String,Object>> lines(String no,String build,String route,long cutoff,long after,int limit) {
        return DataScopeContext.executeWithoutScope(() -> mapper.statementBuildLines(no,build,route,cutoff,after,limit));
    }
    @Override public List<String> routes(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.statementBuildRoutes(no));
    }
    @Override public List<Build> pending(String type,String after,int limit) {
        return DataScopeContext.executeWithoutScope(() -> mapper.pendingStatementBuilds(type,after,limit)).stream()
                .map(row -> mapped((String)row.get("build_no"),row)).toList();
    }
    @Override public void published(String no) {
        require(DataScopeContext.executeWithoutScope(() -> mapper.publishStatementBuild(no))==1);
    }
    @Override public Build find(String no) {
        return mapped(no,DataScopeContext.executeWithoutScope(() -> mapper.statementBuild(no)));
    }
    @Override public Build lock(String no) {
        return mapped(no,DataScopeContext.executeWithoutScope(() -> mapper.lockStatementBuild(no)));
    }
    private Build mapped(String no,Map<String,Object> row) {
        if (row==null) return null;
        return new Build(no,(String)row.get("type_code"),(String)row.get("store_no"),
                n(row,"period_from"),n(row,"period_to"),n(row,"high_water"),n(row,"scan_cursor"),
                n(row,"source_count"),(String)row.get("state"),n(row,"version"));
    }
    @Override public long highWater(String type) {
        return DataScopeContext.executeWithoutScope(() -> mapper.statementBuildHighWater(type));
    }
    @Override public void create(Build b,String actor) {
        require(DataScopeContext.executeWithoutScope(() -> mapper.insertStatementBuild(b,actor))==1);
    }
    @Override public List<Map<String,Object>> page(Build b,int limit) {
        return DataScopeContext.executeWithoutScope(() -> mapper.statementBuildPage(b,limit));
    }
    @Override public void append(Build b,Map<String,Object> source,String route,long remaining) {
        require(DataScopeContext.executeWithoutScope(() -> mapper.insertStatementBuildItem(b.buildNo(),
                (String)source.get("position_no"),n(source,"ready_seq"),route,n(source,"version"),remaining))==1);
    }
    @Override public void advance(Build before,Build after) {
        require(DataScopeContext.executeWithoutScope(() -> mapper.advanceStatementBuild(before,after))==1);
    }
    @Override public void bind(String source,String type,String store,String statement) {
        require(DataScopeContext.executeWithoutScope(() -> mapper.bindStatementBuildSource(source,type,store,statement))==1);
    }
    @Override public Map<String,Object> firstSource(String statement) {
        return DataScopeContext.executeWithoutScope(() -> mapper.firstStatementBuildSource(statement));
    }
    @Override public Map<String,Object> totals(String statement) {
        return DataScopeContext.executeWithoutScope(() -> mapper.statementBuildTotals(statement));
    }
    private long n(Map<String,Object> row,String name) { return ((Number)row.get(name)).longValue(); }
    private void require(boolean value) { if (!value) throw new IllegalStateException("POINTS_STATEMENT_BUILD_CONFLICT"); }
}
