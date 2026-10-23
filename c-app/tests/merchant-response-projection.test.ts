import {expect,it} from 'vitest';
import {db,toMerchant} from '@shared/mock/db';
import {merchantMock} from '@/api/mocks/merchant';

it('merchant responses preserve displayed facts without advertising mock-only coverage fields',async()=>{
 const no=db.merchantSeeds[0]!.merchantNo;
 const seed=toMerchant(no);
 const detail=await merchantMock.merchantDetail(no);
 expect(detail).toMatchObject({merchantNo:no,name:seed.name,rating:seed.rating,ratingCount:seed.ratingCount,tags:seed.tags,scores:seed.scores,goodsCount:seed.goodsCount});
 expect(detail).not.toHaveProperty('serviceScope');expect(detail).not.toHaveProperty('serviceCommunityNos');expect(detail).not.toHaveProperty('distance');
 expect(detail.address).toBe(seed.address??null);expect(detail.openHours).toBe(seed.openHours??null);
 const list=await merchantMock.merchantList();
 expect(list.find(m=>m.merchantNo===no)).toEqual(detail);
});

it('favorite store responses keep actual store identity and expose only the brief DTO',async()=>{
 const original=[...db.favoriteStores];const no=db.merchantSeeds[0]!.merchantNo;
 try {
  db.favoriteStores=[`ST${no}`];
  const rows=await merchantMock.favoriteStores();
  expect(rows).toHaveLength(1);
  expect(rows[0]).toMatchObject({merchantNo:no,storeNo:`ST${no}`,name:toMerchant(no).name,rating:toMerchant(no).rating});
  expect(Object.keys(rows[0]!).sort()).toEqual(['merchantNo','storeNo','name','logo','rating','verified','breachCount'].sort());
 } finally {db.favoriteStores=original;}
});
