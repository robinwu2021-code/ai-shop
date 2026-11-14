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

/**
 * Pay v2 collections (original user credentials and bytes only; authorization remains with near-pay).
 * Split out of {@link BizPayOnboardingController} so each controller owns one resource family.
 */
@RestController
@Validated
@Profile("api")
@ConditionalOnProperty(name = "shop.pay.biz-onboarding.enabled", havingValue = "true")
@RequestMapping("/biz/pay/v2")
public class BizPayCollectionController {
  private final BizOnboardingForwarder forwarder;

  public BizPayCollectionController(BizOnboardingForwarder forwarder) {
    this.forwarder = forwarder;
  }

  /** Actual PAY owner retains request identity, membership, store scope and token policy. */
  @PostMapping("/collections")
  public ResponseEntity<byte[]> collect(HttpServletRequest request) throws IOException {
    return PayBizOnboardingRelay.forward(forwarder, request);
  }

  @GetMapping("/collections")
  public ResponseEntity<byte[]> collection(HttpServletRequest request) throws IOException {
    return PayBizOnboardingRelay.forward(forwarder, request);
  }
}
