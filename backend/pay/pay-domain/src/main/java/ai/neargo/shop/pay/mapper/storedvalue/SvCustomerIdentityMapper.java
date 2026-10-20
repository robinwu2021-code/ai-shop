package ai.neargo.shop.pay.mapper.storedvalue;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** Trusted Core identity facts only. No balance or historical voucher is changed by binding. */
@Mapper
public interface SvCustomerIdentityMapper {
    record Binding(String customerNo,String userNo) { }
    @Insert("INSERT IGNORE INTO sv_customer_identity(tenant_no,customer_no,user_no) VALUES(#{tenant},#{customer},#{user})")
    int ensure(@Param("tenant") String tenant,@Param("customer") String customer,@Param("user") String user);
    @Select("SELECT customer_no,user_no FROM sv_customer_identity WHERE tenant_no=#{tenant} AND (customer_no=#{customer} OR user_no=#{user}) FOR UPDATE")
    List<Binding> lock(@Param("tenant") String tenant,@Param("customer") String customer,@Param("user") String user);
    @Select("SELECT user_no FROM sv_customer_identity WHERE tenant_no=#{tenant} AND customer_no=#{customer}")
    String boundUser(@Param("tenant") String tenant,@Param("customer") String customer);
    @Select("SELECT COUNT(*) FROM sv_account a JOIN sv_account b ON a.tenant_no=b.tenant_no AND a.entity_no=b.entity_no AND a.store_no=b.store_no AND a.currency=b.currency AND a.account_no<>b.account_no WHERE a.tenant_no=#{tenant} AND a.customer_no=#{customer} AND b.user_no=#{user} AND a.deleted=0 AND b.deleted=0")
    int conflicts(@Param("tenant") String tenant,@Param("customer") String customer,@Param("user") String user);
    @Update("UPDATE sv_account SET user_no=#{user},customer_no=#{customer},updated_at=#{at},updated_by=#{actor},version=version+1 WHERE tenant_no=#{tenant} AND (customer_no=#{customer} OR user_no=#{user}) AND (user_no IS NULL OR user_no=#{user}) AND (user_no IS NULL OR customer_no IS NULL OR customer_no<>#{customer}) AND deleted=0")
    int bindAccounts(@Param("tenant") String tenant,@Param("customer") String customer,@Param("user") String user,@Param("actor") String actor,@Param("at") java.time.LocalDateTime at);
    @Update("UPDATE sv_user_store_index SET user_no=#{user},customer_no=#{customer},updated_at=#{at},updated_by=#{actor},version=version+1 WHERE tenant_no=#{tenant} AND (customer_no=#{customer} OR user_no=#{user}) AND (user_no IS NULL OR user_no=#{user}) AND (user_no IS NULL OR customer_no IS NULL OR customer_no<>#{customer}) AND deleted=0")
    int bindDirectory(@Param("tenant") String tenant,@Param("customer") String customer,@Param("user") String user,@Param("actor") String actor,@Param("at") java.time.LocalDateTime at);
}
