package ai.neargo.shop.pay.storedvalue.settlement;
import ai.neargo.shop.common.BizException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class StoredValueSettlementEvidenceCipherTest {
 @Test void privateReceiptIsBoundToItsTenantAndBillAndCannotBeTampered(){
  String key=Base64.getEncoder().encodeToString(new byte[32]);var c=new StoredValueSettlementEvidenceCipher(key,"TEST");byte[] first=c.encrypt("BANK-RECEIPT-123","MAIN:BILL1:PAY1");assertThat(c.decrypt(first,"MAIN:BILL1:PAY1")).isEqualTo("BANK-RECEIPT-123");assertThatThrownBy(()->c.decrypt(first,"OTHER:BILL1:PAY1")).isInstanceOf(BizException.class);assertThat(c.encrypt("BANK-RECEIPT-123","MAIN:BILL1:PAY1")).isNotEqualTo(first);first[first.length-3]^=1;assertThatThrownBy(()->c.decrypt(first,"MAIN:BILL1:PAY1")).isInstanceOf(BizException.class);
 }
 @Test void missingKeyIsUnavailableInsteadOfUsingPlaintextOrDefault(){var c=new StoredValueSettlementEvidenceCipher("","");assertThat(c.configured()).isFalse();assertThatThrownBy(()->c.encrypt("BANK-001","MAIN:BILL")).isInstanceOf(BizException.class);}
}
