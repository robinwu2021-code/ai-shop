package ai.neargo.shop.pay.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/**
 * ai-shop adapter: bounded, allowlisted relay of the original BIZ token and payload. No identity
 * headers, service secrets, token cache, retries, redirect or business storage.
 */
public final class BizOnboardingForwarder {
  private final String origin;
  private final HttpClient http;

  public BizOnboardingForwarder(URI origin) {
    String host = origin.getHost();
    boolean local = host != null && Set.of("127.0.0.1", "localhost", "[::1]").contains(host);
    if (host == null
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
        || !(origin.getScheme().equals("https") || local && origin.getScheme().equals("http")))
      throw new IllegalArgumentException("near-pay origin required");
    this.origin = origin.toString().replaceAll("/$", "");
    http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public record Response(int status, Map<String, List<String>> headers, byte[] body) {
    @Override
    public String toString() {
      return "BizOnboardingResponse[status=" + status + "]";
    }
  }

  public Response forward(String method, String target, Map<String, String> headers, byte[] body)
      throws IOException, InterruptedException {
    if (!allowed(method, target))
      throw new IllegalArgumentException("unsupported BIZ onboarding route");
    URI uri = URI.create(target);
    String path = uri.getPath();
    boolean upload = method.equals("POST") && path.endsWith("/files");
    if (body == null || body.length > (upload ? 6 * 1024 * 1024 : 1024 * 1024))
      throw new IllegalArgumentException("BIZ request exceeds limit");
    var b =
        HttpRequest.newBuilder(URI.create(origin + target))
            .timeout(Duration.ofSeconds(20))
            .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
    Set<String> input =
        Set.of(
            "authorization",
            "x-merchant-no",
            "x-store-no",
            "x-request-id",
            "x-trace-id",
            "accept",
            "accept-language",
            "content-type");
    headers.forEach(
        (name, value) -> {
          if (name != null && value != null && input.contains(name.toLowerCase(Locale.ROOT)))
            b.header(name, value);
        });
    var r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    Map<String, List<String>> out = new LinkedHashMap<>();
    Set<String> output =
        Set.of(
            "content-type",
            "content-disposition",
            "cache-control",
            "pragma",
            "x-content-type-options",
            "content-security-policy",
            "x-frame-options",
            "x-request-id");
    r.headers()
        .map()
        .forEach(
            (k, v) -> {
              if (output.contains(k)) out.put(k, List.copyOf(v));
            });
    return new Response(r.statusCode(), Map.copyOf(out), r.body());
  }

  public static boolean allowed(String method, String target) {
    if (method == null
        || target == null
        || target.contains("%")
        || target.contains("\\")
        || target.contains("..")
        || target.contains("#")) return false;
    URI uri;
    try {
      uri = URI.create(target);
    } catch (IllegalArgumentException bad) {
      return false;
    }
    if (uri.isAbsolute() || uri.getAuthority() != null) return false;
    String p = uri.getPath(), base = "/biz/pay/v2";
    if (method.equals("GET")
        && Set.of(base + "/memberships", base + "/capability-progress", base + "/onboardings")
            .contains(p)) return true;
    if (method.equals("POST") && p.equals(base + "/merchant-profile-revisions")) return true;
    if (method.equals("GET") && (p.equals(base + "/merchant-profile-revisions") || p.matches(base + "/merchant-profile-revisions/by-request/[A-Za-z0-9_-]{1,64}"))) return true;
    if (method.equals("POST") && p.equals(base + "/merchant-creations")) return true;
    if (method.equals("GET") && (p.equals(base + "/merchant-creations/context") || p.matches(base + "/merchant-creations/by-request/[A-Za-z0-9_-]{1,64}"))) return true;
    if (method.equals("POST") && p.equals(base + "/onboardings")) return true;
    String root = java.util.regex.Pattern.quote(base) + "/onboardings/[A-Za-z0-9_-]{1,64}";
    if (method.equals("GET"))
      return p.matches(
          root
              + "(?:/draft|/readiness|/draft/commands/[A-Za-z0-9_-]{1,64}|/files(?:/[a-f0-9]{32})?)?");
    if (method.equals("PUT")) return p.matches(root + "/draft");
    return method.equals("POST")
        && p.matches(root + "/(?:draft/confirm|newebpay-materials|files|cancel)");
  }
}
