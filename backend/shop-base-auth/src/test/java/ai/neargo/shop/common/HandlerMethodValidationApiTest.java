package ai.neargo.shop.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Spring MVC built-in method validation must preserve the project's input/server error distinction. */
class HandlerMethodValidationApiTest {
    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new ValidationController())
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseWrapper()).build();
    }

    @Test void invalidMethodInputReturnsBadRequestInsteadOfRetryableServerError() throws Exception {
        mvc().perform(get("/biz/validation-test/input").param("qty", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10400));
    }

    @Test void invalidServerReturnValueStillReturnsInternalError() throws Exception {
        mvc().perform(get("/biz/validation-test/output"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10500));
    }

    @RestController
    static class ValidationController {
        @GetMapping("/biz/validation-test/input")
        public int input(@RequestParam @Min(1) int qty) { return qty; }
        @NotNull
        @GetMapping("/biz/validation-test/output")
        public Object output() { return null; }
    }
}
