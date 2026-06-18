'use client';

import React from 'react';
import { Form, Input, Button, Card, App } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAuthStore } from '@/store/authStore';
import { api } from '@/lib/api';

interface LoginFormValues {
    username: string;
    password: string;
}

export default function LoginPage() {
    const router = useRouter();
    const login = useAuthStore((state) => state.login);
    const { message } = App.useApp();

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
                message.success('Login successful!');

                // Fetch real user info first, then navigate
                const meRes = await api('/api/auth/me', {
                    headers: { 'Authorization': `Bearer ${data.token}` }
                });
                if (meRes.ok) {
                    const me = await meRes.json();
                    login(data.token, me);
                    router.push(getRedirectPath());
                } else {
                    message.error('Failed to fetch user info');
                }
            } else {
                message.error(data.error || 'Login failed');
            }
        } catch {
            message.error('Network error');
        }
    };

    return (
        <div className="flex justify-center items-center min-h-[80vh] px-4">
            <Card title="Sign In" className="w-full max-w-md dark:bg-gray-900 dark:border-gray-800" styles={{ header: { color: 'inherit' } }}>
                <Form onFinish={onFinish} size="large">
                    <Form.Item name="username" rules={[{ required: true, message: 'Please input your Username!' }]}>
                        <Input prefix={<UserOutlined />} placeholder="Username" />
                    </Form.Item>
                    <Form.Item name="password" rules={[{ required: true, message: 'Please input your Password!' }]}>
                        <Input.Password prefix={<LockOutlined />} placeholder="Password" />
                    </Form.Item>
                    <Form.Item>
                        <Button type="primary" htmlType="submit" block className="bg-blue-600">
                            Log in
                        </Button>
                    </Form.Item>
                    <div className="text-center">
                        <Link href="/register" className="text-blue-500 dark:text-blue-400">Don&apos;t have an account? Sign Up</Link>
                    </div>
                </Form>
            </Card>
        </div>
    );
}
