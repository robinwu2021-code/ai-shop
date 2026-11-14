package ai.neargo.shop.payclient;
import ai.neargo.shop.pay.points.policy.EarningCashBasis;
import ai.neargo.shop.spi.points.PointsFactPort.LineSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class EarningCashBasisTest {
 @Test void allocatesCashRatherThanInvertingRoundedDeductions() {
  assertThat(EarningCashBasis.allocate(List.of(new LineSnapshot("A",1,1,0),new LineSnapshot("B",1,1,0)),1))
    .containsEntry("A",1L).containsEntry("B",0L);
 }
 @Test void rejectsDeductionExceedingGrossAndPreservesZero() {
  var lines=List.of(new LineSnapshot("A",1,0,0));
  assertThat(EarningCashBasis.allocate(lines,0)).containsEntry("A",0L);
  assertThatThrownBy(()->EarningCashBasis.allocate(lines,1)).hasMessage("POINTS_EARN_CASH_BASIS_INVALID");
 }
}
