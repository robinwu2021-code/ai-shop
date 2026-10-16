package ai.neargo.shop.pay.points.store.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.points.PointsConfigurationMapper;
import ai.neargo.shop.pay.points.store.StatementStore;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** Internal statement orchestration owns authorization; SQL stays confined to the pay store. */
@Repository
public class MybatisStatementStore implements StatementStore {
    private final PointsConfigurationMapper mapper;
    public MybatisStatementStore(PointsConfigurationMapper mapper) { this.mapper = mapper; }
    @Override public Map<String, Object> lock(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockStatement(no));
    }
    @Override public Map<String, Object> lockSource(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockStatementSource(no));
    }
    @Override public List<Map<String, Object>> candidates(String type, String store) {
        return DataScopeContext.executeWithoutScope(() -> mapper.statementCandidates(type, store));
    }
}
