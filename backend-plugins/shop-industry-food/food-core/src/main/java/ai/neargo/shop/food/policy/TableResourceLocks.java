package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.entity.FnbTable;
import ai.neargo.shop.food.entity.FnbTableArea;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** All occupancy and credential writers lock the same existing area/table resources in stable order. */
public final class TableResourceLocks {
    private final TableAreaMapper areas;
    private final TableMapper tables;
    public TableResourceLocks(TableAreaMapper areas, TableMapper tables) { this.areas=areas;this.tables=tables; }

    public List<FnbTable> lock(String entity,String store,Collection<String> numbers,
            Map<String,Long> versions,boolean active) {
        var snapshots=numbers.stream().distinct().sorted().map(no -> find(entity,store,no,false)).toList();
        for(var row:snapshots) if(versions!=null && (!versions.containsKey(row.getTableNo())
                || !row.getVersion().equals(versions.get(row.getTableNo())))) throw BizException.of(ErrorCode.CONFLICT);
        for(String area:snapshots.stream().map(FnbTable::getAreaNo).distinct().sorted().toList()) {
            var found=areas.selectList(Wrappers.<FnbTableArea>lambdaQuery().eq(FnbTableArea::getEntityNo,entity)
                    .eq(FnbTableArea::getStoreNo,store).eq(FnbTableArea::getAreaNo,area).last("LIMIT 1 FOR UPDATE"));
            if(found.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
            if(active && !"ACTIVE".equals(found.getFirst().getStatus())) throw BizException.of(ErrorCode.CONFLICT);
        }
        var locked=new ArrayList<FnbTable>();
        for(var snapshot:snapshots) {
            var row=find(entity,store,snapshot.getTableNo(),true);
            if(!row.getAreaNo().equals(snapshot.getAreaNo()) || !row.getVersion().equals(snapshot.getVersion()))
                throw BizException.of(ErrorCode.CONFLICT);
            if(active && !"ACTIVE".equals(row.getStatus())) throw BizException.of(ErrorCode.CONFLICT);
            locked.add(row);
        }
        return locked;
    }
    private FnbTable find(String entity,String store,String table,boolean lock) {
        var found=tables.selectList(Wrappers.<FnbTable>lambdaQuery().eq(FnbTable::getEntityNo,entity)
                .eq(FnbTable::getStoreNo,store).eq(FnbTable::getTableNo,table)
                .last(lock ? "LIMIT 1 FOR UPDATE":"LIMIT 1"));
        if(found.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        return found.getFirst();
    }
}
