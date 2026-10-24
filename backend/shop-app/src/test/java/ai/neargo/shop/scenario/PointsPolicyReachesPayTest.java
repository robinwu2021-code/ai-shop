package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.SwitchChange;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Flow;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Scope;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.store.PointsReadStore.Availability;
import ai.neargo.shop.pay.points.type.PointTypeService;
import ai.neargo.shop.payclient.PointsConfigurationAppService;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Operator writes must reach the actual pay availability reader, across the application boundary. */
@SpringJUnitConfig(PointsOpsConfigurationTest.Config.class)
@TestPropertySource(properties = {"shop.points.enabled=true",
    "spring.datasource.url=jdbc:h2:mem:points_policy_reaches;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointsPolicyReachesPayTest {
  @Autowired DataSource data;
  @Autowired PointsConfigurationAppService app;
  @Autowired PointsAdministrationService admin;
  @Autowired PointTypeService types;
  @Autowired PointsReadStore read;

  @BeforeAll void schema() throws java.sql.SQLException {
    PointsPostingDatabaseTest.initializeSchema(data);
    // The configuration mapper uses the current named point-type contract.
    try (var connection = data.getConnection();
         var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "pts_point_type", "name")) {
      if (!columns.next()) {
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource("db/migration/V537__points_type_name_unique.sql"))
            .execute(data);
      }
    }
  }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @Test void opsChangeReachesPayDomain() {
    String type = "PR" + UUID.randomUUID().toString().replace("-", "").substring(0,20);
    types.create(new PointTypeService.CreateType(type,"CN",new RedeemRate(2,1),3000,365),"OPS");
    var user = LoginUser.operator("OPS_REAL","Synthetic",List.of(),List.of());
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user,null,List.of()));
    assertThat(read.enabled(context(type,"MP_WECHAT",Flow.EARN))).isTrue();
    var change = new SwitchChange("PAUSE:"+type,Scope.CLIENT,type,"MP_WECHAT",false,Flow.EARN,"PAUSE");
    app.changeSwitch(change,"OPS_REAL");
    assertThat(read.enabled(context(type,"MP_WECHAT",Flow.EARN))).isFalse();
    assertThat(read.enabled(context(type,"MP_WECHAT",Flow.REDEEM))).isTrue();
    assertThat(read.enabled(context(type,"H5",Flow.EARN))).isTrue();
    assertThat(app.switches(type,0,20).rows()).isEqualTo(admin.switches(type,0,20).rows());
    app.changeSwitch(new SwitchChange("RESUME:"+type,Scope.CLIENT,type,"MP_WECHAT",true,Flow.EARN,"RESUME"),"OPS_REAL");
    assertThat(read.enabled(context(type,"MP_WECHAT",Flow.EARN))).isTrue();
  }

  private Availability context(String type,String client,Flow flow) {
    return new Availability(type,"USER","STORE","MERCHANT","CN","ONLINE",client,flow);
  }
}
