package ai.neargo.shop.pay.storedvalue.settlement.impl;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.number;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.exact;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.view;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.numbering.DocumentSource;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueCrossEntityReadiness;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementAudit;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementService;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Authority;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Generated;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Generation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Closed posting-period bills claim each immutable clearing row once. No channel payout or wallet changes. */
@Service("storedValueSettlementService")
public class StoredValueSettlementServiceImpl implements StoredValueSettlementService {
 private static final String GENERATE="sv.settlement.generate";
 private final StoredValueSettlementRepository repo;private final StoredValueSettlementAudit audit;private final DocumentNumberCoordinator numbers;private final EntitlementCommandDeduplicator commands;private final StoredValueCrossEntityReadiness readiness;
 public StoredValueSettlementServiceImpl(StoredValueSettlementRepository repo,StoredValueSettlementAudit audit,@Qualifier("payDocumentNumberCoordinator") DocumentNumberCoordinator numbers,EntitlementCommandDeduplicator commands,StoredValueCrossEntityReadiness readiness){this.repo=repo;this.audit=audit;this.numbers=numbers;this.commands=commands;this.readiness=readiness;}
 private static final String ELIGIBLE=" FROM sv_clearing_entry c WHERE c.tenant_no=? AND c.issuer_no=? AND c.currency=? AND c.created_at<? AND c.id<=? AND c.deleted=0 AND NOT EXISTS(SELECT 1 FROM sv_settlement_bill_item i WHERE CAST(i.tenant_no AS BINARY(256))=CAST(c.tenant_no AS BINARY(256)) AND CAST(i.clearing_no AS BINARY(256))=CAST(c.clearing_no AS BINARY(256)))";
 public Generated generate(Generation c){
  var a=c.authority();if(a.entityPermissions().values().stream().noneMatch(p->p.contains(GENERATE)))throw BizException.of(ErrorCode.FORBIDDEN);var issuer=repo.issuer(a,c.issuerNo(),false);var version=repo.db.one("SELECT settlement_entity_no,currency FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND issuer_version_no=? AND deleted=0",a.tenantNo(),c.issuerNo(),text(issuer,"current_issuer_version_no"));
  if(version==null||!c.currency().equals(text(version,"currency")))throw BizException.of(ErrorCode.CONFLICT);
  var scope=new CatalogScope(a.tenantNo(),text(version,"settlement_entity_no"),a.actorNo(),false);var material=List.of(a.principalNo(),c.issuerNo(),c.currency(),c.period());
  var original=commands.replay(scope,"SV_SETTLEMENT_GENERATE",c.commandKey(),material,Generated.class);if(original.isPresent())return authorized(a,original.get());readiness.requireNewSettlement();if(c.period().to().isAfter(Instant.now()))throw BizException.of(ErrorCode.BAD_REQUEST);
  var pre=groups(c,Long.MAX_VALUE);int budget=Math.max(1,pre.size());
  return numbers.withReservation(Map.of(DocumentType.SV_SETTLEMENT_BILL,budget,DocumentType.SV_SETTLEMENT_EVENT,budget),()->repo.db.write(()->{
   repo.issuer(a,c.issuerNo(),true);
   return authorized(a,commands.execute(scope,"SV_SETTLEMENT_GENERATE",c.commandKey(),material,c.issuerNo(),Generated.class,()->{
    long watermark=exact(repo.db.one("SELECT COALESCE(MAX(id),0) AS n FROM sv_clearing_entry WHERE tenant_no=? AND issuer_no=? AND deleted=0",a.tenantNo(),c.issuerNo()).get("n"));
    var groups=groups(c,watermark);if(groups.size()>budget)throw BizException.of(ErrorCode.CONFLICT);
    for(var group:groups)create(c,group,watermark);
    var existing=repo.db.rows("SELECT * FROM sv_settlement_bill WHERE tenant_no=? AND issuer_no=? AND currency=? AND period_from=? AND period_to=? AND deleted=0 ORDER BY bill_no",a.tenantNo(),c.issuerNo(),c.currency(),Timestamp.from(c.period().from()),Timestamp.from(c.period().to()));
    return new Generated(existing.stream().filter(r->allowed(a,text(r,"payer_entity_no"),text(r,"payee_entity_no"))).map(StoredValueSettlementRepository::view).toList(),watermark);
   }));
  }));
 }
 private List<Map<String,Object>> groups(Generation c,long watermark){return repo.db.rows("SELECT c.payer_entity_no,c.payee_entity_no,COUNT(*) AS count_rows,SUM(c.payable_minor) AS payable,SUM(c.principal_used_minor) AS principal,SUM(c.bonus_used_minor) AS bonus"+ELIGIBLE+" GROUP BY c.payer_entity_no,c.payee_entity_no ORDER BY c.payer_entity_no,c.payee_entity_no",args(c,watermark)).stream().filter(g->allowed(c.authority(),text(g,"payer_entity_no"),text(g,"payee_entity_no"))).toList();}
 private boolean allowed(Authority a,String payer,String payee){return a.can(payer,GENERATE)||a.can(payee,GENERATE);}
 /** Every unclaimed older posting carries forward, including a party not authorized in an earlier generation. */
 private Object[] args(Generation c,long watermark){return new Object[]{c.authority().tenantNo(),c.issuerNo(),c.currency(),Timestamp.from(c.period().to()),watermark};}
 private Generated authorized(Authority a,Generated original){return new Generated(original.bills().stream().filter(b->allowed(a,b.payerEntityNo(),b.payeeEntityNo())).toList(),original.sourceWatermark());}

