package ai.neargo.shop.paybridge;
import ai.neargo.shop.common.*;
import ai.neargo.shop.promotion.service.StoredValueActivityModels.Scope;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.time.*;
import org.springframework.stereotype.Service;
@Service
public class StoredValueActivityScheduleService {
 private final MarketPort markets;private final MerchantQueryPort merchants;
 public record Schedule(String startAt,String endAt,String zone,String currency,int currencyScale) { }
 public StoredValueActivityScheduleService(MarketPort markets,MerchantQueryPort merchants){this.markets=markets;this.merchants=merchants;}
 public record Context(String currency,int currencyScale,String timeZone) { }
 public Context context(Scope scope){var market=markets.find(merchants.marketOf(scope.entityNo())).filter(MarketPort.MarketBrief::enabled).orElseThrow(()->BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));return new Context(market.currency(),market.currencyScale(),market.timeZone());}
 public Schedule resolve(Scope scope,String first,String last){
  var market=context(scope);
  if(!scope.policyScope().currency().equals(market.currency()))throw BizException.of(ErrorCode.BAD_REQUEST);
  try{var start=LocalDate.parse(first);var end=LocalDate.parse(last);if(end.isBefore(start))throw BizException.of(ErrorCode.BAD_REQUEST);var zone=ZoneId.of(market.timeZone());return new Schedule(start.atStartOfDay(zone).toInstant().toString(),end.plusDays(1).atStartOfDay(zone).toInstant().toString(),zone.getId(),market.currency(),market.currencyScale());}
  catch(DateTimeException|ArithmeticException invalid){throw BizException.of(ErrorCode.BAD_REQUEST);}
 }
}
