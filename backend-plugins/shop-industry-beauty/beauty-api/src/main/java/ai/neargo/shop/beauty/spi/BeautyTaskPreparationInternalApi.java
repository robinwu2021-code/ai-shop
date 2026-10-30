package ai.neargo.shop.beauty.spi;

import ai.neargo.shop.beauty.dto.PrepareServiceTaskReq;
import ai.neargo.shop.beauty.dto.ServiceTaskPreparationVO;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

import java.util.Objects;

/**
 * Single consumed standalone preparation route; recovery already belongs to the existing operation
 * channel.
 */
public interface BeautyTaskPreparationInternalApi {
    String ENDPOINT = "BEAUTY_TASK_PREPARATION";
    String PATH = "/internal/beauty/task-preparations/prepare";

    @PostExchange(PATH)
    InternalReply<ServiceTaskPreparationVO> prepareTask(
            @RequestBody InternalCall<PrepareTaskCall> call);

    record PrepareTaskCall(
            String entityNo,
            String storeNo,
            String actorNo,
            String workOrderNo,
            String taskNo,
            PrepareServiceTaskReq req,
            String requestKey) {
        public PrepareTaskCall {
            for (String value :
                    java.util.List.of(entityNo, storeNo, actorNo, workOrderNo, taskNo)) {
                ExecutionEvidenceContract.reference(value, 32);
            }
            Objects.requireNonNull(req, "req");
            if (req.expectedTaskRevision() < 0
                    || req.expectedBindingRevision() <= 0
                    || req.expectedScopeRevision() <= 0) {
                throw new IllegalArgumentException("invalid preparation revision");
            }
            ExecutionEvidenceContract.reference(requestKey, 128);
        }

        @Override
        public String toString() {
            return "PrepareTaskCall[REDACTED]";
        }
    }
}
