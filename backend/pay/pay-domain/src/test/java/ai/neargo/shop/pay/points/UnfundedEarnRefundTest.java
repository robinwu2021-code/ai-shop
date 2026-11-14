package ai.neargo.shop.pay.points;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl;
import ai.neargo.shop.pay.points.store.*;
import ai.neargo.shop.pay.points.model.*;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.spi.points.PointsFactPort.*;
import ai.neargo.shop.spi.points.PointsRefundPort.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.Instant;
class UnfundedEarnRefundTest {
 final PointsReadStore read=mock(PointsReadStore.class);
 final PointsConfigurationStore config=mock(PointsConfigurationStore.class);
 final PointsPostingService posting=mock(PointsPostingService.class);
 final ObjectMapper json=new ObjectMapper();
 final PaymentSnapshot payment=new PaymentSnapshot("DIRECT","WECHAT","M","S","V1","SM","T","OT",10000);
 PointsRefundPreparationServiceImpl service;
 @BeforeEach @SuppressWarnings("unchecked") void setup(){
  when(read.position(anyString(),anyString())).thenReturn(null);
  when(read.type("P")).thenReturn(Optional.of(new PointType("P","P","CN","CNY",2,new RedeemRate(1,1),3000,365,PointType.Status.ACTIVE)));
  when(read.account("P",HolderType.USER,"U")).thenReturn(new AccountSnapshot("P",HolderType.USER,"U",Map.of(),false,null,0));
  var paid=new LifecycleFact("PAID","hash","O","U","P",1,FactKind.PAID,10000,0,List.of(new LineSnapshot("I",1,10000,0)),Instant.now(),payment);
  when(config.lockLifecycle("P","O")).thenReturn(Map.of("payload_json",json.writeValueAsString(Map.of("paidFact",paid))));
  service=new PointsRefundPreparationServiceImpl(read,config,posting,mock(ObjectProvider.class),mock(ObjectProvider.class));
 }
 RefundCommand command(long cumulative,PaymentSnapshot p){return new RefundCommand("R","O","U","P","CNY",2,cumulative,cumulative,List.of(new RefundLine("I",cumulative)),p);}
 @Test void cashRefundDoesNotDependOnRewardsBudget(){
  var prepared=service.prepare(command(2500,payment));
  assertThat(prepared.netCashMinor()).isEqualTo(2500);
  assertThat(prepared.reclaimedPending()).isZero();assertThat(prepared.cashOffsetMinor()).isZero();
 }
 @Test void unverifiedPaymentCannotUsePaidFactFallback(){
  var other=new PaymentSnapshot("DIRECT","WECHAT","M","S","V1","SM","WRONG","OT",10000);
  assertThatThrownBy(()->service.prepare(command(2500,other))).hasMessage("POINTS_REFUND_PAYMENT_MISMATCH");
 }
 @Test void cumulativeRefundMustNotRegressWhenNoGrantExists(){
  var prior=new PointsRefundPreparationServiceImpl.RefundSnapshot(command(5000,payment),null,null,null,Map.of(),null,null,null,null,null,null);
  when(read.confirmedRefunds("P","O")).thenReturn(List.of(Map.of("payload_json",json.writeValueAsString(prior))));
  assertThatThrownBy(()->service.prepare(command(2500,payment))).hasMessage("POINTS_REFUND_LINES_INVALID");
 }
 @Test void subsequentPaidOnlyRefundKeepsCumulativeAmountsMonotonic(){
  var first=new PointsRefundPreparationServiceImpl.RefundSnapshot(command(2500,payment),null,null,null,Map.of(),null,null,null,null,null,null);
  var second=new PointsRefundPreparationServiceImpl.RefundSnapshot(command(5000,payment),null,null,null,Map.of(),null,null,null,null,null,null);
  when(read.confirmedRefunds("P","O")).thenReturn(List.of(Map.of("payload_json",json.writeValueAsString(first)),Map.of("payload_json",json.writeValueAsString(second))));
  assertThat(service.prepare(command(7500,payment)).netCashMinor()).isEqualTo(7500);
  assertThatThrownBy(()->service.prepare(command(4000,payment))).hasMessage("POINTS_REFUND_LINES_INVALID");
 }

}
