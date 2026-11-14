package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.pay.storedvalue.issuer.*;
import ai.neargo.shop.payclient.StoredValueBrandAppService;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class StoredValueBrandAppServiceImpl implements StoredValueBrandAppService {
    private final StoredValueBrandRuntimeReadiness readiness;private final StoredValueBrandActivationService pay;
    public StoredValueBrandAppServiceImpl(StoredValueBrandRuntimeReadiness readiness,StoredValueBrandActivationService pay){this.readiness=readiness;this.pay=pay;}
    public Capability capability(){return readiness.capability();}public Ack activate(Prepare command){return pay.activate(command);}
}
