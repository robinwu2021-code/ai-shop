package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.admin.PointsInspectionService;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.portal.biz.pay.BizStorePointsController;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BizStorePointsScopeTest {
  @AfterEach
  void clear() {
    BizContext.clear();
  }

  @Test
  void store_account_and_entries_follow_authenticated_current_store() {
    var admin = mock(PointsAdministrationService.class);
    when(admin.types())
        .thenReturn(
            java.util.List.of(
                new PointType(
                    "DRAFT",
                    "CN",
                    "CNY",
                    2,
                    new RedeemRate(1, 1),
                    1000,
                    365,
                    PointType.Status.DRAFT),
                new PointType(
                    "RETIRED",
                    "CN",
                    "CNY",
                    2,
                    new RedeemRate(1, 1),
                    1000,
                    365,
                    PointType.Status.RETIRED)));
    var inspection = mock(PointsInspectionService.class);
    var controller = new BizStorePointsController(admin, inspection);
    BizContext.set(new BizContext("M", Set.of(), Set.of(), Set.of("ST1", "ST2"), "ST1", true));
    org.assertj.core.api.Assertions.assertThat(controller.types())
        .extracting(PointType::typeCode)
        .containsExactly("RETIRED");
    controller.account("TYPE");
    controller.entries("TYPE", 0, 20);
    BizContext.set(BizContext.current().withStore("ST2"));
    controller.account("TYPE");
    verify(admin).types();
    verify(inspection).account("TYPE", HolderType.STORE, "ST1");
    verify(inspection).entries("TYPE", HolderType.STORE, "ST1", null, 0, 20);
    verify(inspection).account("TYPE", HolderType.STORE, "ST2");
    verifyNoMoreInteractions(inspection);
  }

  @Test
  void missing_current_store_cannot_fall_back_to_merchant_account() {
    var controller =
        new BizStorePointsController(
            mock(PointsAdministrationService.class), mock(PointsInspectionService.class));
    BizContext.set(new BizContext("M", Set.of(), Set.of()));
    assertThatThrownBy(() -> controller.account("TYPE")).hasMessageContaining("FORBIDDEN");
  }
}
