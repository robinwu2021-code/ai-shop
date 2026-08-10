package ai.neargo.shop.marketing.campaign.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.campaign.CampaignService;
import ai.neargo.shop.marketing.campaign.dto.CampaignVO;
import ai.neargo.shop.marketing.campaign.CampaignJson;
import ai.neargo.shop.marketing.campaign.entity.MktCampaign;
import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers.CouponMapper;
import ai.neargo.shop.marketing.campaign.mapper.CampaignMappers.CampaignMapper;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CampaignServiceImpl implements CampaignService {

    private static final String DRAFT = "DRAFT";
    private static final String RUNNING = "RUNNING";
    private static final String PAUSED = "PAUSED";
    private static final String ENDED = "ENDED";

    private static final Set<String> TYPES = Set.of("COUPON", "FULL_CUT", "FLASH", "BUY_GIFT");

    /**
     * 由活动号推导券号的前缀。`@` 保证它不会与 BizKey 生成的券号撞上，
     * 也让「这张券是从哪个活动来的」一眼可读。
     */
    private static final String COUPON_PREFIX = "CU@";

    private final CampaignMapper campaignMapper;
    private final CouponMapper couponMapper;
    /** 限时特价的多规格校验要知道商品有几个 SKU。marketing → product 走 Port */
    private final ai.neargo.shop.spi.product.GoodsQueryPort goodsPort;
    private final ObjectMapper json;

    public CampaignServiceImpl(CampaignMapper campaignMapper, CouponMapper couponMapper,
                               ai.neargo.shop.spi.product.GoodsQueryPort goodsPort, ObjectMapper json) {
        this.campaignMapper = campaignMapper;
        this.couponMapper = couponMapper;
        this.goodsPort = goodsPort;
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
        assertFlashSingleSku(cmd);

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
        syncCoupon(c);
        return toVO(c);
    }

    /**
     * 限时特价**暂不支持多规格商品**。
     *
     * <p>`mkt_campaign` 的模型是「几个商品 + 一个活动价」，没有 SKU 维度。
     * 对多规格商品套用会把所有规格拉到同一个价 —— 实测 10 斤装（¥49.80）与
     * 20 斤装（¥95.80）会一起变成 ¥30.00，**每卖一件大规格就亏一次**。
     *
     * <p>为什么选择在创建时拒绝，而不是「只对某个规格生效」：
     * 商家选的是「这个商品」，他心里想的是哪个规格没人知道，
     * 系统替他猜一个，猜错了是拿他的钱试错。拒绝至少让他立刻知道 ——
     * 而「悄悄不生效」和「悄悄按错价卖」都是他事后才会发现的。
     *
     * <p>正式解法是给活动加 SKU 维度（改表 + 改 B 端表单），需要业务先定
     * 「商品级特价」对多规格到底是什么语义。见 docs/technical/营销枚举对账报告.md §5。
     */
    private void assertFlashSingleSku(SaveCommand cmd) {
        if (!MktCampaign.FLASH.equals(cmd.type()) || cmd.goodsNos() == null || cmd.goodsNos().isEmpty()) {
            return;
        }
        Map<String, Integer> counts = goodsPort.skuCounts(cmd.goodsNos());
        boolean multi = cmd.goodsNos().stream().anyMatch(no -> counts.getOrDefault(no, 1) > 1);
        if (multi) {
            // 专用码：通用的「请求参数有误」会让商家反复改价格与时间，
            // 而问题在于「这件商品有两个规格，而活动价只有一个」
            throw BizException.of(ErrorCode.FLASH_MULTI_SKU_UNSUPPORTED);
        }
    }

    /**
     * COUPON 型活动 → 往 {@code mkt_coupon} 落一张**商家券**。
     *
     * <p><b>这是此前断掉的那半段</b>：商家在 B 端建「店铺券」活动，
     * `mkt_campaign` 存下来了，而领券中心读的是 `mkt_coupon` ——
     * 两张表之间没有任何桥接，于是**建了不会生成任何一张券，用户永远领不到**。
     * `MktCoupon` 本身早就支持商家券（有 {@code entityNo} 与 {@code funder=MERCHANT}），
     * 缺的只是这一次写入。
     *
     * <p><b>券号由活动号推导</b>（{@code CU@} + campaignNo）而不是新生成：
     * 重复保存要更新同一张券，不能每存一次就多发一张。库里没有
     * campaign→coupon 的外键列，用可推导的券号是不加迁移就能拿到幂等的做法；
     * 前缀里的 {@code @} 保证它不会与 {@link BizKey} 生成的券号撞上。
     *
     * <p>只处理 COUPON 型：满减走 {@code CampaignPort} 在下单时算，
     * 限时特价与买赠还没接（见 CampaignPortImpl 的说明）。
     */
    private void syncCoupon(MktCampaign c) {
        if (!MktCampaign.COUPON.equals(c.getType())) {
            return;
        }
        String couponNo = COUPON_PREFIX + c.getCampaignNo();
        DataScopeContext.executeWithoutScope(() -> {
            MktCoupon exist = couponMapper.selectOne(Wrappers.<MktCoupon>lambdaQuery()
                    .eq(MktCoupon::getCouponNo, couponNo));
            MktCoupon coupon = exist == null ? new MktCoupon() : exist;
            coupon.setCouponNo(couponNo);
            coupon.setTitle(c.getName());
            coupon.setType(MktCoupon.FULL_CUT);
            coupon.setFaceMinor(c.getDiscountMinor());
            coupon.setThresholdMinor(c.getThresholdMinor());
            // 商家自己建的券，钱由商家出 —— 这个字段决定 M7 分账扣谁
            coupon.setFunder(MktCoupon.BY_MERCHANT);
            coupon.setEntityNo(c.getEntityNo());
            coupon.setTotalCount(c.getTotalCount());
            if (exist == null) {
                coupon.setReceivedCount(0);
            }
            // 每人限领 1 张：商家侧还没有这个配置项，先给一个不会被滥用的默认值
            coupon.setPerUserLimit(1);
            coupon.setStartAt(c.getStartAt());
            coupon.setEndAt(c.getEndAt());
            /*
             * 活动状态 → 券状态。只有 RUNNING 的活动，券才在领券中心可见
             * （center() 筛 status=ACTIVE）。商家暂停活动，券立刻停发，
             * 但**已领的券不受影响** —— 那是用户已经拿到手的东西。
             */
            coupon.setStatus(MktCampaign.RUNNING.equals(c.getStatus()) ? "ACTIVE" : "PAUSED");
            if (exist == null) {
                couponMapper.insert(coupon);
            } else {
                couponMapper.updateById(coupon);
            }
            return null;
        });
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
        // 券要跟着停发：商家点了暂停，领券中心就不该再发；已领的不受影响
        syncCoupon(c);
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

    /**
     * 读参与商品。走 {@link CampaignJson} 而不是直接 readValue ——
     * H2 会把整个 JSON 数组再包一层字符串，直接解析会静默得到空列表，
     * 于是「参与商品」在测试环境永远是空的（本类此前就是这样）。
     */
    private List<String> readList(String raw) {
        return CampaignJson.readStringList(json, raw);
    }

    private String writeJson(List<String> v) {
        return v == null || v.isEmpty() ? null : json.writeValueAsString(v);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
