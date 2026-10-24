package ai.neargo.shop.promotion.service;
import ai.neargo.shop.common.*;
import ai.neargo.shop.promotion.dto.ActivityActor;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
/** Read-only ownership checks live in Core. Report access does not require activity editing permission. */
@Service
public class StoredValueActivityReportOwnership {
 private final JdbcTemplate db;private final ActivityVersionService activities;
 public StoredValueActivityReportOwnership(@Qualifier("transactionManager")PlatformTransactionManager manager,ActivityVersionService activities){if(!(manager instanceof DataSourceTransactionManager data))throw new IllegalStateException("Core JDBC transaction required");this.db=new JdbcTemplate(Objects.requireNonNull(data.getDataSource()));this.activities=activities;}
 public void require(String tenant,String actor,String management,String type,String scope,String entity,String currency,String activity){
  ActivityActor access;
  if("BRAND".equals(type)){var owners=db.queryForList("SELECT entity_no FROM pmt_brand_activity_owner WHERE tenant_no=? AND management_no=? AND brand_no=? AND activity_no=?",tenant,management,scope,activity);if(owners.size()!=1)throw BizException.of(ErrorCode.NOT_FOUND);access=new ActivityActor(tenant,String.valueOf(owners.getFirst().get("entity_no")),actor,null,management,scope);}
  else access=new ActivityActor(tenant,entity,actor,Set.of(scope));
  var definition=activities.get(access,activity).definition();
  if(!"RECHARGE".equals(definition.scene())||!currency.equals(definition.currency())||definition.storeParticipationScope()==null||("STORE".equals(type)&&(!"STORES".equals(definition.storeParticipationScope().mode())||!List.of(scope).equals(definition.storeParticipationScope().refs()))))throw BizException.of(ErrorCode.FORBIDDEN);
 }
}
