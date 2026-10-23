package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.spi.printing.PrintPort.JobVO;
import ai.neargo.shop.spi.printing.PrintPort.Scope;
import java.util.List;

/** Restaurant adapter freezes selected source facts before calling the shared print owner. */
public interface FoodPrintService {
    List<JobVO> enqueue(Scope scope,PrintDocumentReq request,String requestKey);
}
