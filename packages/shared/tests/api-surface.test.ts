import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { expect, it } from "vitest";
import { apiSurface } from "./helpers/api-surface";

it("reads inherited methods and actual spread members regardless of formatting", () => {
  const dir = mkdtempSync(join(tmpdir(), "api-surface-"));
  try {
    writeFileSync(join(dir, "base.ts"), "export interface Base { inherited(): Promise<void> } export const base: Base={inherited:async()=>{}};");
    const file = join(dir, "entry.ts");
    writeFileSync(file, "import {Base,base} from './base'; export interface Api extends Base {local():void} export const impl:Api={...base,local(){}};");
    expect([...apiSurface(file, "Api")].sort()).toEqual(["inherited", "local"]);
    expect([...apiSurface(file, "impl")].sort()).toEqual(["inherited", "local"]);
    // The type annotation must not hide a missing implementation member.
    writeFileSync(join(dir, "base.ts"), "export interface Base { inherited(): Promise<void> } export const base: Base={};");
    expect([...apiSurface(file, "impl")]).toEqual(["local"]);
    writeFileSync(file, "export {base as impl} from './base';");
    expect([...apiSurface(file, "impl")]).toEqual([]);
    expect(() => apiSurface(file, "missing")).toThrow("Missing API declaration");
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
