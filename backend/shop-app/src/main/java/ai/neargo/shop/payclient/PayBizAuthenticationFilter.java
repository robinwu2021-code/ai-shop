package ai.neargo.shop.payclient;

import ai.neargo.shop.auth.AbstractTokenAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Merchant authentication for the identity callback and the bounded payment relay only. */
public final class PayBizAuthenticationFilter extends OncePerRequestFilter {
  private final PayBizSessionVerifier sessions;
  private final HandlerExceptionResolver errors;

  public PayBizAuthenticationFilter(
      PayBizSessionVerifier sessions, HandlerExceptionResolver errors) {
    this.sessions = sessions;
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String token = AbstractTokenAuthFilter.bearer(request);
    try {
      var user = sessions.verify(token);
      var auth =
          new UsernamePasswordAuthenticationToken(
              user, null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
      auth.setDetails(token);
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(auth);
      SecurityContextHolder.setContext(context);
    } catch (PayBizException rejected) {
      errors.resolveException(request, response, null, rejected);
      return;
    }
    try {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
