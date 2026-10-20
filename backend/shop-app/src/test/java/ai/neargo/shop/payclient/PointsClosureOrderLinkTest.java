package ai.neargo.shop.payclient;
import ai.neargo.shop.pay.points.admin.impl.PointsInspectionServiceImpl;
import ai.neargo.shop.pay.points.store.*;
import ai.neargo.shop.pay.points.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.Clock;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PointsClosureOrderLinkTest {
 @Test void closureLinkComesFromVerifiedVoucherResultNotAnInboxGuess() {
  var read=mock(PointsReadStore.class);var store=mock(PointsOperationsStore.class);
  when(read.type("P")).thenReturn(java.util.Optional.of(new PointType("P","Name","CN","CNY",2,new RedeemRate(1,1),10000,365,PointType.Status.ACTIVE)));
  var beans=new StaticListableBeanFactory();beans.addBean("clock",Clock.systemUTC());
  var service=new PointsInspectionServiceImpl(read,store,null,null,null,null,beans.getBeanProvider(Clock.class));
  String ref="REFUND_CLOSE:"+PointsKeys.position("C","P","S");
  when(store.voucherByNo("P","V")).thenReturn(Map.of("biz_ref",ref));
  when(read.voucher(ref)).thenReturn(Map.of("result_json","{\"subOrderNo\":\"S\",\"releasedPoints\":0,\"releasedMinor\":0,\"replayed\":false}"));
  assertThat(service.earningOrder("P","V")).isEqualTo("S");
 }
}
