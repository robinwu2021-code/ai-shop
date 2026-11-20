package ai.neargo.shop.payclient;

import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.svc.client.ServiceCallException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * 商户权限由 ai-shop 决定，near-pay 只执行：店主名下主体对应的支付商户，转发时带 ai-shop 的来源调用方凭据并由 ai-shop 指定商户号，
 * near-pay 据此按商户管理员处理，不再查它自己的成员表。店主之外（店员）暂不委托，仍按原样转发。
 */
public final class PayBizSourceDelegation {
  /** near-pay 商户管理员动作（与 near-pay {@code MerchantRole.MERCHANT_ADMIN} 一致，供 App 判断能查 / 能申请）。 */
  static final List<String> ADMIN_ACTIONS =
      List.of("payment.create", "payment.query", "payment.close", "refund.apply", "refund.review",
          "device.activate", "qrcode.manage", "store.ledger.read", "statement.read", "onboarding.apply",
          "member.manage", "store.bind", "app.grant.query", "app.grant");
  private static final Set<String> MAPPED = Set.of("PENDING", "NEED_MORE", "REJECTED", "APPROVED");

  /** 本人作为店主的主体 → 支付商户号 */
  @FunctionalInterface
  public interface OwnedMerchants {
    List<String> of(String userNo);
  }

  private final OwnedMerchants owned;
  private final String caller;
  private final String credential;
  private final ObjectMapper json = new ObjectMapper();

  public PayBizSourceDelegation(OwnedMerchants owned, String caller, String credential) {
    this.owned = owned;
    this.caller = caller;
    this.credential = credential;
  }

  public static PayBizSourceDelegation of(
      ObjectProvider<MerchantEntityService> entities,
      ObjectProvider<PaySourceLinkApi> links,
      ObjectProvider<StoreSyncProperties> sync) {
    StoreSyncProperties props = sync.getIfAvailable();
    if (props == null) return new PayBizSourceDelegation(user -> List.of(), null, null);
    return new PayBizSourceDelegation(
        user -> {
          var service = entities.getIfAvailable();
          var api = links.getIfAvailable();
          if (service == null || api == null) throw new PayBizException(HttpStatus.SERVICE_UNAVAILABLE);
          List<String> out = new ArrayList<>();
          for (var e : service.myEntities(user)) {
            PaySourceLinkApi.Status s;
            try {
              s = api.status(props.sourceScope(), e.entityNo());
            } catch (ServiceCallException unavailable) {
              throw new PayBizException(HttpStatus.SERVICE_UNAVAILABLE);
            }
            if (s != null && MAPPED.contains(s.status()) && s.merchantRef() != null && !s.merchantRef().isBlank()
                && !out.contains(s.merchantRef())) out.add(s.merchantRef());
          }
          return out;
        },
        props.caller(),
        props.credential());
  }

  public boolean enabled() {
    return caller != null && !caller.isBlank() && credential != null && !credential.isBlank();
  }

  /** 店主名下的支付商户；没有（或未配置来源凭据）返回空，调用方按原样转发。 */
  public List<String> merchantsOf(String userNo) {
    return enabled() ? owned.of(userNo) : List.of();
  }

  /** App 指定的商户号须在本人名下；未指定且只有一个时用它；否则 null。 */
  public static String select(List<String> mine, String requested) {
    if (requested == null || requested.isBlank()) return mine.size() == 1 ? mine.getFirst() : null;
    return mine.contains(requested.trim()) ? requested.trim() : null;
  }

  /** 去掉 App 带来的商户号与任何内部调用头，换成 ai-shop 指定的商户号。 */
  public static Map<String, String> headers(Map<String, String> original, String merchant) {
    var out = new LinkedHashMap<String, String>();
    original.forEach(
        (name, value) -> {
          String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
          if (!n.equals("x-merchant-no") && !n.startsWith("x-internal-")) out.put(name, value);
        });
    out.put("X-Merchant-No", merchant);
    return out;
  }

  /** ai-shop 的来源调用方凭据：只在 ai-shop 已确定商户之后交给转发器。 */
  public Map<String, String> source() {
    return Map.of("X-Internal-Caller", caller, "X-Internal-Credential", credential);
  }

  /** 去掉 App 伪造的内部调用头（不委托时也要去掉）。 */
  public static Map<String, String> withoutInternal(Map<String, String> original) {
    var out = new LinkedHashMap<String, String>();
    original.forEach(
        (name, value) -> {
          if (name == null || !name.toLowerCase(Locale.ROOT).startsWith("x-internal-")) out.put(name, value);
        });
    return out;
  }

  /** 商户切换器：本人店主身份下的支付商户，形状同 near-pay {@code GET /biz/pay/v2/memberships}。 */
  public byte[] memberships(String userNo, List<String> merchants) {
    List<Map<String, Object>> data = new ArrayList<>();
    for (String m : merchants) {
      var row = new LinkedHashMap<String, Object>();
      row.put("merchantNo", m);
      row.put("membershipNo", "SRC-" + userNo);
      row.put("role", "MERCHANT_ADMIN");
      row.put("storeScope", "ALL");
      row.put("storeNos", List.of());
      row.put("actions", ADMIN_ACTIONS);
      data.add(row);
    }
    var body = new LinkedHashMap<String, Object>();
    body.put("code", 0);
    body.put("message", "OK");
    body.put("data", data);
    body.put("requestId", null);
    return json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
  }
}
