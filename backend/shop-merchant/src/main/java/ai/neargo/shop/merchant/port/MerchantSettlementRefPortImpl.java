package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.spi.user.SettlementRefPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;

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

    public MerchantSettlementRefPortImpl(MerchantMappers.ServiceAreaMapper serviceAreaMapper) {
        this.serviceAreaMapper = serviceAreaMapper;
    }

    @Override
    public int repointSettlement(String fromNo, String intoNo) {
        if (fromNo == null || intoNo == null || fromNo.isBlank() || fromNo.equals(intoNo)) {
            return 0;
        }
        return DataScopeContext.executeWithoutScope(() -> {
            int n = 0;
            // 覆盖只在门店上（主体层的小区覆盖已退役）；各方式的收窄拷贝也是范围行，同一条路一起改
            n += repointServiceAreas(fromNo, intoNo);
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
    /** 唯一键的「主体 + 门店」那一截（V381）。门店为空的孤行单独成一组 */
    private static final class MchServiceAreaKey {
        static String of(MchServiceArea a) {
            return a.getEntityNo() + "|" + (a.getStoreNo() == null ? "" : a.getStoreNo())
                    + "|" + (a.getChannel() == null ? "" : a.getChannel());
        }
    }

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
                        MchServiceAreaKey::of, MchServiceArea::getAreaNo, (a, b) -> a));
        int n = 0;
        for (MchServiceArea r : rows) {
            // 去重维度跟着唯一键走：门店 + 方式（channel）+ 层级 + 对象
            String key = MchServiceAreaKey.of(r);
            String survivor = targetAreaNo.get(key);
            if (survivor != null) {
                serviceAreaMapper.hardDeleteById(r.getId());
            } else {
                r.setRefCode(intoNo);
                serviceAreaMapper.updateById(r);
                targetAreaNo.put(key, r.getAreaNo());
            }
            n++;
        }
        return n;
    }
}
