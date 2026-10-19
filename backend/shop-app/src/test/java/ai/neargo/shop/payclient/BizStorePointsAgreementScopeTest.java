package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.agreement.AgreementService;
import ai.neargo.shop.portal.biz.pay.BizStorePointsAgreementController;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class BizStorePointsAgreementScopeTest {
  private final PointsAdministrationService admin = mock(PointsAdministrationService.class);
  private final AgreementService agreements = mock(AgreementService.class);
  private final MerchantQueryPort merchants = mock(MerchantQueryPort.class);
  private final BizStorePointsAgreementController controller =
      new BizStorePointsAgreementController(admin, agreements, merchants);

  @AfterEach
  void clear() {
    BizContext.clear();
    SecurityContextHolder.clearContext();
  }

  @Test
  void staff_cannot_sign_or_terminate_even_with_finance_access() {
    BizContext.set(new BizContext("M", Set.of(), Set.of(), Set.of("ST1"), "ST1", false));
    var request = new BizStorePointsAgreementController.SignRequest("T", "v1", "hash", "PER_ORDER", "TEST");
    assertThatThrownBy(() -> controller.sign(request)).hasMessageContaining("POINTS_SIGNER_NOT_OWNER");
    assertThatThrownBy(() -> controller.terminate(new BizStorePointsAgreementController.TypeRequest("T")))
        .hasMessageContaining("POINTS_SIGNER_NOT_OWNER");
    verifyNoInteractions(agreements, merchants);
  }

  @Test
  void owner_signs_only_for_authenticated_store_and_server_validated_route() {
    BizContext.set(new BizContext("M", Set.of(), Set.of(), Set.of("ST1", "ST2"), "ST1", true));
    var user = LoginUser.merchantByUser("OWNER", "Owner");
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
    var request = new BizStorePointsAgreementController.SignRequest("T", "v1", "hash", "PER_ORDER", "TEST");
    assertThatThrownBy(() -> controller.sign(request)).hasMessageContaining("POINTS_PAYMENT_ROUTE_REQUIRED");
    assertThatThrownBy(() -> controller.sign(new BizStorePointsAgreementController.SignRequest(
        "T", "v1", "hash", "PER_ORDER", null)))
        .hasMessageContaining("POINTS_AGREEMENT_REQUEST_INVALID");
    when(merchants.paymentRouteOf("M", "ST1", "TEST")).thenReturn(Optional.of(
        new MerchantQueryPort.PaymentRoute("M", "ST2", "PM", "TEST", "SUB", "V1", "DIRECT")));
    assertThatThrownBy(() -> controller.sign(request)).hasMessageContaining("POINTS_PAYMENT_ROUTE_INVALID");
    when(merchants.paymentRouteOf("M", "ST1", "TEST")).thenReturn(Optional.of(
        new MerchantQueryPort.PaymentRoute("M", "ST1", "PM", "TEST", "SUB", "V1", "DIRECT")));
    controller.sign(request);
    var command = ArgumentCaptor.forClass(AgreementService.SignCommand.class);
    verify(agreements).sign(command.capture(), eq("OWNER"));
    assertThat(command.getValue().storeNo()).isEqualTo("ST1");
    assertThat(command.getValue().fundsContext().accountVersion()).isEqualTo("V1");
    assertThat(command.getValue().fundsContext().storeNo()).isEqualTo("ST1");
    controller.terminate(new BizStorePointsAgreementController.TypeRequest("T"));
    verify(agreements).terminate("T", "ST1", "OWNER");
  }
}
