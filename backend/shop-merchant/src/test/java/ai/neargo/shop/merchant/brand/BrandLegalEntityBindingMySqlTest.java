package ai.neargo.shop.merchant.brand;

import static ai.neargo.shop.merchant.brand.ManagementModels.*;
import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.brand.impl.BrandServiceImpl;
import ai.neargo.shop.merchant.port.StoreOwnershipWritePortImpl;
import ai.neargo.shop.spi.storedvalue.BrandChangeImpactPort;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Immutable owner binding and original response use the real shared ownership fence and native row locks. */
@EnabledIfSystemProperty(named="p1.mysql.url",matches="jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class BrandLegalEntityBindingMySqlTest {
    record Setup(BrandFixture f, BrandServiceImpl service, String management, String brand) { }
    private Setup setup() throws Exception {
        var f = new BrandFixture();
        f.jdbc.execute("ALTER TABLE mch_entity ADD COLUMN ownership_change_no VARCHAR(64) NULL");
        for (String part : Files.readString(Path.of("../shop-app/src/main/resources/db/migration/V651__brand_legal_entity.sql"))
                .replaceAll("(?m)^--.*$", "").split(";")) if (!part.isBlank()) f.jdbc.execute(part);
        String m = f.no(f.space(), "managementNo");
        var service = new BrandServiceImpl(f.repo, f.scopes,
                new ManagementTokenCodec("brand-legal-test-secret-at-least-32", f.repo.json),
                (a,e,t) -> new BrandChangeImpactPort.Impact(true,false,0,0,0,0), f.numbers());
        service.setEntityWrites(new StoreOwnershipWritePortImpl(f.jdbc));
        String brand = f.no(service.create(f.owner, new BrandCreate(m, "Chain", null, "ACTIVE"), "create"), "brandNo");
        return new Setup(f, service, m, brand);
    }
    @Test void onlyRealOwnedEntityIsOfferedAndBindingCannotBeReplaced() throws Exception {
        var s = setup(); var f = s.f();
        assertThat(s.service().legalEntityCandidates(f.owner, s.brand())).singleElement()
                .satisfies(v -> assertThat(v).containsEntry("entityNo", "E1"));
        assertThatThrownBy(() -> s.service().bindLegalEntity(f.owner, s.brand(), new BrandLegalEntity("E2",0), "foreign"))
                .isInstanceOf(BizException.class);
        var original = s.service().bindLegalEntity(f.owner, s.brand(), new BrandLegalEntity("E1",0), "bind-original");
        assertThat(original).containsEntry("legalEntityNo", "E1");
        s.service().edit(f.owner, s.brand(), new BrandEdit("Renamed",null,1), "rename");
        assertThat(s.service().bindLegalEntity(f.owner, s.brand(), new BrandLegalEntity("E1",0), "bind-original"))
                .isEqualTo(f.repo.decode(f.repo.encode(original)));
        assertThat(s.service().legalEntityResult(f.owner,s.brand(),"bind-original"))
                .isEqualTo(f.repo.decode(f.repo.encode(original)));
        assertThatThrownBy(() -> s.service().bindLegalEntity(f.owner,s.brand(),new BrandLegalEntity("E2",2),"replace"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> s.service().legalEntityResult(f.other,s.brand(),"bind-original"))
                .isInstanceOf(BizException.class);
    }
    @Test void validExplicitSponsorGrantDoesNotMakeAnUnrelatedEntityImplicitlyOwned() throws Exception {
        var s = setup(); var f = s.f();
        f.scopes.grant(f.owner,s.management(),new GrantCommand(f.other.actorKey(),"SPACE",s.management(),
                "management.enter",null,null,false,null,false));
        f.scopes.grant(f.owner,s.management(),new GrantCommand(f.other.actorKey(),"BRAND",s.brand(),
                "brand.manage",null,null,false,null,false));
        f.scopes.grant(f.owner,s.management(),new GrantCommand(f.other.actorKey(),"ENTITY","E1",
                "promotion.sponsor.authorize",null,null,false,null,false));
        assertThat(s.service().legalEntityCandidates(f.other,s.brand())).hasSize(2);
        var result=s.service().bindLegalEntity(f.other,s.brand(),new BrandLegalEntity("E1",0),"delegated");
        assertThat(result).containsEntry("legalEntityNo","E1");
    }
    @Test void frozenEntityIsNotAValidBindingEvenWhenTheActorOwnsIt() throws Exception {
        var s = setup(); var f = s.f();
        f.jdbc.update("UPDATE mch_entity SET ownership_change_no='CLAIM' WHERE entity_no='E1'");
        assertThat(s.service().legalEntityCandidates(f.owner,s.brand())).isEmpty();
        assertThatThrownBy(() -> s.service().bindLegalEntity(f.owner,s.brand(),new BrandLegalEntity("E1",0),"frozen"))
                .isInstanceOf(BizException.class);
        assertThat(s.service().detail(f.owner,s.brand()).get("legalEntityNo")).isNull();
    }
}
