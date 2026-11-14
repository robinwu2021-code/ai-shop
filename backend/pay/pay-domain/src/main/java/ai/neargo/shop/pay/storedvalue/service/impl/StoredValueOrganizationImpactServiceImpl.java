package ai.neargo.shop.pay.storedvalue.service.impl;

import ai.neargo.shop.pay.mapper.storedvalue.SvOrganizationImpactMapper;
import ai.neargo.shop.pay.storedvalue.service.StoredValueOrganizationImpactService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueOrganizationImpactPort.Impact;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("storedValueOrganizationImpactService")
public class StoredValueOrganizationImpactServiceImpl implements StoredValueOrganizationImpactService {
 private final SvOrganizationImpactMapper rows;
 public StoredValueOrganizationImpactServiceImpl(SvOrganizationImpactMapper rows){this.rows=rows;}
 @Transactional(transactionManager="payTxManager",readOnly=true)
 public Impact inspect(String tenant,String entity,String store){
  ContractChecks.id(tenant);ContractChecks.id(entity);ContractChecks.id(store);
  var facts=rows.obligations(tenant,entity,store);boolean brand=rows.brandSchema()>0&&rows.activeBrandIssuers(tenant)>0;
  return new Impact(tenant,entity,store,true,brand,value(facts,"outstandingMinor"),value(facts,"heldMinor"),value(facts,"pendingRecharges"),value(facts,"unattributedAccounts"));
 }
 private long value(Map<String,Object> row,String name){
  var value=row.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElseThrow(()->new IllegalStateException("Missing owner evidence"));
  long number=new BigDecimal(value.toString()).longValueExact();if(number<0)throw new IllegalStateException("Invalid owner evidence");return number;
 }
}
