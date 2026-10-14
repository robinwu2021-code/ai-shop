// 对差连续干净的轮数（M7）。
//
// **判据是「连续 N 天为零」，而对差页此前只有当天的数** —— 一次干净说明不了任何事：
// 切换真相源（G3）之前要看的是它连着干净了多久。
//
// 数据不新建：`inv-recon` 每天跑一轮，结论落在它自己的运行记录里（成功 = 那天为零）。
// 另存一份的话，两处迟早对同一天给出两种说法。
//
// **放在 lib 而不是页面里**：ops-web 的 vitest 只收 lib/，
// 而下面那条 SKIPPED 规则正是这里唯一不显然的判断 —— 放在 app/ 下等于没测。
import type { JobLogRow, JobStatus } from "@/lib/types";

/**
 * 从最近往回数，连着有几轮是干净的。
 *
 * **SKIPPED 不算断**：它是「锁没抢到，上一轮还在跑」—— 正常的并发保护，
 * 不是这一天有差异。把它算成断，连续计数会在完全正常的日子里被清零，
 * 而看的人会以为对差出了问题、迟迟不敢切。
 *
 * 其余任何状态都算断（FAILED 自不必说；TIMEOUT / UNREACHABLE 是**没有结论**，
 * 而没有结论不能当作「那天为零」）。
 */
export function cleanStreak(rows: readonly JobLogRow[] | undefined): number {
  if (!rows) {
    return 0;
  }
  let n = 0;
  for (const r of rows) {
    const s: JobStatus = r.status;
    if (s === "SUCCESS") {
      n += 1;
      continue;
    }
    if (s === "SKIPPED") {
      continue;
    }
    break;
  }
  return n;
}
