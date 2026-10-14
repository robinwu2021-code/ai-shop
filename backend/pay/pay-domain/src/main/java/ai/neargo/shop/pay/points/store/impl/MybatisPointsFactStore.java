package ai.neargo.shop.pay.points.store.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.points.PointsConfigurationMapper;
import ai.neargo.shop.pay.points.store.PointsFactStore;
import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;
import org.springframework.stereotype.Repository;
import java.util.Map;

/** Source metadata never increments accounts or changes funds. */
@Repository
public class MybatisPointsFactStore implements PointsFactStore {
    private final PointsConfigurationMapper mapper;
    public MybatisPointsFactStore(PointsConfigurationMapper mapper) { this.mapper = mapper; }
    @Override public Map<String,Object> inbox(String event) {
        return DataScopeContext.executeWithoutScope(() -> mapper.inbox(event));
    }
    @Override public void record(LifecycleFact fact, String payload, String state) {
        DataScopeContext.executeWithoutScope(() -> mapper.inboxInsert(fact.typeCode(),state,payload,
                fact.eventNo(),fact.payloadHash()));
    }
    @Override public void saveLifecycle(LifecycleFact fact, String payload, Long expected) {
        int changed=DataScopeContext.executeWithoutScope(() -> expected==null
                ? mapper.lifecycleInsert(fact.kind().name(),fact.typeCode(),fact.subOrderNo(),fact.sourceVersion(),payload)
                : mapper.lifecycleFinalize(fact.kind().name(),fact.typeCode(),fact.subOrderNo(),fact.sourceVersion(),payload,expected));
        if (changed!=1) { throw new IllegalStateException("POINTS_FACT_STATE_CONFLICT"); }
    }
}
