package ai.neargo.shop.food.service;
import ai.neargo.shop.common.PageData;
import java.util.List;
public interface FoodStockReconciliationService {
    record DifferenceVO(String differenceNo,String itemNo,String differenceType,Long sourceVersion,Long targetVersion,String sourceSnapshot,String targetSnapshot,String resolutionStatus){}
    /** PAGE_OBSERVED is an asynchronous observation, not a single globally frozen inventory snapshot. */
    record ReconciliationVO(String reconciliationNo,String status,long generation,long checkedCount,long differenceCount,
            PageData<DifferenceVO> differences,long cursorId,long endId,java.time.LocalDateTime createdAt,
            java.time.LocalDateTime startedAt,java.time.LocalDateTime finishedAt,String consistencyMode) {
        public ReconciliationVO(String number,String status,long generation,long checked,long count,PageData<DifferenceVO> differences) {
            this(number,status,generation,checked,count,differences,0,0,null,null,null,"PAGE_OBSERVED");
        }
    }
    ReconciliationVO create(String entity,String store,String actor,List<String> itemNos,String key);
    ReconciliationVO get(String entity,String store,String number,int page,int size);
}
