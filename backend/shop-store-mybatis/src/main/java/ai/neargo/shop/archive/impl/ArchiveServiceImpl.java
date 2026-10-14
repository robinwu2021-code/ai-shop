package ai.neargo.shop.archive.impl;

import ai.neargo.shop.archive.ArchiveMapper;
import ai.neargo.shop.archive.ArchiveService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class ArchiveServiceImpl implements ArchiveService {

    /**
     * 实体 → (表名, 主键列)。
     *
     * <p><b>这张表是 {@link ArchiveMapper} 里那两个 {@code ${}} 的唯一来源</b> ——
     * 全是编译期常量，永远不接受请求参数。加新实体时也必须加在这里，
     * 不要让路径参数直接流到 SQL 里。
     */
    private static final Map<Kind, String[]> TABLES = Map.of(
            Kind.COUPON, new String[]{"mkt_coupon", "coupon_no"},
            Kind.MERCHANT, new String[]{"mch_entity", "entity_no"},
            Kind.PICKUP, new String[]{"cmt_pickup_point", "pickup_no"},
            Kind.CAMPAIGN, new String[]{"mkt_campaign", "campaign_no"},
            Kind.COMMUNITY, new String[]{"cmt_community", "community_no"},
            Kind.CONTENT_SLOT, new String[]{"mkt_content_slot", "slot_no"});

    private final ArchiveMapper archiveMapper;
    private final AuditLogPort auditLogPort;

    public ArchiveServiceImpl(ArchiveMapper archiveMapper, AuditLogPort auditLogPort) {
        this.archiveMapper = archiveMapper;
        this.auditLogPort = auditLogPort;
    }

    @Override
    @Transactional
    public long archive(Kind kind, String bizNo, String operatorNo) {
        String[] t = require(kind, bizNo);
        LocalDateTime at = LocalDateTime.now();
        /*
         * 幂等：已归档的再归档一次只是把时间戳刷新，不报错。
         * 运营连点两下不该看到「已经归档过了」——那句话对他没有任何意义，
         * 而他想要的结果（这东西从列表消失）已经达成了。
         */
        DataScopeContext.executeWithoutScope(() ->
                archiveMapper.markArchived(t[0], t[1], bizNo, at));
        auditLogPort.record(kind + "_ARCHIVE", bizNo, "归档");
        return at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Override
    @Transactional
    public void unarchive(Kind kind, String bizNo, String operatorNo) {
        String[] t = require(kind, bizNo);
        DataScopeContext.executeWithoutScope(() -> archiveMapper.clearArchived(t[0], t[1], bizNo));
        auditLogPort.record(kind + "_UNARCHIVE", bizNo, "恢复");
    }

    /**
     * 存在性检查。<b>归档一个不存在的东西必须报 404</b>，不能静默成功 ——
     * 静默成功的表现是「点了归档，列表刷新后它还在」，而运营会以为是缓存，
     * 再点几次，然后来报「归档功能坏了」。
     */
    private String[] require(Kind kind, String bizNo) {
        String[] t = TABLES.get(kind);
        if (t == null || bizNo == null || bizNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        int n = DataScopeContext.executeWithoutScope(() -> archiveMapper.exists(t[0], t[1], bizNo));
        if (n == 0) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return t;
    }
}
