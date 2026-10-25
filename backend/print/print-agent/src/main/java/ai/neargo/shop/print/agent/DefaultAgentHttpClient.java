package ai.neargo.shop.print.agent;

import ai.neargo.shop.print.agent.AgentModels.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public final class DefaultAgentHttpClient implements AgentHttpClient {
    private final URI server;
    private final AgentCredentialStore credentials;
    private final AgentJson json = new AgentJson();
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public DefaultAgentHttpClient(URI server, AgentCredentialStore credentials) {
        if (!"https".equals(server.getScheme())
                && !(Boolean.getBoolean("print.agent.allowLoopbackHttp")
                        && "http".equals(server.getScheme())
                        && Set.of("localhost", "127.0.0.1", "[::1]").contains(server.getHost())))
            throw new IllegalArgumentException("Agent requires HTTPS");
        if (server.getUserInfo() != null
                || server.getQuery() != null
                || server.getFragment() != null)
            throw new IllegalArgumentException("Agent server URI");
        this.server = server;
        this.credentials = credentials;
    }

    private String key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("business key");
        return key;
    }

    private <T> T call(
            String method, String path, Object request, Class<T> type, boolean enrollment) {
        try {
            URI uri =
                    URI.create(server.toString().replaceAll("/+$", "") + "/device/print/v1" + path);
            var builder =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(30))
                            .header("Accept", "application/json");
            if (!enrollment)
                builder.header(
                        "Authorization",
                        "Bearer "
                                + credentials
                                        .load()
                                        .orElseThrow(
                                                () -> new IllegalStateException("AGENT_NOT_PAIRED"))
                                        .value());
            if (method.equals("POST")) {
                String body = json.write(request);
                if (body.getBytes(StandardCharsets.UTF_8).length > 3 * 1024 * 1024)
                    throw new IllegalArgumentException("protocol request too large");
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
            } else builder.GET();
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
                if (response.statusCode() / 100 != 2 || bytes.length > 16 * 1024 * 1024)
                    throw new IllegalStateException("AGENT_API_REJECTED_" + response.statusCode());
                var node = json.tree(new String(bytes, StandardCharsets.UTF_8));
                if (node.has("code")) {
                    if (node.path("code").asInt() != 0)
                        throw new IllegalStateException("AGENT_API_BUSINESS_REJECTED");
                    node = node.path("data");
                }
                return json.read(node.toString(), type);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AGENT_API_INTERRUPTED", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("AGENT_API_UNAVAILABLE", e);
        }
    }

    public DeviceEnrollVO enroll(DeviceEnrollReq request) {
        return call("POST", "/enrollments", request, DeviceEnrollVO.class, true);
    }

    public DeviceOpenSessionVO openSession(DeviceOpenSessionReq request) {
        return call("POST", "/sessions", request, DeviceOpenSessionVO.class, false);
    }

    public DeviceHeartbeatVO heartbeat(DeviceHeartbeatReq request) {
        return call("POST", "/heartbeats", request, DeviceHeartbeatVO.class, false);
    }

    public DeviceClaimVO claim(DeviceClaimReq request) {
        return call("POST", "/claims", request, DeviceClaimVO.class, false);
    }

    public DeviceAcceptDispatchVO accept(String dispatchKey, DeviceAcceptDispatchReq request) {
        return call(
                "POST",
                "/dispatches/" + key(dispatchKey) + "/acceptances",
                request,
                DeviceAcceptDispatchVO.class,
                false);
    }

    public DeviceRenewLeaseVO renew(String dispatchKey, DeviceRenewLeaseReq request) {
        return call(
                "POST",
                "/dispatches/" + key(dispatchKey) + "/lease-renewals",
                request,
                DeviceRenewLeaseVO.class,
                false);
    }

    public DeviceReportEventsVO report(DeviceReportEventsReq request) {
        return call("POST", "/events", request, DeviceReportEventsVO.class, false);
    }

    public DeviceGetDispatchVO getDispatch(String dispatchKey) {
        return call(
                "GET",
                "/dispatches/" + key(dispatchKey) + "",
                null,
                DeviceGetDispatchVO.class,
                false);
    }

    public DeviceRecoverVO recover(DeviceRecoverReq request) {
        return call("POST", "/recoveries", request, DeviceRecoverVO.class, false);
    }

    public DeviceReportCommandResultVO reportCommand(
            String commandNo, DeviceReportCommandResultReq request) {
        return call(
                "POST",
                "/commands/" + key(commandNo) + "/results",
                request,
                DeviceReportCommandResultVO.class,
                false);
    }
}
