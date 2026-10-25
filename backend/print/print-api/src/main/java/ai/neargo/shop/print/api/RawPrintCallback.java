package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record RawPrintCallback(
        String adapterCode,
        String accountRef,
        byte[] body,
        Map<String, String> headers,
        Instant receivedAt) {
    public RawPrintCallback {
        body = Objects.requireNonNull(body, "body").clone();
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
