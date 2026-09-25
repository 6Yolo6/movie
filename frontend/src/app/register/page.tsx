"use client";

import React, { useCallback, useEffect, useState } from "react";
import { Alert, App, Button, Card, Form, Input, Space, Typography } from "antd";
import { LockOutlined, MailOutlined, SafetyCertificateOutlined, UserOutlined } from "@ant-design/icons";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api, readApiError } from "@/lib/api";

interface Values { username:string; email:string; password:string; confirm:string; emailCode?:string; inviteCode?:string }
interface Policy { publicRegistrationEnabled:boolean; invitationEnabled:boolean; inviteValid:boolean; registrationAllowed:boolean; registrationLimitReached:boolean; maxUsers:number; emailRequired:boolean; emailVerificationEnabled:boolean }

export default function RegisterPage(){
 const router=useRouter(); const {message}=App.useApp(); const [form]=Form.useForm<Values>();
 const [policy,setPolicy]=useState<Policy|null>(null); const [checking,setChecking]=useState(false); const [sending,setSending]=useState(false); const [submitting,setSubmitting]=useState(false);
 const checkPolicy=useCallback(async(invite?:string)=>{setChecking(true);try{const q=invite?.trim()?`?invite=${encodeURIComponent(invite.trim())}`:"";const r=await api(`/api/auth/registration-policy${q}`);if(r.ok)setPolicy(await r.json());else message.error(await readApiError(r,"无法读取注册策略"));}finally{setChecking(false);}},[message]);
 useEffect(()=>{const code=new URLSearchParams(window.location.search).get("invite")||"";if(code)form.setFieldValue("inviteCode",code);checkPolicy(code);},[checkPolicy,form]);
 const sendCode=async()=>{try{const email=await form.validateFields(["email"]);setSending(true);const r=await api("/api/auth/email-code",{method:"POST",body:JSON.stringify({email:email.email,inviteCode:form.getFieldValue("inviteCode")})});if(r.ok)message.success("验证码已发送，请检查邮箱");else message.error(await readApiError(r,"发送失败"));}finally{setSending(false);}};
 const submit=async(v:Values)=>{setSubmitting(true);try{const r=await api("/api/auth/register",{method:"POST",body:JSON.stringify({username:v.username,email:v.email,password:v.password,emailCode:v.emailCode,inviteCode:v.inviteCode})});if(r.ok){message.success("注册成功，请登录");router.push("/login");}else message.error(await readApiError(r,"注册失败"));}finally{setSubmitting(false);}};
 const inviteOnly=policy&&!policy.publicRegistrationEnabled;
 return <div className="flex min-h-[80vh] items-center justify-center px-4"><Card title="注册 影窝" className="w-full max-w-md shadow-xl">
  {policy?.registrationLimitReached&&<Alert className="mb-4" type="error" showIcon message="公开注册人数已达上限" description="站点已达到自助注册人数上限，仍可使用有效邀请码注册，或联系管理员手动开通账号。"/>}
  {inviteOnly&&<Alert className="mb-4" type={policy.registrationAllowed?"success":"info"} showIcon message={policy.registrationAllowed?"邀请码有效":"当前已关闭公开注册"} description={policy.registrationAllowed?"可继续完成注册。":"请输入老用户提供的邀请码或打开邀请链接。"}/>}
  {policy&&!policy.emailVerificationEnabled&&<Alert className="mb-4" type="warning" showIcon message="邮箱验证码暂未启用" description="系统尚未配置邮件发送服务；仍会校验邮箱格式和邮箱唯一性。"/>}
  <Form form={form} onFinish={submit} size="large" layout="vertical">
   <Form.Item name="username" label="用户名" rules={[{required:true,message:"请输入用户名"},{min:3,max:50}]}><Input prefix={<UserOutlined/>} autoComplete="username"/></Form.Item>
   <Form.Item name="email" label="邮箱" rules={[{required:true,message:"请输入邮箱"},{type:"email",message:"邮箱格式不正确"}]}><Input prefix={<MailOutlined/>} autoComplete="email"/></Form.Item>
   {policy?.emailVerificationEnabled&&<Form.Item label="邮箱验证码"><Space.Compact className="w-full"><Form.Item name="emailCode" noStyle rules={[{required:true,message:"请输入邮箱验证码"}]}><Input prefix={<SafetyCertificateOutlined/>}/></Form.Item><Button loading={sending} disabled={!policy?.registrationAllowed} onClick={sendCode}>发送验证码</Button></Space.Compact></Form.Item>}
   <Form.Item name="password" label="密码" rules={[{required:true},{min:12,max:72,message:"密码至少 12 个字符，最多 72 个 UTF-8 字节"},{validator:(_,v)=>!v||new TextEncoder().encode(v).length<=72?Promise.resolve():Promise.reject(new Error("密码最多 72 个 UTF-8 字节"))}]}><Input.Password prefix={<LockOutlined/>} autoComplete="new-password"/></Form.Item>
   <Form.Item name="confirm" label="确认密码" dependencies={["password"]} rules={[{required:true},({getFieldValue})=>({validator(_,v){return !v||v===getFieldValue("password")?Promise.resolve():Promise.reject(new Error("两次密码不一致"));}})]}><Input.Password prefix={<LockOutlined/>}/></Form.Item>
   {policy?.invitationEnabled&&<Form.Item label="邀请码"><Space.Compact className="w-full"><Form.Item name="inviteCode" noStyle rules={inviteOnly?[{required:true,message:"当前必须提供邀请码"}]:[]}><Input placeholder="邀请码或邀请链接中的 invite 参数"/></Form.Item><Button loading={checking} onClick={()=>checkPolicy(form.getFieldValue("inviteCode"))}>校验</Button></Space.Compact></Form.Item>}
   <Button block type="primary" htmlType="submit" loading={submitting} disabled={!policy?.registrationAllowed}>注册</Button>
  </Form><Typography.Paragraph className="mt-4 text-center" type="secondary">已有账号？ <Link href="/login">登录</Link></Typography.Paragraph>
 </Card></div>;
}
