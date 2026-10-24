package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.Ack;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.Prepare;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.AuthorizedScope;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.View;
import java.util.Map;

/** Original P5 business contract; implementation retains its authorization and transaction boundaries. */
public interface BrandFinancialActivationService {
    record Pending(String activationNo,String state,Prepare command) { }

    /** Replay only the original actor, command key and expected issuer revision. */
    Pending original(Actor actor,String management,String brand,String key,long expectedRevision);

    /** Freeze every authorized live brand member before crossing into Pay. */
    Pending prepare(Actor actor,AuthorizedScope authority,View issuer,String key);

    /** Accept only a matching Pay acknowledgement against the still-current organization fence. */
    Map<String,Object> acknowledge(Actor actor,String management,String brand,Ack ack);
}
