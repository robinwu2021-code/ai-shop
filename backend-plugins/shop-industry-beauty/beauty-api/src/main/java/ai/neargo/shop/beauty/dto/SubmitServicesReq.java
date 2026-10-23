package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.CheckoutPort;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Explicit selected scope and original source reference; no caller buyer, money or consumer
 * authority.
 */
public record SubmitServicesReq(
        long expectedRevision,
        long expectedScopeRevision,
        List<TaskSelection> tasks,
        List<AddedItem> additions,
        SourceSelection source) {
    public SubmitServicesReq {
        require(
                expectedRevision >= 0
                        && expectedScopeRevision > 0
                        && tasks != null
                        && additions != null);
        tasks = List.copyOf(tasks);
        additions = List.copyOf(additions);
        int quantity = tasks.size();
        var refs = new HashSet<String>();
        for (var task : tasks) {
            require(refs.add(task.taskNo()));
        }
        for (var item : additions) {
            quantity = Math.addExact(quantity, item.quantity());
            require(refs.add(item.clientItemRef()));
        }
        require(source != null && quantity > 0 && quantity <= 10);
    }

    @JsonAnySetter
    public void unknown(String field, Object value) {
        throw invalid();
    }

    @Override
    public String toString() {
        return "SubmitServicesReq[proof=REDACTED]";
    }

    /** Selects the entire immutable COUNT task without a client quantity override. */
    public record TaskSelection(
            String taskNo, long expectedRevision, long expectedBindingRevision) {
        public TaskSelection {
            reference(taskNo, 32);
            require(expectedRevision >= 0 && expectedBindingRevision >= 0);
        }

        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }
    }

    /** New tasks must use the explicitly expected published definition version. */
    public record AddedItem(
            String clientItemRef,
            String skuNo,
            String definitionVersionNo,
            @JsonDeserialize(using = CheckoutPort.CountDeserializer.class) int quantity) {
        public AddedItem {
            reference(clientItemRef, 48);
            reference(skuNo, 32);
            reference(definitionVersionNo, 32);
            require(quantity > 0 && quantity <= 10);
        }

        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }
    }

    /** PREPARE is merchant quote preparation; EXISTING refers to one original owner operation. */
    public record SourceSelection(
            String mode,
            String customerRef,
            String payMode,
            String couponNo,
            Map<String, String> activityChoices,
            String remark,
            String checkoutOperationNo,
            String subOrderNo,
            Long sourceRevision,
            String sourceHash,
            List<ExistingLineSelection> lines) {
        public SourceSelection {
            require(activityChoices != null && lines != null);
            activityChoices = Map.copyOf(activityChoices);
            lines = List.copyOf(lines);
            require(activityChoices.size() <= 64);
            if ("PREPARE".equals(mode)) {
                require(
                        customerRef != null
                                && customerRef.matches("cr1_[A-Za-z0-9_-]{43}")
                                && ("ONLINE".equals(payMode) || "OFFLINE".equals(payMode))
                                && checkoutOperationNo == null
                                && subOrderNo == null
                                && sourceRevision == null
                                && sourceHash == null
                                && lines.isEmpty());
                if (couponNo != null) {
                    reference(couponNo, 64);
                }
                require(remark == null || remark.length() <= 500);
                activityChoices.forEach(
                        (key, value) -> {
                            reference(key, 64);
                            reference(value, 64);
                        });
            } else {
                require(
                        "EXISTING".equals(mode)
                                && customerRef == null
                                && payMode == null
                                && couponNo == null
                                && activityChoices.isEmpty()
                                && remark == null
                                && !lines.isEmpty()
                                && lines.size() <= 10);
                reference(checkoutOperationNo, 64);
                reference(subOrderNo, 64);
                require(
                        sourceRevision != null
                                && sourceRevision > 0
                                && sourceHash != null
                                && sourceHash.matches("[0-9a-f]{64}"));
                require(
                        lines.stream().map(ExistingLineSelection::selectionRef).distinct().count()
                                == lines.size());
            }
        }

        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "SourceSelection[mode=" + mode + ", proof=REDACTED]";
        }
    }

    /** Every selected task/addition explicitly identifies its original source line. */
    public record ExistingLineSelection(String selectionRef, String sourceLineNo) {
        public ExistingLineSelection {
            reference(selectionRef, 48);
            reference(sourceLineNo, 64);
        }

        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }
    }

    private static void reference(String value, int limit) {
        require(
                value != null
                        && !value.isBlank()
                        && value.length() <= limit
                        && value.equals(value.strip()));
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw invalid();
        }
    }

    private static BizException invalid() {
        return BizException.of(ErrorCode.BAD_REQUEST);
    }
}
