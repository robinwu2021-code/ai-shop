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

    public MybatisStatementStore(PointsConfigurationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Map<String, Object> lock(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockStatement(no));
    }

    @Override
    public Map<String, Object> lockSource(String no) {
        return DataScopeContext.executeWithoutScope(() -> {
            var row=mapper.lockStatementSource(no);
            if (row!=null && row.get("statement_no")!=null) {
                var head=mapper.lockStatement((String)row.get("statement_no"));
                String effective=ai.neargo.shop.pay.points.statement.StatementSourceState.effective(row,head);
                if (!effective.equals(row.get("state"))) {
                    row=new java.util.HashMap<>(row); row.put("raw_state",row.get("state")); row.put("state",effective);
                }
            }
            return row;
        });
    }

    @Override public Map<String,Object> firstSource(String statement) {
        return DataScopeContext.executeWithoutScope(() -> mapper.firstStatementBuildSource(statement));
    }

    @Override
    public List<Map<String, Object>> forPeriod(String type, String store, long from) {
        return DataScopeContext.executeWithoutScope(
                () -> mapper.statementsForPeriod(type, store, from));
    }

    @Override
    public List<Map<String, Object>> candidates(String type, String store, long toMillis) {
        return DataScopeContext.executeWithoutScope(() -> mapper.statementCandidates(type, store, toMillis));
    }

    @Override
    public List<Map<String, Object>> debts(String type, String store) {
        return DataScopeContext.executeWithoutScope(() -> mapper.statementDebts(type, store));
    }
}
