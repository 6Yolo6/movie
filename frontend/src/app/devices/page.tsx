"use client";

import { App, Button, Card, Empty, List, Popconfirm, Space, Tag, Typography } from "antd";
import { DesktopOutlined, MobileOutlined, SafetyCertificateOutlined } from "@ant-design/icons";
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslation } from "react-i18next";
import { api, readApiError } from "@/lib/api";
import { useAuthStore } from "@/store/authStore";

interface LoginDevice {
    id: number;
    deviceName: string;
    ipAddress?: string;
    userAgent?: string;
    loginAt: string;
    lastSeenAt: string;
    expiresAt: string;
    current?: boolean;
}

export default function LoginDevicesPage() {
    const { user, token, logout } = useAuthStore();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const router = useRouter();
    const [devices, setDevices] = useState<LoginDevice[]>([]);
    const [loading, setLoading] = useState(true);
    const [revoking, setRevoking] = useState<number | null>(null);

    const load = useCallback(async () => {
        if (!token) { setLoading(false); return; }
        setLoading(true);
        try {
            const response = await api('/api/auth/devices', { headers: { Authorization: `Bearer ${token}` } });
            if (!response.ok) {
                message.error(await readApiError(response, t('devicesLoadFailed')));
                return;
            }
            const data = await response.json();
            setDevices(data.devices || []);
        } finally {
            setLoading(false);
        }
    }, [message, t, token]);

    useEffect(() => {
        if (!user) { router.push('/login?redirect=/devices'); return; }
        load();
    }, [load, router, user]);

    const revoke = async (device: LoginDevice) => {
        setRevoking(device.id);
        try {
            const response = await api(`/api/auth/devices/${device.id}`, {
                method: 'DELETE',
                headers: { Authorization: `Bearer ${token}` },
            });
            if (!response.ok) {
                message.error(await readApiError(response, t('deviceRevokeFailed')));
                return;
            }
            if (device.current) {
                logout();
                message.success(t('deviceRevoked'));
                router.push('/login');
            } else {
                message.success(t('deviceRevoked'));
                load();
            }
        } finally {
            setRevoking(null);
        }
    };

    return <main className="mx-auto max-w-4xl p-4 sm:p-8">
        <Card loading={loading} title={<Space><SafetyCertificateOutlined className="text-blue-500" />{t('loginDevices')}</Space>}>
            <Typography.Paragraph type="secondary">{t('loginDevicesHint')}</Typography.Paragraph>
            {devices.length === 0 ? <Empty description={t('noLoginDevices')} /> : <List
                dataSource={devices}
                renderItem={(device) => <List.Item actions={[<Popconfirm key="revoke" title={t('revokeDeviceConfirm')} onConfirm={() => revoke(device)} okType="danger"><Button danger loading={revoking === device.id}>{t('revokeDevice')}</Button></Popconfirm>]}>
                    <List.Item.Meta
                        avatar={device.deviceName?.includes('Android') || device.deviceName?.includes('iOS') ? <MobileOutlined className="text-xl" /> : <DesktopOutlined className="text-xl" />}
                        title={<Space>{device.deviceName || t('unknownDevice')}{device.current && <Tag color="green">{t('currentDevice')}</Tag>}</Space>}
                        description={<div className="space-y-1 text-xs"><div>{t('loginIp')}: {device.ipAddress || '-'}</div><div>{t('loginAt')}: {new Date(device.loginAt).toLocaleString()}</div><div>{t('lastSeenAt')}: {new Date(device.lastSeenAt).toLocaleString()}</div></div>}
                    />
                </List.Item>}
            />}
        </Card>
    </main>;
}
