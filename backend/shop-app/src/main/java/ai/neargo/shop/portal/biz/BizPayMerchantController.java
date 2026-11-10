package ai.neargo.shop.portal.biz;

import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import ai.neargo.shop.payclient.PayBizOnboardingRelay;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Account creation and merchant profile forwarding; independent PAY owns authorization. */
@RestController
@Validated
@Profile("api")
@ConditionalOnProperty(name = "shop.pay.biz-onboarding.enabled", havingValue = "true")
@RequestMapping("/biz/pay/v2")
public class BizPayMerchantController {
  private final BizOnboardingForwarder forwarder;
  public BizPayMerchantController(BizOnboardingForwarder forwarder) { this.forwarder = forwarder; }
  @GetMapping({"/merchant-profile-revisions", "/merchant-profile-revisions/by-request/{requestNo}"})
  public ResponseEntity<byte[]> merchantProfiles(HttpServletRequest request) throws IOException {
    return PayBizOnboardingRelay.forward(forwarder, request);
  }

  @PostMapping("/merchant-profile-revisions")
  public ResponseEntity<byte[]> submitMerchantProfile(HttpServletRequest request) throws IOException {
    return PayBizOnboardingRelay.forward(forwarder, request);
  }

  @PostMapping("/merchant-creations")
  public ResponseEntity<byte[]> createMerchant(HttpServletRequest request) throws IOException {
    return PayBizOnboardingRelay.forward(forwarder, request);
  }

  @GetMapping({"/merchant-creations/context", "/merchant-creations/by-request/{requestNo}"})
  public ResponseEntity<byte[]> merchantCreation(HttpServletRequest request) throws IOException {
    return PayBizOnboardingRelay.forward(forwarder, request);
  }

}
