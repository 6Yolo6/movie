'use client';

import React, { useEffect } from 'react';
import { Form, Input, Button, Card, App } from 'antd';
import { useAuthStore } from '@/store/authStore';
import { useRouter } from 'next/navigation';
import { api } from '@/lib/api';
import { useTranslation } from 'react-i18next';

interface ResetPasswordValues {
    password: string;
}

export default function ProfilePage() {
    const { user, token } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const { t } = useTranslation();

    useEffect(() => {
        if (!token) {
            router.push('/login');
        }
    }, [token, router]);

    const onResetPassword = async (values: ResetPasswordValues) => {
        try {
            const res = await api('/api/auth/reset-password', {
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${token}`
                },
                body: JSON.stringify(values),
            });
            const data = await res.json();

            if (res.ok) {
                message.success(t('profilePasswordUpdated'));
            } else {
                message.error(data.error || t('profilePasswordFailed'));
            }
        } catch {
            message.error(t('networkError'));
        }
    };

    if (!user) return null;

    return (
        <div className="container mx-auto p-8 max-w-4xl min-h-[80vh] flex flex-col gap-8">
            <div className="flex items-center gap-6">
                <div className="w-24 h-24 rounded-full bg-gradient-to-r from-blue-500 to-cyan-500 flex items-center justify-center text-4xl font-bold text-black shadow-lg">
                    {user.username.charAt(0).toUpperCase()}
                </div>
                <div>
                    <h1 className="text-3xl font-bold mb-2">{user.username}</h1>
                    <div className="flex gap-2">
                        <span className="bg-blue-900 text-blue-300 px-3 py-1 rounded-full text-sm border border-blue-700">
                            {user.role}
                        </span>
                    </div>
                </div>
            </div>

            <Card title={<span className="text-xl">{t('profileSecuritySettings')}</span>} className="shadow-xl">
                <div className="max-w-md">
                    <h3 className="text-lg mb-4">{t('profileUpdatePassword')}</h3>
                    <Form onFinish={onResetPassword} layout="vertical">
                        <Form.Item
                            name="password"
                            label={t('profileNewPassword')}
                            rules={[{ required: true, min: 6, message: t('profilePasswordRule') }]}
                        >
                            <Input.Password />
                        </Form.Item>
                        <Form.Item>
                            <Button type="primary" htmlType="submit" size="large" className="bg-blue-600 hover:bg-blue-500">
                                {t('profileUpdatePassword')}
                            </Button>
                        </Form.Item>
                    </Form>
                </div>
            </Card>
        </div>
    );
}
