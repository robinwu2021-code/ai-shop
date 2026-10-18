package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.promotion.service.ActivityGovernanceService;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import java.util.Set;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.ActivityView;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.UnifiedActivityManagementMapper;
import ai.neargo.shop.spi.platform.AuditLogPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Operations reads and locks retain the existing pmt_activity merchant data-scope anchor. */
@Service
public class ActivityGovernanceServiceImpl implements ActivityGovernanceService {
    private final ActivityMapper heads;
    private final UnifiedActivityManagementMapper writes;
    private final ActivityCommandDeduplicator commands;
    private final AuditLogPort audit;
    private final ObjectMapper json;
    public ActivityGovernanceServiceImpl(ActivityMapper heads, UnifiedActivityManagementMapper writes,
            ActivityCommandDeduplicator commands, AuditLogPort audit, ObjectMapper json) {
        this.heads = heads; this.writes = writes; this.commands = commands; this.audit = audit; this.json = json;
    }

    public PageData<GovernanceView> list(String tenant, String entity, String status, long page, long size) {
        if (page < 1 || size < 1 || size > 100 || page > Integer.MAX_VALUE / size
                || status != null && !Set.of("DRAFT", "RUNNING", "PAUSED", "ENDED").contains(status)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var rows = heads.selectList(Wrappers.<PmtActivity>lambdaQuery()
                .eq(PmtActivity::getTenantNo, tenant).eq(entity != null, PmtActivity::getEntityNo, entity)
                .eq(status != null, PmtActivity::getStatus, status).isNotNull(PmtActivity::getDraftSnapshot)
                .isNull(PmtActivity::getArchivedAt).orderByDesc(PmtActivity::getId));
        int from = (int) Math.min((page - 1) * size, rows.size());
        int to = (int) Math.min(from + size, rows.size());
        return PageData.of(rows.subList(from, to).stream().map(this::view).toList(), rows.size(), page, size);
    }

    public GovernanceView get(String tenant, String activity) { return view(scoped(tenant, activity, false)); }

    @Transactional("transactionManager")
    public GovernanceView stop(String tenant, String actorNo, String activity, long revision,
                               String reason, String key) {
        if (reason == null || reason.trim().length() < 4 || reason.length() > 500) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // Never infer authorization from having seen an ID in a previous list response.
        var candidate = scoped(tenant, activity, false);
        var actor = new ActivityActor(tenant, candidate.getEntityNo(), actorNo, null);
        var payload = List.of(activity, revision, reason);
        var replay = commands.replay(actor, "ACTIVITY_OPS_STOP", key, payload, GovernanceView.class);
        if (replay.isPresent()) return replay.get();
        return commands.execute(actor, "ACTIVITY_OPS_STOP", key, payload, activity, GovernanceView.class, () -> {
            var row = scoped(tenant, activity, true);
            if (!Long.valueOf(revision).equals(row.getVersion()) || "ENDED".equals(row.getStatus())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            row.setStatus("ENDED"); row.setEndedReason("MANUAL");
            row.setUpdatedBy(actorNo); row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            if (DataScopeContext.executeWithoutScope(() -> writes.update(row)) != 1) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            row.setVersion(row.getVersion() + 1);
            audit.record("ACTIVITY_FORCE_STOP", activity, "平台强制停止，原因：" + reason.trim());
            return view(row);
        });
    }

    private PmtActivity scoped(String tenant, String activity, boolean lock) {
        var row = heads.selectOne(Wrappers.<PmtActivity>lambdaQuery().eq(PmtActivity::getTenantNo, tenant)
                .eq(PmtActivity::getActivityNo, activity).isNotNull(PmtActivity::getDraftSnapshot)
                .isNull(PmtActivity::getArchivedAt).last(lock ? "FOR UPDATE" : ""));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }
    private GovernanceView view(PmtActivity row) {
        var definition = json.readValue(row.getDraftSnapshot(), UnifiedActivityDefinition.class);
        return new GovernanceView(row.getEntityNo(), new ActivityView(row.getActivityNo(), row.getVersion(),
                row.getStatus(), row.getCurrentVersionNo(), definition));
    }
}
