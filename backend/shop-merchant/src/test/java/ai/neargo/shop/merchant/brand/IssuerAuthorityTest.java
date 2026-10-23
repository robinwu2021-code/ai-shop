package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static ai.neargo.shop.merchant.brand.ManagementModels.*;
import static org.assertj.core.api.Assertions.*;

class IssuerAuthorityTest {
    @Test void preparationUsesLiveStoreOwnerWithoutInventingManagementOrSettlementGrant() throws Exception {
        var f=new BrandFixture();var service=new IssuerAuthorityService(f.repo,f.scopes,new PaymentMerchantScopeService(f.repo,f.scopes));
        f.jdbc.update("UPDATE mch_store SET version=7 WHERE store_no='S1'");
        var scope=service.storePreparationScope(f.owner,"S1");
        assertThat(scope.entityNo()).isEqualTo("E1");assertThat(scope.storeRevision()).isEqualTo(7);
        assertThat(scope.storeName()).isEqualTo("甲店");assertThat(scope.actorNo()).isEqualTo("MCH:MA1");
        assertThatThrownBy(()->service.storePreparationScope(f.other,"S1")).isInstanceOf(BizException.class);
        assertThatThrownBy(()->service.storePreparationScope(new Actor("OTHER","MCH:MA1"),"S1")).isInstanceOf(BizException.class);
        f.jdbc.update("UPDATE mch_entity SET status='INACTIVE' WHERE entity_no='E1'");
        assertThatThrownBy(()->service.storePreparationScope(f.owner,"S1")).isInstanceOf(BizException.class);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_management_space",Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_management_grant",Long.class)).isZero();
    }

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

    @Test void explicitOwnerPreparationGrantsSettingsButNeverSettlementOrAnotherOwnersStore() throws Exception {
        var f=new BrandFixture();String m=f.no(f.space(),"managementNo");
        var service=new IssuerAuthorityService(f.repo,f.scopes,new PaymentMerchantScopeService(f.repo,f.scopes));
        f.scopes.grant(f.owner,m,new GrantCommand(f.owner.actorKey(),"STORE","S1","store.read",null,null,false,null,false));
        f.scopes.grant(f.owner,m,new GrantCommand(f.owner.actorKey(),"ENTITY","E1","entity.read",null,null,false,null,false));
        assertThat(f.scopes.can(f.owner,m,"STORE","S1","sv.issuer.configure")).isFalse();
        assertThatThrownBy(()->service.prepareSettings(f.other,m,"S1")).isInstanceOf(BizException.class);
        service.prepareSettings(f.owner,m,"S1");service.prepareSettings(f.owner,m,"S1");
        assertThat(f.scopes.can(f.owner,m,"STORE","S1","sv.issuer.read")).isTrue();
        assertThat(f.scopes.can(f.owner,m,"STORE","S1","sv.issuer.configure")).isTrue();
        assertThat(f.scopes.can(f.owner,m,"ENTITY","E1","sv.issuer.settlement.authorize")).isFalse();
        var candidates=service.settlementCandidates(f.owner,m,"STORE","S1");
        assertThat(candidates).hasSize(1);assertThat(candidates.getFirst()).containsEntry("entityNo","E1").containsEntry("accepted",false).containsEntry("canAccept",true);
        f.scopes.grant(f.owner,m,new GrantCommand(f.owner.actorKey(),"ENTITY","E1","sv.issuer.settlement.authorize",null,null,false,null,false));
        assertThat(service.settlementCandidates(f.owner,m,"STORE","S1").getFirst()).containsEntry("accepted",true).containsEntry("canAccept",false);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_management_grant WHERE resource_type='STORE' AND permission_code LIKE 'sv.issuer.%'",Long.class)).isEqualTo(2);
    }
}
