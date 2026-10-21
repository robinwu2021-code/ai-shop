package ai.neargo.shop.pay.storedvalue;
import ai.neargo.shop.pay.mapper.storedvalue.SvOrganizationImpactMapper;
import ai.neargo.shop.pay.storedvalue.service.StoredValueOrganizationImpactService;
import ai.neargo.shop.spi.storedvalue.StoredValueOrganizationImpactPort;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
class StoredValueOrganizationImpactTest {
 @Test void pendingReceiptsAndUnattributedAccountsAreNotReportedAsClear() {
  var m=mock(SvOrganizationImpactMapper.class);when(m.obligations("MAIN","E1","S1")).thenReturn(Map.of("outstandingMinor",0L,"heldMinor",0L,"pendingRecharges",1L,"unattributedAccounts",1L));
  var f=new StoredValueOrganizationImpactService(m).inspect("MAIN","E1","S1");
  assertThat(f.pendingRecharges()).isEqualTo(1);assertThat(f.unattributedAccounts()).isEqualTo(1);assertThat(f.complete()).isTrue();
 }
 @Test void unknownOwnerEvidenceFailsClosed() {
  var m=mock(SvOrganizationImpactMapper.class);when(m.obligations("MAIN","E1","S1")).thenReturn(Map.of());
  assertThatThrownBy(()->new StoredValueOrganizationImpactService(m).inspect("MAIN","E1","S1")).isInstanceOf(RuntimeException.class);
 }
 @Test void activeBrandIssuerIsExplicitlyReported() {
  var m=mock(SvOrganizationImpactMapper.class);when(m.obligations("MAIN","E1","S1")).thenReturn(Map.of("outstandingMinor",0L,"heldMinor",0L,"pendingRecharges",0L,"unattributedAccounts",0L));when(m.brandSchema()).thenReturn(1L);when(m.activeBrandIssuers("MAIN")).thenReturn(1L);
  assertThat(new StoredValueOrganizationImpactService(m).inspect("MAIN","E1","S1").brandFinancialEnabled()).isTrue();
 }
}
