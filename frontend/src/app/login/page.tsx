'use client';

import React from 'react';
import { Form, Input, Button, Card, App } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAuthStore } from '@/store/authStore';
import { api } from '@/lib/api';
import { useTranslation } from 'react-i18next';

interface LoginFormValues {
    username: string;
    password: string;
}

export default function LoginPage() {
    const router = useRouter();
    const login = useAuthStore((state) => state.login);
    const { message } = App.useApp();
    const { t } = useTranslation();

    const getRedirectPath = () => {
        if (typeof window === 'undefined') return '/';
        const redirect = new URLSearchParams(window.location.search).get('redirect') || '/';
        return redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/';
    };

    const onFinish = async (values: LoginFormValues) => {
        try {
            const res = await api('/api/auth/login', {
                method: 'POST',
                body: JSON.stringify(values),
            });
            const data = await res.json();

            if (res.ok) {
                message.success(t('loginSuccess'));

                // Fetch real user info first, then navigate
                const meRes = await api('/api/auth/me', {
                    headers: { 'Authorization': `Bearer ${data.token}` }
                });
                if (meRes.ok) {
                    const me = await meRes.json();
                    login(data.token, me);
                    router.push(getRedirectPath());
                } else {
                    message.error(t('loginUserInfoFailed'));
                }
            } else {
                message.error(data.message || data.error || t('loginFailed'));
            }
        } catch {
            message.error(t('networkError'));
        }
    };

    return (
        <div className="flex justify-center items-center min-h-[80vh] px-4">
            <Card title={t('loginTitle')} className="w-full max-w-md dark:bg-gray-900 dark:border-gray-800" styles={{ header: { color: 'inherit' } }}>
                <Form onFinish={onFinish} size="large">
                    <Form.Item name="username" rules={[{ required: true, message: t('loginUsernameRequired') }]}>
                        <Input prefix={<UserOutlined />} placeholder={t('loginIdentifier')} autoComplete="username" />
                    </Form.Item>
                    <Form.Item name="password" rules={[{ required: true, message: t('loginPasswordRequired') }]}>
                        <Input.Password prefix={<LockOutlined />} placeholder={t('password')} autoComplete="current-password" />
                    </Form.Item>
                    <Form.Item>
                        <Button type="primary" htmlType="submit" block className="bg-blue-600">
                            {t('loginSubmit')}
                        </Button>
                    </Form.Item>
                    <div className="text-center">
                        <Link href="/register" className="text-blue-500 dark:text-blue-400">{t('loginNoAccount')} {t('loginSignUp')}</Link>
                    </div>
                </Form>
            </Card>
        </div>
    );
}
