/**
 * 演示种子账号 —— **它们的口令是「登录名 + 123」**。
 *
 * `DevSeeder` 在 `shop.seed.enabled=true` 时灌这 11 个平台账号，本意是本地
 * 与验收环境能一键跑通。**而生产环境的 `shop-app.env` 里这个开关是 `true`**
 * （2026-08-29 实测），于是它们真的在生产库里，其中 `admin` 是 `SUPER_ADMIN`
 * （`perms: ["*"]`，全平台 99 个权限码全有）。
 *
 * `docs/qa/线上验收-总纲.md` 2026-08-18 就把这条列为待修，11 天后仍然如此。
 *
 * **这份清单只做一件事：让它们在员工列表里认得出来。** 处置（改口令、停用、
 * 删除）由人在这个页面上做 —— 那需要有运营账号的人，而不是一次部署。
 *
 * ⚠️ 判据不在这里：`ops-web/lib/seeded-accounts.test.ts` 直接读
 * `DevSeeder.java`，两边对不上就红。**手抄一份名单必然会过期**，
 * 而过期的名单比没有更糟：它会让某个种子账号看起来是正常账号。
 */
export const SEEDED_USERNAMES: readonly string[] = [
  "admin", "bd", "goods", "support", "campaign", "community",
  "auditor", "finance", "risk", "analyst", "techops",
];

/** 这个登录名是不是演示种子账号。 */
export function isSeededAccount(username: string | null | undefined): boolean {
  return !!username && SEEDED_USERNAMES.includes(username);
}
