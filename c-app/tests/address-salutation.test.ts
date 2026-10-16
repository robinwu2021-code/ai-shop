import { beforeEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
const { saveAddress } = vi.hoisted(() => ({ saveAddress: vi.fn(async () => []) }));
vi.mock("@/api", () => ({ api: { saveAddress, phoneCapable: async () => ({capable:false}) } }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k:string) => k }) }));
vi.mock("@shared/ports/location", () => ({ canChooseLocation:()=>false,canChooseWxAddress:()=>false }));
vi.mock("@shared/ports/geo-search", () => ({ canSearchPlaces:()=>false }));
import Form from "@/components/biz/biz-address-form.vue";
import { useUserStore } from "@/stores/user";
beforeEach(()=>{setActivePinia(createPinia());vi.clearAllMocks();useUserStore().user={phone:'13900000900'} as never;});
const render=(salutation?:string)=>mount(Form,{props:{address:{addressId:'A1',name:'张',phone:'13900000900',region:'浙江省杭州市',detail:'测试路',houseNo:'101',isDefault:false,salutation} as never},global:{mocks:{$t:(k:string)=>k},stubs:{'phone-gate':true}}});
it('编辑回显、互斥选择、再次点击取消并显式保存',async()=>{
 const w=render('MS');await flushPromises();
 expect(w.find('[data-salutation="MS"]').classes()).toContain('sh-chip--primary');
 await w.find('[data-salutation="MR"]').trigger('tap');
 expect(w.find('[data-salutation="MS"]').classes()).not.toContain('sh-chip--primary');
 await w.find('[data-salutation="MR"]').trigger('tap');
 await w.find('.form__save').trigger('tap'); await flushPromises();
 expect(saveAddress).toHaveBeenCalledWith(expect.objectContaining({salutation:'UNSPECIFIED',name:'张'}));
});
it('旧地址不默认选择任何称呼',async()=>{
 const w=render();await flushPromises();expect(w.findAll('.salutation .sh-chip--primary')).toHaveLength(0);
 await w.find('.form__save').trigger('tap');await flushPromises();
 expect(saveAddress).toHaveBeenCalledWith(expect.objectContaining({salutation:'UNSPECIFIED'}));
});
