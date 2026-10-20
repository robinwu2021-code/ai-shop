package ai.neargo.shop.unit;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.paybridge.dto.StoredValueRechargeProgress;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.*;
import org.junit.jupiter.api.Test;

class StoredValueDocumentProgressTest {
    @Test void humanNumberSurvivesBffConversionAndReceiptStatusChanges() {
        var numbered=new RechargeView("internal",RechargeState.WAIT_COLLECTION,"U","E","ST","CNY",100L,100L,null,null,0,"U","PR20261020000001");
        assertThat(StoredValueRechargeProgress.from(numbered).documentNo()).isEqualTo("PR20261020000001");
        var receipt=new RechargeView("internal",RechargeState.SUBMITTED,"U","E","ST","CNY",100L,100L,null,null,1);
        assertThat(StoredValueRechargeProgress.from(receipt,numbered.documentNo()).documentNo()).isEqualTo(numbered.documentNo());
        assertThat(StoredValueRechargeProgress.from(receipt).documentNo()).isNull();
        assertThat(StoredValueRechargeProgress.from(numbered).rechargeNo()).isEqualTo("internal");
    }
}
