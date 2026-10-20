package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.pay.storedvalue.entity.SvStatement;
import ai.neargo.shop.pay.storedvalue.service.impl.StoredValueMerchantQueryServiceImpl;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.mapper.storedvalue.*;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.numbering.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoredValueStatementDocumentNumbersTest {
 @Test void pageResolvesRechargeUsageAndVoucherAliasesInBatchesAndLeavesHistoryEmpty() throws Exception {
  // The observable contract adds two independent aliases without changing either internal locator.
  assertNotNull(ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews.Record.class.getMethod("documentNo"));
  assertNotNull(ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews.Record.class.getMethod("voucherDocumentNo"));
  var statements=mock(SvStatementMapper.class);
  var usageMapper=mock(EntUsageMapper.class);
  var numbers=mock(DocumentNumberService.class);
  var json=new ObjectMapper();
  var service=new StoredValueMerchantQueryServiceImpl(mock(SvAccountMapper.class),mock(SvMerchantAccountMapper.class),statements,
   new StoredValueCursorCodec("k".repeat(32),json),json);
  ReflectionTestUtils.setField(service,"documentNumbers",numbers);
  ReflectionTestUtils.setField(service,"usages",usageMapper);
  var recharge=row("voucher-r","RECHARGE","recharge");
  var consume=row("voucher-c","REDEMPTION","operation");
  var old=row("voucher-old","RECHARGE","old");
  when(statements.selectList(any())).thenReturn(List.of(recharge,consume,old));
  var usage=new EntUsage();usage.setOperationNo("operation");usage.setUsageNo("usage");
  when(usageMapper.selectList(any())).thenReturn(List.of(usage));
  when(numbers.findAll(any(),anyCollection())).thenAnswer(a->{
   DocumentType type=a.getArgument(0);var result=new HashMap<DocumentSource,String>();
   for(DocumentSource source:(Collection<DocumentSource>)a.getArgument(1))
    if(!source.sourceNo().contains("old")) result.put(source,type.prefix()+"20261020000001");
   return result;
  });
  var page=service.records(new CatalogScope("MAIN","ENTITY","ACTOR",false),"STORE","ALL",null,null,null,null,10);
  assertEquals(3,page.records().size());
  var first=page.records().get(0);var second=page.records().get(1);var historical=page.records().get(2);
  assertEquals("recharge",first.businessNo());assertEquals("operation",second.businessNo());
  assertEquals("PR20261020000001",first.getClass().getMethod("documentNo").invoke(first));
  assertEquals("EU20261020000001",second.getClass().getMethod("documentNo").invoke(second));
  assertEquals("SV20261020000001",second.getClass().getMethod("voucherDocumentNo").invoke(second));
  assertNull(historical.getClass().getMethod("documentNo").invoke(historical));
  assertNull(historical.getClass().getMethod("voucherDocumentNo").invoke(historical));
  verify(numbers,times(3)).findAll(any(),anyCollection());
  verify(numbers,never()).findBySource(any(),any());
  verify(numbers,never()).bind(any(),any(),any());
 }
 private SvStatement row(String voucher,String type,String business){
  var r=new SvStatement();r.setTenantNo("MAIN");r.setVoucherNo(voucher);r.setRecordNo(voucher);
  r.setType(type);r.setBusinessNo(business);r.setCurrency("CNY");r.setSignedAmountMinor(100L);
  r.setBalanceAfterMinor(100L);r.setOccurredAt(LocalDateTime.of(2026,10,20,0,0));return r;
 }
}
