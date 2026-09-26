'use client';

import React, { useCallback, useEffect, useState } from 'react';
import { Alert, App, Button, Card, Form, Input, Space, Typography } from 'antd';
import { LockOutlined, MailOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api, readApiError } from '@/lib/api';
import { useTranslation } from 'react-i18next';

interface Values { username: string; email: string; password: string; confirm: string; emailCode?: string; inviteCode?: string }
interface Policy { publicRegistrationEnabled: boolean; invitationEnabled: boolean; inviteValid: boolean; registrationAllowed: boolean; registrationLimitReached: boolean; maxUsers: number; emailRequired: boolean; emailVerificationEnabled: boolean }

export default function RegisterPage() {
    const router = useRouter();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [form] = Form.useForm<Values>();
    const [policy, setPolicy] = useState<Policy | null>(null);
    const [checking, setChecking] = useState(false);
    const [sending, setSending] = useState(false);
    const [submitting, setSubmitting] = useState(false);

    const checkPolicy = useCallback(async (invite?: string) => {
        setChecking(true);
        try {
            const q = invite?.trim() ? `?invite=${encodeURIComponent(invite.trim())}` : '';
            const r = await api(`/api/auth/registration-policy${q}`);
            if (r.ok) setPolicy(await r.json());
            else message.error(await readApiError(r, t('registerPolicyLoadFailed')));
        } finally {
            setChecking(false);
        }
    }, [message, t]);

    useEffect(() => {
        const code = new URLSearchParams(window.location.search).get('invite') || '';
        if (code) form.setFieldValue('inviteCode', code);
        checkPolicy(code);
    }, [checkPolicy, form]);

    const sendCode = async () => {
        try {
            const email = await form.validateFields(['email']);
            setSending(true);
            const r = await api('/api/auth/email-code', {
                method: 'POST',
                body: JSON.stringify({ email: email.email, inviteCode: form.getFieldValue('inviteCode') }),
            });
            if (r.ok) message.success(t('registerCodeSent'));
            else message.error(await readApiError(r, t('registerSendCodeFailed')));
        } catch (error) {
            if (error && typeof error === 'object' && 'errorFields' in error) return;
            message.error(t('networkError'));
        } finally {
            setSending(false);
        }
    };

    const submit = async (v: Values) => {
        setSubmitting(true);
        try {
            const r = await api('/api/auth/register', {
                method: 'POST',
                body: JSON.stringify({ username: v.username, email: v.email, password: v.password, emailCode: v.emailCode, inviteCode: v.inviteCode }),
            });
            if (r.ok) {
                message.success(t('registerSuccess'));
                router.push('/login');
            } else {
                message.error(await readApiError(r, t('registerFailed')));
            }
        } finally {
            setSubmitting(false);
        }
    };

    const inviteOnly = policy && !policy.publicRegistrationEnabled;

    return (
        <div className="flex min-h-[80vh] items-center justify-center px-4">
            <Card title={t('registerTitle')} className="w-full max-w-md shadow-xl">
                {policy?.registrationLimitReached && <Alert className="mb-4" type="error" showIcon message={t('registerLimitReachedTitle')} description={t('registerLimitReachedDesc')} />}
                {inviteOnly && <Alert className="mb-4" type={policy.registrationAllowed ? 'success' : 'info'} showIcon message={policy.registrationAllowed ? t('registerInviteValid') : t('registerInviteOnly')} description={policy.registrationAllowed ? t('registerInviteValidDesc') : t('registerInviteOnlyDesc')} />}
                {policy && !policy.emailVerificationEnabled && <Alert className="mb-4" type="warning" showIcon message={t('registerEmailOffTitle')} description={t('registerEmailOffDesc')} />}
                <Form form={form} onFinish={submit} size="large" layout="vertical">
                    <Form.Item name="username" label={t('username')} extra={t('registerUsernameHint')} rules={[{ required: true, message: t('registerUsernameRequired') }, { min: 3, max: 50 }]}>
                        <Input prefix={<UserOutlined />} autoComplete="username" />
                    </Form.Item>
                    <Form.Item name="email" label={t('email')} extra={policy?.emailVerificationEnabled ? t('registerEmailHint') : undefined} rules={[{ required: true, message: t('registerEmailRequired') }, { type: 'email', message: t('registerEmailInvalid') }]}>
                        <Input prefix={<MailOutlined />} placeholder={t('registerEmailPlaceholder')} autoComplete="email" />
                    </Form.Item>
                    {policy?.emailVerificationEnabled && <Form.Item label={t('registerEmailCode')}>
                        <Space.Compact className="w-full">
                            <Form.Item name="emailCode" noStyle rules={[{ required: true, message: t('registerEmailCodeRequired') }]}>
                                <Input prefix={<SafetyCertificateOutlined />} />
                            </Form.Item>
                            <Button loading={sending} disabled={!policy?.registrationAllowed} onClick={sendCode}>{t('registerSendCode')}</Button>
                        </Space.Compact>
                    </Form.Item>}
                    <Form.Item name="password" label={t('password')} rules={[{ required: true }, { min: 12, max: 72, message: t('registerPasswordRule') }, { validator: (_, v) => !v || new TextEncoder().encode(v).length <= 72 ? Promise.resolve() : Promise.reject(new Error(t('registerPasswordByteRule'))) }]}>
                        <Input.Password prefix={<LockOutlined />} autoComplete="new-password" />
                    </Form.Item>
                    <Form.Item name="confirm" label={t('registerConfirmPassword')} dependencies={['password']} rules={[{ required: true }, ({ getFieldValue }) => ({ validator(_, v) { return !v || v === getFieldValue('password') ? Promise.resolve() : Promise.reject(new Error(t('registerPasswordMismatch'))); } })]}>
                        <Input.Password prefix={<LockOutlined />} autoComplete="new-password" />
                    </Form.Item>
                    {policy?.invitationEnabled && <Form.Item label={t('registerInviteCode')}>
                        <Space.Compact className="w-full">
                            <Form.Item name="inviteCode" noStyle rules={inviteOnly ? [{ required: true, message: t('registerInviteRequired') }] : []}>
                                <Input placeholder={t('registerInvitePlaceholder')} />
                            </Form.Item>
                            <Button loading={checking} onClick={() => checkPolicy(form.getFieldValue('inviteCode'))}>{t('registerCheckInvite')}</Button>
                        </Space.Compact>
                    </Form.Item>}
                    <Button block type="primary" htmlType="submit" loading={submitting} disabled={!policy?.registrationAllowed}>{t('registerSubmit')}</Button>
                </Form>
                <Typography.Paragraph className="mt-4 text-center" type="secondary">
                    {t('registerHasAccount')} <Link href="/login">{t('loginSubmit')}</Link>
                </Typography.Paragraph>
            </Card>
        </div>
    );
}
