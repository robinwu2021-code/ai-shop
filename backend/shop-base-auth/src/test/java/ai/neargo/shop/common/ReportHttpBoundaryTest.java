package ai.neargo.shop.common;

import ai.neargo.shop.svc.ReportServiceResponses;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportHttpBoundaryTest {
    @Test void internalFailuresHaveRealStatusesAndNoDtoOrPrivateMessage() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new InternalController()).setControllerAdvice(new ApiResponseWrapper()).build();
        int[] expected={400,403,404,409,409,500};
        for(int i=0;i<expected.length;i++) mvc.perform(get("/internal/report-test/"+i))
                .andExpect(status().is(expected[i])).andExpect(content().string(""));
    }
    @Test void onlyControlledDomainErrorsBecomeBusinessErrors() {
        assertEquals(ErrorCode.BAD_REQUEST,assertThrows(BizException.class,()->ReportServiceResponses.domain(()-> {throw new IllegalArgumentException("REPORT_INVALID_FILTER");})).errorCode());
        assertEquals(ErrorCode.CONFLICT,assertThrows(BizException.class,()->ReportServiceResponses.domain(()-> {throw new IllegalStateException("REPORT_NO_PUBLICATION");})).errorCode());
        var defect=new IllegalStateException("private infrastructure detail");
        assertSame(defect,assertThrows(IllegalStateException.class,()->ReportServiceResponses.domain(()-> {throw defect;})));
    }
    @RestController static class InternalController {
        @GetMapping("/internal/report-test/{kind}") Object fail(@PathVariable int kind) {
            throw switch(kind) {
                case 0 -> new IllegalArgumentException("private input");
                case 1 -> BizException.of(ErrorCode.FORBIDDEN);
                case 2 -> BizException.of(ErrorCode.NOT_FOUND);
                case 3 -> BizException.of(ErrorCode.CONFLICT);
                case 4 -> new IllegalStateException("REPORT_PUBLICATION_NOT_READY");
                default -> new IllegalStateException("private infrastructure detail");
            };
        }
        @ExceptionHandler(RuntimeException.class) ResponseEntity<Void> error(RuntimeException failure) {
            return ReportServiceResponses.error(failure);
        }
    }
}
