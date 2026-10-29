package ai.neargo.shop.inventory.service;

import ai.neargo.shop.common.*;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.mapper.FoodStockProjectionMapper;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/** Permission-resolved source scope is provided by the B adapter; mirrors expose no native stock ID. */
@Service @ConditionalOnInventory
@Transactional(transactionManager="invTransactionManager")
public class FoodStockProjectionQueryService {
    public record ProjectionVO(String sourceSystem,String sourceEntityNo,String sourceStoreNo,
            long generation,String syncStatus,LocalDateTime receivedAt,Snapshot stock){}
    private final FoodStockProjectionMapper rows;private final InventoryAclService acl;
    public FoodStockProjectionQueryService(FoodStockProjectionMapper rows,InventoryAclService acl){this.rows=rows;this.acl=acl;}
    public PageData<ProjectionVO> list(String entity,String store,int page,int size){var s=scope(entity,store);if(page<1||size<1||size>100)throw BizException.of(ErrorCode.BAD_REQUEST);var session=rows.session(s);
        var data=rows.page(s,(long)(page-1)*size,size).stream().map(item->view(s,session,rows.projection(s,item))).toList();return PageData.of(data,rows.count(s),page,size);}
    public ProjectionVO get(String entity,String store,String item){var s=scope(entity,store);if(item==null||item.isBlank()||item.length()>32)throw BizException.of(ErrorCode.BAD_REQUEST);var session=rows.session(s);var p=rows.projection(s,item);if(p==null)throw BizException.of(ErrorCode.NOT_FOUND);return view(s,session,p);}
    private ProjectionVO view(Scope s,FoodStockProjectionMapper.Session session,FoodStockProjectionMapper.Projection p){String status=session==null?"UNCONFIGURED":session.generation()!=p.generation()?"STALE":session.status();return new ProjectionVO("FOOD_LOCAL",s.entityNo(),s.storeNo(),p.generation(),status,p.receivedAt(),p.snapshot());}
    private Scope scope(String entity,String store){if(entity==null||entity.isBlank()||entity.length()>32||store==null||store.isBlank()||store.length()>32)throw BizException.of(ErrorCode.BAD_REQUEST);if(acl.findOwnerId(entity)==null||acl.stockLocationOfExistingStore(entity,store)==null)throw BizException.of(ErrorCode.NOT_FOUND);return new Scope(entity,store);}
}
