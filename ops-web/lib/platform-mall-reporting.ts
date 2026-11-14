import { exponentOf } from "./commerce-money";
/** Aggregate money may exceed int64 and net can be negative. Never coerce to Number. */
export function formatReportingMinor(currency: string, minor: string): string {
  if (!/^-?(0|[1-9]\d*)$/.test(minor)) return `${currency} ?${minor}`;
  const negative = minor.startsWith("-");
  const digits = negative ? minor.slice(1) : minor;
  const exponent = exponentOf(currency);
  const padded = digits.padStart(exponent + 1, "0");
  const integer = (exponent ? padded.slice(0, -exponent) : padded).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return `${currency} ${negative ? "-" : ""}${integer}${exponent ? `.${padded.slice(-exponent)}` : ""}`;
}
export function validReportingRange(from: number, to: number): boolean {
  return Number.isSafeInteger(from) && Number.isSafeInteger(to) && from >= 0 && to > from && to - from <= 366 * 86400000;
}
