package ai.neargo.shop.pay.storedvalue.issuer.impl;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.number;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.storedvalue.entity.SvLot;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueLotBasisService;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueAllocationPolicy.LotBalance;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueUseFacts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Original credited amounts are immutable; the pure allocator never performs database reads. */
@Service("storedValueLotBasisService")
public class StoredValueLotBasisServiceImpl implements StoredValueLotBasisService {
 private final IssuerRepository db;private final ObjectMapper json;
 public StoredValueLotBasisServiceImpl(IssuerRepository db,ObjectMapper json){this.db=db;this.json=json;}
 public List<LotBalance> available(String tenant,List<SvLot> lots){
  if(lots.isEmpty())return List.of();
  var ids=lots.stream().map(SvLot::getRechargeNo).distinct().toList();var args=new ArrayList<Object>();args.add(tenant);args.addAll(ids);
  var originals=db.rows("SELECT recharge_no,source_kind,principal_minor,bonus_minor,status FROM sv_recharge WHERE tenant_no=? AND recharge_no IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND deleted=0",args.toArray());
  var byNo=new HashMap<String,Map<String,Object>>();for(var row:originals)if(byNo.put(text(row,"recharge_no"),row)!=null)throw BizException.of(ErrorCode.CONFLICT);
  var available=StoredValueUseFacts.available(lots,json);var result=new ArrayList<LotBalance>();
  for(int n=0;n<lots.size();n++){
   var row=byNo.get(lots.get(n).getRechargeNo());if(row==null||!"ISSUER_RELEASE".equals(text(row,"source_kind"))||!"CREDITED".equals(text(row,"status")))throw BizException.of(ErrorCode.CONFLICT);
   var l=available.get(n);long p=number(row,"principal_minor"),b=number(row,"bonus_minor");
   if(p<=0||b<0||l.principalMinor()>p||l.bonusMinor()>b)throw BizException.of(ErrorCode.CONFLICT);
   result.add(new LotBalance(l.lotNo(),l.principalMinor(),l.bonusMinor(),l.scope(),l.creditedAt(),p,b));
  }
  return List.copyOf(result);
 }
}
