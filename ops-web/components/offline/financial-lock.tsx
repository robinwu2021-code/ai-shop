"use client";
import {createContext,useContext,useEffect} from "react";
/** Drawer owners retain unresolved financial commands in RAM. */
export const FinancialLockContext=createContext<(id:string,locked:boolean)=>void>(()=>{});
export function useFinancialLock(id:string,locked:boolean){
 const lock=useContext(FinancialLockContext);
 useEffect(()=>{lock(id,locked);return()=>lock(id,false);},[id,locked,lock]);
 useEffect(()=>{if(!locked)return;const prevent=(e:BeforeUnloadEvent)=>e.preventDefault();window.addEventListener("beforeunload",prevent);return()=>window.removeEventListener("beforeunload",prevent);},[locked]);
}
