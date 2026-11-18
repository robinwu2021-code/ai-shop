package ai.neargo.shop.printbridge;

import ai.neargo.shop.common.ApiResult;
import ai.neargo.shop.print.api.*;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

public final class PrintAgentAuthenticationFilter extends OncePerRequestFilter {
    private final PrintAgentAuthenticationService authentication;
    private final ObjectMapper json;
    private final boolean requireHttps;

    public PrintAgentAuthenticationFilter(
            PrintAgentAuthenticationService a, ObjectMapper j, boolean secure) {
        authentication = a;
        json = j;
        requireHttps = secure;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/device/print/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (requireHttps && !request.isSecure())
                throw new PrintFailure("print.agentHttpsRequired");
            String path = request.getServletPath();
            if (path.equals("/device/print/v1/enrollments")) {
                chain.doFilter(request, response);
                return;
            }
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer "))
                throw new PrintFailure("print.agentUnauthorized");
            AgentRequestPurpose purpose =
                    path.endsWith("/events")
                            ? AgentRequestPurpose.REPORT_EVIDENCE
                            : path.endsWith("/results")
                                    ? AgentRequestPurpose.COMMAND_RESULT
                                    : path.endsWith("/sessions")
                                            ? AgentRequestPurpose.OPEN_SESSION
                                            : path.endsWith("/claims")
                                                    ? AgentRequestPurpose.CLAIM
                                                    : path.endsWith("/lease-renewals")
                                                            ? AgentRequestPurpose.RENEW_LEASE
                                                            : path.endsWith("/acceptances")
                                                                    ? AgentRequestPurpose
                                                                            .ACKNOWLEDGE
                                                                    : path.endsWith("/recoveries")
                                                                            ? AgentRequestPurpose
                                                                                    .RECOVER
                                                                            : path.endsWith(
                                                                                            "/heartbeats")
                                                                                    ? AgentRequestPurpose
                                                                                            .HEARTBEAT
                                                                                    : AgentRequestPurpose
                                                                                            .READ_DISPATCH;
            var identity =
                    authentication.authenticate(
                            new OpaqueAgentCredential(header.substring(7)), purpose);
            request.setAttribute("printAgentIdentity", identity);
            chain.doFilter(request, response);
        } catch (PrintFailure failure) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter()
                    .write(
                            json.writeValueAsString(
                                    ApiResult.error(10401, "Agent authentication required")));
        }
    }
}