 private void create(Generation c,Map<String,Object> group,long watermark){
  var a=c.authority();String originalPayer=text(group,"payer_entity_no"),originalPayee=text(group,"payee_entity_no");long payable=exact(group.get("payable"));boolean reverse=payable<0;String payer=reverse?originalPayee:originalPayer,payee=reverse?originalPayer:originalPayee;long amount=reverse?Math.negateExact(payable):payable;
  if(!allowed(a,payer,payee))throw BizException.of(ErrorCode.FORBIDDEN);
  if(repo.db.one("SELECT bill_no FROM sv_settlement_bill WHERE tenant_no=? AND issuer_no=? AND payer_entity_no=? AND payee_entity_no=? AND currency=? AND period_from=? AND period_to=?",a.tenantNo(),c.issuerNo(),payer,payee,c.currency(),Timestamp.from(c.period().from()),Timestamp.from(c.period().to()))!=null)throw BizException.of(ErrorCode.CONFLICT);
  String source=EntitlementRecords.digest(repo.db.encode(List.of(c.issuerNo(),payer,payee,c.currency(),c.period())));String no=numbers.bind(DocumentType.SV_SETTLEMENT_BILL,new DocumentSource(a.tenantNo(),"PAY","SV_SETTLEMENT_BILL",source));
  var row=repo.base(a);row.put("bill_no",no);row.put("issuer_no",c.issuerNo());row.put("payer_entity_no",payer);row.put("payee_entity_no",payee);row.put("currency",c.currency());row.put("period_from",Timestamp.from(c.period().from()));row.put("period_to",Timestamp.from(c.period().to()));row.put("bill_kind",payer.equals(payee)?"INTERNAL_RECON":"CROSS_ENTITY");row.put("status","DRAFT");row.put("payable_minor",amount);row.put("registered_payment_minor",0);row.put("confirmed_receipt_minor",0);row.put("principal_minor",exact(group.get("principal")));row.put("bonus_minor",exact(group.get("bonus")));row.put("source_count",exact(group.get("count_rows")));row.put("source_watermark",watermark);repo.insert("sv_settlement_bill",row);
  long cursor=0,count=0;String insert="INSERT INTO sv_settlement_bill_item(tenant_no,item_no,bill_no,clearing_no,amount_minor,principal_minor,bonus_minor,source_hash,version,deleted,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,0,0,?,?,?,?)";
  for(;;){var parameters=new ArrayList<Object>(Arrays.asList(args(c,watermark)));parameters.add(originalPayer);parameters.add(originalPayee);parameters.add(cursor);
   var entries=repo.db.rows("SELECT c.*"+ELIGIBLE+" AND c.payer_entity_no=? AND c.payee_entity_no=? AND c.id>? ORDER BY c.id LIMIT 500",parameters.toArray());if(entries.isEmpty())break;var batch=new ArrayList<Object[]>();
   for(var e:entries){cursor=number(e,"id");long item=number(e,"payable_minor");batch.add(new Object[]{a.tenantNo(),BizKey.next("SVSI"),no,text(e,"clearing_no"),reverse?Math.negateExact(item):item,number(e,"principal_used_minor"),number(e,"bonus_used_minor"),text(e,"content_hash"),row.get("created_at"),row.get("updated_at"),a.actorNo(),a.actorNo()});}
   repo.db.batchUpdate(insert,batch);count+=batch.size();
  }
  if(count!=exact(group.get("count_rows")))throw BizException.of(ErrorCode.CONFLICT);var bill=repo.bill(a,no,false);audit.append(a,bill,"GENERATED",c.commandKey(),null,Map.of("sourceWatermark",watermark,"itemCount",count));
 }
}
