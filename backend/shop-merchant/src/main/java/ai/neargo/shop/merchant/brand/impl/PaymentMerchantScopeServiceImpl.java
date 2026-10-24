package ai.neargo.shop.merchant.brand.impl;

import static ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import static ai.neargo.shop.merchant.brand.ManagementRepository.str;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.brand.ManagementModels;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.brand.ManagementScopeService;
import ai.neargo.shop.merchant.brand.PaymentMerchantScopeService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service("paymentMerchantScopeService")
public class PaymentMerchantScopeServiceImpl implements PaymentMerchantScopeService {
 private final ManagementRepository db;private final ManagementScopeService scopes;
 public PaymentMerchantScopeServiceImpl(ManagementRepository db,ManagementScopeService scopes){this.db=db;this.scopes=scopes;}
 public List<Map<String,Object>> candidates(Actor a,String m,String entity,String channel,String scene,String store){
  scopes.resolve(a,m);scopes.require(a,m,"ENTITY",entity,"entity.read");scopes.require(a,m,"ENTITY",entity,"payment.merchant.read");
  if(store!=null){scopes.require(a,m,"STORE",store,"store.read");if(!entity.equals(scopes.resourceEntity(a,"STORE",store)))throw BizException.of(ErrorCode.FORBIDDEN);}
  if(channel!=null&&!Set.of("WECHAT","ALIPAY").contains(channel))throw BizException.of(ErrorCode.BAD_REQUEST);
  if(scene!=null&&!Set.of("JSAPI","APP","H5","NATIVE","MICROPAY","PAYCODE").contains(scene))throw BizException.of(ErrorCode.BAD_REQUEST);
  return db.rows("SELECT pay_merchant_no,entity_no,store_no,pay_channel,apply_status,pay_methods FROM mch_payment_merchant WHERE tenant_no=? AND entity_no=? AND apply_status='ACTIVE' AND deleted=0 AND (? IS NULL OR pay_channel=?) AND (? IS NULL OR store_no='' OR store_no=?) ORDER BY pay_merchant_no",a.tenantNo(),entity,channel,channel,store,store).stream()
   .filter(row->scopes.can(a,m,"PAYMENT_MERCHANT",str(row,"pay_merchant_no"),"payment.merchant.read"))
   .filter(row->scene==null||authorizedMethods(str(row,"pay_methods")).contains(scene))
   .map(row->{var result=db.view(row);result.put("payMethods",authorizedMethods(str(row,"pay_methods")));return result;}).toList();
 }
 private List<String> authorizedMethods(String json){try{return json==null?List.of():db.stringList(json);}catch(RuntimeException e){return List.of();}}
}
