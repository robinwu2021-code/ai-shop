package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchChannelArea;
import ai.neargo.shop.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.spi.user.SettlementRefPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 商家域这边指向聚落的三处引用，在合并时一起改写。
 *
 * <p>三处都决定「以后谁看得到什么」：经营范围（{@code mch_service_area}）、
 * 商家社区池（{@code mch_entity_community}，老模型仍在用）、
 * 渠道覆盖子集（{@code mch_channel_area}）。漏掉任何一处的后果都一样 ——
 * 合并之后商家的货在那个小区**悄悄消失**，而他的设置页看上去一切正常。
 */
@Component
public class MerchantSettlementRefPortImpl implements SettlementRefPort {

    private static final String AREA_COMMUNITY = "COMMUNITY";

    private final MerchantMappers.ServiceAreaMapper serviceAreaMapper;
    private final MerchantMappers.MchEntityCommunityMapper entityCommunityMapper;
    private final MerchantMappers.ChannelAreaMapper channelAreaMapper;

    public MerchantSettlementRefPortImpl(MerchantMappers.ServiceAreaMapper serviceAreaMapper,
                                         MerchantMappers.MchEntityCommunityMapper entityCommunityMapper,
                                         MerchantMappers.ChannelAreaMapper channelAreaMapper) {
        this.serviceAreaMapper = serviceAreaMapper;
        this.entityCommunityMapper = entityCommunityMapper;
        this.channelAreaMapper = channelAreaMapper;
    }

    @Override
    public int repointSettlement(String fromNo, String intoNo) {
        if (fromNo == null || intoNo == null || fromNo.isBlank() || fromNo.equals(intoNo)) {
            return 0;
        }
        return DataScopeContext.executeWithoutScope(() -> {
            int n = 0;
            n += repointServiceAreas(fromNo, intoNo);
            n += repointEntityCommunities(fromNo, intoNo);
            return n;
        });
    }

    /**
     * 经营范围。**同一个商家两条都勾着**是最常见的情况（他自己也分不清那是同一个小区），
     * 直接改 ref_code 会撞 entity+level+ref 的唯一键 —— 撞上的删掉即可，
     * 那两条本来说的就是同一件事。
     *
     * <p>删之前要把**渠道覆盖子集**（{@code mch_channel_area.area_no}）指过去：
     * 它引用的是覆盖项的 area_no，不是社区号。删掉一条覆盖项而不管它，
     * 那家店的「商家自送只送这几个小区」里会留下一个指向不存在覆盖项的子集 ——
     * 表现为该渠道的范围凭空少了一个小区，且没有任何报错。
     */
    private int repointServiceAreas(String fromNo, String intoNo) {
        List<MchServiceArea> rows = serviceAreaMapper.selectList(Wrappers.<MchServiceArea>lambdaQuery()
                .eq(MchServiceArea::getLevel, AREA_COMMUNITY)
                .eq(MchServiceArea::getRefCode, fromNo));
        if (rows.isEmpty()) {
            return 0;
        }
        java.util.Map<String, String> targetAreaNo = serviceAreaMapper
                .selectList(Wrappers.<MchServiceArea>lambdaQuery()
                        .eq(MchServiceArea::getLevel, AREA_COMMUNITY)
                        .eq(MchServiceArea::getRefCode, intoNo))
                .stream().collect(java.util.stream.Collectors.toMap(
                        MchServiceArea::getEntityNo, MchServiceArea::getAreaNo, (a, b) -> a));
        int n = 0;
        for (MchServiceArea r : rows) {
            String survivor = targetAreaNo.get(r.getEntityNo());
            if (survivor != null) {
                repointChannelAreas(r.getAreaNo(), survivor);
                serviceAreaMapper.hardDelete(r.getEntityNo(), AREA_COMMUNITY, fromNo);
            } else {
                r.setRefCode(intoNo);
                serviceAreaMapper.updateById(r);
                targetAreaNo.put(r.getEntityNo(), r.getAreaNo());
            }
            n++;
        }
        return n;
    }

    private int repointEntityCommunities(String fromNo, String intoNo) {
        List<MchEntityCommunity> rows = entityCommunityMapper
                .selectList(Wrappers.<MchEntityCommunity>lambdaQuery()
                        .eq(MchEntityCommunity::getCommunityNo, fromNo));
        if (rows.isEmpty()) {
            return 0;
        }
        Set<String> alreadyHasTarget = new HashSet<>(entityCommunityMapper
                .selectList(Wrappers.<MchEntityCommunity>lambdaQuery()
                        .eq(MchEntityCommunity::getCommunityNo, intoNo))
                .stream().map(MchEntityCommunity::getEntityNo).toList());
        int n = 0;
        for (MchEntityCommunity r : rows) {
            if (alreadyHasTarget.contains(r.getEntityNo())) {
                entityCommunityMapper.deleteById(r.getId());
            } else {
                r.setCommunityNo(intoNo);
                entityCommunityMapper.updateById(r);
                alreadyHasTarget.add(r.getEntityNo());
            }
            n++;
        }
        return n;
    }

    /** 渠道覆盖子集跟着覆盖项走：合并掉的那条 area_no 换成留下来的那条 */
    private void repointChannelAreas(String fromAreaNo, String intoAreaNo) {
        if (fromAreaNo == null || intoAreaNo == null || fromAreaNo.equals(intoAreaNo)) {
            return;
        }
        for (MchChannelArea r : channelAreaMapper.selectList(Wrappers.<MchChannelArea>lambdaQuery()
                .eq(MchChannelArea::getAreaNo, fromAreaNo))) {
            r.setAreaNo(intoAreaNo);
            channelAreaMapper.updateById(r);
        }
    }
}
