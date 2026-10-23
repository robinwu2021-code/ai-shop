package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.entity.FnbTable;
import ai.neargo.shop.food.entity.FnbTableOccupancy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure capacity calculation; callers hold the real table rows before reading active occupancy. */
public final class TableOccupancyPolicy {
    private TableOccupancyPolicy() { }
    public static Map<String,Integer> allocate(List<FnbTable> tables,
            Map<String,List<FnbTableOccupancy>> active,int party,String mode,String ownCheck) {
        if(party<1 || !("EXCLUSIVE".equals(mode)||"SHARED".equals(mode))) throw BizException.of(ErrorCode.BAD_REQUEST);
        var available=new LinkedHashMap<String,Integer>();long total=0;
        for(var table:tables) {
            if(table.getCapacity()==null || table.getCapacity()<1 || table.getResourceNo()!=null)
                throw BizException.of(ErrorCode.FORBIDDEN);
            if("NEEDS_CLEANING".equals(table.getTurnoverState())) throw BizException.of(ErrorCode.CONFLICT);
            long occupied=0;
            for(var row:active.get(table.getTableNo())) {
                if(row.getResourceClaimNo()!=null) throw BizException.of(ErrorCode.FORBIDDEN);
                if(row.getCheckNo()!=null && row.getCheckNo().equals(ownCheck)) continue;
                if(!"SHARED".equals(mode) || !"SHARED".equals(row.getSharingMode()) || row.getSeats()==null || row.getSeats()<1)
                    throw BizException.of(ErrorCode.CONFLICT);
                occupied+=row.getSeats();
            }
            long free=table.getCapacity()-occupied;
            if(free<1) throw BizException.of(ErrorCode.CONFLICT);
            available.put(table.getTableNo(),(int)free);total+=free;
        }
        if(tables.isEmpty()) return available;
        if(total<party) throw BizException.of(ErrorCode.CONFLICT);
        if("EXCLUSIVE".equals(mode)) return available;
        if(party<tables.size()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var allocated=new LinkedHashMap<String,Integer>();int remaining=party,remainingTables=tables.size();
        for(var row:available.entrySet()) {
            int count=Math.min(row.getValue(),remaining-(--remainingTables));
            allocated.put(row.getKey(),count);remaining-=count;
        }
        return allocated;
    }
}
