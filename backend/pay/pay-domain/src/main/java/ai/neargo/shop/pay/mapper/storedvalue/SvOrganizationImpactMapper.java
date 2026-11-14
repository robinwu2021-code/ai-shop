package ai.neargo.shop.pay.mapper.storedvalue;
import org.apache.ibatis.annotations.*;
import java.util.Map;
/** One SQL statement reads the store obligations at one database snapshot. No customer data is returned. */
public interface SvOrganizationImpactMapper {
 @Select("SELECT (SELECT COALESCE(SUM(principal_minor+bonus_minor),0) FROM sv_account WHERE tenant_no=#{tenant} AND entity_no=#{entity} AND store_no=#{store} AND deleted=0) AS outstandingMinor, "
   +"(SELECT COALESCE(SUM(held_principal_minor+held_bonus_minor),0) FROM sv_account WHERE tenant_no=#{tenant} AND entity_no=#{entity} AND store_no=#{store} AND deleted=0) AS heldMinor, "
   +"(SELECT COUNT(*) FROM sv_recharge WHERE tenant_no=#{tenant} AND entity_no=#{entity} AND store_no=#{store} AND status IN ('WAIT_COLLECTION','SUBMITTED','RECEIVED') AND deleted=0) AS pendingRecharges, "
   +"(SELECT COUNT(*) FROM sv_account WHERE tenant_no=#{tenant} AND entity_no=#{entity} AND store_no IS NULL AND deleted=0) AS unattributedAccounts")
 Map<String,Object> obligations(@Param("tenant") String tenant,@Param("entity") String entity,@Param("store") String store);
 @Select("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sv_issuer'") long brandSchema();
 @Select("SELECT COUNT(*) FROM sv_issuer WHERE tenant_no=#{tenant} AND scope_type='BRAND' AND status='ACTIVE'") long activeBrandIssuers(@Param("tenant") String tenant);
}
