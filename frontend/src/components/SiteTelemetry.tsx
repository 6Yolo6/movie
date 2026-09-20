"use client";
import { usePathname } from "next/navigation";
import { useEffect } from "react";
export default function SiteTelemetry(){const pathname=usePathname();useEffect(()=>{if(!pathname)return;fetch("/api/monitoring/page-view",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({path:pathname}),keepalive:true}).catch(()=>{});},[pathname]);return null;}
