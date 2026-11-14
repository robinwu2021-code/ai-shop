package ai.neargo.shop.food.service;
import ai.neargo.shop.common.PageData;
import java.util.List;
public interface FoodStockSyncService {
    record ConfigVO(String status,long generation,long version,long backlog,String lastErrorCode) {}
    record ConfigCommand(boolean enabled,long version,String reason) {}
    record Preflight(boolean allowed,List<String> issues) {public Preflight{issues=List.copyOf(issues);}}
    record MappingCommand(String targetOwnerRef,String targetLocationRef,String targetItemRef,
                          String sourceUnit,String targetUnit,long version) {}
    record MappingVO(String itemNo,String targetOwnerRef,String targetLocationRef,String targetItemRef,
                     String sourceUnit,String targetUnit,long mappingRevision,String status,long version) {}
    record DeliveryVO(String syncNo,String itemNo,long generation,long sourceVersion,String status,
                      int attempts,String errorCode,String ackRef) {}
    ConfigVO config(String entity,String store);
    Preflight preflight(String entity,String store);
    ConfigVO configure(String entity,String store,String actor,ConfigCommand input,String key);
    PageData<MappingVO> mappings(String entity,String store,int page,int size);
    MappingVO map(String entity,String store,String item,String actor,MappingCommand input,String key);
    PageData<DeliveryVO> deliveries(String entity,String store,int page,int size,String status,String item);
    DeliveryVO retry(String entity,String store,String syncNo,String actor,String key);
}
