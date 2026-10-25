package ai.neargo.shop.pay.storedvalue.settlement;
import ai.neargo.shop.common.*;
import ai.neargo.shop.numbering.*;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.*;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.*;
/** Party reconciliation and append-only corrections. Settled history is never rewritten. */
@Service
public class StoredValueSettlementDecisionService {
 private final StoredValueSettlementRepository repo;private final StoredValueSettlementAudit audit;private final DocumentNumberCoordinator numbers;private final EntitlementCommandDeduplicator commands;
 public StoredValueSettlementDecisionService(StoredValueSettlementRepository repo,StoredValueSettlementAudit audit,@org.springframework.beans.factory.annotation.Qualifier("payDocumentNumberCoordinator") DocumentNumberCoordinator numbers,EntitlementCommandDeduplicator commands){this.repo=repo;this.audit=audit;this.numbers=numbers;this.commands=commands;}
 public Bill reconcile(Decision c){
  if(c.side()==null)throw BizException.of(ErrorCode.BAD_REQUEST);var a=c.authority();var before=repo.bill(a,c.billNo(),false);String entity=text(before,"PAYER".equals(c.side())?"payer_entity_no":"payee_entity_no");requireParty(a,before,entity,"sv.settlement.reconcile");
  return mutate(a,before,"SV_SETTLEMENT_RECONCILE",c.commandKey(),Arrays.asList(a.principalNo(),c.billNo(),c.expectedRevision(),c.side(),c.reason()),c.expectedRevision(),row->{
   requireParty(a,row,entity,"sv.settlement.reconcile");if(!"DRAFT".equals(text(row,"status")))conflict();boolean same=Objects.equals(text(row,"payer_entity_no"),text(row,"payee_entity_no"));String field="PAYER".equals(c.side())?"payer":"payee";if(row.get(field+"_reconciled_at")!=null)conflict();var now=Timestamp.from(Instant.now());
   if(same)repo.database().update("UPDATE sv_settlement_bill SET payer_reconciled_by=?,payer_reconciled_at=?,payee_reconciled_by=?,payee_reconciled_at=? WHERE tenant_no=? AND bill_no=?",a.actorNo(),now,a.actorNo(),now,a.tenantNo(),c.billNo());
   else repo.database().update("UPDATE sv_settlement_bill SET "+field+"_reconciled_by=?,"+field+"_reconciled_at=? WHERE tenant_no=? AND bill_no=?",a.actorNo(),now,a.tenantNo(),c.billNo());
   row=repo.bill(a,c.billNo(),false);row=audit.advance(a,row,restored(row));audit.append(a,row,"RECON_CONFIRM",c.commandKey(),c.reason(),Map.of("side",c.side(),"state",text(row,"status")));return view(row);
  });
 }
 public Bill dispute(Decision c){
  reason(c.reason());var a=c.authority();var before=repo.bill(a,c.billNo(),false);requireEither(a,before,"sv.settlement.dispute");
  return mutate(a,before,"SV_SETTLEMENT_DISPUTE",c.commandKey(),List.of(a.principalNo(),c.billNo(),c.expectedRevision(),c.reason()),c.expectedRevision(),row->{
   requireEither(a,row,"sv.settlement.dispute");if("DISPUTED".equals(text(row,"status")))conflict();String no=audit.eventNumber(a,row,"DISPUTE",c.commandKey());repo.database().update("UPDATE sv_settlement_bill SET dispute_no=?,dispute_previous_status=? WHERE tenant_no=? AND bill_no=?",no,text(row,"status"),a.tenantNo(),c.billNo());row=audit.advance(a,row,"DISPUTED");audit.append(a,row,"DISPUTE",c.commandKey(),c.reason(),Map.of("disputeNo",no));return view(row);
  });
 }
 public Bill resolve(Resolution c){
  var a=c.authority();var before=repo.bill(a,c.billNo(),false);requireEither(a,before,"sv.settlement.dispute.resolve");
  return mutate(a,before,"SV_SETTLEMENT_DISPUTE_RESOLVE",c.commandKey(),List.of(a.principalNo(),c.billNo(),c.expectedRevision(),c.disputeNo(),c.decision(),c.reason()),c.expectedRevision(),row->{
   requireEither(a,row,"sv.settlement.dispute.resolve");if(!"DISPUTED".equals(text(row,"status"))||!c.disputeNo().equals(text(row,"dispute_no")))conflict();repo.database().update("UPDATE sv_settlement_bill SET dispute_no=NULL,dispute_previous_status=NULL WHERE tenant_no=? AND bill_no=?",a.tenantNo(),c.billNo());row=audit.advance(a,row,restored(row));audit.append(a,row,"DISPUTE_RESOLVE",c.commandKey(),c.reason(),Map.of("disputeNo",c.disputeNo(),"decision",c.decision(),"state",text(row,"status")));return view(row);
  });
 }
 public Bill adjust(Adjustment c){
  var a=c.authority();var before=repo.bill(a,c.billNo(),false);requireEither(a,before,"sv.settlement.adjust");
  return mutate(a,before,"SV_SETTLEMENT_ADJUST",c.commandKey(),List.of(a.principalNo(),c.billNo(),c.clearingNo(),c.expectedRevision(),c.payableDeltaMinor(),c.reason()),c.expectedRevision(),row->{
   requireEither(a,row,"sv.settlement.adjust");var original=repo.database().one("SELECT c.* FROM sv_clearing_entry c JOIN sv_settlement_bill_item i ON CAST(i.tenant_no AS BINARY(256))=CAST(c.tenant_no AS BINARY(256)) AND CAST(i.clearing_no AS BINARY(256))=CAST(c.clearing_no AS BINARY(256)) WHERE c.tenant_no=? AND c.clearing_no=? AND i.bill_no=? AND c.deleted=0 AND i.deleted=0",a.tenantNo(),c.clearingNo(),c.billNo());
   if(original==null||!"USE".equals(text(original,"entry_type"))||!text(row,"issuer_no").equals(text(original,"issuer_no"))||!text(row,"currency").equals(text(original,"currency"))||Objects.equals(text(original,"payer_entity_no"),text(original,"payee_entity_no")))conflict();
   long prior=exact(repo.database().one("SELECT COALESCE(SUM(payable_minor),0) AS n FROM sv_clearing_entry WHERE tenant_no=? AND issuer_no=? AND original_clearing_no=? AND entry_type='ADJUSTMENT' AND deleted=0",a.tenantNo(),text(row,"issuer_no"),c.clearingNo()).get("n"));long effective=Math.addExact(Math.addExact(number(original,"payable_minor"),prior),c.payableDeltaMinor());if(effective<0||effective>number(original,"principal_used_minor"))conflict();
   boolean future=number(row,"confirmed_receipt_minor")==number(row,"payable_minor")&&row.get("payer_reconciled_at")!=null&&row.get("payee_reconciled_at")!=null;
   boolean sameDirection=Objects.equals(text(row,"payer_entity_no"),text(original,"payer_entity_no"))&&Objects.equals(text(row,"payee_entity_no"),text(original,"payee_entity_no"));
   if(!sameDirection&&(!Objects.equals(text(row,"payer_entity_no"),text(original,"payee_entity_no"))||!Objects.equals(text(row,"payee_entity_no"),text(original,"payer_entity_no"))))conflict();
   long billDelta=sameDirection?c.payableDeltaMinor():Math.negateExact(c.payableDeltaMinor());
   long due=number(row,"payable_minor");if(!future){due=Math.addExact(due,billDelta);if(due<0||due<number(row,"registered_payment_minor"))conflict();}
   String event=audit.eventNumber(a,row,"ADJUST",c.commandKey()),no=BizKey.next(BizKey.STORED_VALUE_CLEARING);var entry=repo.base(a);
   for(String field:List.of("issuer_no","usage_no","allocation_no","lot_no","release_no","issuer_version_no","recharge_store_no","redemption_store_no","payer_entity_no","payee_entity_no","currency"))entry.put(field,original.get(field));
   entry.put("clearing_no",no);entry.put("principal_used_minor",0);entry.put("bonus_used_minor",0);entry.put("payable_minor",c.payableDeltaMinor());entry.put("issuer_borne_minor",0);entry.put("entry_type","ADJUSTMENT");entry.put("original_clearing_no",c.clearingNo());entry.put("decision_no",event);entry.put("occurred_at",entry.get("created_at"));entry.put("source_event_key",EntitlementRecords.digest(repo.database().encode(List.of(a.actorNo(),c.billNo(),c.clearingNo(),c.commandKey()))));entry.put("content_hash",EntitlementRecords.digest(repo.database().encode(List.of(text(original,"content_hash"),c.clearingNo(),c.billNo(),c.payableDeltaMinor(),event,entry.get("source_event_key")))));repo.database().insert("sv_clearing_entry",entry);
   if(!future){var item=repo.base(a);item.put("item_no",BizKey.next("SVSI"));item.put("bill_no",c.billNo());item.put("clearing_no",no);item.put("amount_minor",billDelta);item.put("principal_minor",0);item.put("bonus_minor",0);item.put("source_hash",entry.get("content_hash"));repo.insert("sv_settlement_bill_item",item);
    repo.database().update("UPDATE sv_settlement_bill SET payable_minor=?,source_count=source_count+1,payer_reconciled_by=NULL,payer_reconciled_at=NULL,payee_reconciled_by=NULL,payee_reconciled_at=NULL WHERE tenant_no=? AND bill_no=?",due,a.tenantNo(),c.billNo());
   }
   row=repo.bill(a,c.billNo(),false);String state="DISPUTED".equals(text(row,"status"))?"DISPUTED":future?text(row,"status"):"DRAFT";row=audit.advance(a,row,state);audit.append(a,row,"ADJUST",c.commandKey(),c.reason(),Map.of("originalClearingNo",c.clearingNo(),"adjustmentClearingNo",no,"amountMinor",c.payableDeltaMinor(),"nextPeriod",future));return view(row);
  });
 }
 private Bill mutate(Authority a,Map<String,Object> before,String operation,String key,Object material,long expected,Function<Map<String,Object>,Bill> change){
  var scope=new CatalogScope(a.tenantNo(),text(before,"payer_entity_no"),a.actorNo(),false);var original=commands.replay(scope,operation,key,material,Bill.class);if(original.isPresent())return original.get();
  return numbers.withReservation(Map.of(DocumentType.SV_SETTLEMENT_EVENT,1),()->repo.database().write(()->{repo.issuer(a,text(before,"issuer_no"),true);return commands.execute(scope,operation,key,material,text(before,"bill_no"),Bill.class,()->{var row=repo.bill(a,text(before,"bill_no"),true);revision(row,expected);return change.apply(row);});}));
 }
 private static String restored(Map<String,Object> row){return row.get("payer_reconciled_at")==null||row.get("payee_reconciled_at")==null?"DRAFT":StoredValueSettlementAudit.paymentState(row);}
 private static void reason(String value){if(value==null||value.isBlank()||value.length()>255)throw BizException.of(ErrorCode.BAD_REQUEST);}
 private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}
