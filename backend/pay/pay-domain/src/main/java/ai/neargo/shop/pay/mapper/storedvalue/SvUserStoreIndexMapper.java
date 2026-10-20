package ai.neargo.shop.pay.mapper.storedvalue;

import ai.neargo.shop.pay.storedvalue.entity.SvUserStoreIndex;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SvUserStoreIndexMapper extends BaseMapper<SvUserStoreIndex> {
    @Select("SELECT * FROM sv_user_store_index WHERE tenant_no=#{tenant} AND user_no=#{user} AND has_recharge=1 "
            + "AND deleted=0 ORDER BY last_activity_at DESC,store_no ASC LIMIT #{limit}")
    @org.apache.ibatis.annotations.Options(flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE,
            useCache = false)
    List<SvUserStoreIndex> firstDirectoryPage(@Param("tenant") String tenant, @Param("user") String user,
            @Param("limit") int limit);

    @Select("SELECT * FROM sv_user_store_index WHERE tenant_no=#{tenant} AND user_no=#{user} AND has_recharge=1 "
            + "AND deleted=0 AND (last_activity_at<#{afterAt} "
            + "OR (last_activity_at=#{afterAt} AND store_no>#{afterStore})) "
            + "ORDER BY last_activity_at DESC,store_no ASC LIMIT #{limit}")
    @org.apache.ibatis.annotations.Options(flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE,
            useCache = false)
    List<SvUserStoreIndex> directoryPage(@Param("tenant") String tenant, @Param("user") String user,
            @Param("afterAt") LocalDateTime afterAt, @Param("afterStore") String afterStore,
            @Param("limit") int limit);

    @Select("SELECT * FROM sv_user_store_index WHERE tenant_no=#{tenant} AND user_no=#{user} "
            + "AND store_no=#{store} AND has_recharge=1 AND deleted=0")
    SvUserStoreIndex consumerStore(@Param("tenant") String tenant, @Param("user") String user,
            @Param("store") String store);

    @Insert("INSERT IGNORE INTO sv_user_store_index(tenant_no,user_no,customer_no,entity_no,store_no,store_name,has_recharge,"
            + "last_recharge_at,last_activity_at,last_activity_type,store_status,created_at,updated_at,created_by,"
            + "updated_by) VALUES(#{tenantNo},#{userNo},#{customerIdentity},#{entityNo},#{storeNo},#{storeName},1,#{lastRechargeAt},"
            + "#{lastActivityAt},'RECHARGE','UNVERIFIED',#{createdAt},#{updatedAt},#{createdBy},#{updatedBy})")
    int ensure(SvUserStoreIndex row);
    @Insert("INSERT IGNORE INTO sv_user_store_index(tenant_no,user_no,customer_no,entity_no,store_no,store_name,has_recharge,"
            + "last_consume_at,last_activity_at,last_activity_type,store_status,created_at,updated_at,created_by,"
            + "updated_by) VALUES(#{tenantNo},#{userNo},#{customerIdentity},#{entityNo},#{storeNo},#{storeName},0,#{lastConsumeAt},"
            + "#{lastActivityAt},'CONSUMPTION','UNVERIFIED',#{createdAt},#{updatedAt},#{createdBy},#{updatedBy})")
    int ensureConsumption(SvUserStoreIndex row);
    @Select("SELECT * FROM sv_user_store_index WHERE tenant_no=#{tenant} AND (user_no=#{user} OR customer_no=#{user}) AND store_no=#{store} "
            + "AND deleted=0 FOR UPDATE")
    SvUserStoreIndex lock(@Param("tenant") String tenant, @Param("user") String user, @Param("store") String store);
}
