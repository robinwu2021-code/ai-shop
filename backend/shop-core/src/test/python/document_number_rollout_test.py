from pathlib import Path
import unittest
import re
ROOT = Path(__file__).resolve().parents[2] / 'main/java/ai/neargo/shop'
class CoreDocumentNumberContractTest(unittest.TestCase):
    def test_creation_services_bind_numbers(self):
        for name in ['promotion/service/impl/ActivityServiceImpl.java', 'promotion/service/impl/PlatformActivityServiceImpl.java', 'promotion/service/impl/ActivityVersionServiceImpl.java', 'promotion/service/impl/PmtCouponServiceImpl.java', 'marketing/coupon/impl/CouponServiceImpl.java', 'trade/service/impl/AfterSaleServiceImpl.java', 'trade/service/impl/InvoiceRequestServiceImpl.java', 'marketing/campaign/impl/CampaignServiceImpl.java', 'marketing/attribution/impl/FissionServiceImpl.java']:
            with self.subTest(service=name): self.assertTrue('documentNumbers.bind(' in (ROOT / name).read_text(), name)
    def test_sources_are_separate(self):
        self.assertIn('"MKT_LEGACY"', (ROOT/'marketing/coupon/impl/CouponServiceImpl.java').read_text())
        self.assertIn('"PMT"', (ROOT/'promotion/service/impl/PmtCouponServiceImpl.java').read_text())
    def test_output_records_have_document_no(self):
        for file,name in [('promotion/dto/UnifiedActivityViews.java','ActivityView'),('promotion/dto/ActivityVOs.java','ActivityVO'),('promotion/dto/PlatformVOs.java','PlatformActivityVO'),('promotion/dto/CouponVOs.java','CouponIssueVO'),('marketing/coupon/dto/CouponVO.java','CouponVO'),('trade/service/InvoiceRequestService.java','InvoiceRequestVO'),('trade/dto/AfterSaleVO.java','AfterSaleVO'),('promotion/dto/OpsPromotionVOs.java','OpsCouponVO'),('promotion/dto/OpsPromotionVOs.java','OpsActivityVO')]:
            with self.subTest(record=name): self.assertTrue(re.search(r'record '+name+r'\([^)]*String documentNo', re.sub(r'/\*[\s\S]*?\*/', '', (ROOT/file).read_text())), name)
if __name__=='__main__': unittest.main()
