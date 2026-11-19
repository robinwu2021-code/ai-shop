package ai.neargo.shop.platform.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.ratelimit.RateLimiter;
import ai.neargo.shop.common.ratelimit.RateRule;
import ai.neargo.shop.platform.AppOpsTicketService;
import ai.neargo.shop.platform.config.AppOpsProps;
import ai.neargo.shop.platform.dto.AppOpsTicketVOs.TicketVO;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Asks AppOps for an installation enrollment ticket (APP-API-13) with ai-shop's APP_BACKEND CLIENT credential.
 *
 * <p>The endpoint is anonymous on purpose: an app checks for updates before anyone signs in. What keeps it safe:
 * only configured apps, a strict Ed25519 SPKI public key, per-IP and per-app rate limits, and tickets that are
 * single use, short lived and bound to that public key on the AppOps side. The CLIENT token never leaves ai-shop.
 */
@Slf4j
@Service
public class AppOpsTicketServiceImpl implements AppOpsTicketService {

    private static final byte[] ED25519_SPKI_PREFIX =
            {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    private final AppOpsProps props;
    private final RateLimiter rateLimiter;
    private final ObjectMapper json;
    private final AtomicReference<Token> token = new AtomicReference<>();
    private volatile HttpClient http;

    private record Token(String value, Instant refreshAfter) {
    }

    public AppOpsTicketServiceImpl(AppOpsProps props, RateLimiter rateLimiter, ObjectMapper json) {
        this.props = props;
        this.rateLimiter = rateLimiter;
        this.json = json;
    }

    @Override
    public TicketVO issue(String app, String publicKey, String clientIp) {
        if (!props.enabled()) {
            throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
        AppOpsProps.Target target = app == null ? null : props.getApps().get(app);
        if (target == null || target.getProfileId().isBlank() || target.getDistributionId().isBlank()
                || !validPublicKey(publicKey)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (!rateLimiter.tryAcquire("appops:ticket:ip:" + (clientIp == null ? "-" : clientIp),
                RateRule.of("appops-ticket-ip", Duration.ofMinutes(1), props.getTicketsPerIpPerMinute())).allowed()
                || !rateLimiter.tryAcquire("appops:ticket:app:" + app,
                RateRule.of("appops-ticket-app", Duration.ofMinutes(1), props.getTicketsPerAppPerMinute())).allowed()) {
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS);
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("profile_id", target.getProfileId());
        body.put("distribution_id", target.getDistributionId());
        body.put("public_key", publicKey);
        try {
            HttpResponse<String> r = requestTicket(json.writeValueAsString(body), false);
            if (r.statusCode() == 401) {
                // The cached CLIENT token was revoked or rotated: one fresh token, then give up.
                token.set(null);
                r = requestTicket(json.writeValueAsString(body), true);
            }
            if (r.statusCode() != 201 && r.statusCode() != 200) {
                log.warn("[appops] ticket refused status={} code={}", r.statusCode(), errorCode(r.body()));
                throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
            }
            JsonNode data = json.readTree(r.body()).path("data");
            String ticket = data.path("ticket").asString(null);
            if (ticket == null || ticket.isBlank()) {
                throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
            }
            return new TicketVO(ticket, data.path("expires_at").asString(null));
        } catch (BizException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("[appops] ticket call failed: {}", e.getClass().getSimpleName());
            throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
    }

    /** Unpadded Base64URL of the 44-byte Ed25519 SubjectPublicKeyInfo (59 characters). */
    static boolean validPublicKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{59}")) {
            return false;
        }
        byte[] der = Base64.getUrlDecoder().decode(value);
        return der.length == 44 && Arrays.equals(Arrays.copyOf(der, 12), ED25519_SPKI_PREFIX);
    }

    private HttpResponse<String> requestTicket(String body, boolean retried) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/api/appops/v1/internal/installations/enrollment-tickets"))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Authorization", "Bearer " + accessToken())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    /** OAuth client_credentials token, reused until a minute before it expires. */
    private String accessToken() throws IOException {
        Token current = token.get();
        if (current != null && Instant.now().isBefore(current.refreshAfter())) {
            return current.value();
        }
        String basic = Base64.getEncoder().encodeToString((URLEncoder.encode(props.getClientId(), StandardCharsets.UTF_8)
                + ":" + URLEncoder.encode(props.getClientSecret(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base() + "/oauth2/token"))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build());
        if (r.statusCode() != 200) {
            log.warn("[appops] client token refused status={}", r.statusCode());
            throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
        JsonNode n = json.readTree(r.body());
        String value = n.path("access_token").asString(null);
        long expiresIn = n.path("expires_in").asLong(0);
        if (value == null || value.isBlank()) {
            throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
        token.set(new Token(value, Instant.now().plusSeconds(Math.max(0, expiresIn - 60))));
        return value;
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return client().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private HttpClient client() {
        HttpClient current = http;
        if (current == null) {
            current = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            http = current;
        }
        return current;
    }

    private String base() {
        return props.getBaseUrl().replaceAll("/+$", "");
    }

    private String errorCode(String body) {
        try {
            return json.readTree(body).path("error").path("code").asString("?");
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
