package ai.neargo.shop.spi.media;

import ai.neargo.shop.spi.media.MediaPublicSourcePort.Facts;
import ai.neargo.shop.spi.media.MediaPublicSourcePort.Request;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Canonical media evidence travels unchanged over the existing service-token transport. */
public interface MediaPublicSourceInternalApi {
    String CAPTURE_PATH = "/internal/media/public-sources/capture";

    @PostExchange(CAPTURE_PATH)
    Facts capture(@RequestBody Request request);
}
