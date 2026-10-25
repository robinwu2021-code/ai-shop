package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership.*;
import java.util.*;

/**
 * 门店换品牌的资金侧日志：登记 → Pay 预备 → 切换绑定 → 完成（mch_brand_financial_change）。
 * 实现见 {@code impl.BrandFinancialChangeServiceImpl}；跨服务编排在 shop-app 的 BrandFinancialChangeCoordinator。
 */
public interface BrandFinancialChangeService {
 public record Pending(String changeNo,String state,boolean staged,Prepare command){}
 public record Frozen(Prepare command,long storeVersion,String fromBrand,String toBrand,String oldBinding,String newBinding,long newRevision){}
 public record Receipt(Ack ack,boolean staged){}
 public record Source(String managementNo,String storeNo,String entityNo,List<String> brands){public Source{brands=List.copyOf(brands);}}

 Source source(Actor a,String management,String store,String to);
 Pending original(Actor a,String management,String no);
 Pending findOriginal(Actor a,String management,String store,String key);
 Pending byKey(Actor a,String management,String store,String key,long expected,String to,String reason);
 Pending register(Actor a,String management,String store,String to,long expected,String reason,String key,List<Target> targets);
 Pending prepared(Actor a,String management,String no,Ack ack);
 Pending stage(Actor a,String management,String no);
 Pending complete(Actor a,String management,String no,Ack ack);
 String expectedPayHash(Prepare c);
}
