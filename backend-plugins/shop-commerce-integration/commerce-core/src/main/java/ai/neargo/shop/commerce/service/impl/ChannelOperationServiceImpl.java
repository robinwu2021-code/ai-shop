package ai.neargo.shop.commerce.service.impl;

import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OperationAttemptMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OperationMapper;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ChannelOperationService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.Instant;
import java.time.ZoneOffset;

/** 查询不触发外部动作；授权URL在消费state或到期后立即裁掉。 */
public class ChannelOperationServiceImpl implements ChannelOperationService {
    private final OperationMapper operations;
    private final OperationAttemptMapper attempts;
    private final CommerceCommands commands;

    public ChannelOperationServiceImpl(
            OperationMapper operations,
            OperationAttemptMapper attempts,
            CommerceCommands commands) {
        this.operations = operations;
        this.attempts = attempts;
        this.commands = commands;
    }

    @Override
    public OperationVO getOperation(String entity, String operationNo) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(operationNo, 32);
        return commands.query(
                () -> {
                    var op =
                            operations.selectOne(
                                    Wrappers.<CimOperation>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", entity)
                                            .eq("operation_no", operationNo));
                    if (op == null) throw BizException.of(ErrorCode.NOT_FOUND);
                    var attempt =
                            attempts.selectOne(
                                    Wrappers.<CimOperationAttempt>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", entity)
                                            .eq("operation_no", operationNo)
                                            .eq("attempt_no", op.getCurrentAttemptNo()));
                    return view(op, attempt);
                });
    }

    static OperationVO view(CimOperation op, CimOperationAttempt attempt) {
        Instant expires =
                op.getExpiresAt() == null ? null : op.getExpiresAt().toInstant(ZoneOffset.UTC);
        String url =
                "SUBMITTED".equals(op.getStatus())
                                && op.getCallbackConsumedAt() == null
                                && expires != null
                                && expires.isAfter(Instant.now())
                        ? op.getAuthorizationUrl()
                        : null;
        return new OperationVO(
                op.getOperationNo(),
                op.getOperationType(),
                op.getStatus(),
                op.getAccountNo(),
                url,
                expires,
                attempt == null ? null : attempt.getErrorCode(),
                op.getVersion());
    }
}
