package ai.neargo.shop.pay.mapper.storedvalue;

import ai.neargo.shop.pay.storedvalue.dto.StoreAccountRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SvMerchantAccountMapper {
    @Select({"<script>",
            "SELECT a.*,i.last_activity_at FROM sv_account a JOIN sv_user_store_index i",
            "ON i.tenant_no=a.tenant_no AND i.entity_no=a.entity_no AND i.store_no=a.store_no AND (i.customer_no=a.customer_no OR (i.user_no IS NOT NULL AND i.user_no=a.user_no))",
            "WHERE a.tenant_no=#{tenant} AND a.entity_no=#{entity} AND a.store_no=#{store}",
            "AND a.deleted=0 AND i.deleted=0 AND i.has_recharge=1",
            "<if test='users != null'> AND (a.user_no IN",
            "<foreach collection='users' item='u' open='(' separator=',' close=')'>#{u}</foreach> OR a.customer_no IN",
            "<foreach collection='users' item='u' open='(' separator=',' close=')'>#{u}</foreach>)</if>",
            "<if test='afterAt != null'> AND (i.last_activity_at &lt; #{afterAt}",
            "OR (i.last_activity_at=#{afterAt} AND a.account_no &lt; #{afterNo}))</if>",
            "ORDER BY i.last_activity_at DESC,a.account_no DESC LIMIT #{limit}", "</script>"})
    List<StoreAccountRow> directory(@Param("tenant") String tenant,@Param("entity") String entity,
            @Param("store") String store,@Param("users") List<String> users,@Param("afterAt") LocalDateTime afterAt,
            @Param("afterNo") String afterNo,@Param("limit") int limit);
}
