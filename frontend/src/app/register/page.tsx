'use client';

import React, { useCallback, useEffect, useState } from 'react';
import { Form, Input, Button, Card, App, Space } from 'antd';
import { UserOutlined, LockOutlined, SafetyCertificateOutlined, ReloadOutlined } from '@ant-design/icons';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api } from '@/lib/api';

interface RegisterFormValues {
    username: string;
    password: string;
    confirm: string;
    captchaCode: string;
}

interface CaptchaResponse {
    captchaId: string;
    image: string;
}

export default function RegisterPage() {
    const router = useRouter();
    const { message } = App.useApp();
    const [captcha, setCaptcha] = useState<CaptchaResponse | null>(null);
    const [captchaLoading, setCaptchaLoading] = useState(false);

    const loadCaptcha = useCallback(async () => {
        setCaptchaLoading(true);
        try {
            const res = await api('/api/captcha/generate');
            if (res.ok) {
                setCaptcha(await res.json());
            } else {
                message.error('Failed to load captcha');
            }
        } catch {
            message.error('Network error');
        } finally {
            setCaptchaLoading(false);
        }
    }, [message]);

    useEffect(() => {
        loadCaptcha();
    }, [loadCaptcha]);

    const onFinish = async (values: RegisterFormValues) => {
        if (!captcha?.captchaId) {
            message.error('Please refresh the captcha');
            return;
        }
        try {
            const query = new URLSearchParams({
                captchaId: captcha.captchaId,
                captchaCode: values.captchaCode,
            });
            const res = await api(`/api/auth/register?${query.toString()}`, {
                method: 'POST',
                body: JSON.stringify({ username: values.username, password: values.password }),
            });
            const data = await res.json();

            if (res.ok) {
                message.success('Registration successful! Please login.');
                router.push('/login');
            } else {
                message.error(data.error || 'Registration failed');
                loadCaptcha();
            }
        } catch {
            message.error('Network error');
        }
    };

    return (
        <div className="flex justify-center items-center min-h-[80vh] px-4">
            <Card title="Sign Up" className="w-full max-w-md shadow-xl">
                <Form onFinish={onFinish} size="large" layout="vertical">
                    <Form.Item name="username" rules={[{ required: true, message: 'Please choose a Username!' }]}>
                        <Input prefix={<UserOutlined />} placeholder="Username" />
                    </Form.Item>
                    <Form.Item name="password" rules={[{ required: true, min: 6, message: 'Password must be at least 6 characters' }]}>
                        <Input.Password prefix={<LockOutlined />} placeholder="Password" />
                    </Form.Item>
                    <Form.Item name="confirm" dependencies={['password']} hasFeedback rules={[
                        { required: true, message: 'Please confirm your password!' },
                        ({ getFieldValue }) => ({
                            validator(_, value) {
                                if (!value || getFieldValue('password') === value) {
                                    return Promise.resolve();
                                }
                                return Promise.reject(new Error('Passwords do not match!'));
                            },
                        }),
                    ]}>
                        <Input.Password prefix={<LockOutlined />} placeholder="Confirm Password" />
                    </Form.Item>
                    <Form.Item name="captchaCode" rules={[{ required: true, message: 'Please enter the captcha!' }]}>
                        <Space.Compact className="w-full">
                            <Input prefix={<SafetyCertificateOutlined />} placeholder="Captcha" />
                            <Button onClick={loadCaptcha} loading={captchaLoading} icon={<ReloadOutlined />} />
                        </Space.Compact>
                    </Form.Item>
                    {captcha?.image && (
                        <button type="button" onClick={loadCaptcha} className="mb-4 block rounded border border-gray-200 dark:border-zinc-700 overflow-hidden">
                            {/* eslint-disable-next-line @next/next/no-img-element */}
                            <img src={captcha.image} alt="captcha" className="h-[42px] w-[120px] object-cover bg-white" />
                        </button>
                    )}
                    <Form.Item>
                        <Button type="primary" htmlType="submit" block className="bg-green-600">
                            Register
                        </Button>
                    </Form.Item>
                    <div className="text-center">
                        <Link href="/login" className="text-blue-500">Already have an account? Sign In</Link>
                    </div>
                </Form>
            </Card>
        </div>
    );
}
