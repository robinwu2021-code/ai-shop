package ai.neargo.shop.beauty.spi;

import ai.neargo.shop.beauty.dto.AcceptTaskReq;
import ai.neargo.shop.beauty.dto.HandoverTaskReq;
import ai.neargo.shop.beauty.dto.StartServiceTaskReq;
import ai.neargo.shop.beauty.dto.TaskActionResultVO;
import ai.neargo.shop.beauty.dto.TaskWorkflowReq;
import ai.neargo.shop.beauty.dto.TaskWorkflowVO;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Fixed typed workflow routes; scope and actor are always the bound current identity. */
public interface BeautyTaskWorkflowInternalApi {
    String ENDPOINT = "BEAUTY_TASK_WORKFLOW";
    String PATH = "/internal/beauty/task-workflow";

    interface Target {
        String workOrderNo();

        String taskNo();
    }

    record Accept(
            @NotBlank @Size(max = 32) String workOrderNo,
            @NotBlank @Size(max = 32) String taskNo,
            @NotNull @Valid AcceptTaskReq request,
            @NotBlank @Size(max = 200) String key)
            implements Target {}

    @PostExchange(PATH + "/accept")
    InternalReply<TaskActionResultVO> accept(@RequestBody InternalCall<Accept> request);

    record Start(
            @NotBlank @Size(max = 32) String workOrderNo,
            @NotBlank @Size(max = 32) String taskNo,
            @NotNull @Valid StartServiceTaskReq request,
            @NotBlank @Size(max = 200) String key)
            implements Target {}

    @PostExchange(PATH + "/start")
    InternalReply<TaskWorkflowVO> start(@RequestBody InternalCall<Start> request);

    record Handover(
            @NotBlank @Size(max = 32) String workOrderNo,
            @NotBlank @Size(max = 32) String taskNo,
            @NotNull @Valid HandoverTaskReq request,
            @NotBlank @Size(max = 200) String key)
            implements Target {}

    @PostExchange(PATH + "/propose-handover")
    InternalReply<String> proposeHandover(@RequestBody InternalCall<Handover> request);

    record Act(
            @NotBlank @Size(max = 32) String workOrderNo,
            @NotBlank @Size(max = 32) String taskNo,
            @NotNull @Valid TaskWorkflowReq request,
            @NotBlank @Size(max = 200) String key)
            implements Target {}

    @PostExchange(PATH + "/act")
    InternalReply<TaskWorkflowVO> act(@RequestBody InternalCall<Act> request);

    record Query(
            @NotBlank @Size(max = 32) String workOrderNo, @NotBlank @Size(max = 32) String taskNo)
            implements Target {}

    record Recover(
            @NotBlank @Size(max = 32) String workOrderNo,
            @NotBlank @Size(max = 32) String taskNo,
            @NotBlank @Size(max = 32) String operationNo)
            implements Target {}

    @PostExchange(PATH + "/query")
    InternalReply<TaskWorkflowVO> query(@RequestBody InternalCall<Query> request);

    @PostExchange(PATH + "/recover")
    InternalReply<TaskWorkflowVO> recover(@RequestBody InternalCall<Recover> request);

    record Cancel(
            @NotBlank @Size(max = 32) String workOrderNo,
            @NotBlank @Size(max = 32) String taskNo,
            @NotBlank @Size(max = 32) String operationNo)
            implements Target {}

    @PostExchange(PATH + "/cancel")
    InternalReply<TaskWorkflowVO> cancelPending(@RequestBody InternalCall<Cancel> request);
}
