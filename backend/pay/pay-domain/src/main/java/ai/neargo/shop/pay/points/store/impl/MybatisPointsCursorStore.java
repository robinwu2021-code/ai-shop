package ai.neargo.shop.pay.points.store.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.points.PointsEventMapper;
import ai.neargo.shop.pay.points.store.PointsCursorStore;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MybatisPointsCursorStore implements PointsCursorStore {
    private final PointsEventMapper mapper;
    private final PointsConfigurationStore config;
    public MybatisPointsCursorStore(PointsEventMapper mapper,PointsConfigurationStore config) {
        this.mapper=mapper; this.config=config;
    }
    @Override public long current(String task) {
        return DataScopeContext.executeWithoutScope(() -> {
            var row=mapper.cursor(task); return row==null?0:((Number)row.get("watermark")).longValue();
        });
    }
    @Override @Transactional("payTxManager")
    public void advance(String task,String type,long expected,long next,String payload) {
        if (expected<0 || next<expected || task==null || task.length()>64) {
            throw new IllegalArgumentException("POINTS_CURSOR_INVALID");
        }
        config.lockTypeAndPool(type);
        DataScopeContext.executeWithoutScope(() -> {
            var row=mapper.lockCursor(task);
            if (row==null) {
                if (expected!=0) { throw new IllegalStateException("POINTS_CURSOR_CONFLICT"); }
                mapper.insertCursor(task,type,next,payload);
            } else {
                long current=((Number)row.get("watermark")).longValue();
                if (current>=next) { return null; }
                if (current!=expected || mapper.advanceCursor(task,expected,next,payload)!=1) {
                    throw new IllegalStateException("POINTS_CURSOR_CONFLICT");
                }
            }
            return null;
        });
    }
}
