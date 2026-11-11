package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.service.FoodStockProjectionQueryService;
import ai.neargo.shop.inventory.service.FoodStockProjectionService;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.Scope;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Permission-resolved source scope is provided by the B adapter; mirrors expose no native stock ID. */
@Service("foodStockProjectionQueryService") @ConditionalOnInventory

public class FoodStockProjectionQueryServiceImpl implements FoodStockProjectionQueryService {
    private final FoodStockProjectionService remote;
    private final ObjectMapper json;
    public FoodStockProjectionQueryServiceImpl(FoodStockProjectionService remote,
            ObjectMapper json){this.remote=remote;this.json=json;}
    @Override public PageData<ProjectionVO> list(String entity,String store,int page,int size){
        var p=remote.list(new Scope(entity,store),page,size);
        return PageData.of(p.rows().stream().map(v->json.convertValue(v,ProjectionVO.class)).toList(),p.total(),page,size);
    }
    @Override public ProjectionVO get(String entity,String store,String item){
        return json.convertValue(remote.get(new Scope(entity,store),item),ProjectionVO.class);
    }
}
