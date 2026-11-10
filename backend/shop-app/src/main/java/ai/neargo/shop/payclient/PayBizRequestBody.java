package ai.neargo.shop.payclient;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import org.apache.tomcat.util.http.fileupload.FileUpload;
import org.apache.tomcat.util.http.fileupload.UploadContext;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException;
import org.springframework.http.HttpStatus;

/** Bounded raw capture, with Tomcat's streaming multipart parser; nothing is persisted. */
public final class PayBizRequestBody {
  private static final int MIB = 1024 * 1024;

  private PayBizRequestBody() {}

  public static byte[] read(HttpServletRequest request, boolean upload) throws IOException {
    int limit = upload ? 6 * MIB : MIB;
    if (request.getContentLengthLong() > limit)
      throw new PayBizException(HttpStatus.PAYLOAD_TOO_LARGE);
    String contentType = request.getContentType();
    if (upload
        && (contentType == null
            || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")))
      throw new PayBizException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    var bytes = new ByteArrayOutputStream();
    try (var input =
        new FilterInputStream(request.getInputStream()) {
          private int count;

          @Override
          public int read() throws IOException {
            if (count > limit) throw new PayBizException(HttpStatus.PAYLOAD_TOO_LARGE);
            int value = in.read();
            if (value >= 0) {
              if (++count > limit) throw new PayBizException(HttpStatus.PAYLOAD_TOO_LARGE);
              bytes.write(value);
            }
            return value;
          }

          @Override
          public int read(byte[] buffer, int offset, int length) throws IOException {
            // Multipart cleanup may read again after the limit exception. Never enter a
            // zero-length read/drain loop after the request has already exceeded its bound.
            if (count > limit) throw new PayBizException(HttpStatus.PAYLOAD_TOO_LARGE);
            int read = in.read(buffer, offset, Math.min(length, limit - count + 1));
            if (read > 0) {
              count += read;
              if (count > limit) throw new PayBizException(HttpStatus.PAYLOAD_TOO_LARGE);
              bytes.write(buffer, offset, read);
            }
            return read;
          }
        }) {
      if (upload) checkMultipart(request, input);
      input.transferTo(OutputStream.nullOutputStream());
    }
    return bytes.toByteArray();
  }

  private static void checkMultipart(HttpServletRequest request, InputStream input)
      throws IOException {
    var parser = new FileUpload();
    parser.setSizeMax(6L * MIB);
    parser.setFileSizeMax(5L * MIB);
    try {
      var parts =
          parser.getItemIterator(
              new UploadContext() {
                public long contentLength() {
                  return request.getContentLengthLong();
                }

                public String getCharacterEncoding() {
                  return request.getCharacterEncoding();
                }

                public String getContentType() {
                  return request.getContentType();
                }

                public InputStream getInputStream() {
                  return input;
                }
              });
      while (parts.hasNext()) {
        try (var part = parts.next().openStream()) {
          part.transferTo(OutputStream.nullOutputStream());
        }
      }
    } catch (IOException malformed) {
      // FileUploadIOException wraps streaming size violations; never return parser details.
      var cause = malformed.getCause() == null ? malformed : malformed.getCause();
      if (cause instanceof FileSizeLimitExceededException
          || cause instanceof SizeLimitExceededException)
        throw new PayBizException(HttpStatus.PAYLOAD_TOO_LARGE);
      throw new PayBizException(HttpStatus.BAD_REQUEST);
    }
  }
}
