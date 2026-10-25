package ai.neargo.shop.common;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiResourceResponseTest {
    @Test void privateCsvResponseKeepsItsBytesInsteadOfBecomingAnApiResult() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new DownloadController()).setControllerAdvice(new ApiResponseWrapper()).build();
        mvc.perform(get("/biz/x/food/operating-reports/exports/REXP1/download"))
                .andExpect(status().isOk()).andExpect(content().bytes("value\r\n123\r\n".getBytes(StandardCharsets.UTF_8)));
    }
    @RestController static class DownloadController {
        @GetMapping("/biz/x/food/operating-reports/exports/REXP1/download")
        ResponseEntity<ByteArrayResource> download() {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                    .body(new ByteArrayResource("value\r\n123\r\n".getBytes(StandardCharsets.UTF_8)));
        }
    }
}
