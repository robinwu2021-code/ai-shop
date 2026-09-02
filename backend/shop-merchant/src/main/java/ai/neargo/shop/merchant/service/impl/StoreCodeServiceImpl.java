package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.StoreCodeService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

@Service
public class StoreCodeServiceImpl implements StoreCodeService {

    /** 店铺码字符集：**去掉 0/O/1/I/L**，印在纸上让人手输时不会认错。 */
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int CODE_LEN = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** C 端门店页。扫码落这里，scene 里的店铺码由页面读出来做归因 */
    private static final String STORE_PAGE = "pages/store/index";

    private final MchEntityMapper merchantMapper;
    private final MchStoreMapper storeMapper;
    private final ai.neargo.shop.spi.user.WxAcodePort acodePort;

    public StoreCodeServiceImpl(MchEntityMapper merchantMapper,
                                MchStoreMapper storeMapper,
                                ai.neargo.shop.spi.user.WxAcodePort acodePort) {
        this.merchantMapper = merchantMapper;
        this.storeMapper = storeMapper;
        this.acodePort = acodePort;
    }

    @Override
    @Transactional
    public String acodeBase64(String merchantNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 已经有了就直接给 —— 永久码，且额度有限，不能每次请求都去要一张
        if (m.getAcodeBase64() != null && !m.getAcodeBase64().isBlank()) {
            return m.getAcodeBase64();
        }
        if (!acodePort.enabled()) {
            return null;
        }
        String code = ensureFor(merchantNo);
        byte[] png = acodePort.unlimited(code, STORE_PAGE);
        if (png == null || png.length == 0) {
            /*
             * 生成失败**不落库、也不抛**：下次请求会再试一次。
             * 抛出去的话商家整个店铺页打不开，而他此刻要看的多半不是码。
             */
            return null;
        }
        String b64 = java.util.Base64.getEncoder().encodeToString(png);
        m.setAcodeBase64(b64);
        DataScopeContext.executeWithoutScope(() -> merchantMapper.updateById(m));
        return b64;
    }

    @Override
    public String resolve(String storeCode) {
        return resolveTarget(storeCode).entityNo();
    }

    @Override
    public CodeTarget resolveTarget(String storeCode) {
        /*
         * **先查门店表**：V298 之后新发的码都在那儿，默认店的旧码也回填了过去。
         * 查得到就同时知道是哪家分店 —— 这正是一店一码要买的东西。
         */
        MchStore s = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getStoreCode, storeCode).last("limit 1")));
        if (s != null) {
            return new CodeTarget(s.getEntityNo(), s.getStoreNo());
        }
        /*
         * 回落主体表。走到这里的是**没有门店行的历史主体** —— 回填只覆盖了有默认店的。
         * 这条路给不出门店号，但仍然要能扫进来：印在纸上的码作废是线下成本，
         * 不能因为库里少一行就让贴纸变成死链。
         */
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getStoreCode, storeCode).last("limit 1")));
        if (m == null) {
            // 码不存在就 404，**不静默回退到首页** —— 静默回退会让「码印错了」
            // 这种事永远没人发现，店主一直以为在带客
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return new CodeTarget(m.getEntityNo(), null);
    }

    @Override
    @Transactional
    public String ensureFor(String merchantNo) {
        return ensureForStore(merchantNo, null);
    }

    @Override
    @Transactional
    public String ensureForStore(String merchantNo, String storeNo) {
        MchStore store = storeOf(merchantNo, storeNo);
        if (store == null) {
            /*
             * 主体连一行门店都没有 —— 历史数据。仍然发码，落在主体上，
             * 与 V298 之前的行为一致；解析时会走 resolveTarget 的回落分支。
             */
            return ensureOnEntity(merchantNo);
        }
        if (notBlank(store.getStoreCode())) {
            return store.getStoreCode();
        }
        store.setStoreCode(newCode());
        DataScopeContext.executeWithoutScope(() -> storeMapper.updateById(store));
        return store.getStoreCode();
    }

    /**
     * 指定门店；不指定就取默认店。
     *
     * <p><b>按 entityNo 一起过滤</b>：只按 storeNo 查的话，传错门店号会发码到别人家店上，
     * 而这种错在界面上完全看不出来 —— 码是新的、扫得通、只是算到了另一家的账上。
     */
    private MchStore storeOf(String merchantNo, String storeNo) {
        return DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, merchantNo)
                        .eq(notBlank(storeNo), MchStore::getStoreNo, storeNo)
                        .eq(!notBlank(storeNo), MchStore::getIsDefault, 1)
                        .last("limit 1")));
    }

    private String ensureOnEntity(String merchantNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (notBlank(m.getStoreCode())) {
            return m.getStoreCode();
        }
        m.setStoreCode(newCode());
        DataScopeContext.executeWithoutScope(() -> merchantMapper.updateById(m));
        return m.getStoreCode();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 撞码由 {@code uk_store_code} 兜底：插入失败重试一次比预先查重更简单也更可靠。 */
    private String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LEN);
        for (int i = 0; i < CODE_LEN; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
