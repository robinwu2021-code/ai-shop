package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.payclient.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class StoredValueIssuerAppServiceImpl implements StoredValueIssuerAppService {
    private final ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerService target;
    public StoredValueIssuerAppServiceImpl(ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerService target) { this.target=target; }
    public Lookup find(AuthorizedScope scope) { return new Lookup(target.find(scope).orElse(null)); }
    public View configure(AuthorizedScope scope,Config command,String key) { return target.configure(scope,command,key); }
}
