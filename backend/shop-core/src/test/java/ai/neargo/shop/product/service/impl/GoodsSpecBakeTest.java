package ai.neargo.shop.product.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.product.service.SpecLibraryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GoodsSpecBakeTest {
    @Mock SpecLibraryService library;
    @InjectMocks MerchantGoodsServiceImpl goods;

    @Test void plainAndFreeTextSnapshotsNeedNoLibraryLookup() {
        assertThat(goods.bakeSpecs("E", "CAT", "[]").changed()).isFalse();
        var manual = goods.bakeSpecs("E", "CAT", "[{\"name\":\"包装\",\"options\":[\"盒\"]}]");
        assertThat(manual.changed()).isFalse();
        assertThat(manual.valueNoByGroup()).hasSize(1);
        verifyNoInteractions(library);
    }

    @Test void referencedDimensionStillUsesCurrentCategoryAndFlagsRetiredIdentity() {
        var result = goods.bakeSpecs("E", "CAT",
                "[{\"name\":\"包装\",\"templateNo\":\"D\",\"options\":[\"盒\"]}]");
        verify(library).templatesForCategory("E", "CAT");
        verify(library).dimUsable("D");
        assertThat(result.unresolved()).isNotEmpty();
    }
}
