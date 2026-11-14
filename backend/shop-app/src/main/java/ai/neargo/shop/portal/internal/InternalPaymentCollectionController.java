package ai.neargo.shop.portal.internal;

import ai.neargo.shop.paybridge.PaymentCollectionFact;
import ai.neargo.shop.paybridge.PaymentCollectionSecurity;
import ai.neargo.shop.paybridge.PaymentCollectionService;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

@RestController
@Validated
@ConditionalOnProperty(name = "shop.payment-collections.enabled", havingValue = "true")
public class InternalPaymentCollectionController {
    private final PaymentCollectionSecurity security;
    private final PaymentCollectionService service;
    private final ObjectMapper json;
    private final Validator validator;

    public InternalPaymentCollectionController(
            PaymentCollectionSecurity security,
            PaymentCollectionService service,
            ObjectMapper json,
            Validator validator) {
        this.security = security;
        this.service = service;
        this.json = json;
        this.validator = validator;
    }

    @PostMapping("/internal/payment-results/merchant-collections")
    public PaymentCollectionService.Accepted receive(
            @RequestBody @Size(max = 16384) String raw,
            @RequestHeader("X-Payment-Timestamp") @Pattern(regexp = "[0-9]{1,12}") String timestamp,
            @RequestHeader("X-Payment-Nonce")
                    @Pattern(
                            regexp =
                                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    String nonce,
            @RequestHeader("X-Payment-Environment") String environment,
            @RequestHeader("X-Payment-Signature") @Pattern(regexp = "[0-9a-f]{64}")
                    String signature) {
        security.verify(timestamp, nonce, environment, signature, raw);
        var fact = json.readValue(raw, PaymentCollectionFact.class);
        var violations = validator.validate(fact);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        return service.receive(fact, raw);
    }
}
