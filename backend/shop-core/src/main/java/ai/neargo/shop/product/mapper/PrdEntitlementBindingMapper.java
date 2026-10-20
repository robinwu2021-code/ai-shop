package ai.neargo.shop.product.mapper;

import ai.neargo.shop.product.entity.PrdEntitlementBinding;
import ai.neargo.shop.product.entity.PrdGoods;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PrdEntitlementBindingMapper extends BaseMapper<PrdEntitlementBinding> {
    /** Includes historical mappings: disabling/deleting a binding must not re-enable legacy sales. */
    @Select("SELECT * FROM prd_entitlement_binding WHERE tenant_no=#{tenant} AND goods_no=#{goods} AND sku_no=#{sku}")
    PrdEntitlementBinding findIdentity(@Param("tenant") String tenant, @Param("goods") String goods,
                                      @Param("sku") String sku);
    @Select("SELECT * FROM prd_goods WHERE tenant_no=#{tenant} AND entity_no=#{entity} AND goods_no=#{goods} "
            + "AND deleted=0 FOR UPDATE")
    PrdGoods lockGoods(@Param("tenant") String tenant, @Param("entity") String entity, @Param("goods") String goods);
    @Update("UPDATE prd_goods SET version=version+1,updated_at=#{at},updated_by=#{actor} WHERE tenant_no=#{tenant} "
            + "AND entity_no=#{entity} AND goods_no=#{goods} AND version=#{revision} AND deleted=0")
    int bumpProduct(@Param("tenant") String tenant, @Param("entity") String entity, @Param("goods") String goods,
                    @Param("revision") long revision, @Param("at") LocalDateTime at, @Param("actor") String actor);
}
