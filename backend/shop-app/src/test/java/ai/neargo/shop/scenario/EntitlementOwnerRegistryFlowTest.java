package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.service.EntitlementOwnerRegistryService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerSystem;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** A source collision must never become a fallback to a different writer or a second issuance. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:ent-owner-registry;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class EntitlementOwnerRegistryFlowTest {
    @Autowired EntitlementOwnerRegistryService registry;
    @Autowired JdbcTemplate jdbc;
    @Test void sameLegacyNumberRemainsDistinctBySystemAndKindAndMissingOwnerNeverFallsBack() {
        String no=UUID.randomUUID().toString(),batch=UUID.randomUUID().toString();
        var operator=new CatalogScope("MAIN","MIGRATION","OP1",true);
        var ctx=new EntitlementContext("MAIN","OP1",batch,"a".repeat(64));
        var mkt=new SourceIdentity(SourceSystem.MKT_LEGACY,SourceKind.INSTANCE,no);
        var pmt=new SourceIdentity(SourceSystem.PMT,SourceKind.INSTANCE,no);
        var first=registry.registerLegacy(operator,mkt,batch);
        var second=registry.registerLegacy(operator,pmt,batch);
        assertThat(first.ownerSystem()).isEqualTo(OwnerSystem.CORE_LEGACY);
        assertThat(first.source()).isNotEqualTo(second.source());
        assertThat(first.targetNo()).isNull();
        assertThat(registry.registerLegacy(operator,mkt,batch)).isEqualTo(first);
        assertThat(registry.resolve(ctx,mkt)).isEqualTo(first);
        assertThat(registry.resolve(ctx,pmt)).isEqualTo(second);
        var template=new SourceIdentity(SourceSystem.MKT_LEGACY,SourceKind.TEMPLATE,no);
        assertThat(registry.registerLegacy(operator,template,batch).source().sourceKind()).isEqualTo(SourceKind.TEMPLATE);
        assertThatThrownBy(() -> registry.registerLegacy(operator,mkt,"OTHER_BATCH")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> registry.resolve(ctx,new SourceIdentity(SourceSystem.PAY,SourceKind.INSTANCE,no)))
                .isInstanceOfSatisfying(BizException.class,e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> registry.registerLegacy(new CatalogScope("MAIN","M1","U1",false),mkt,batch))
                .isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance",Integer.class)).isZero();
    }
    @Test void brokenPayTargetAndCaseChangedSourceCannotRouteToAnotherLegacyOwner() {
        var operator=new CatalogScope("MAIN","MIGRATION","OP1",true);
        String no="ExactCase-"+UUID.randomUUID();
        var source=new SourceIdentity(SourceSystem.MKT_LEGACY,SourceKind.INSTANCE,no);
        var ctx=new EntitlementContext("MAIN","OP1","BATCH","a".repeat(64));
        registry.registerLegacy(operator,source,"BATCH");
        assertThatThrownBy(() -> registry.resolve(ctx,new SourceIdentity(SourceSystem.MKT_LEGACY,SourceKind.INSTANCE,
                no.toUpperCase(java.util.Locale.ROOT)))).isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        jdbc.update("UPDATE ent_owner_registry SET owner_system='PAY',status='CUTOVER',target_no='MISSING',"
                + "cutover_watermark='WM' WHERE source_no=?",no);
        assertThatThrownBy(() -> registry.resolve(ctx,source)).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
}
