package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import ai.neargo.shop.food.entity.FnbBundle;
import ai.neargo.shop.food.entity.FnbBundleChoice;
import ai.neargo.shop.food.entity.FnbBundleGroup;
import ai.neargo.shop.food.entity.FnbConfigRevision;
import ai.neargo.shop.food.mapper.FoodMappers.BundleChoiceMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BundleGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BundleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/** Captures one coherent local working aggregate; original remote identity reads remain in the publication service. */
public final class BundlePublicationSource {
    private final BundleMapper heads;
    private final BundleGroupMapper groups;
    private final BundleChoiceMapper choices;
    private final BooleanSupplier enabled;

    public BundlePublicationSource(BundleMapper heads, BundleGroupMapper groups, BundleChoiceMapper choices,
            BooleanSupplier enabled) {
        this.heads = heads; this.groups = groups; this.choices = choices; this.enabled = enabled;
    }

    public record Captured(BundleConfigVO definition, List<String> skuNos) { }

    public Captured capture(String entity, String number, boolean lock) {
        if (!enabled.getAsBoolean()) throw BizException.of(ErrorCode.NOT_FOUND);
        var query = Wrappers.<FnbBundle>lambdaQuery().eq(FnbBundle::getEntityNo, entity)
                .eq(FnbBundle::getSkuNo, number);
        if (lock) query.last("FOR UPDATE");
        var head = heads.selectOne(query);
        if (head == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (head.getVersion() == null || head.getVersion() < 0 || head.getConfigVersion() == null
                || head.getConfigVersion() < 0
                || !("DRAFT".equals(head.getStatus()) || "ACTIVE".equals(head.getStatus()))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var children = groups.selectList(Wrappers.<FnbBundleGroup>lambdaQuery()
                .eq(FnbBundleGroup::getEntityNo, entity).eq(FnbBundleGroup::getBundleSkuNo, number)
                .orderByAsc(FnbBundleGroup::getSortNo, FnbBundleGroup::getGroupNo));
        var groupNos = children.stream().map(FnbBundleGroup::getGroupNo).toList();
        var options = groupNos.isEmpty() ? List.<FnbBundleChoice>of()
                : choices.selectList(Wrappers.<FnbBundleChoice>lambdaQuery()
                .eq(FnbBundleChoice::getEntityNo, entity).in(FnbBundleChoice::getGroupNo, groupNos)
                .orderByAsc(FnbBundleChoice::getGroupNo, FnbBundleChoice::getSortNo, FnbBundleChoice::getChoiceNo));
        var definition = BundlePublicationPolicy.validate(new ReplaceBundleDefinitionReq(head.getVersion(),
                children.stream().map(g -> new BundleGroupInput(g.getGroupNo(), g.getName(), g.getMinQty(),
                        g.getMaxQty(), g.getBaseSkuNo(), g.getSortNo())).toList(),
                options.stream().map(c -> new BundleChoiceInput(c.getChoiceNo(), c.getGroupNo(), c.getSkuNo(),
                        c.getUnitsPerPick(), c.getPriceMode(), c.getDefaultQty(), c.getMinQty(),
                        c.getMaxQty(), c.getSortNo())).toList()));
        var members = new TreeSet<String>();
        definition.choices().forEach(c -> members.add(c.skuNo()));
        definition.groups().forEach(g -> { if (g.baseSkuNo() != null) members.add(g.baseSkuNo()); });
        if (members.contains(number)) throw BizException.of(ErrorCode.BAD_REQUEST);
        var nested = Wrappers.<FnbBundle>lambdaQuery().eq(FnbBundle::getEntityNo, entity)
                .in(FnbBundle::getSkuNo, members).orderByAsc(FnbBundle::getSkuNo);
        if (lock) nested.last("FOR UPDATE");
        if (!heads.selectList(nested).isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        members.add(number);
        var view = new BundleConfigVO(number, head.getVersion(), head.getStatus(), new BundleConfigVO.Data(
                head.getConfigVersion(), head.getPublishedRevisionNo(), definition.groups(), definition.choices()));
        return new Captured(view, List.copyOf(members));
    }

    /** Publishing advances only the working CAS and management hint, never the definition counter. */
    public void advance(String entity, String number, long version, FnbConfigRevision revision) {
        var row = heads.selectOne(Wrappers.<FnbBundle>lambdaQuery().eq(FnbBundle::getEntityNo, entity)
                .eq(FnbBundle::getSkuNo, number));
        if (row == null) throw BizException.of(ErrorCode.CONFLICT);
        row.setVersion(version);
        row.setStatus("EFFECTIVE".equals(revision.getStatus()) ? "ACTIVE" : "DRAFT");
        if ("EFFECTIVE".equals(revision.getStatus())) row.setPublishedRevisionNo(revision.getRevisionNo());
        if (heads.updateById(row) != 1) throw BizException.of(ErrorCode.CONFLICT);
    }
}
