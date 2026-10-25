package ai.neargo.shop.pay.storedvalue.settlement;
import ai.neargo.shop.common.*;
import ai.neargo.shop.numbering.*;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Authority;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
/** Immutable settlement facts, without receipt plaintext, attachments, or changes to account/voucher history. */
@Service
public class StoredValueSettlementAudit {
 private final StoredValueSettlementRepository repository;private final DocumentNumberCoordinator numbers;
 private static final Set<String> FIELDS=Set.of("sourceWatermark","itemCount","paymentNo","amountMinor","side","disputeNo","originalClearingNo","adjustmentClearingNo","nextPeriod","state","registeredMinor","confirmedMinor","decision");
 public StoredValueSettlementAudit(StoredValueSettlementRepository repository,@org.springframework.beans.factory.annotation.Qualifier("payDocumentNumberCoordinator") DocumentNumberCoordinator numbers){this.repository=repository;this.numbers=numbers;}
 public void append(Authority a,Map<String,Object> bill,String type,String command,String reason,Map<String,Object> snapshot){
  if(!repository.database().payTransactionActive())throw new IllegalStateException("Pay transaction required");
  if(!FIELDS.containsAll(snapshot.keySet()))throw new IllegalArgumentException("Private evidence is not an event snapshot");
  String key=key(a,bill,type,command);
  var row=repository.base(a);row.put("event_no",eventNumber(a,bill,type,command));
  row.put("bill_no",text(bill,"bill_no"));row.put("event_type",type);row.put("command_no",key);row.put("event_snapshot",repository.database().encode(snapshot));row.put("bill_revision",number(bill,"version"));row.put("reason",reason);repository.insert("sv_settlement_event",row);
 }
 public String eventNumber(Authority a,Map<String,Object> bill,String type,String command){return numbers.bind(DocumentType.SV_SETTLEMENT_EVENT,new DocumentSource(a.tenantNo(),"PAY","SV_SETTLEMENT_EVENT",key(a,bill,type,command)));}
 private String key(Authority a,Map<String,Object> bill,String type,String command){return EntitlementRecords.digest(repository.database().encode(List.of(a.actorNo(),text(bill,"bill_no"),type,command)));}
 public Map<String,Object> advance(Authority a,Map<String,Object> bill,String state){
  int changed=repository.database().update("UPDATE sv_settlement_bill SET status=?,version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND bill_no=? AND version=?",state,Timestamp.from(Instant.now()),a.actorNo(),a.tenantNo(),text(bill,"bill_no"),number(bill,"version"));
  if(changed!=1)throw BizException.of(ErrorCode.CONFLICT);return repository.bill(a,text(bill,"bill_no"),false);
 }
 public static String paymentState(Map<String,Object> bill){long due=number(bill,"payable_minor"),registered=number(bill,"registered_payment_minor"),confirmed=number(bill,"confirmed_receipt_minor");
  if("INTERNAL_RECON".equals(text(bill,"bill_kind")))return "INTERNAL_RECONCILED";
  if(confirmed==due)return "SETTLED";if(registered>confirmed)return "WAIT_RECEIPT";if(confirmed>0)return "PART_PAID";return "RECONCILED";
 }
}
