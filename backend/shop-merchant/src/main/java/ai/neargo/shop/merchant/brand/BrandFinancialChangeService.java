package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership.Prepare;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership.Ack;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership.Target;
import java.util.List;

/** Durable Core participant retaining original actor, command and binding identities. */
public interface BrandFinancialChangeService {
    public record Pending(String changeNo,String state,boolean staged,Prepare command){}
    public record Frozen(Prepare command,long storeVersion,String fromBrand,String toBrand,String oldBinding,String newBinding,long newRevision){}
    public record Receipt(Ack ack,boolean staged){}
    public record Source(String managementNo,String storeNo,String entityNo,List<String> brands){public Source{brands=List.copyOf(brands);}}

    public Source source(Actor a,String management,String store,String to);
    public Pending original(Actor a,String management,String no);
    public Pending findOriginal(Actor a,String management,String store,String key);
    public Pending byKey(Actor a,String management,String store,String key,long expected,String to,String reason);
    public Pending register(Actor a,String management,String store,String to,long expected,String reason,String key,List<Target> targets);
    public Pending prepared(Actor a,String management,String no,Ack ack);
    public Pending stage(Actor a,String management,String no);
    public Pending complete(Actor a,String management,String no,Ack ack);
    public String expectedPayHash(Prepare c);
}
