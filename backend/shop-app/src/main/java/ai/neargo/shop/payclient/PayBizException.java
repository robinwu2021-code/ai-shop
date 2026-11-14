package ai.neargo.shop.payclient;

import org.springframework.http.HttpStatus;

/** Only a safe status crosses the host / near-pay protocol boundary. */
public final class PayBizException extends RuntimeException {
  private final HttpStatus status;

  public PayBizException(HttpStatus status) {
    super(status.getReasonPhrase());
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }
}
