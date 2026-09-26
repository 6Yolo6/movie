'use client';

import React, { useCallback, useEffect, useState } from 'react';
import { Alert, App, Button, Card, Form, Input, Space, Tag, Typography } from 'antd';
import { MailOutlined, SafetyCertificateOutlined, SmileOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/store/authStore';
import { useRouter } from 'next/navigation';
import { api, readApiError } from '@/lib/api';
import { useTranslation } from 'react-i18next';

interface NicknameValues { nickname: string }
interface ChangeEmailValues { email: string; emailCode?: string }
interface ResetPasswordValues { password: string; confirm: string; emailCode?: string }

function isFormValidationError(error: unknown): boolean {
    return !!error && typeof error === 'object' && 'errorFields' in error;
}

function formatDateTime(value?: string | null): string {
    if (!value) return '';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

export default function ProfilePage() {
    const { user, token, login } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [emailForm] = Form.useForm<ChangeEmailValues>();
    const [passwordForm] = Form.useForm<ResetPasswordValues>();
    const [nicknameForm] = Form.useForm<NicknameValues>();
    const [sendingEmailCode, setSendingEmailCode] = useState(false);
    const [sendingPasswordCode, setSendingPasswordCode] = useState(false);
    const [savingEmail, setSavingEmail] = useState(false);
    const [savingPassword, setSavingPassword] = useState(false);
    const [savingNickname, setSavingNickname] = useState(false);

    const emailVerificationEnabled = user?.emailVerificationEnabled ?? true;
    const emailChangeLockedUntil = user?.emailChangeAvailableAt || null;

    useEffect(() => {
        if (!token) router.push('/login');
    }, [token, router]);

    const refreshProfile = useCallback(async () => {
        if (!token) return;
        try {
            const res = await api('/api/auth/me', { headers: { Authorization: `Bearer ${token}` } });
            if (res.ok) login(token, await res.json());
        } catch { /* Keep the cached profile when refresh fails. */ }
    }, [token, login]);

    useEffect(() => { void refreshProfile(); }, [refreshProfile]);

    useEffect(() => {
        nicknameForm.setFieldsValue({ nickname: user?.nickname || user?.username || '' });
    }, [nicknameForm, user?.nickname, user?.username]);

    const submitNickname = async (values: NicknameValues) => {
        if (!token || !user) return;
        setSavingNickname(true);
        try {
            const res = await api('/api/auth/profile', {
                method: 'PUT',
                headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
                body: JSON.stringify({ nickname: values.nickname.trim() }),
            });
            if (!res.ok) { message.error(await readApiError(res, t('profileNicknameFailed'))); return; }
            const data = await res.json();
            message.success(t('profileNicknameUpdated'));
            login(token, { ...user, nickname: data.nickname });
            nicknameForm.setFieldsValue({ nickname: data.nickname });
        } catch {
            message.error(t('networkError'));
        } finally {
            setSavingNickname(false);
        }
    };

    const sendPasswordCode = async () => {
        if (!token) return;
        setSendingPasswordCode(true);
        try {
            const res = await api('/api/auth/reset-password/code', {
                method: 'POST',
                headers: { Authorization: `Bearer ${token}` },
            });
            if (!res.ok) { message.error(await readApiError(res, t('profileSendCodeFailed'))); return; }
            message.success(t('profileCodeSent'));
        } catch {
            message.error(t('networkError'));
        } finally {
            setSendingPasswordCode(false);
        }
    };

    const sendEmailCode = async () => {
        if (!token) return;
        let values: ChangeEmailValues;
        try {
            values = await emailForm.validateFields(['email']);
        } catch (error) {
            if (isFormValidationError(error)) return;
            throw error;
        }
        setSendingEmailCode(true);
        try {
            const res = await api('/api/auth/email/code', {
                method: 'POST',
                headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: values.email.trim() }),
            });
            if (!res.ok) { message.error(await readApiError(res, t('profileSendCodeFailed'))); return; }
            message.success(t('profileCodeSent'));
        } catch {
            message.error(t('networkError'));
        } finally {
            setSendingEmailCode(false);
        }
    };

    const submitEmail = async (values: ChangeEmailValues) => {
        if (!token || !user) return;
        setSavingEmail(true);
        try {
            const res = await api('/api/auth/email', {
                method: 'PUT',
                headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: values.email.trim(), emailCode: values.emailCode }),
            });
            if (!res.ok) { message.error(await readApiError(res, t('profileEmailChangeFailed'))); return; }
            const data = await res.json();
            message.success(t('profileEmailChanged'));
            emailForm.resetFields();
            login(token, { ...user, email: data.email, emailUpdatedAt: data.emailUpdatedAt, emailChangeAvailableAt: data.emailChangeAvailableAt });
        } catch {
            message.error(t('networkError'));
        } finally {
            setSavingEmail(false);
        }
    };

    const submitPassword = async (values: ResetPasswordValues) => {
        if (!token) return;
        setSavingPassword(true);
        try {
            const res = await api('/api/auth/reset-password', {
                method: 'POST',
                headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
                body: JSON.stringify({ password: values.password, emailCode: values.emailCode }),
            });
            if (!res.ok) { message.error(await readApiError(res, t('profilePasswordFailed'))); return; }
            message.success(t('profilePasswordUpdatedAllDevices'));
            passwordForm.resetFields();
        } catch {
            message.error(t('networkError'));
        } finally {
            setSavingPassword(false);
        }
    };

    if (!user) return null;

    const codeRule = emailVerificationEnabled
        ? [{ required: true, message: t('profileEmailCodeRequired') }]
        : [];

    return (
        <div className="container mx-auto p-8 max-w-4xl min-h-[80vh] flex flex-col gap-8">
            <div className="flex items-center gap-6">
                <div className="w-24 h-24 rounded-full bg-gradient-to-r from-blue-500 to-cyan-500 flex items-center justify-center text-4xl font-bold text-black shadow-lg">
                    {(user.nickname || user.username).charAt(0).toUpperCase()}
                </div>
                <div>
                    <h1 className="text-3xl font-bold mb-2">{user.nickname || user.username}</h1>
                    <div className="flex gap-2 items-center flex-wrap">
                        <Tag color="blue" className="!m-0">{user.role}</Tag>
                        <Typography.Text type="secondary">{user.email || t('profileNoEmail')}</Typography.Text>
                    </div>
                </div>
            </div>

            <Card title={<span className="text-xl">{t('profileBasicInfo')}</span>} className="shadow-xl">
                <div className="max-w-lg">
                    <Form form={nicknameForm} onFinish={submitNickname} layout="vertical">
                        <Form.Item
                            name="nickname"
                            label={t('nickname')}
                            rules={[{ required: true, whitespace: true, message: t('profileNicknameRequired') }, { max: 20, message: t('profileNicknameRule') }]}
                        >
                            <Input prefix={<SmileOutlined />} maxLength={20} showCount autoComplete="nickname" />
                        </Form.Item>
                        <Form.Item className="!mb-2">
                            <Button type="primary" htmlType="submit" loading={savingNickname} className="bg-blue-600 hover:bg-blue-500">{t('profileNicknameSave')}</Button>
                        </Form.Item>
                    </Form>
                    <Typography.Paragraph type="secondary" className="!mb-1">{t('profileUsernameImmutable', { username: user.username })}</Typography.Paragraph>
                    <Typography.Paragraph type="secondary" className="!mb-0">{t('profileLoginHint')}</Typography.Paragraph>
                </div>
            </Card>

            <Card title={<span className="text-xl">{t('profileEmailSettings')}</span>} className="shadow-xl">
                <div className="max-w-lg">
                    {emailChangeLockedUntil && (
                        <Alert className="mb-4" type="info" showIcon message={t('profileEmailChangeLocked', { time: formatDateTime(emailChangeLockedUntil) })} />
                    )}
                    {!emailVerificationEnabled && (
                        <Alert className="mb-4" type="warning" showIcon message={t('profileEmailVerificationOff')} />
                    )}
                    <Form form={emailForm} onFinish={submitEmail} layout="vertical" disabled={!!emailChangeLockedUntil}>
                        <Form.Item
                            name="email"
                            label={t('profileNewEmail')}
                            rules={[{ required: true, message: t('registerEmailRequired') }, { type: 'email', message: t('registerEmailInvalid') }]}
                        >
                            <Input prefix={<MailOutlined />} placeholder={t('registerEmailPlaceholder')} autoComplete="email" />
                        </Form.Item>
                        {emailVerificationEnabled && (
                            <Form.Item label={t('profileEmailCode')}>
                                <Space.Compact className="w-full">
                                    <Form.Item name="emailCode" noStyle rules={codeRule}>
                                        <Input prefix={<SafetyCertificateOutlined />} />
                                    </Form.Item>
                                    <Button loading={sendingEmailCode} disabled={!!emailChangeLockedUntil} onClick={sendEmailCode}>{t('profileSendCode')}</Button>
                                </Space.Compact>
                            </Form.Item>
                        )}
                        <Form.Item>
                            <Button type="primary" htmlType="submit" loading={savingEmail} disabled={!!emailChangeLockedUntil} className="bg-blue-600 hover:bg-blue-500">
                                {t('profileEmailChange')}
                            </Button>
                        </Form.Item>
                    </Form>
                </div>
            </Card>

            <Card title={<span className="text-xl">{t('profileSecuritySettings')}</span>} className="shadow-xl">
                <div className="max-w-lg">
                    <h3 className="text-lg mb-4">{t('profileUpdatePassword')}</h3>
                    {emailVerificationEnabled && user.email && (
                        <Typography.Paragraph type="secondary">{t('profilePasswordCodeHint', { email: user.email })}</Typography.Paragraph>
                    )}
                    <Form form={passwordForm} onFinish={submitPassword} layout="vertical">
                        <Form.Item
                            name="password"
                            label={t('profileNewPassword')}
                            rules={[{ required: true }, { min: 12, message: t('registerPasswordRule') }, { validator: (_, v) => !v || new TextEncoder().encode(v).length <= 72 ? Promise.resolve() : Promise.reject(new Error(t('registerPasswordByteRule'))) }]}
                        >
                            <Input.Password autoComplete="new-password" />
                        </Form.Item>
                        <Form.Item
                            name="confirm"
                            label={t('registerConfirmPassword')}
                            dependencies={['password']}
                            rules={[{ required: true }, ({ getFieldValue }) => ({ validator(_, v) { return !v || v === getFieldValue('password') ? Promise.resolve() : Promise.reject(new Error(t('registerPasswordMismatch'))); } })]}
                        >
                            <Input.Password autoComplete="new-password" />
                        </Form.Item>
                        {emailVerificationEnabled && (
                            <Form.Item label={t('profileEmailCode')}>
                                <Space.Compact className="w-full">
                                    <Form.Item name="emailCode" noStyle rules={codeRule}>
                                        <Input prefix={<SafetyCertificateOutlined />} />
                                    </Form.Item>
                                    <Button loading={sendingPasswordCode} onClick={sendPasswordCode}>{t('profileSendCode')}</Button>
                                </Space.Compact>
                            </Form.Item>
                        )}
                        <Form.Item>
                            <Button type="primary" htmlType="submit" loading={savingPassword} className="bg-blue-600 hover:bg-blue-500">
                                {t('profileUpdatePassword')}
                            </Button>
                        </Form.Item>
                    </Form>
                </div>
            </Card>
        </div>
    );
}
