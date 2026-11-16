import type { Page } from "@/lib/types";
export interface CustomerFilter { keyword?: string; storeNo?: string; brandNo?: string; page?: number; size?: number }
export interface CustomerStore { storeNo: string; name: string | null; entityNo: string; brandNo: string | null; brandName: string | null }
export interface CustomerRegistration { userNo: string; nickname: string | null; status: string; market: string | null; registeredAt: number; entityCount: number; storeCount: number }
export interface CustomerRegistrations { page: Page<CustomerRegistration>; total: number; withoutStores: number; newLast30Days: number; trend: { date: string; count: number }[] }
export interface CustomerBrand { brandNo: string; name: string; status: string; managementNo: string; managementName: string | null; stores: CustomerStore[] }
export interface PlatformMember { personNo: string; userNo: string | null; phoneTail: string | null; status: string; joinedAt: number; membershipCount: number; sourceType: string | null; sourceStoreNo: string | null; sourceStoreName: string | null }
export interface CustomerMembership { memberNo: string; entityNo: string; entityName: string | null; status: string; joinedAt: number; sourceType: string; sourceStoreNo: string | null; sourceStoreName: string | null }
export interface CustomerMemberSource { sourceNo: string; memberNo: string; sourceType: string; storeNo: string | null; storeName: string | null; occurredAt: number; first: boolean }
export interface PlatformMemberDetail { person: PlatformMember; memberships: CustomerMembership[]; sources: CustomerMemberSource[]; stores: CustomerStore[] }
export interface CustomerApi {
  customerRegistrations(q?: CustomerFilter): Promise<CustomerRegistrations>;
  customerRegistrationStores(userNo: string): Promise<CustomerStore[]>;
  customerBrands(q?: CustomerFilter): Promise<Page<CustomerBrand>>;
  platformMembers(q?: CustomerFilter): Promise<Page<PlatformMember>>;
  platformMember(personNo: string): Promise<PlatformMemberDetail>;
}
