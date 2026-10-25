package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.pay.client.PayStoredValueWalletRequests;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.storedvalue.service.StoredValueMerchantQueryService;
import ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerQueryService;
import ai.neargo.shop.payclient.StoredValueWalletAppService;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class StoredValueWalletAppServiceImpl implements StoredValueWalletAppService {
    @Autowired private StoredValueMerchantQueryService records;
    private final StoredValueIssuerQueryService pay;
    public StoredValueWalletAppServiceImpl(StoredValueIssuerQueryService pay){this.pay=pay;}
    public List<AvailableAccount> accounts(Query query){return pay.accounts(query);}
    public CursorPage<StoredValueMerchantViews.Record> records(PayStoredValueWalletRequests.Records r){
        var q=r.selection().query();var s=q.scope();var selected=pay.readSelection(q,r.selection().issuerRef(),r.selection().accountRef());
        return records.walletRecords(new CatalogScope(s.tenantNo(),s.entityNo(),s.actorNo(),false),s.storeNo(),r.type(),r.from(),r.to(),selected.accountNo(),r.cursor(),r.size());
    }
    public Selected select(Query query,String issuerRef,String accountRef){return pay.select(query,issuerRef,accountRef);}
}
