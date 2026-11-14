package ai.neargo.shop.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RetailStoreScopeCompatibilityTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        BizContext.clear();
    }

    @Test
    void legacyRetailFilterStillFallsBackWhileStrictValidatorIsOptIn() throws Exception {
        var user = LoginUser.merchant("ACCOUNT", "clerk");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
        var filter = new BizContextFilter((account, requested) ->
                new BizContext("E", Set.of(), Set.of(), Set.of("A"), "A", false));
        var request = new MockHttpServletRequest("POST", "/biz/order");
        request.addHeader(BizContextFilter.STORE_HEADER, "INVALID");
        filter.doFilter(request, new MockHttpServletResponse(), (req, response) -> {
            assertEquals("E", BizContext.current().merchantNo());
            assertEquals("A", BizContext.current().currentStoreNo());
        });
        assertNull(BizContext.current().merchantNo());
    }
}
