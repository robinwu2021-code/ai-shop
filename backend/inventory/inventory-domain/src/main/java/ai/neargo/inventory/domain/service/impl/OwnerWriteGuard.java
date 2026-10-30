package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 单一写入方（AC-27）在领域写入口上的那道闸：这本账的写入方不是本进程（{@code inventory.write-mode}）就拒写。
 *
 * <p>为什么守在领域里而不只在入口：宿主有不止一条路拿到业主号 —— 商城按商家号解析（有守卫），
 * 也按 SKU 反查（下单锁货、商品镜像），后一条绕过了解析入口。守在写方法上，哪条路进来都一样。
 *
 * <p>另给跨业主的按单号批处理一个过滤子句：只挑写入方是本进程（且可选限定系统）的业主。
 */
@ConditionalOnInventory
@Component
class OwnerWriteGuard {

    private static final Pattern SYSTEM = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    private final OwnerMapper ownerMapper;
    private String writeMode = InvEnums.WriteAuthority.EMBEDDED;

    OwnerWriteGuard(OwnerMapper ownerMapper) {
        this.ownerMapper = ownerMapper;
    }

    @Value("${inventory.write-mode:EMBEDDED}")
    void setWriteMode(String writeMode) {
        this.writeMode = InvEnums.WriteAuthority.normalize(writeMode);
    }

    /** @throws InventoryException {@code NOT_WRITER} 这本账由另一侧写（或正在翻转） */
    void require(String ownerId) {
        if (ownerId == null) {
            return;
        }
        InvOwner owner = ownerMapper.selectOne(Wrappers.<InvOwner>lambdaQuery()
                .eq(InvOwner::getOwnerId, ownerId).select(InvOwner::getWriteAuthority));
        if (owner != null && owner.getWriteAuthority() != null && !writeMode.equals(owner.getWriteAuthority())) {
            throw InventoryException.of(InventoryError.NOT_WRITER, owner.getWriteAuthority());
        }
    }

    /**
     * 这个业主是否由本进程写（且 {@code systemCode} 非空时属于该系统）。给已经加了锁的跨业主结果集做过滤：
     * 按单号加锁的顺序与留存检查沿用原实现，锁到别的写入方的行只是不碰。
     */
    boolean writes(String ownerId, String systemCode) {
        InvOwner owner = ownerId == null ? null : ownerMapper.selectOne(Wrappers.<InvOwner>lambdaQuery()
                .eq(InvOwner::getOwnerId, ownerId).select(InvOwner::getWriteAuthority, InvOwner::getSystemCode));
        if (owner == null) {
            return false;
        }
        String authority = owner.getWriteAuthority() == null ? InvEnums.WriteAuthority.EMBEDDED : owner.getWriteAuthority();
        return writeMode.equals(authority) && (systemCode == null || systemCode.equals(owner.getSystemCode()));
    }

    /**
     * 跨业主批处理的业主子查询：写入方是本进程，{@code systemCode} 非空时再限定系统。
     * 拼进 SQL 的两个值都先收口（写入方只有两个常量，系统码按格式校验）。
     */
    String ownersSql(String systemCode) {
        StringBuilder sql = new StringBuilder("SELECT owner_id FROM inv_owner WHERE write_authority = '")
                .append(writeMode).append('\'');
        if (systemCode != null) {
            if (!SYSTEM.matcher(systemCode).matches()) {
                throw InventoryException.of(InventoryError.BAD_REQUEST, "system");
            }
            sql.append(" AND system_code = '").append(systemCode).append('\'');
        }
        return sql.toString();
    }
}
