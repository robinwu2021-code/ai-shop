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
