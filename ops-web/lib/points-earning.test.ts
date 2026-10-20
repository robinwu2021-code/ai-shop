import {expect,it} from "vitest";
import {percentToBasisPoints,fiatToMinor} from "./points-earning";
it("validates percentage text without rounding excess precision",()=>{
 expect(percentToBasisPoints("1.25")).toBe(125);expect(percentToBasisPoints("0")).toBe(0);expect(percentToBasisPoints("100.00")).toBe(10000);
 for(const invalid of ["100.01","1.001","-1","1e2",""])expect(()=>percentToBasisPoints(invalid)).toThrow();
});
it("converts currency amounts exactly for supported precision",()=>{
 expect(fiatToMinor("1.25",2)).toBe(125);expect(fiatToMinor("1.001",3)).toBe(1001);expect(fiatToMinor("1",0)).toBe(1);
 for(const invalid of ["1.001","-1","1e2","9007199254740992"])expect(()=>fiatToMinor(invalid,2)).toThrow();
});
