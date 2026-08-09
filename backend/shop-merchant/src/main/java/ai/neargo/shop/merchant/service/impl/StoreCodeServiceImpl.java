package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
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

    private final MchEntityMapper merchantMapper;

    public StoreCodeServiceImpl(MchEntityMapper merchantMapper) {
        this.merchantMapper = merchantMapper;
    }

    @Override
    public String resolve(String storeCode) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getStoreCode, storeCode).last("limit 1")));
        if (m == null) {
            // 码不存在就 404，**不静默回退到首页** —— 静默回退会让「码印错了」
            // 这种事永远没人发现，店主一直以为在带客
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return m.getEntityNo();
    }

    @Override
    @Transactional
    public String ensureFor(String merchantNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (m.getStoreCode() != null && !m.getStoreCode().isBlank()) {
            return m.getStoreCode();
        }
        m.setStoreCode(newCode());
        DataScopeContext.executeWithoutScope(() -> merchantMapper.updateById(m));
        return m.getStoreCode();
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
