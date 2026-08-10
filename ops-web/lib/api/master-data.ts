// 平台主数据（行业 / 主体类型 / 支付通道）的读取与查名。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么不在 ops-web 里再建一份翻译
// ─────────────────────────────────────────────────────────────────────────────
// 后端 `/common/master-data` 一直在下发每个码的展示名（`name`），
// 而 ops-web **完全没接过它** —— 入驻审核列表因此把 `RETAIL` / `INDIVIDUAL`
// 原样显示给运营看。
//
// 直觉反应是「补两条 i18n 词条」，但那会造出第二份真源：
// `sys_legal_form` 与 `sys_industry` 是**运营可维护的字典表**（带 enabled / sort /
// 通道码 / 是否要执照），运营新加一个行业，写死的词条不会跟着变，
// 于是又回到「新行业显示成 XXX_YYY」。
//
// shared 里 `MerchantSubject` 的注释早就写明了这条：
// 「端上取 GET /common/master-data，不要在页面里写死」。这里照做。
import { useQuery } from "@tanstack/react-query";

export interface MasterDataDTO {
  industries: { industry: string; name: string; microAllowed: boolean }[];
  subjects: { subjectType: string; name: string; needLicense: boolean }[];
  channels: { payChannel: string; name: string; enabled: boolean }[];
}

const BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://127.0.0.1:8081";

async function fetchMasterData(): Promise<MasterDataDTO> {
  const res = await fetch(`${BASE}/common/master-data`);
  const body = await res.json();
  return body.data as MasterDataDTO;
}

/**
 * 主数据。**免登录、极少变**，所以缓存整个会话 ——
 * 每开一次抽屉都重新拉一次，只会让审核台变慢而不会更准。
 */
export function useMasterData() {
  return useQuery({
    queryKey: ["master-data"],
    queryFn: fetchMasterData,
    staleTime: Infinity,
    gcTime: Infinity,
  });
}

/**
 * 码 → 展示名。**查不到时回退成码本身**，不回退成空字符串：
 * 一个空单元格看起来像「这条数据没填」，而显示 `RETAIL` 至少能让人看出
 * 「有值，只是这里没翻译」—— 后者是能被发现的问题，前者不是。
 */
export function useCodeLabel() {
  const { data } = useMasterData();
  return {
    industry: (code?: string | null) =>
      code ? data?.industries.find((i) => i.industry === code)?.name ?? code : "—",
    subject: (code?: string | null) =>
      code ? data?.subjects.find((s) => s.subjectType === code)?.name ?? code : "—",
  };
}
