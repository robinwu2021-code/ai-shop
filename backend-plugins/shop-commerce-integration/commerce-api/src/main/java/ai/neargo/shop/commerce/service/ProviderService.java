package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.ProductProfileVO;
import ai.neargo.shop.commerce.dto.ProviderVO;
import ai.neargo.shop.commerce.dto.SaveProductProfileReq;
import ai.neargo.shop.common.PageData;

/** 平台产品目录只读，不把配置启用误当作获准协议。 */
public interface ProviderService {
    default ProductProfileVO saveProfile(
            String profileNo, String actorNo, SaveProductProfileReq request, String key) {
        throw new UnsupportedOperationException("Profile status management unavailable");
    }

    PageData<ProviderVO> listProviders(int page, int size);

    PageData<ProductProfileVO> listProfiles(String providerNo, int page, int size);
}
