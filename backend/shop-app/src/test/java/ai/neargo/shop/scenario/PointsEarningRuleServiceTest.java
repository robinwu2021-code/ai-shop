package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.spi.points.PointsEarnPort.*;
import ai.neargo.shop.spi.points.PointsEarningRulePort;
import ai.neargo.shop.spi.product.PointsEarningCatalogPort;
import ai.neargo.shop.trade.order.points.earning.*;
import ai.neargo.shop.points.earning.PointsEarningRuleService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PointsEarningRuleServiceTest {
    PointsEarningRuleService service;
    PointsEarningRules rules;
    PointsAdministrationService admin;
    PointsEarningCatalogPort catalog;
    PointType p=type("P",PointType.Status.ACTIVE), q=type("Q",PointType.Status.ACTIVE);
    static PointType type(String code,PointType.Status status) {return new PointType(code,code+"积分","CN","CNY",2,new RedeemRate(2,1),0,0,status);}
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:earning_service_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V540__online_points_earning_rules.sql")).execute(source);
        catalog=new PointsEarningCatalogPort() {
            public List<Category> categories(){return List.of(new Category("ROOT",null,"食品","食品"),new Category("LEAF","ROOT","水果","食品 / 水果"));}
            public String goodsCategory(String goods){return "LEAF";}
            public List<String> ancestry(String c){return c==null?List.of():c.equals("LEAF")?List.of("LEAF","ROOT"):List.of(c);}
        };
        rules=new PointsEarningRules(source,catalog);admin=mock(PointsAdministrationService.class);
        when(admin.types()).thenReturn(List.of(p,q));when(admin.orderDefault("CN")).thenReturn(p);
        when(admin.fallback("P")).thenReturn(new PointsAdministrationService.FallbackRule(7,RuleMode.RATIO,100));
        service=new PointsEarningRuleService(rules,admin,catalog);
    }
    @Test void otherTypeStartsAtZeroAndNeverInheritsOrderDefault() {
        assertThat(service.calculate(new PointsEarningRulePort.Query("P","CNY",null,10000)).points()).isEqualTo(200);
        assertThat(service.calculate(new PointsEarningRulePort.Query("Q","CNY",null,10000)).points()).isZero();
        verify(admin,never()).fallback("Q");
    }
    @Test void draftPublicationAndCategoryMatchingAreIsolatedByType() {
        service.published("Q");
        rules.change(new PointsEarningRules.Change("A","Q","ROOT",0,"SAVE",500L,"test"),"ops");
        assertThat(service.calculate(new PointsEarningRulePort.Query("Q","CNY","LEAF",10000)).points()).isZero();
        rules.change(new PointsEarningRules.Change("B","Q","ROOT",1,"PUBLISH",null,"test"),"ops");
        var result=service.calculate(new PointsEarningRulePort.Query("Q","CNY","LEAF",10000));
        assertThat(result.points()).isEqualTo(1000);assertThat(result.matchedScope()).isEqualTo("ROOT");
        assertThat(result.revision()).isEqualTo(2);assertThat(result.type().ratePoints()).isEqualTo(2);
        assertThat(service.calculate(new PointsEarningRulePort.Query("P","CNY","LEAF",10000)).points()).isEqualTo(200);
    }
    @Test void validatesCurrencyCategoryAmountAndExplicitType() {
        for(var query:List.of(new PointsEarningRulePort.Query("Q","USD",null,100),new PointsEarningRulePort.Query("Q","CNY","MISSING",100),new PointsEarningRulePort.Query("Q","CNY",null,-1),new PointsEarningRulePort.Query(null,"CNY",null,100)))
            assertThatThrownBy(()->service.calculate(query)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void inactiveTypeReadableButCannotCreatePromises() {
        service.published("Q");
        when(admin.types()).thenReturn(List.of(p,type("Q",PointType.Status.DRAINING)));
        assertThat(service.type("Q",false).status()).isEqualTo(PointType.Status.DRAINING);
        assertThat(service.published("Q")).hasSize(1);
        assertThatThrownBy(()->service.calculate(new PointsEarningRulePort.Query("Q","CNY",null,100))).hasMessage("POINTS_EARN_TYPE_NOT_ACTIVE");
        assertThat(service.availableTypes()).extracting(PointsEarningRulePort.Type::typeCode).containsExactly("P");
    }
    @Test void activeTypesWorkWithoutAnOrderDefault() {
        when(admin.orderDefault("CN")).thenReturn(null);
        assertThat(service.calculate(new PointsEarningRulePort.Query("Q","CNY",null,100)).points()).isZero();
    }
    @Test void unifiedRuleListContainsOnlyPublishedRevisionsAndSnapshotsRemainStable() {
        service.published("Q");
        rules.change(new PointsEarningRules.Change("DEFAULT_SAVE","Q","DEFAULT",1,"SAVE",1000L,"test"),"ops");
        assertThat(service.published("Q").getFirst().value()).isZero();
        rules.change(new PointsEarningRules.Change("DEFAULT_PUBLISH","Q","DEFAULT",2,"PUBLISH",null,"test"),"ops");
        var snapshot=service.calculate(new PointsEarningRulePort.Query("Q","CNY",null,10000));
        rules.change(new PointsEarningRules.Change("DEFAULT_LATER","Q","DEFAULT",3,"SAVE",0L,"test"),"ops");
        assertThat(snapshot.points()).isEqualTo(2000);assertThat(snapshot.revision()).isEqualTo(3);
        assertThat(service.published("Q").getFirst().revision()).isEqualTo(3);
        assertThat(rules.page(null,1,50).rows()).extracting(PointsEarningRules.Rule::typeCode).contains("Q");
    }
    @Test void zeroCategoryStopsInheritanceAndDisableRestoresTheParent() {
        service.published("P");
        rules.change(new PointsEarningRules.Change("ZERO","P","LEAF",0,"SAVE",0L,"test"),"ops");
        rules.change(new PointsEarningRules.Change("ZERO_PUB","P","LEAF",1,"PUBLISH",null,"test"),"ops");
        assertThat(service.calculate(new PointsEarningRulePort.Query("P","CNY","LEAF",10000)).points()).isZero();
        rules.change(new PointsEarningRules.Change("ZERO_OFF","P","LEAF",2,"DISABLE",null,"test"),"ops");
        assertThat(service.calculate(new PointsEarningRulePort.Query("P","CNY","LEAF",10000)).points()).isEqualTo(200);
    }
    @Test void overflowIsADeclaredRejectionRatherThanAnInternalError() {
        when(admin.types()).thenReturn(List.of(new PointType("Q","奖励","CN","CNY",2,new RedeemRate(Long.MAX_VALUE,1),0,0,PointType.Status.ACTIVE)));
        service.published("Q");
        rules.change(new PointsEarningRules.Change("BIG","Q","DEFAULT",1,"SAVE",10000L,"test"),"ops");
        rules.change(new PointsEarningRules.Change("BIG_PUB","Q","DEFAULT",2,"PUBLISH",null,"test"),"ops");
        assertThatThrownBy(()->service.calculate(new PointsEarningRulePort.Query("Q","CNY",null,Long.MAX_VALUE))).hasMessage("POINTS_EARN_AMOUNT_OVERFLOW");
    }

    ai.neargo.shop.portal.ops.pay.OpsPointsEarningRulesController controller() {
        var user=ai.neargo.shop.auth.LoginUser.operator("OPS","运营",List.of(),List.of("finance:settle:read","finance:settle:execute"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user,null,List.of()));
        return new ai.neargo.shop.portal.ops.pay.OpsPointsEarningRulesController(rules,admin,catalog,service,null,null);
    }
    @AfterEach void clearSecurity(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @Test void operationsApiListsFiltersAndWritesTheChosenType() {
        var api=controller();
        assertThat(api.list(1,50,null,true).rows()).extracting(PointsEarningRules.Rule::typeCode).containsExactly("P","Q");
        assertThat(api.list(1,50,"Q",false).rows()).allMatch(r->r.typeCode().equals("Q"));
        var saved=api.change(new PointsEarningRules.Change("OPS_Q","Q","ROOT",0,"SAVE",300L,"test"));
        assertThat(saved.actor()).isEqualTo("OPS");
        assertThat(api.history("ROOT","Q")).hasSize(1);assertThat(api.history("ROOT","P")).isEmpty();
    }
    @Test void oldPreviewPayloadStillUsesDefaultWhileExplicitTypeWorksWithoutOne() {
        var api=controller();var mapper=new tools.jackson.databind.ObjectMapper();
        var old=mapper.readValue("{\"categoryNo\":null,\"cashMinor\":10000}",ai.neargo.shop.portal.ops.pay.OpsPointsEarningRulesController.Preview.class);
        assertThat(api.preview(old).type().typeCode()).isEqualTo("P");assertThat(api.preview(old).points()).isEqualTo(200);
        when(admin.orderDefault("CN")).thenReturn(null);
        assertThat(api.context().types()).hasSize(2);
        assertThat(api.preview(new ai.neargo.shop.portal.ops.pay.OpsPointsEarningRulesController.Preview(null,10000,"Q","CNY")).points()).isZero();
    }

}
