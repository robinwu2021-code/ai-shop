package ai.neargo.shop.pay.numbering;

import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlPayout;
import ai.neargo.shop.pay.impl.PayoutServiceImpl;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PayoutMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaySettlementAssociationNumbersTest {
    @Test void payoutPageResolvesAllSettlementNumbersOnceAndKeepsInternalAssociations() throws Exception {
        var payouts = mock(PayoutMapper.class);var bills = mock(BillMapper.class);var documents = mock(PayOperationsDocuments.class);
        var p1 = new StlPayout();p1.setPayoutNo("P1");var p2 = new StlPayout();p2.setPayoutNo("P2");
        var b1 = new StlBill();b1.setSettleNo("internal1");var b2 = new StlBill();b2.setSettleNo("historical");
        when(payouts.selectList(any())).thenReturn(List.of(p1,p2));
        when(bills.selectList(any())).thenReturn(List.of(b1),List.of(b2));
        when(documents.findAll(DocumentType.SETTLEMENT_BILL,"MAIN",List.of("internal1","historical")))
                .thenReturn(Map.of("internal1","STS20261020000001"));
        var service = new PayoutServiceImpl(bills,null,payouts,null,null,null,null);
        var field = PayoutServiceImpl.class.getDeclaredField("documents");field.setAccessible(true);field.set(service,documents);
        var result = service.list(null,null);
        assertThat(result.get(0).settleNos()).containsExactly("internal1");
        assertThat(result.get(0).settleDocumentNos()).containsExactly("STS20261020000001");
        assertThat(result.get(1).settleNos()).containsExactly("historical");
        assertThat(result.get(1).settleDocumentNos()).isEmpty();
        verify(documents).findAll(DocumentType.SETTLEMENT_BILL,"MAIN",List.of("internal1","historical"));
        verifyNoMoreInteractions(documents);
    }
}
