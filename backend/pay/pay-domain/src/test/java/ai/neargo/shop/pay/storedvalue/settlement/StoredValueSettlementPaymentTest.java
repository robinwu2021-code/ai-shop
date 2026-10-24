package ai.neargo.shop.pay.storedvalue.settlement;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class StoredValueSettlementPaymentTest {
 @Test void paymentRegistrationIsNotReceiptAndPartialReceiptsNeverOverpay()throws Exception{
  var f=new StoredValueSettlementFixture();f.bill(500);var command=f.registration(200,0,"bank-001","PAY1");var first=f.payments.register(command);assertThat(first.status()).isEqualTo("REGISTERED");assertThat(f.value("registered_payment_minor")).isEqualTo(200);assertThat(f.value("confirmed_receipt_minor")).isZero();assertThat(f.payments.register(command)).isEqualTo(first);
  assertThatThrownBy(()->f.payments.register(f.registration(400,1,"BANK-002","OVER"))).isInstanceOf(BizException.class);
  var second=f.payments.register(f.registration(300,1,"BANK-002","PAY2"));assertThat(f.value("registered_payment_minor")).isEqualTo(500);assertThat(f.value("confirmed_receipt_minor")).isZero();
  f.payments.confirm(new Receipt(f.payee(),first.paymentNo(),0,Instant.parse("2026-10-03T00:00:00Z"),"RC1"));assertThat(f.value("confirmed_receipt_minor")).isEqualTo(200);
  var receipt=new Receipt(f.payee(),second.paymentNo(),0,Instant.parse("2026-10-03T00:00:00Z"),"RC2");f.payments.confirm(receipt);f.payments.confirm(receipt);assertThat(f.value("confirmed_receipt_minor")).isEqualTo(500);assertThat(f.jdbc.queryForObject("SELECT status FROM sv_settlement_bill",String.class)).isEqualTo("SETTLED");assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_settlement_event",Long.class)).isEqualTo(4);
 }
 @Test void anotherAccountForTheSameHumanCannotConfirmOwnPayment()throws Exception{
  var f=new StoredValueSettlementFixture();f.bill(500);var first=f.payments.register(f.registration(500,0,"BANK-001","PAY"));
  var alias=f.actor("USER:PAYER","USER:PAYER","E2");assertThatThrownBy(()->f.payments.confirm(new Receipt(alias,first.paymentNo(),0,Instant.parse("2026-10-03T00:00:00Z"),"SELF"))).isInstanceOf(BizException.class);assertThat(f.value("confirmed_receipt_minor")).isZero();
  assertThatThrownBy(()->f.payments.confirm(new Receipt(f.payer(),first.paymentNo(),0,Instant.parse("2026-10-03T00:00:00Z"),"WRONG_PARTY"))).isInstanceOf(BizException.class);
 }
 @Test void unknownRegistrationResolvesWithoutPlaintextAndOriginalReceiptSurvivesNewCommandClosure()throws Exception{
  var f=new StoredValueSettlementFixture();f.bill(500);var command=f.registration(500,0,"BANK-SECRET-001","PAY");var payment=f.payments.register(command);var raw=f.jdbc.queryForMap("SELECT * FROM sv_settlement_payment");assertThat(new String((byte[])raw.get("receipt_ciphertext"),java.nio.charset.StandardCharsets.US_ASCII)).doesNotContain("SECRET");assertThat(f.json.writeValueAsString(payment)).doesNotContain("receipt","SECRET");
  assertThat(f.payments.resolve(f.payer(),"BILL1","PAY")).isEqualTo(payment);doThrow(BizException.of(ai.neargo.shop.common.ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE)).when(f.readiness).requireNewSettlement();assertThat(f.payments.register(command)).isEqualTo(payment);
  f.payments.confirm(new Receipt(f.payee(),payment.paymentNo(),0,Instant.parse("2026-10-03T00:00:00Z"),"RC"));assertThat(f.value("confirmed_receipt_minor")).isEqualTo(500);
 }
 @Test void voidKeepsTheAuditAndAllowsCorrectingOnlyAnUnconfirmedReceipt()throws Exception{
  var f=new StoredValueSettlementFixture();f.bill(500);var first=f.payments.register(f.registration(500,0,"BANK-001","PAY1"));f.payments.voidRegistration(new VoidPayment(f.payer(),first.paymentNo(),0,"Wrong registration","VOID"));assertThat(f.value("registered_payment_minor")).isZero();
  var corrected=f.payments.register(f.registration(500,2,"bank-001","PAY2"));f.payments.confirm(new Receipt(f.payee(),corrected.paymentNo(),0,Instant.parse("2026-10-03T00:00:00Z"),"RC"));assertThatThrownBy(()->f.payments.voidRegistration(new VoidPayment(f.payer(),corrected.paymentNo(),1,"Cannot void received","BAD_VOID"))).isInstanceOf(BizException.class);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_settlement_payment",Long.class)).isEqualTo(2);
 }
}
