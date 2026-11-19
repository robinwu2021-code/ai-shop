package ai.neargo.shop.portal.biz;

import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import ai.neargo.shop.payclient.PayBizOnboardingRelay;
import ai.neargo.shop.payclient.PayBizSourceDelegation;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * near-pay 商户渠道视图（TDD-支付渠道三端统一数据源）：与运营端、H5 收银台同一数据源。
 * 店主名下的支付商户由 ai-shop 授权并指定商户号转发（{@link PayBizSourceDelegation}）。
 */
@RestController
@Profile("api")
@ConditionalOnProperty(name = "shop.pay.biz-onboarding.enabled", havingValue = "true")
@RequestMapping("/biz/pay/v2")
public class BizPayMerchantChannelController {
  private final BizOnboardingForwarder forwarder;
  private final ObjectProvider<PayBizSourceDelegation> delegation;

  public BizPayMerchantChannelController(
      BizOnboardingForwarder forwarder, ObjectProvider<PayBizSourceDelegation> delegation) {
    this.forwarder = forwarder;
    this.delegation = delegation;
  }

  @GetMapping("/merchant-channels")
  public ResponseEntity<byte[]> merchantChannels(HttpServletRequest request) throws IOException {
    return PayBizOnboardingRelay.forward(forwarder, request, delegation.getIfAvailable());
  }
}
