package ai.neargo.shop.paybridge;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.service.StoredValueActivityModels.Scope;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class StoredValueActivityScheduleTest {
 @Test void actualStoreTimeZoneControlsCalendarRangeIncludingDaylightSaving(){
  var markets=mock(MarketPort.class);var merchants=mock(MerchantQueryPort.class);when(merchants.marketOf("E1")).thenReturn("US");when(markets.find("US")).thenReturn(Optional.of(new MarketPort.MarketBrief("US","United States","USD",2,"America/New_York",1,true)));
  var target=new StoredValueActivityScheduleService(markets,merchants);var scope=new Scope(new StoreScope("MAIN","M1","S1",null,"USD"),"E1","OWNER","OWNER",1);
  var spring=target.resolve(scope,"2026-03-08","2026-03-08");assertThat(spring.startAt()).isEqualTo("2026-03-08T05:00:00Z");assertThat(spring.endAt()).isEqualTo("2026-03-09T04:00:00Z");assertThat(Duration.between(Instant.parse(spring.startAt()),Instant.parse(spring.endAt())).toHours()).isEqualTo(23);
  var fall=target.resolve(scope,"2026-11-01","2026-11-01");assertThat(Duration.between(Instant.parse(fall.startAt()),Instant.parse(fall.endAt())).toHours()).isEqualTo(25);
  assertThatThrownBy(()->target.resolve(scope,"2026-11-02","2026-11-01")).isInstanceOf(BizException.class);
  var wrong=new Scope(new StoreScope("MAIN","M1","S1",null,"CNY"),"E1","OWNER","OWNER",1);assertThatThrownBy(()->target.resolve(wrong,"2026-03-08","2026-03-08")).isInstanceOf(BizException.class);
 }
}
