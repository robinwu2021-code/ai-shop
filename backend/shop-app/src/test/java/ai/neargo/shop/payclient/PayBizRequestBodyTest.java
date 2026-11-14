package ai.neargo.shop.payclient;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class PayBizRequestBodyTest {
  @Test
  void chunkedMultipartTotalLimitDoesNotStallWhileClosingTheCurrentPart() throws Exception {
    var bytes = new ByteArrayOutputStream();
    for (int size : new int[] {3 * 1024 * 1024, 4 * 1024 * 1024}) {
      bytes.write(
          ("--TotalBoundary\r\n"
               + "Content-Disposition: form-data; name=\"file\"; filename=\"proof.png\"\r\n"
               + "Content-Type: image/png\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      bytes.write(new byte[size]);
      bytes.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
    bytes.write("--TotalBoundary--\r\n".getBytes(StandardCharsets.UTF_8));
    var request =
        new MockHttpServletRequest() {
          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    request.setContentType("multipart/form-data; boundary=TotalBoundary");
    request.setContent(bytes.toByteArray());
    assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(3),
        () ->
            assertEquals(
                HttpStatus.PAYLOAD_TOO_LARGE,
                assertThrows(PayBizException.class, () -> PayBizRequestBody.read(request, true))
                    .status()));
  }

  @Test
  void jsonAndUnknownLengthAreBounded() throws Exception {
    var req = request("application/json", new byte[1024 * 1024]);
    assertEquals(1024 * 1024, PayBizRequestBody.read(req, false).length);
    req.setContent(new byte[1024 * 1024 + 1]);
    assertEquals(
        HttpStatus.PAYLOAD_TOO_LARGE,
        assertThrows(PayBizException.class, () -> PayBizRequestBody.read(req, false)).status());
    var chunked =
        new MockHttpServletRequest() {
          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    chunked.setContentType("application/json");
    chunked.setContent(new byte[1024 * 1024 + 1]);
    assertEquals(
        HttpStatus.PAYLOAD_TOO_LARGE,
        assertThrows(PayBizException.class, () -> PayBizRequestBody.read(chunked, false)).status());
  }

  @Test
  void multipartPreservesEveryByteAndChecksEachFileBeforeForwarding() throws Exception {
    byte[] valid = multipart(5 * 1024 * 1024);
    var req = request("multipart/form-data; boundary=raw-boundary", valid);
    assertArrayEquals(valid, PayBizRequestBody.read(req, true));
    req.setContent(multipart(5 * 1024 * 1024 + 1));
    assertEquals(
        HttpStatus.PAYLOAD_TOO_LARGE,
        assertThrows(PayBizException.class, () -> PayBizRequestBody.read(req, true)).status());
    req.setContent(new byte[6 * 1024 * 1024 + 1]);
    assertEquals(
        HttpStatus.PAYLOAD_TOO_LARGE,
        assertThrows(PayBizException.class, () -> PayBizRequestBody.read(req, true)).status());
  }

  @Test
  void malformedMultipartAndWrongContentTypeFailBeforeForwarding() {
    var req = request("multipart/form-data", new byte[32]);
    assertEquals(
        HttpStatus.BAD_REQUEST,
        assertThrows(PayBizException.class, () -> PayBizRequestBody.read(req, true)).status());
    req.setContentType("application/json");
    assertEquals(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        assertThrows(PayBizException.class, () -> PayBizRequestBody.read(req, true)).status());
  }

  static MockHttpServletRequest request(String type, byte[] content) {
    var req = new MockHttpServletRequest();
    req.setMethod("POST");
    req.setContentType(type);
    req.setContent(content);
    return req;
  }

  static byte[] multipart(int size) throws Exception {
    var out = new ByteArrayOutputStream();
    out.write(
        ("--raw-boundary\r\n"
                + "Content-Disposition: form-data; name=\"requestNo\"\r\n\r\n"
                + "original-command\r\n"
                + "--raw-boundary\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"license.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    out.write(new byte[size]);
    out.write("\r\n--raw-boundary--\r\n".getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }
}
