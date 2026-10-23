package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static ai.neargo.shop.merchant.brand.ManagementModels.*;
import static org.assertj.core.api.Assertions.*;

class IssuerAuthorityTest {
    @Test void storeManagementDoesNotImplicitlyAuthorizeSettlementAndOfflineHasNoFakeMerchant() throws Exception {
        var f=new BrandFixture();String m=f.no(f.space(),"managementNo");
        var service=new IssuerAuthorityService(f.repo,f.scopes,new PaymentMerchantScopeService(f.repo,f.scopes));
        var offline=new Route(null,"E1","S1","MERCHANT_OFFLINE","OFFLINE");
        var c=new Config("本店发行","E1","STORE_LOCAL","INTERNAL_RECON","PRO_RATA","CNY","Asia/Shanghai",List.of(offline),0);
        f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","STORE","S1","sv.issuer.configure",null,null,false,null,false));
        assertThatThrownBy(()->service.configureScope(f.owner,m,"STORE","S1",c)).isInstanceOf(BizException.class);
        f.scopes.grant(f.owner,m,new GrantCommand("MCH:MA1","ENTITY","E1","sv.issuer.settlement.authorize",null,null,false,null,false));
        var authority=service.configureScope(f.owner,m,"STORE","S1",c);
        assertThat(authority.authorizedSettlementEntities()).containsExactly("E1");
        assertThat(authority.authorizedRoutes().getFirst().paymentMerchantNo()).isNull();
        assertThatThrownBy(()->service.configureScope(f.other,m,"STORE","S1",c)).isInstanceOf(BizException.class);
        var wrong=new Config("本店发行","E2","STORE_LOCAL","INTERNAL_RECON","PRO_RATA","CNY","Asia/Shanghai",List.of(offline),0);
        assertThatThrownBy(()->service.configureScope(f.owner,m,"STORE","S1",wrong)).isInstanceOf(BizException.class);
    }
}
