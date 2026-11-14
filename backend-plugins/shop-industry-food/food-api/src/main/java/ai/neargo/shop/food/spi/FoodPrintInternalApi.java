package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.spi.printing.PrintPort.JobVO;
import ai.neargo.shop.spi.printing.PrintPort.Scope;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Typed standalone source-document adapter; actual paper delivery remains owned by PLATFORM. */
public interface FoodPrintInternalApi {
    String PATH="/internal/food/print-documents/enqueue";
    @PostExchange(PATH) List<JobVO> enqueue(@RequestBody Command request);
    record Command(Scope scope,PrintDocumentReq definition,String requestKey) { }
}
