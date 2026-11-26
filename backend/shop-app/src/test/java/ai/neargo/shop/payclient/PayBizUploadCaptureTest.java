package ai.neargo.shop.payclient;

import ai.neargo.shop.merchantbridge.PayBizSessionVerifier;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

class PayBizUploadCaptureTest {
  @Test
  void authenticatedUploadSurvivesLaterFrameworkInputConsumption() throws Exception {
    byte[] body = PayBizRequestBodyTest.multipart(1024);
    var request = new MockHttpServletRequest("POST", "/biz/pay/v2/onboardings/ONB/files");
    request.setContentType("multipart/form-data; boundary=raw-boundary");
    request.setContent(body);
    request.addHeader("Authorization", "Bearer btk_capture");
    var sessions = mock(PayBizSessionVerifier.class);
    when(sessions.verify("btk_capture")).thenReturn(mock(LoginUser.class));
    var filter = new PayBizAuthenticationFilter(sessions, mock(HandlerExceptionResolver.class));
    filter.doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
      // Reproduces the observed live failure: the later relay sees an exhausted stream.
      incoming.getInputStream().readAllBytes();
      assertArrayEquals(body, PayBizRequestBody.read((HttpServletRequest) incoming, true));
    });
  }

  @Test
  void rejectedIdentityNeverCapturesUploadContent() throws Exception {
    var request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("POST");
    when(request.getRequestURI()).thenReturn("/biz/pay/v2/onboardings/ONB/files");
    when(request.getHeader("Authorization")).thenReturn("Bearer btk_rejected");
    var sessions = mock(PayBizSessionVerifier.class);
    when(sessions.verify("btk_rejected")).thenThrow(new PayBizException(HttpStatus.UNAUTHORIZED));
    var errors = mock(HandlerExceptionResolver.class);
    var filter = new PayBizAuthenticationFilter(sessions, errors);
    filter.doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
      throw new AssertionError("Rejected request reached downstream");
    });
    verify(request, never()).getInputStream();
    verify(errors).resolveException(any(), any(), any(), any(PayBizException.class));
  }
}
