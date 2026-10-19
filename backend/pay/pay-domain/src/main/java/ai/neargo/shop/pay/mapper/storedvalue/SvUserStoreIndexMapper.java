package ai.neargo.shop.pay.mapper.storedvalue;

import ai.neargo.shop.pay.storedvalue.entity.SvUserStoreIndex;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SvUserStoreIndexMapper extends BaseMapper<SvUserStoreIndex> {
    @Insert("INSERT IGNORE INTO sv_user_store_index(tenant_no,user_no,entity_no,store_no,store_name,has_recharge,"
            + "last_recharge_at,last_activity_at,last_activity_type,store_status,created_at,updated_at,created_by,"
            + "updated_by) VALUES(#{tenantNo},#{userNo},#{entityNo},#{storeNo},#{storeName},1,#{lastRechargeAt},"
            + "#{lastActivityAt},'RECHARGE','UNVERIFIED',#{createdAt},#{updatedAt},#{createdBy},#{updatedBy})")
    int ensure(SvUserStoreIndex row);
    @Select("SELECT * FROM sv_user_store_index WHERE tenant_no=#{tenant} AND user_no=#{user} AND store_no=#{store} "
            + "AND deleted=0 FOR UPDATE")
    SvUserStoreIndex lock(@Param("tenant") String tenant, @Param("user") String user, @Param("store") String store);
}
