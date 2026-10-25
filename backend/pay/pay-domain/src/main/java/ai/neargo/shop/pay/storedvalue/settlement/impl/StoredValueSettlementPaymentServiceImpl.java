package ai.neargo.shop.pay.storedvalue.settlement.impl;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.number;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.instant;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.missing;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.paymentView;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.requireParty;
import static ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository.revision;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.numbering.DocumentSource;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueCrossEntityReadiness;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementAudit;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementEvidenceCipher;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementPaymentService;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementRepository;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Authority;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Payment;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Receipt;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Registration;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.VoidPayment;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Offline evidence registration and independent receipt acknowledgement. Never invokes a bank or touches wallets. */
@Service("storedValueSettlementPaymentService")
public class StoredValueSettlementPaymentServiceImpl implements StoredValueSettlementPaymentService {
 private static final String REGISTER="sv.settlement.payment.register",RECEIVE="sv.settlement.receipt.confirm",VOID="sv.settlement.payment.void";
 private final StoredValueSettlementRepository repo;private final StoredValueSettlementAudit audit;private final StoredValueSettlementEvidenceCipher cipher;
 private final EntitlementCommandDeduplicator commands;private final DocumentNumberCoordinator numbers;private final StoredValueCrossEntityReadiness readiness;
 public StoredValueSettlementPaymentServiceImpl(StoredValueSettlementRepository repo,StoredValueSettlementAudit audit,StoredValueSettlementEvidenceCipher cipher,EntitlementCommandDeduplicator commands,@Qualifier("payDocumentNumberCoordinator") DocumentNumberCoordinator numbers,StoredValueCrossEntityReadiness readiness){this.repo=repo;this.audit=audit;this.cipher=cipher;this.commands=commands;this.numbers=numbers;this.readiness=readiness;}
 public Payment register(Registration c){
  var a=c.authority();var bill=repo.bill(a,c.billNo(),false);requireParty(a,bill,text(bill,"payer_entity_no"),REGISTER);
  String reference=normalized(c.receiptReference());var material=Arrays.asList(a.principalNo(),c.billNo(),c.expectedRevision(),c.amountMinor(),c.paidAt(),c.method(),reference,c.evidenceRefs().stream().sorted().toList());var scope=scope(a,bill);
  var original=commands.replay(scope,"SV_SETTLEMENT_PAYMENT",c.commandKey(),material,Payment.class);if(original.isPresent())return original.get();
  readiness.requireNewSettlement();cipher.keyId();if(!a.verifiedEvidenceRefs().containsAll(c.evidenceRefs()))throw BizException.of(ErrorCode.FORBIDDEN);
  return numbers.withReservation(Map.of(DocumentType.SV_SETTLEMENT_PAYMENT,1,DocumentType.SV_SETTLEMENT_EVENT,1),()->repo.database().write(()->{
   repo.issuer(a,text(bill,"issuer_no"),true);
   return commands.execute(scope,"SV_SETTLEMENT_PAYMENT",c.commandKey(),material,c.billNo(),Payment.class,()->{
    var locked=repo.bill(a,c.billNo(),true);requireParty(a,locked,text(locked,"payer_entity_no"),REGISTER);revision(locked,c.expectedRevision());
    if(!"CROSS_ENTITY".equals(text(locked,"bill_kind"))||!Set.of("RECONCILED","PART_PAID","WAIT_RECEIPT").contains(text(locked,"status"))||locked.get("payer_reconciled_at")==null||locked.get("payee_reconciled_at")==null)conflict();
    if(c.amountMinor()>Math.subtractExact(number(locked,"payable_minor"),number(locked,"registered_payment_minor"))||c.paidAt().isAfter(Instant.now()))conflict();long total=Math.addExact(number(locked,"registered_payment_minor"),c.amountMinor());
    String digest=EntitlementRecords.digest(repo.database().encode(List.of(text(locked,"payer_entity_no"),c.method(),reference)));
    if(repo.database().one("SELECT payment_no FROM sv_settlement_payment WHERE tenant_no=? AND payer_entity_no=? AND active_receipt_digest=?",a.tenantNo(),text(locked,"payer_entity_no"),digest)!=null)conflict();
    String source=EntitlementRecords.digest(repo.database().encode(List.of(a.actorNo(),c.billNo(),c.commandKey())));
    String no=numbers.bind(DocumentType.SV_SETTLEMENT_PAYMENT,new DocumentSource(a.tenantNo(),"PAY","SV_SETTLEMENT_PAYMENT",source));
    var row=repo.base(a);row.put("payment_no",no);row.put("bill_no",c.billNo());row.put("payer_entity_no",text(locked,"payer_entity_no"));row.put("payee_entity_no",text(locked,"payee_entity_no"));row.put("currency",text(locked,"currency"));row.put("amount_minor",c.amountMinor());row.put("paid_at",Timestamp.from(c.paidAt().truncatedTo(ChronoUnit.MILLIS)));row.put("payment_method",c.method());
    String aad=aad(a.tenantNo(),no,locked);row.put("receipt_ciphertext",cipher.encrypt(reference,aad));row.put("receipt_key_id",cipher.keyId());row.put("receipt_digest",digest);row.put("evidence_refs",repo.database().encode(c.evidenceRefs()));row.put("status","REGISTERED");row.put("registered_by",a.actorNo());row.put("registered_principal_no",a.principalNo());repo.insert("sv_settlement_payment",row);
    repo.database().update("UPDATE sv_settlement_bill SET registered_payment_minor=? WHERE tenant_no=? AND bill_no=?",total,a.tenantNo(),c.billNo());
    locked=repo.bill(a,c.billNo(),false);locked=audit.advance(a,locked,StoredValueSettlementAudit.paymentState(locked));audit.append(a,locked,"PAYMENT",c.commandKey(),null,Map.of("paymentNo",no,"amountMinor",c.amountMinor(),"state","REGISTERED"));return paymentView(repo.payment(a,no,false));
   });
  }));
 }
 public Payment confirm(Receipt c){
  var a=c.authority();var prior=repo.payment(a,c.paymentNo(),false);var bill=repo.bill(a,text(prior,"bill_no"),false);requireParty(a,bill,text(bill,"payee_entity_no"),RECEIVE);
  var material=List.of(a.principalNo(),c.paymentNo(),c.expectedRevision(),c.receivedAt());var scope=scope(a,bill);
  var original=commands.replay(scope,"SV_SETTLEMENT_RECEIPT",c.commandKey(),material,Payment.class);if(original.isPresent())return original.get();
  return numbers.withReservation(Map.of(DocumentType.SV_SETTLEMENT_EVENT,1),()->repo.database().write(()->{
   repo.issuer(a,text(bill,"issuer_no"),true);
   return commands.execute(scope,"SV_SETTLEMENT_RECEIPT",c.commandKey(),material,c.paymentNo(),Payment.class,()->{
    var locked=repo.bill(a,text(bill,"bill_no"),true);var payment=repo.payment(a,c.paymentNo(),true);requireParty(a,locked,text(locked,"payee_entity_no"),RECEIVE);revision(payment,c.expectedRevision());
    if(!"REGISTERED".equals(text(payment,"status"))||a.actorNo().equals(text(payment,"registered_by"))||a.principalNo().equals(text(payment,"registered_principal_no"))||c.receivedAt().isBefore(instant(payment.get("paid_at")))||c.receivedAt().isAfter(Instant.now()))conflict();
    if(number(payment,"amount_minor")>Math.subtractExact(number(locked,"registered_payment_minor"),number(locked,"confirmed_receipt_minor")))conflict();long confirmed=Math.addExact(number(locked,"confirmed_receipt_minor"),number(payment,"amount_minor"));if(confirmed>number(locked,"payable_minor"))conflict();
    repo.database().update("UPDATE sv_settlement_payment SET status='RECEIPT_CONFIRMED',received_by=?,received_principal_no=?,received_at=?,version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND payment_no=? AND version=?",a.actorNo(),a.principalNo(),Timestamp.from(c.receivedAt().truncatedTo(ChronoUnit.MILLIS)),Timestamp.from(Instant.now()),a.actorNo(),a.tenantNo(),c.paymentNo(),c.expectedRevision());
    repo.database().update("UPDATE sv_settlement_bill SET confirmed_receipt_minor=? WHERE tenant_no=? AND bill_no=?",confirmed,a.tenantNo(),text(locked,"bill_no"));
    locked=repo.bill(a,text(locked,"bill_no"),false);String state="DISPUTED".equals(text(locked,"status"))?"DISPUTED":StoredValueSettlementAudit.paymentState(locked);locked=audit.advance(a,locked,state);audit.append(a,locked,"RECEIPT",c.commandKey(),null,Map.of("paymentNo",c.paymentNo(),"amountMinor",number(payment,"amount_minor"),"confirmedMinor",confirmed));return paymentView(repo.payment(a,c.paymentNo(),false));
   });
  }));
 }
 public Payment voidRegistration(VoidPayment c){
  var a=c.authority();var prior=repo.payment(a,c.paymentNo(),false);var bill=repo.bill(a,text(prior,"bill_no"),false);requireParty(a,bill,text(bill,"payer_entity_no"),VOID);
  var material=List.of(a.principalNo(),c.paymentNo(),c.expectedRevision(),c.reason());var scope=scope(a,bill);
  var original=commands.replay(scope,"SV_SETTLEMENT_PAYMENT_VOID",c.commandKey(),material,Payment.class);if(original.isPresent())return original.get();
  return numbers.withReservation(Map.of(DocumentType.SV_SETTLEMENT_EVENT,1),()->repo.database().write(()->{
   repo.issuer(a,text(bill,"issuer_no"),true);
   return commands.execute(scope,"SV_SETTLEMENT_PAYMENT_VOID",c.commandKey(),material,c.paymentNo(),Payment.class,()->{
    var locked=repo.bill(a,text(bill,"bill_no"),true);var payment=repo.payment(a,c.paymentNo(),true);requireParty(a,locked,text(locked,"payer_entity_no"),VOID);revision(payment,c.expectedRevision());if(!"REGISTERED".equals(text(payment,"status")))conflict();
    long registered=Math.subtractExact(number(locked,"registered_payment_minor"),number(payment,"amount_minor"));if(registered<number(locked,"confirmed_receipt_minor"))conflict();
    repo.database().update("UPDATE sv_settlement_payment SET status='VOIDED',void_reason=?,version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND payment_no=? AND version=?",c.reason(),Timestamp.from(Instant.now()),a.actorNo(),a.tenantNo(),c.paymentNo(),c.expectedRevision());
    repo.database().update("UPDATE sv_settlement_bill SET registered_payment_minor=? WHERE tenant_no=? AND bill_no=?",registered,a.tenantNo(),text(locked,"bill_no"));locked=repo.bill(a,text(locked,"bill_no"),false);String state="DISPUTED".equals(text(locked,"status"))?"DISPUTED":StoredValueSettlementAudit.paymentState(locked);locked=audit.advance(a,locked,state);audit.append(a,locked,"PAYMENT_VOID",c.commandKey(),c.reason(),Map.of("paymentNo",c.paymentNo(),"amountMinor",number(payment,"amount_minor"),"registeredMinor",registered));return paymentView(repo.payment(a,c.paymentNo(),false));
   });
  }));
 }
 public Payment resolve(Authority a,String billNo,String key){
  var bill=repo.bill(a,billNo,false);requireParty(a,bill,text(bill,"payer_entity_no"),REGISTER);
  var row=repo.database().one("SELECT created_by,resource_no,result_status,response_snapshot FROM pay_ent_command_dedup WHERE tenant_no=? AND operation_kind='SV_SETTLEMENT_PAYMENT' AND request_key=? AND deleted=0",a.tenantNo(),key);
  if(row==null)return null;if(!a.actorNo().equals(text(row,"created_by"))||!billNo.equals(text(row,"resource_no")))missing();if(!"COMPLETED".equals(text(row,"result_status")))conflict();var result=repo.database().decode(text(row,"response_snapshot"),Payment.class);var payment=repo.payment(a,result.paymentNo(),false);if(!billNo.equals(text(payment,"bill_no")))conflict();return paymentView(payment);
 }
 public static String normalized(String input){String value=Normalizer.normalize(input,Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s]+","").toUpperCase(Locale.ROOT);if(value.isBlank()||value.length()>160)throw BizException.of(ErrorCode.BAD_REQUEST);return value;}
 private static CatalogScope scope(Authority a,Map<String,Object> bill){return new CatalogScope(a.tenantNo(),text(bill,"payer_entity_no"),a.actorNo(),false);}
 private String aad(String tenant,String no,Map<String,Object> bill){return EntitlementRecords.digest(repo.database().encode(List.of(tenant,no,text(bill,"bill_no"),text(bill,"payer_entity_no"),text(bill,"payee_entity_no"),text(bill,"currency"))));}
 private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}
