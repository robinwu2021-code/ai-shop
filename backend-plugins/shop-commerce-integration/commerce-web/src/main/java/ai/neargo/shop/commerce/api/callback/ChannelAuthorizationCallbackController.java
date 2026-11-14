package ai.neargo.shop.commerce.api.callback;

import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.service.ChannelAuthorizationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/** OAuth state与网关交换共同证明回调；String协议JSON沿原wrapper排除，不接受任意回跳。 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
public class ChannelAuthorizationCallbackController {
    private final ChannelAuthorizationService service;
    private final ObjectMapper json = new ObjectMapper();

    public ChannelAuthorizationCallbackController(ChannelAuthorizationService service) {
        this.service = service;
    }

    @GetMapping(
            value = "/callback/commerce/{providerCode}/authorization",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public String completeAuthorization(
            @PathVariable("providerCode") @NotBlank @Size(max = 32) String providerCode,
            @RequestParam("state") String state,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "error", required = false) String error,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("state", "code", "error"));
        return json.writeValueAsString(
                service.completeAuthorization(providerCode, state, code, error));
    }
}
