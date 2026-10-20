package ai.neargo.shop.pay.mapper.storedvalue;

import ai.neargo.shop.pay.storedvalue.entity.SvAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SvAccountMapper extends BaseMapper<SvAccount> {
    @Insert("INSERT IGNORE INTO sv_account(tenant_no,account_no,user_no,customer_no,entity_no,store_no,currency,status,created_at,"
            + "updated_at,created_by,updated_by) VALUES(#{tenantNo},#{accountNo},#{userNo},#{customerIdentity},#{entityNo},#{storeNo},#{currency},"
            + "'ACTIVE',#{createdAt},#{updatedAt},#{createdBy},#{updatedBy})")
    int ensure(SvAccount row);
    @Select("SELECT * FROM sv_account WHERE tenant_no=#{tenant} AND (user_no=#{user} OR customer_no=#{user}) AND entity_no=#{entity} "
            + "AND currency=#{currency} AND store_no=#{store} AND deleted=0 FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    SvAccount lockOwner(@Param("tenant") String tenant, @Param("user") String user, @Param("entity") String entity,
                         @Param("currency") String currency, @Param("store") String store);
    @Select("SELECT * FROM sv_account WHERE tenant_no=#{tenant} AND (user_no=#{user} OR customer_no=#{user}) AND entity_no=#{entity} "
            + "AND currency=#{currency} AND store_no=#{store} AND deleted=0")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    SvAccount findOwner(@Param("tenant") String tenant, @Param("user") String user, @Param("entity") String entity,
                         @Param("currency") String currency, @Param("store") String store);

    @Select("SELECT COUNT(*)>0 FROM sv_account WHERE tenant_no=#{tenant} AND user_no=#{user} "
            + "AND entity_no=#{entity} AND currency=#{currency} AND store_no IS NULL AND deleted=0")
    boolean hasLegacyAccount(@Param("tenant") String tenant, @Param("user") String user,
            @Param("entity") String entity, @Param("currency") String currency);
}
