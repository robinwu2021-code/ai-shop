package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Masks;
import ai.neargo.shop.spi.pay.PayApplymentGateway;
import ai.neargo.shop.spi.pay.PayChannelMasterPort;
import ai.neargo.shop.spi.platform.MasterDataPort;
import ai.neargo.shop.merchant.dto.PaymentApplymentVO;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchPaymentMapper;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.service.MerchantPaymentService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 收款进件的推进链路：占位 → 补资料 → 提交 → 回执 → 能收钱。
 *
 * <p>此前只有「占位」这一步：主体激活时建一条 APPLYING 的记录就结束了，
 * 没有任何代码能把它推到 ACTIVE。表现是整条链路能跑到下单，
 * 而**收款方是个占位记录** —— 真钱进来分不出去。
 */
@Service
public class MerchantPaymentServiceImpl implements MerchantPaymentService {

    private static final Logger log = LoggerFactory.getLogger(MerchantPaymentServiceImpl.class);

    private final MchPaymentMapper paymentMapper;
    private final MchEntityMapper merchantMapper;
    private final MasterDataPort masterDataPort;
    /** 通道主数据。2026-09-01 从 MasterDataPort 拆出来 —— 通道属性归 pay（见 ADR-023 那条原则） */
    private final PayChannelMasterPort payChannelMasterPort;
    private final tools.jackson.databind.ObjectMapper json;
    /** 每通道一个实现；开发期是 STUB 一个顶俩 */
    private final Map<String, PayApplymentGateway> gateways;
    /** 为门店开进件时校验归属 —— 不是本主体的店一律 404 */
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;

    public MerchantPaymentServiceImpl(MchPaymentMapper paymentMapper, MchEntityMapper merchantMapper,
                                      MasterDataPort masterDataPort,
                                      PayChannelMasterPort payChannelMasterPort,
                                      tools.jackson.databind.ObjectMapper json,
                                      ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper,
                                      List<PayApplymentGateway> gatewayList) {
        this.paymentMapper = paymentMapper;
        this.merchantMapper = merchantMapper;
        this.masterDataPort = masterDataPort;
        this.payChannelMasterPort = payChannelMasterPort;
        this.json = json;
        this.storeMapper = storeMapper;
        this.gateways = gatewayList.stream()
                .collect(Collectors.toMap(PayApplymentGateway::payChannel, Function.identity()));
    }

    @Override
    public List<PaymentApplymentVO> list(String merchantNo) {
        List<MchPaymentMerchant> rows = rows(merchantNo);
        if (rows.isEmpty()) {
            /*
             * **一条都没有时给一条「还没开始」的占位。**
             *
             * 进件记录本该在入驻通过时建（{@code MerchantPortImpl.ensurePayment}），
             * 但走别的路进来的主体（迁移灌的种子、历史数据）没有这一行。
             * 而端上那一页的表单是 `v-if="current"` —— 于是：
             * 工作台红字写着「收款进件没走完 · 去处理」，点进去<b>整页只有一句话</b>，
             * 没有任何可填的东西。**指着一条死路的提示比没有提示更糟。**
             *
             * 占位不落库：真正建行在 {@link #submit} 那一刻（那里才知道他填了什么）。
             */
            return List.of(placeholder(merchantNo));
        }
        return rows.stream().map(this::toVO).toList();
    }

    /**
     * 本主体能开的全部通道，含没开过的。
     *
     * <p>没开过的给一条 {@code NONE} 的占位而不是不返回 —— 页面按同一套状态机渲染，
     * 不用为「没有这一行」另写一支。只看**主体级**那一行（storeNo 空）：
     * 分店的收款行是另一件事，混进来会让「我能开什么」这一问变成一张长表。
     */
    @Override
    public List<PaymentApplymentVO> availableChannels(String merchantNo) {
        java.util.Map<String, MchPaymentMerchant> opened = rows(merchantNo).stream()
                .filter(r -> r.getStoreNo() == null || r.getStoreNo().isEmpty())
                .collect(java.util.stream.Collectors.toMap(MchPaymentMerchant::getPayChannel,
                        r -> r, (a, b) -> a, java.util.LinkedHashMap::new));
        /*
         * **按本商家的市场筛**（V288）。此前传的是 null —— 一律按默认市场算，
         * 于是台湾商家与大陆商家看到同一份渠道列表，
         * 点进去进件必然被通道拒，而拒的理由是一串英文码。
         *
         * 市场取不到时仍传 null（等于按默认市场），不抛错：
         * 存量主体还没有这个字段的值，而「看不到渠道」比「看到多余的渠道」更糟。
         */
        return payChannelMasterPort.enabledChannels(marketOf(merchantNo)).stream()
                .map(ch -> opened.containsKey(ch) ? toVO(opened.get(ch)) : placeholder(merchantNo, ch))
                .toList();
    }

