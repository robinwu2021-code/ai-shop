package ai.neargo.commerce.starter.http;

import ai.neargo.commerce.api.catalog.CatalogException;
import ai.neargo.commerce.api.mall.MallException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

abstract class MallHttpSupport {
    final MallHttpContextPort context;
    final MallHttpEnvelopePort envelope;
    final MallHttpJson json = new MallHttpJson();

    MallHttpSupport(MallHttpContextPort context, MallHttpEnvelopePort envelope) {
        this.context = context;
        this.envelope = envelope;
    }

    Object ok(Object value) {
        return envelope.success(json.view(value));
    }

    static final int MAX_EVIDENCE_BYTES = 10 * 1024 * 1024;

    /** Reserved private evidence key; never an external URL or a traversal. */
    static String assetKey(String value) {
        if (value == null || value.length() > 255 || value.contains("..")
                || !value.matches("[A-Za-z0-9_\\-/]+\\.(jpg|jpeg|png)"))
            throw new MallException("INVALID_REQUEST");
        return value;
    }

    /** Operator routes name the store explicitly; tenant always comes from the trusted actor. */
    static ai.neargo.commerce.api.mall.OrderQuery opsOrder(
            ai.neargo.commerce.api.common.ActorContext actor, String store, String order) {
        return new ai.neargo.commerce.api.mall.OrderQuery(
                new ai.neargo.commerce.api.common.StoreRef(actor.tenantNo(), code(store)), code(order));
    }

    static ai.neargo.commerce.api.port.PrivateEvidencePort.Upload upload(byte[] body, String contentType) {
        if (body == null || body.length == 0 || body.length > MAX_EVIDENCE_BYTES)
            throw new MallException("INVALID_REQUEST");
        String type =
                contentType == null
                        ? ""
                        : contentType.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        byte[] copy = body.clone();
        return new ai.neargo.commerce.api.port.PrivateEvidencePort.Upload(
                () -> new java.io.ByteArrayInputStream(copy), copy.length, type);
    }

    static String code(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,64}"))
            throw new MallException("INVALID_REQUEST");
        return value;
    }

    @ModelAttribute
    public void canonical(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.contains("%")
                || path.contains(";")
                || path.contains("\\")
                || path.contains("//")
                || path.contains("/./")
                || path.contains("/../")) throw new MallException("INVALID_REQUEST");
    }

    @ExceptionHandler({MallException.class, CatalogException.class})
    public ResponseEntity<Object> failure(RuntimeException failure) {
        String code =
                failure instanceof MallException mall
                        ? mall.code()
                        : ((CatalogException) failure).code().name();
        var response = envelope.failure(code);
        return ResponseEntity.status(response.status()).body(response.body());
    }
}
