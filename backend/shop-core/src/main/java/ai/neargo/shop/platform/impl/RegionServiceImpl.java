package ai.neargo.shop.platform.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.RegionService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.platform.entity.SysRegion;
import ai.neargo.shop.platform.mapper.PlatformMappers.RegionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link RegionService} 实现。 */
@Service
public class RegionServiceImpl implements RegionService {

    /** 回溯深度上限。四级树最多走 4 步，给 8 是为了让**坏数据不会变成死循环** */
    private static final int MAX_DEPTH = 8;

    private final RegionMapper mapper;
    private final ObjectProvider<MerchantQueryPort> merchantPort;

    /**
     * @param merchantPort 只用来把 entity_no 换成商家名给运营看。
     *                     <b>可选注入</b>（{@code @Autowired(required=false)} 语义靠
     *                     ObjectProvider）—— ops 部署里这个端口在，api 部署里也在，
     *                     但区划查询本身不该因为它缺席就起不来
     */
    public RegionServiceImpl(RegionMapper mapper,
                             ObjectProvider<MerchantQueryPort> merchantPort) {
        this.mapper = mapper;
        this.merchantPort = merchantPort;
    }

    @Override
    public List<RegionVO> children(String parentCode, boolean enabledOnly) {
        return children(parentCode, enabledOnly, null);
    }

    @Override
    public List<RegionVO> children(String parentCode, boolean enabledOnly, String entityNo) {
        String owner = entityNo == null || entityNo.isBlank() ? null : entityNo;
        List<SysRegion> rows = DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<SysRegion>lambdaQuery()
                        .eq(enabledOnly, SysRegion::getEnabled, true)
                        // 顶层是 parent_code IS NULL，不是空串 —— 见实体上的说明
                        .isNull(parentCode == null || parentCode.isBlank(), SysRegion::getParentCode)
                        .eq(parentCode != null && !parentCode.isBlank(),
                                SysRegion::getParentCode, parentCode)
                        /*
                         * 可见范围：**已通过的 + 我自己提报的（含被驳回的）**。
                         *
                         * 判据是 audit_status 而不是 owner_entity_no ——
                         * 后者现在只记「谁报的」，通过之后也保留，用它判可见性
                         * 会让通过后的补录反而只有提报方看得到。
                         *
                         * 被驳回的也给提报方看：连同理由。看不到的话那个村在他那里
                         * 凭空消失，他不知道为什么，多半原样再录一遍。
                         */
                        .and(q -> {
                            q.eq(SysRegion::getAuditStatus, SysRegion.APPROVED);
                            if (owner != null) {
                                q.or(o -> o.eq(SysRegion::getOwnerEntityNo, owner));
                            }
                        })
                        .orderByAsc(SysRegion::getRegionCode)));
        return toVOs(rows);
    }

    @Override
    public List<PendingVO> pendingVillages(String status) {
        String st = status == null || status.isBlank() ? SysRegion.PENDING : status;
        List<SysRegion> rows = DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<SysRegion>lambdaQuery()
                        .eq(SysRegion::getSource, SysRegion.SOURCE_MERCHANT)
                        .eq(SysRegion::getAuditStatus, st)
                        .orderByDesc(SysRegion::getId)));
        return rows.stream().map(r -> {
            /*
             * 整条路径必须给：光一个「新桥社区」全国有好几个 ——
             * 运营看不到「浙江省 / 杭州市 / 西湖区 / 西溪街道」就判断不了真假，
             * 只能靠猜或者去库里查，而那时他多半直接通过了。
             */
            String path = path(r.getRegionCode()).stream()
                    .map(RegionVO::name).reduce((a, b) -> a + " / " + b).orElse(r.getName());
            String entityName = merchantPort.getIfAvailable() == null ? null
                    : merchantPort.getIfAvailable().find(r.getOwnerEntityNo())
                            .map(MerchantQueryPort.MerchantBrief::merchantName).orElse(null);
            return new PendingVO(r.getRegionCode(), r.getName(), path, r.getAuditStatus(),
                    r.getOwnerEntityNo(),
                    entityName == null ? r.getOwnerEntityNo() : entityName,
                    r.getRejectReason(),
                    r.getCreatedAt() == null ? 0L
                            : r.getCreatedAt().atZone(java.time.ZoneId.systemDefault())
                                    .toInstant().toEpochMilli());
        }).toList();
    }

    @Override
    @Transactional
    public void confirmVillage(String regionCode, boolean pass, String reason, String operatorNo) {
        SysRegion row = find(regionCode);
        if (row == null || !SysRegion.SOURCE_MERCHANT.equals(row.getSource())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 驳回必须写原因：它原样回给商家，不写的话他只会原样再提一次
        if (!pass && (reason == null || reason.isBlank())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        row.setAuditStatus(pass ? SysRegion.APPROVED : SysRegion.REJECTED);
        row.setRejectReason(pass ? null : reason.trim());
        row.setUpdatedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(row));
    }


    @Override
    public List<RegionVO> path(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            return List.of();
        }
        List<SysRegion> chain = new ArrayList<>();
        String code = regionCode;
        for (int i = 0; i < MAX_DEPTH && code != null && !code.isBlank(); i++) {
            SysRegion row = find(code);
            if (row == null) {
                /*
                 * 链断了就返回**已经走到的部分**，不抛异常也不返回空。
                 *
                 * 存量数据里会有已撤并的区划码（区划每年调整，而这份数据停在 2023）。
                 * 抛异常的话，一个早年归属的社区会让整个运营页打不开；
                 * 返回空的话，界面显示「未归属」而它其实归属过 —— 两个都比
                 * 「显示到能显示的那一级」更糟。
                 */
                break;
            }
            chain.add(row);
            code = row.getParentCode();
        }
        Collections.reverse(chain);   // 从省到自身
        return toVOs(chain);
    }

    private SysRegion find(String code) {
        return DataScopeContext.executeWithoutScope(() ->
                mapper.selectOne(Wrappers.<SysRegion>lambdaQuery()
                        .eq(SysRegion::getRegionCode, code).last("LIMIT 1")));
    }

    /**
     * 一次性查出「哪些码还有下级」，而不是每行各查一次。
     *
     * <p>省级只有 31 行时逐行查看不出问题，而街道一层单个区就能有几十条 ——
     * 那就是几十次往返。列表页的 N+1 一向如此：小数据集上永远发现不了。
     */
    private List<RegionVO> toVOs(List<SysRegion> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> codes = rows.stream().map(SysRegion::getRegionCode).toList();
        Set<String> withChild = DataScopeContext.executeWithoutScope(() ->
                        mapper.selectList(Wrappers.<SysRegion>lambdaQuery()
                                .select(SysRegion::getParentCode)
                                .in(SysRegion::getParentCode, codes)
                                .groupBy(SysRegion::getParentCode))).stream()
                .map(SysRegion::getParentCode).collect(Collectors.toSet());

        return rows.stream().map(r -> new RegionVO(
                r.getRegionCode(), r.getParentCode(), r.getLevel(), r.getName(),
                Boolean.TRUE.equals(r.getEnabled()),
                withChild.contains(r.getRegionCode()),
                r.getSource() == null ? "OFFICIAL" : r.getSource(),
                !SysRegion.APPROVED.equals(r.getAuditStatus()),
                r.getAuditStatus(), r.getRejectReason())).toList();
    }
}
