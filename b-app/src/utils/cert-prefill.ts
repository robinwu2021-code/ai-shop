import type { CertRecognition } from "@shared/types";

/** 识别结果要在表单上引起什么动作。 */
export interface PrefillPlan {
  /** 要写进「证件编号」的值。null = 不动这一栏 */
  qualNumber: string | null;
  /** 要写进「有效期至」的值（`YYYY-MM-DD`）。null = 不动这一栏 */
  expireAt: string | null;
  /** 认出来的证件类型与他选的不符 —— 提醒一句，但不拦 */
  typeMismatch: boolean;
}

const NOTHING: PrefillPlan = { qualNumber: null, expireAt: null, typeMismatch: false };

/**
 * 算出识别结果该往表单里填什么。**纯函数，不碰 UI** —— 这几条规则各有一次
 * 真实代价，值得单独钉住。
 *
 * @param cert    后端识别结果
 * @param current 表单此刻的值（他可能已经打了字）
 * @param picked  他选的证件类型
 */
export function planPrefill(
  cert: CertRecognition,
  current: { qualNumber: string; expireAt: string },
  picked: string,
): PrefillPlan {
  // 认不出不是错误，是「让他手填」。这一位是端上唯一的依据
  if (!cert.recognized) return NOTHING;

  /*
   * **只填空格子。** 覆盖他打过的字的话，「我明明填对了，传完图它自己变了」
   * 是最难解释的一种表现，而他多半发现不了 —— 提交之后才是错的。
   */
  const qualNumber = !current.qualNumber.trim() && cert.code ? cert.code : null;

  /*
   * **「长期」不是日期。** 这一栏空着就表示长期有效（后端按 null 存），
   * 照抄字面量进去会被 Date.parse 解析成 NaN，落库是一个坏时间戳。
   * 同理只认 YYYY-MM-DD：模型偶尔会回「2030年3月」这种。
   */
  const validTo = cert.validTo ?? "";
  const expireAt = !current.expireAt.trim() && /^\d{4}-\d{2}-\d{2}$/.test(validTo)
    ? validTo
    : null;

  /*
   * UNKNOWN 不算不符 —— 那是「没认出是什么证」，不是「认出来是别的证」。
   * 把它当成不符会让每一张模型没把握的证都弹一次提醒，提醒就此变成噪音。
   */
  const typeMismatch = !!cert.docType && cert.docType !== "UNKNOWN" && cert.docType !== picked;

  return { qualNumber, expireAt, typeMismatch };
}
