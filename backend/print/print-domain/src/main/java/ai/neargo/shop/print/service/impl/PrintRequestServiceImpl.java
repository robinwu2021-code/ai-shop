package ai.neargo.shop.print.service.impl;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.layout.*;
import ai.neargo.shop.print.operation.PrintOperationService;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class PrintRequestServiceImpl implements PrintRequestService {
    private final PrnRequestMapper requests;
    private final PrnBatchMapper batches;
    private final ai.neargo.shop.print.service.PrintGenerationRecovery facade;
    private final PrintOperationService ops;
    private final PrintJsonCodec json;

    public PrintRequestServiceImpl(
            PrnRequestMapper r,
            PrnBatchMapper b,
            ai.neargo.shop.print.service.PrintGenerationRecovery f,
            PrintOperationService o,
            PrintJsonCodec j) {
        requests = r;
        batches = b;
        facade = f;
        ops = o;
        json = j;
    }

    public PageData<PrintRequestVO> listRequests(ListRequestsQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        var p =
                requests.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        PrintSupport.<PrnRequest>scoped(scope(a))
                                .eq(q.orderNo() != null, "order_no", q.orderNo())
                                .eq(q.subOrderNo() != null, "sub_order_no", q.subOrderNo())
                                .eq(q.scene() != null, "scene", q.scene())
                                .eq(q.status() != null, "status", q.status())
                                .ge(q.from() != null, "created_at", q.from())
                                .le(q.to() != null, "created_at", q.to())
                                .orderByDesc("id"));
        return PageData.of(
                p.getRecords().stream().map(PrintViews::request).toList(),
                p.getTotal(),
                p.getCurrent(),
                p.getSize());
    }

    public RequestDetailVO getRequest(String no, PrintActor a) {
        var r = requests.selectOne(PrintSupport.<PrnRequest>scoped(scope(a)).eq("request_no", no));
        require(r != null, "print.notFound");
        return new RequestDetailVO(
                PrintViews.request(r),
                batches
                        .selectList(
                                PrintSupport.<PrnBatch>scoped(scope(a))
                                        .eq("request_no", no)
                                        .orderByAsc("id"))
                        .stream()
                        .map(PrnBatch::getBatchNo)
                        .toList());
    }

    public PrintOperation retryGeneration(String no, RetryGenerationReq r, PrintActor a) {
        return ops.execute(
                a,
                "RETRY_GENERATION",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () ->
                        succeeded(
                                "RETRY_GENERATION",
                                "REQUEST",
                                no,
                                json.freeze(
                                        facade.recoverGeneration(no, r.expectedRevision(), a))));
    }
}