    /** 「还没进件」的占位：状态 NONE，缺什么按主体算 —— 与提交时同一套判断。 */
    private PaymentApplymentVO placeholder(String merchantNo) {
        return placeholder(merchantNo, resolveChannel(merchantNo, null));
    }

    private PaymentApplymentVO placeholder(String merchantNo, String payChannel) {
        MchPaymentMerchant virtual = new MchPaymentMerchant();
        virtual.setEntityNo(merchantNo);
        virtual.setPayChannel(payChannel);
        virtual.setLegalForm(legalFormOf(merchantNo));
        virtual.setApplyStatus(MchPaymentMerchant.NONE);
        return new PaymentApplymentVO(virtual.getPayChannel(),
                payChannelMasterPort.channelName(virtual.getPayChannel()),
                MchPaymentMerchant.NONE, false, null, null, null, null, null,
                missingOf(virtual, null, null, null), false, null, null, "");
    }

    /** 主体 → 通道进件主体。与建商家、入驻校验走同一份主数据（ADR-002 §4） */
    private String legalFormOf(String merchantNo) {
        var m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchEntity>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchEntity::getEntityNo, merchantNo)
                        .last("limit 1")));
        // 主体上存的就是 legalForm（PERSONAL/INDIVIDUAL/COMPANY），再过一遍主数据归一
        String canonical = m == null ? null : masterDataPort.canonicalSubject(m.getLegalForm());
        return canonical != null ? canonical : MchPaymentMerchant.INDIVIDUAL;
    }

    @Override
    @Transactional
    public PaymentApplymentVO submit(String merchantNo, SubmitCommand cmd) {
        MchPaymentMerchant row = requireOrOpen(merchantNo, cmd.payChannel(), cmd.storeNo());

        /*
         * 已经开好的户不许重复提交。
         *
         * 通道侧重复进件会得到一个**新的二级商户号**，而历史订单的分账仍指向旧号 ——
         * 那是对不上账的开始，且发现时已经过了好几个账期。
         */
        if (MchPaymentMerchant.ACTIVE.equals(row.getApplyStatus())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        MchEntity entity = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (entity == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }

        String settleType = cmd.settleAccountType() != null && !cmd.settleAccountType().isBlank()
                ? cmd.settleAccountType()
                : masterDataPort.settleAccountType(row.getLegalForm());

        // 资料不齐就别往通道发：通道拒一次要等一个工作日，而缺什么我们自己就能看出来
        List<String> missing = missingOf(row, settleType, cmd.settleAccount(), cmd.licenses());
        if (!missing.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        PayApplymentGateway gateway = gateway(cmd.payChannel());
        String channelApplyNo = gateway.submit(new PayApplymentGateway.SubmitCommand(
                merchantNo, entity.getName(), row.getLegalForm(),
                cmd.contactName(), cmd.contactPhone(), cmd.licenses(),
                settleType, cmd.settleAccount()));

        row.setChannelApplyNo(channelApplyNo);
        row.setSettleAccountType(settleType);
        /*
         * **只存掩码**。明文账号在 gateway.submit 里用完就没了 ——
         * 落库的话，一次库泄露就是一批人的银行账号（ADR-002 §5）。
         */
        row.setSettleAccountMasked(mask(cmd.settleAccount()));
        row.setApplyStatus(MchPaymentMerchant.APPLYING);
        // 重提时清掉上一次的拒因：留着的话商家改完资料还看见旧原因，会以为没提交成功
        row.setRejectReason(null);
        row.setAppliedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(row));

        // 提交完立刻回查一次：stub 与部分通道是同步出结果的，让商家少等一轮
        return refresh(merchantNo, cmd.payChannel(), cmd.storeNo());
    }

    @Override
    @Transactional
    public PaymentApplymentVO openForStore(String merchantNo, String storeNo, String payChannel) {
        if (storeNo == null || storeNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 越权保护：不是本主体的门店一律 404，与门店接口同一条口径 ——
        // 403 等于确认这个门店号存在
        MchStore store = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, merchantNo)
                        .eq(MchStore::getStoreNo, storeNo)
                        .last("limit 1")));
        if (store == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        String channel = resolveChannel(merchantNo, payChannel);
        boolean exists = rows(merchantNo).stream()
                .anyMatch(r -> channel.equals(r.getPayChannel())
                        && storeNo.equals(r.getStoreNo()));
        if (exists) {
            // 重复开户在通道侧会得到一个新的二级商户号，而历史订单的分账仍指向旧号
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        // 法律形态与结算账户形态跟主体走 —— 分店不是独立法人，
        // 执照仍是主体那张。分开的只是收款账户，不是合规主体
        MchPaymentMerchant base = rows(merchantNo).stream()
                .filter(r -> channel.equals(r.getPayChannel()))
                .findFirst().orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));

        MchPaymentMerchant p = new MchPaymentMerchant();
        p.setEntityNo(merchantNo);
        p.setStoreNo(storeNo);
        p.setPayChannel(channel);
        p.setLegalForm(base.getLegalForm());
        p.setSettleAccountType(base.getSettleAccountType());
        p.setApplyStatus(MchPaymentMerchant.APPLYING);
        p.setAppliedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> paymentMapper.insert(p));
        return toVO(p);
    }

    @Override
    @Transactional
    public PaymentApplymentVO refresh(String merchantNo, String payChannel, String storeNo) {
        MchPaymentMerchant row = require(merchantNo, payChannel, storeNo);
        if (row.getChannelApplyNo() == null || row.getChannelApplyNo().isBlank()) {
            // 还没提交过，没什么可查的 —— 不要去问通道一个不存在的单号
            return toVO(row);
        }

        PayApplymentGateway.ApplymentResult r = gateway(payChannel).query(row.getChannelApplyNo());
        row.setApplyStatus(r.status());
        if (MchPaymentMerchant.ACTIVE.equals(r.status())) {
            row.setSubMchid(r.subMchid());
            /*
             * 开户成功才生成收款商户号业务键，且**只生成一次** ——
             * 通道重推回执是常态，每次都换一个号的话，门店挂的收款号会指向一个不存在的行。
             */
            if (row.getPayMerchantNo() == null || row.getPayMerchantNo().isBlank()) {
                row.setPayMerchantNo(BizKey.next(BizKey.PAY_MERCHANT));
            }
            if (row.getActivatedAt() == null) {
                row.setActivatedAt(System.currentTimeMillis());
            }
            /*
             * 开户成功才落支付方式清单 —— 而在此之前<b>这一列从没被写过</b>。
             *
             * 它有读取方：结算页与收银台都按它求交集（一笔支付覆盖整单，
             * 有一家不支持这种方式就用不了），而那段交集写得很仔细 ——
             * 空集当「未配置」跳过、未配置返回 null 不返回空数组。
             * <b>只是它的输入永远是空的</b>，于是那条判据从来没有真正生效过。
             *
             * 来源是通道自己支持的那一份：商家能用的方式是通道支持的子集，
             * 通道回执没有明确缩窄时两者相等。将来通道回执带了范围，
             * 改的是这一行的来源，不是下游任何一处。
             *
             * **只在为空时写**：通道重推回执是常态，每次覆盖会把运营
             * 后来收窄过的范围又放回去 —— 与上面 payMerchantNo「只生成一次」同一条理由。
             */
            if (row.getPayMethods() == null || row.getPayMethods().isBlank()) {
                List<String> ms = payChannelMasterPort.payMethodsOf(payChannel);
                if (!ms.isEmpty()) {
                    row.setPayMethods(json.writeValueAsString(ms));
                }
            }
            /*
             * ⚠️ **不能靠 setRejectReason(null) + updateById 来清这一列。**
             *
             * MyBatis-Plus 默认跳过 null 字段，那句 set 一行 SQL 都不会生成 ——
             * 于是商家改完重提、通道批了之后，页面上「可以收款」下面
             * 还挂着一句「结算账户名对不上」，他会以为自己还没过。
             *
             * 这个坑仓库里已经踩过一次（PayChannelDefaultResolveTest 的注释里写着），
             * 而这里又踩了一次 —— <b>是消融把它逼出来的</b>：
             * 用例第一版的夹具本来就没有旧原因，清不清都一样绿。
             *
             * 显式 lambdaUpdate 置空，与 updateById 分开走。
             */
            row.setRejectReason(null);
            clearRejectReason(row);
        } else if (MchPaymentMerchant.REJECTED.equals(r.status())) {
            /*
             * **「驳回必须带原因」此前只写在契约注释里，没有任何东西兑现它。**
             *
             * 通道给空的话这一列就是 null，而 b 端那句原因是 v-if 渲染的 ——
             * 商家看到的是一个「已驳回」标签，下面<b>什么都没有</b>：
             * 不知道哪儿不对，也就只能把同一份资料再提一遍。
             * 而通道侧重复进件会产生新的二级商户号，历史订单的分账仍指向旧号 ——
             * 那是对不上账的开始。
             *
             * 兜底文案<b>不编原因</b>：如实说通道没给，并明确告诉他别再重提。
             * 编一个「资料不齐」之类的猜测更糟 —— 他会照着改一个本来没错的地方。
             */
            String reason = r.rejectReason();
            if (reason == null || reason.isBlank()) {
                log.warn("[applyment] 通道 {} 驳回 {} 但没给原因 —— 契约要求驳回必带原因",
                        payChannel, merchantNo);
                reason = "通道未说明驳回原因。请联系平台客服协助查询，"
                        + "**不要重复提交同一份资料** —— 重复进件会产生新的收款商户号，"
                        + "而历史订单的分账仍指向旧号。";
            }
            row.setRejectReason(reason);
        }
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(row));
        return toVO(row);
    }

    /** 显式把驳回原因置空 —— updateById 会跳过 null，见调用点的说明 */
    private void clearRejectReason(MchPaymentMerchant row) {
        DataScopeContext.executeWithoutScope(() -> paymentMapper.update(null,
                Wrappers.<MchPaymentMerchant>lambdaUpdate()
                        .eq(MchPaymentMerchant::getId, row.getId())
                        .set(MchPaymentMerchant::getRejectReason, null)));
    }

    @Override
    public PollResult pollApplying(int limit, long staleAfter) {
        List<MchPaymentMerchant> applying = DataScopeContext.executeWithoutScope(() ->
                paymentMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<MchPaymentMerchant>lambdaQuery()
                        .eq(MchPaymentMerchant::getApplyStatus, MchPaymentMerchant.APPLYING)
                        .isNotNull(MchPaymentMerchant::getChannelApplyNo)
                        // 最早提交的先查：卡最久的那一单最需要有人知道
                        .orderByAsc(MchPaymentMerchant::getAppliedAt)
                        .last("LIMIT " + Math.max(1, limit))));
        long now = System.currentTimeMillis();
        int settled = 0;
        int failed = 0;
        int stale = 0;
        for (MchPaymentMerchant row : applying) {
            if (row.getChannelApplyNo() == null || row.getChannelApplyNo().isBlank()) {
                continue;
            }
            try {
                /*
                 * 复用 refresh 而不是把那段逻辑抄一遍：开户成功要生成收款号、
                 * 只生成一次、要落 activatedAt —— 抄一遍迟早两边分岔，
                 * 而分岔的表现是「手动刷新能开通、自动轮询开不通」。
                 */
                PaymentApplymentVO vo = refresh(row.getEntityNo(), row.getPayChannel(),
                        row.getStoreNo());
                if (!MchPaymentMerchant.APPLYING.equals(vo.applyStatus())) {
                    settled++;
                    continue;
                }
            } catch (RuntimeException e) {
                /*
                 * 单条失败不影响其余，但**要计数**：一条查不动是偶发，
                 * 一批查不动是通道或凭据出了问题，而只 log 不计数的话没人会发现。
                 */
                failed++;
                log.warn("[applyment-poll] 查进件失败 {} / {}：{}", row.getEntityNo(),
                        row.getPayChannel(), e.toString());
                continue;
            }
            Long appliedAt = row.getAppliedAt();
            if (appliedAt != null && now - appliedAt > staleAfter) {
                stale++;
            }
        }
        return new PollResult(applying.size(), settled, failed, stale);
    }

    // ------------------------------------------------------------------ 内部

    private List<MchPaymentMerchant> rows(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                paymentMapper.selectList(Wrappers.<MchPaymentMerchant>lambdaQuery()
                        .eq(MchPaymentMerchant::getEntityNo, merchantNo)));
    }

    /**
     * 定位一条进件记录。
     *
     * <p>门店维度用<b>空串</b>表示主体级，与库里的 {@code DEFAULT ''} 一致。
     * 这里刻意<b>不做「找不到就落回主体级」</b>：那样商家为 B 店提交的资料
     * 会被写进主体默认号，症状是「给分店进件，改的却是总店的账户」——
     * 而这不会报错，只会在第一次打款时把钱打错地方。
     */
    /**
     * 取这条进件记录；<b>主体级那一条没有就现建</b>。
     *
     * <p>记录本该在入驻通过时建（{@code MerchantPortImpl.ensurePayment}），
     * 而走别的路进来的主体（种子、历史数据）没有 —— 此前的表现是：
     * 商家在收款设置页填完卡号点提交，得到一句 <b>「数据不存在」</b>。
     * 他填的东西没错、店也在，报错却说不存在，这句话没有任何可操作性。
     *
     * <p>只补主体级（{@code storeNo} 空）：给某家分店进件仍要求主体级先开好，
     * 那条链路上「找不到」是真的错（见 {@link #require} 的注释）。
     */
    private MchPaymentMerchant requireOrOpen(String merchantNo, String payChannel, String storeNo) {
        String key = storeNo == null ? "" : storeNo;
        return rows(merchantNo).stream()
                .filter(r -> r.getPayChannel().equals(payChannel))
                .filter(r -> key.equals(r.getStoreNo() == null ? "" : r.getStoreNo()))
                .findFirst()
                .orElseGet(() -> {
                    if (!key.isEmpty()) {
                        throw BizException.of(ErrorCode.NOT_FOUND);
                    }
                    MchPaymentMerchant p = new MchPaymentMerchant();
                    p.setEntityNo(merchantNo);
                    p.setStoreNo("");
                    p.setPayChannel(payChannel);
                    p.setLegalForm(legalFormOf(merchantNo));
                    p.setApplyStatus(MchPaymentMerchant.NONE);
                    DataScopeContext.executeWithoutScope(() -> paymentMapper.insert(p));
                    return p;
                });
    }

    /**
     * 没指定通道时该用哪个。
     *
     * <p><b>此前这里写死 {@code WECHAT}。</b>对只做支付宝的商家、以及将来任何非中国市场的
     * 商家，那个默认都是错的 —— 而它错得没有声音：开出来的是一个商家根本没打算开的通道，
     * 要到第一笔订单收不到钱才看得出来。
     *
     * <p>改成按 {@code sys_pay_channel} 里**启用且覆盖本市场**的通道取第一个。
     * <b>一个都没有时明确报错，不回退到任何通道</b> —— 交易侧路由的注释早就立过这条规矩：
     * 「回退等于把钱发到另一个通道的商户号，那是资金事故」。进件侧照抄。
     *
     * <p>市场暂取默认（主体上还没有市场字段）。<b>这一点写在这里而不是假装它按主体算</b>：
     * 等主体有了市场，改的是这一行的入参，不是这套判断。
     */
    private String resolveChannel(String merchantNo, String payChannel) {
        if (payChannel != null && !payChannel.isBlank()) {
            return payChannel;
        }
        List<String> available = payChannelMasterPort.enabledChannels(marketOf(merchantNo));
        if (available.isEmpty()) {
            throw BizException.of(ErrorCode.PAY_CHANNEL_UNAVAILABLE);
        }
        return available.get(0);
    }

    private MchPaymentMerchant require(String merchantNo, String payChannel, String storeNo) {
        String key = storeNo == null ? "" : storeNo;
        return rows(merchantNo).stream()
                .filter(r -> r.getPayChannel().equals(payChannel))
                .filter(r -> key.equals(r.getStoreNo() == null ? "" : r.getStoreNo()))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    private PayApplymentGateway gateway(String payChannel) {
        /*
         * 没有对应实现时**直接失败**，不要回落到别的通道。
         * 回落的结果是「以为在给微信开户，其实开的是别处」，
         * 而这个错要到第一笔订单分账时才看得出来。
         */
        return Optional.ofNullable(gateways.get(payChannel))
                .or(() -> Optional.ofNullable(gateways.get("STUB")))
                /*
                 * **一个都没有 = 这个环境根本没接通道**，不是他填错了什么。
                 *
                 * 生产上目前正是这种状态：唯一的实现是 StubApplymentGateway，
                 * 而 `shop.pay.stub` 默认关（「假装支付成功」是资金事故）。
                 * 原先这里给 BAD_REQUEST —— 商家把整张进件表填完，
                 * 只得到一句「请求参数有误」，然后回去反复改那几个字段。
                 */
                .orElseThrow(() -> BizException.of(ErrorCode.PAY_CHANNEL_UNAVAILABLE));
    }

    /** 缺什么就说缺什么 —— 「还差结算账户」比「审核中」有用得多。 */
    private List<String> missingOf(MchPaymentMerchant row, String settleType,
                                   String settleAccount, List<String> licenses) {
        List<String> missing = new ArrayList<>();
        if (settleType == null || settleType.isBlank()) {
            missing.add("settleAccountType");
        }
        if (settleAccount == null || settleAccount.isBlank()) {
            missing.add("settleAccount");
        }
        // 小微免执照，这正是它存在的意义（ADR-002 §4）；其余主体必须传
        if (!MchPaymentMerchant.MICRO.equals(row.getLegalForm())
                && (licenses == null || licenses.isEmpty())) {
            missing.add("licenses");
        }
        return missing;
    }

    private PaymentApplymentVO toVO(MchPaymentMerchant row) {
        boolean active = MchPaymentMerchant.ACTIVE.equals(row.getApplyStatus());
        List<String> missing = active ? List.of()
                : missingOf(row, row.getSettleAccountType(), row.getSettleAccountMasked(), null);
        return new PaymentApplymentVO(
                row.getPayChannel(),
                payChannelMasterPort.channelName(row.getPayChannel()),
                row.getApplyStatus(),
                active,
                row.getPayMerchantNo(),
                mask(row.getSubMchid()),
                row.getSettleAccountType(),
                row.getSettleAccountMasked(),
                row.getRejectReason(),
                missing,
                /*
                 * **提交过才算「审核中」。** 入驻通过时建的占位也是 APPLYING，
                 * 但那时商家一个字都没填 —— 两件相反的事共用一个状态串，
                 * 端上只能把它们都显示成「审核中」，而其中一件的球在商家自己脚下。
                 * 通道单号是现成的判据：发出去过才有。
                 */
                row.getChannelApplyNo() != null && !row.getChannelApplyNo().isBlank(),
                row.getAppliedAt(),
                row.getActivatedAt(),
                // 空串是库里的表示，端上用 null 更直白：「没有门店」而不是「门店叫空字符串」
                row.getStoreNo() == null || row.getStoreNo().isBlank() ? null : row.getStoreNo());
    }

    /** 只留尾四位。口径与手机号/地址共用 {@link Masks} —— 三份实现就是三种口径。 */
    private String mask(String raw) {
        return Masks.tail(raw);
    }

    /**
     * 这家主体在哪个市场（V288）。
     *
     * <p>直接查表而不是注入 {@code MerchantQueryPort}：那个 Port 的实现
     * 就在本模块，注进来是同模块内绕一圈，且给循环依赖留了口子。
     *
     * <p>查不到返回 {@code null} —— 传给 {@code enabledChannels} 等于
     * 「按默认市场算」。存量主体还没有这个字段的值，
     * 而<b>「看不到任何渠道」比「看到多余的渠道」更糟</b>：
     * 前者让商家完全走不下去，后者最多在进件时被通道拒。
     */
    private String marketOf(String merchantNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1")));
        return m == null || m.getMarket() == null || m.getMarket().isBlank()
                ? null : m.getMarket();
    }
}
