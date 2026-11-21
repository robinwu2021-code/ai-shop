package ai.neargo.shop.portal.biz.pay;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.agreement.AgreementService;
import ai.neargo.shop.pay.points.model.AgreementSnapshot;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;
import ai.neargo.shop.spi.pay.FundsPath;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Store acceptance: identity and the payment route are resolved on the server. */
@Profile("api")
@RestController
public class BizStorePointsAgreementController {
  private final PointsAdministrationService administration;
  private final AgreementService agreements;
  private final MerchantQueryPort merchants;

  public BizStorePointsAgreementController(
      PointsAdministrationService administration,
      AgreementService agreements,
      MerchantQueryPort merchants) {
    this.administration = administration;
    this.agreements = agreements;
    this.merchants = merchants;
  }

  @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
  @GetMapping("/biz/points/store-terms")
  public PointsAdministrationService.Terms terms(@RequestParam String typeCode) {
    BizContext.requireStoreNo();
    return administration.terms(typeCode);
  }

  @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
  @GetMapping("/biz/points/store-agreement")
  public AgreementSnapshot active(@RequestParam String typeCode) {
    return agreements.active(typeCode, BizContext.requireStoreNo());
  }

  @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
  @PostMapping("/biz/points/store-agreement/sign")
  public AgreementSnapshot sign(@RequestBody SignRequest request) {
    var context = BizContext.current();
    if (!context.owner()) throw new IllegalArgumentException("POINTS_SIGNER_NOT_OWNER");
    if (request == null || blank(request.typeCode()) || blank(request.termsVersion())
        || blank(request.termsHash()) || blank(request.settleMode()) || blank(request.channel())) {
      throw new IllegalArgumentException("POINTS_AGREEMENT_REQUEST_INVALID");
    }
    var merchantNo = BizContext.requireMerchantNo();
    var storeNo = BizContext.requireStoreNo();
    var route = merchants.paymentRouteOf(merchantNo, storeNo, request.channel())
        .orElseThrow(() -> new IllegalArgumentException("POINTS_PAYMENT_ROUTE_REQUIRED"));
    if (!merchantNo.equals(route.merchantNo()) || !storeNo.equals(route.storeNo())
        || !request.channel().equals(route.channel()) || blank(route.accountVersion())) {
      throw new IllegalArgumentException("POINTS_PAYMENT_ROUTE_INVALID");
    }
    FundsPath path;
    try {
      path = FundsPath.valueOf(route.fundsPath());
    } catch (RuntimeException badRoute) {
      throw new IllegalArgumentException("POINTS_PAYMENT_ROUTE_INVALID", badRoute);
    }
    var funds = new FundsContext(path, route.channel(), merchantNo, storeNo,
        route.accountVersion(), route.subMerchantId(), null, null, 0);
    return agreements.sign(new AgreementService.SignCommand(request.typeCode(), merchantNo,
        storeNo, request.termsVersion(), request.termsHash(), request.settleMode(), funds),
        SecurityUtils.currentUserNo());
  }

  @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
  @PostMapping("/biz/points/store-agreement/terminate")
  public void terminate(@RequestBody TypeRequest request) {
    if (!BizContext.current().owner()) throw new IllegalArgumentException("POINTS_SIGNER_NOT_OWNER");
    if (request == null || blank(request.typeCode())) {
      throw new IllegalArgumentException("POINTS_AGREEMENT_REQUEST_INVALID");
    }
    agreements.terminate(request.typeCode(), BizContext.requireStoreNo(),
        SecurityUtils.currentUserNo());
  }

  public record SignRequest(String typeCode, String termsVersion, String termsHash,
      String settleMode, String channel) {}
  public record TypeRequest(String typeCode) {}

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
