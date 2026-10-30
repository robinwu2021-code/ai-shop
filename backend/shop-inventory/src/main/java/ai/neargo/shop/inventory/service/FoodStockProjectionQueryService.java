package ai.neargo.shop.inventory.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.Scope;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.Snapshot;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import ai.neargo.shop.inventory.service.impl.FoodStockProjectionServiceImpl;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

/** Permission-resolved source scope is provided by the B adapter; mirrors expose no native stock ID. */
@Service @ConditionalOnInventory

public class FoodStockProjectionQueryService {
    public record ProjectionVO(String sourceSystem,String sourceEntityNo,String sourceStoreNo,
            long generation,String syncStatus,LocalDateTime receivedAt,Snapshot stock){}
    private final FoodStockProjectionServiceImpl remote;
    private final ObjectMapper json;
    public FoodStockProjectionQueryService(FoodStockProjectionServiceImpl remote,
            ObjectMapper json){this.remote=remote;this.json=json;}
    public PageData<ProjectionVO> list(String entity,String store,int page,int size){
        var p=remote.list(new Scope(entity,store),page,size);
        return PageData.of(p.rows().stream().map(v->json.convertValue(v,ProjectionVO.class)).toList(),p.total(),page,size);
    }
    public ProjectionVO get(String entity,String store,String item){
        return json.convertValue(remote.get(new Scope(entity,store),item),ProjectionVO.class);
    }
}
