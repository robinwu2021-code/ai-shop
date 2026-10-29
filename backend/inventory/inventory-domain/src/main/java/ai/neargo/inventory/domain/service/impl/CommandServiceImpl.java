package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvCommand;
import ai.neargo.inventory.domain.mapper.InventoryMappers.CommandMapper;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.support.Digests;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.function.Supplier;

@ConditionalOnInventory
@Service
public class CommandServiceImpl implements CommandService {

    static final String DONE = "DONE";

    /** 请求摘要要稳定：同一个请求两次序列化必须得到同一串字节，所以键排序。 */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final CommandMapper commandMapper;

    public CommandServiceImpl(CommandMapper commandMapper) {
        this.commandMapper = commandMapper;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public <T> T execute(Caller caller, String commandType, Object request, Class<T> resultType, Supplier<T> action) {
        String requestId = caller.requestId();
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "Idempotency-Key");
        }
        String hash = Digests.sha256(commandType + "\n" + JSON.writeValueAsString(request));

        InvCommand row = new InvCommand();
        row.setSystemCode(caller.systemCode());
        row.setOwnerId(caller.ownerId());
        row.setRequestId(requestId);
        row.setCommandType(commandType);
        row.setRequestHash(hash);
        row.setStatus(DONE);
        row.setActor(caller.actor());
        try {
            // 先占键再执行：并发的同键请求在唯一索引上排队，后到的等先到的提交后看到它的结果
            commandMapper.insert(row);
        } catch (DuplicateKeyException duplicate) {
            InvCommand existing = locked(caller, requestId);
            if (existing == null) {
                throw duplicate;
            }
            if (!existing.getRequestHash().equals(hash) || !existing.getCommandType().equals(commandType)) {
                throw InventoryException.of(InventoryError.IDEMPOTENCY_CONFLICT, requestId);
            }
            return existing.getResultBody() == null ? null : JSON.readValue(existing.getResultBody(), resultType);
        }
        T result = action.get();
        row.setResultBody(result == null ? null : JSON.writeValueAsString(result));
        commandMapper.updateById(row);
        return result;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", readOnly = true)
    public Optional<Stored> find(Caller caller, String requestId) {
        InvCommand row = commandMapper.selectOne(query(caller, requestId));
        return row == null ? Optional.empty()
                : Optional.of(new Stored(row.getCommandType(), row.getResultBody(), row.getCreatedAt()));
    }

    /** 锁定读：可重复读隔离下普通读看不到别人刚提交的那一行，加锁读能。 */
    private InvCommand locked(Caller caller, String requestId) {
        return commandMapper.selectOne(query(caller, requestId).last("FOR UPDATE"));
    }

    private static LambdaQueryWrapper<InvCommand> query(Caller caller, String requestId) {
        return Wrappers.<InvCommand>lambdaQuery()
                .eq(InvCommand::getSystemCode, caller.systemCode())
                .eq(InvCommand::getOwnerId, caller.ownerId())
                .eq(InvCommand::getRequestId, requestId);
    }
}
