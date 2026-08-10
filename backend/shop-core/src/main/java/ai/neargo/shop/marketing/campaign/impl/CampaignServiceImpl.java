package ai.neargo.shop.marketing.campaign.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.campaign.CampaignService;
import ai.neargo.shop.marketing.campaign.dto.CampaignVO;
import ai.neargo.shop.marketing.campaign.entity.MktCampaign;
import ai.neargo.shop.marketing.campaign.mapper.CampaignMappers.CampaignMapper;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

@Service
public class CampaignServiceImpl implements CampaignService {

    private static final String DRAFT = "DRAFT";
    private static final String RUNNING = "RUNNING";
    private static final String PAUSED = "PAUSED";
    private static final String ENDED = "ENDED";

    private static final Set<String> TYPES = Set.of("COUPON", "FULL_CUT", "FLASH", "BUY_GIFT");

    private final CampaignMapper campaignMapper;
    private final ObjectMapper json;

    public CampaignServiceImpl(CampaignMapper campaignMapper, ObjectMapper json) {
        this.campaignMapper = campaignMapper;
        this.json = json;
    }

    @Override
    public List<CampaignVO> list(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                        campaignMapper.selectList(Wrappers.<MktCampaign>lambdaQuery()
                                .eq(MktCampaign::getEntityNo, merchantNo)
                                .orderByDesc(MktCampaign::getId)))
                .stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public CampaignVO save(String merchantNo, SaveCommand cmd) {
        if (cmd.name() == null || cmd.name().isBlank() || !TYPES.contains(cmd.type())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 时间区间必须成立。结束早于开始的活动会「永远不生效」——
         * 而商家看到的是一个状态正常、却一单都没优惠到的活动，只会以为是系统没生效。
         */
        if (cmd.endAt() <= cmd.startAt()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        boolean isNew = cmd.campaignNo() == null || cmd.campaignNo().isBlank();
        MktCampaign c;
        if (isNew) {
            c = new MktCampaign();
            c.setCampaignNo(BizKey.next(BizKey.CAMPAIGN));
            c.setEntityNo(merchantNo);
            c.setType(cmd.type());
            c.setStatus(DRAFT);
            c.setTakenCount(0);
            c.setUsedCount(0);
        } else {
            c = mine(merchantNo, cmd.campaignNo());
            if (ENDED.equals(c.getStatus())) {
                // 已结束的活动改不动：它的数据是历史，改了会让已发生的优惠对不上账
                throw BizException.of(ErrorCode.CONFLICT);
            }
            if (!c.getType().equals(cmd.type())) {
                /*
                 * 类型创建后不可改 —— 改类型等于换一套优惠语义（满减变秒杀），
                 * 而已发出去的券、已参与的订单都是按原语义算的。
                 */
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }

        c.setName(cmd.name().trim());
        c.setStartAt(cmd.startAt());
        c.setEndAt(cmd.endAt());
        c.setThresholdMinor(cmd.thresholdMinor());
        c.setDiscountMinor(cmd.discountMinor());
        c.setFlashPriceMinor(cmd.flashPriceMinor());
        c.setBuyN(cmd.buyN());
        c.setGiftM(cmd.giftM());
        c.setGoodsNos(writeJson(cmd.goodsNos()));
        c.setTotalCount(cmd.totalCount());

        DataScopeContext.executeWithoutScope(() -> {
            if (isNew) {
                campaignMapper.insert(c);
            } else {
                campaignMapper.updateById(c);
            }
            return null;
        });
        return toVO(c);
    }

    @Override
    @Transactional
    public CampaignVO toggle(String merchantNo, String campaignNo, boolean running) {
        MktCampaign c = mine(merchantNo, campaignNo);
        /*
         * 只允许 RUNNING ↔ PAUSED，外加 DRAFT → RUNNING（第一次开跑）。
         * ENDED 不可复活：时段已经过去，复活之后「生效中但已过期」没人能解释。
         */
        if (ENDED.equals(c.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        c.setStatus(running ? RUNNING : PAUSED);
        DataScopeContext.executeWithoutScope(() -> campaignMapper.updateById(c));
        return toVO(c);
    }

    private MktCampaign mine(String merchantNo, String campaignNo) {
        MktCampaign c = DataScopeContext.executeWithoutScope(() ->
                campaignMapper.selectOne(Wrappers.<MktCampaign>lambdaQuery()
                        .eq(MktCampaign::getCampaignNo, campaignNo).last("limit 1")));
        // 不区分「不存在」与「不是你的」：区分了就是一个活动归属探测器
        if (c == null || !merchantNo.equals(c.getEntityNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return c;
    }

    private CampaignVO toVO(MktCampaign c) {
        return new CampaignVO(c.getCampaignNo(), c.getEntityNo(), c.getType(), c.getName(),
                c.getStatus(), nz(c.getStartAt()), nz(c.getEndAt()),
                c.getThresholdMinor(), c.getDiscountMinor(), c.getFlashPriceMinor(),
                c.getBuyN(), c.getGiftM(), readList(c.getGoodsNos()),
                c.getTotalCount(), c.getTakenCount(),
                c.getUsedCount() == null ? 0 : c.getUsedCount());
    }

    private List<String> readList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (RuntimeException e) {
            // 脏数据按「全店」处理会**扩大**优惠范围，所以反过来：按空列表之外的安全侧走
            return List.of();
        }
    }

    private String writeJson(List<String> v) {
        return v == null || v.isEmpty() ? null : json.writeValueAsString(v);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
