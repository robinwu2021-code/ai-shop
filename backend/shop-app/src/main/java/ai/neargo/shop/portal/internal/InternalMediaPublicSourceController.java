package ai.neargo.shop.portal.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.media.MediaPublicSourceFacts;
import ai.neargo.shop.spi.media.MediaPublicSourceInternalApi;
import ai.neargo.shop.spi.media.MediaPublicSourcePort;
import ai.neargo.shop.spi.media.MediaPublicSourcePort.Request;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A trusted service can request original scoped facts, never set public URLs, image dimensions or
 * private media access.
 */
@RestController
public class InternalMediaPublicSourceController {
    private final MediaPublicSourcePort source;
    private final String token;

    public InternalMediaPublicSourceController(
            MediaPublicSourcePort source, @Value("${shop.services.internal-token:}") String token) {
        this.source = source;
        this.token = token;
    }

    @PostMapping(MediaPublicSourceInternalApi.CAPTURE_PATH)
    public ResponseEntity<?> capture(
            @RequestBody(required = false) Request request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    MediaPublicSourceFacts.validateRequest(request);
                    return source.capture(request);
                });
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed(
            HttpMessageNotReadableException failure, HttpServletRequest request) {
        return InternalServiceResponses.invoke(
                token,
                request.getHeader(InternalHttp.TOKEN_HEADER),
                () -> {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                });
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> unavailable(RuntimeException failure, HttpServletRequest request) {
        return InternalServiceResponses.invoke(
                token,
                request.getHeader(InternalHttp.TOKEN_HEADER),
                () -> {
                    throw BizException.of(ErrorCode.INTERNAL_ERROR);
                });
    }
}
