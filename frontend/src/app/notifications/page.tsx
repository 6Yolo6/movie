'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { App, Badge, Button, Card, Empty, List, Space, Switch, Tag, Typography } from 'antd';
import { BellOutlined, CheckOutlined, CloudUploadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { api } from '@/lib/api';
import { UserNotification } from '@/types';
import { useAuthStore } from '@/store/authStore';

const { Title, Text, Paragraph } = Typography;

export default function NotificationsPage() {
    const { token } = useAuthStore();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [loading, setLoading] = useState(true);
    const [notifications, setNotifications] = useState<UserNotification[]>([]);
    const [page, setPage] = useState(1);
    const [total, setTotal] = useState(0);
    const [unreadOnly, setUnreadOnly] = useState(false);
    const [mounted, setMounted] = useState(false);

    const refreshNavbarCount = () => {
        window.dispatchEvent(new Event('notifications:refresh'));
    };

    const fetchNotifications = useCallback(async () => {
        if (!token) {
            setLoading(false);
            return;
        }

        setLoading(true);
        try {
            const query = new URLSearchParams({
                page: String(page),
                size: '20',
                unreadOnly: String(unreadOnly),
            });
            const res = await api(`/api/notifications?${query.toString()}`, {
                headers: { Authorization: `Bearer ${token}` },
            });
            if (res.ok) {
                const data = await res.json();
                setNotifications(data.records || []);
                setTotal(data.total || 0);
            } else {
                message.error(t('notificationsLoadFailed'));
            }
        } catch {
            message.error(t('networkError'));
        } finally {
            setLoading(false);
        }
    }, [message, page, t, token, unreadOnly]);

    useEffect(() => {
        setMounted(true);
    }, []);

    useEffect(() => {
        fetchNotifications();
    }, [fetchNotifications]);

    const markRead = async (id: number) => {
        if (!token) return;
        try {
            const res = await api(`/api/notifications/${id}/read`, {
                method: 'PUT',
                headers: { Authorization: `Bearer ${token}` },
            });
            if (res.ok) {
                message.success(t('notificationMarkedRead'));
                fetchNotifications();
                refreshNavbarCount();
            } else {
                message.error(t('operationFailed'));
            }
        } catch {
            message.error(t('networkError'));
        }
    };

    const markAllRead = async () => {
        if (!token) return;
        try {
            const res = await api('/api/notifications/read-all', {
                method: 'PUT',
                headers: { Authorization: `Bearer ${token}` },
            });
            if (res.ok) {
                message.success(t('notificationsMarkedRead'));
                setPage(1);
                fetchNotifications();
                refreshNavbarCount();
            } else {
                message.error(t('operationFailed'));
            }
        } catch {
            message.error(t('networkError'));
        }
    };

    const getTypeTag = (type: string) => {
        switch (type) {
            case 'RESOURCE_AUDIT':
                return <Tag color="blue">{t('resourceAudit')}</Tag>;
            case 'RESOURCE_LINK_STATUS':
                return <Tag color="orange">{t('linkStatus')}</Tag>;
            default:
                return <Tag>{type}</Tag>;
        }
    };

    if (!mounted) {
        return <div className="min-h-screen bg-[#f5f7fa] dark:bg-black" />;
    }

    if (!token) {
        return (
            <div className="min-h-screen bg-[#f5f7fa] dark:bg-black flex items-center justify-center">
                <div className="text-center">
                    <BellOutlined className="text-5xl text-gray-300 dark:text-gray-600 mb-4" />
                    <p className="text-gray-500 dark:text-gray-400 mb-4">{t('loginRequired')}</p>
                    <Link href="/login">
                        <Button type="primary">{t('signIn')}</Button>
                    </Link>
                </div>
            </div>
        );
    }

    return (
        <div className="min-h-screen bg-[#f5f7fa] dark:bg-black">
            <div className="container mx-auto px-4 lg:px-8 py-8">
                <Card>
                    <div className="mb-6 flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
                        <div className="flex items-center gap-3">
                            <Badge dot={notifications.some(item => !item.readFlag)}>
                                <BellOutlined className="text-3xl text-blue-500" />
                            </Badge>
                            <div>
                                <Title level={2} className="!mb-1">{t('notifications')}</Title>
                                <Text type="secondary">{t('notificationsHint')}</Text>
                            </div>
                        </div>
                        <Space wrap>
                            <Space>
                                <Text>{t('unreadNotifications')}</Text>
                                <Switch
                                    checked={unreadOnly}
                                    onChange={(checked) => {
                                        setUnreadOnly(checked);
                                        setPage(1);
                                    }}
                                    size="small"
                                />
                            </Space>
                            <Button icon={<CheckOutlined />} onClick={markAllRead}>
                                {t('markAllRead')}
                            </Button>
                        </Space>
                    </div>

                    <List
                        loading={loading}
                        dataSource={notifications}
                        locale={{ emptyText: <Empty description={t('noNotifications')} /> }}
                        pagination={{
                            current: page,
                            pageSize: 20,
                            total,
                            onChange: setPage,
                            showTotal: (totalCount) => t('totalNotifications', { count: totalCount }),
                        }}
                        renderItem={(item) => (
                            <List.Item
                                className={!item.readFlag ? 'bg-blue-50/70 dark:bg-blue-950/20 px-4 rounded-md' : 'px-4'}
                                actions={[
                                    item.targetType === 'RESOURCE' ? (
                                        <Link key="target" href="/my-resources">{t('viewMyResources')}</Link>
                                    ) : null,
                                    !item.readFlag ? (
                                        <Button
                                            key="read"
                                            size="small"
                                            type="text"
                                            icon={<CheckOutlined />}
                                            onClick={() => markRead(item.id)}
                                        >
                                            {t('markRead')}
                                        </Button>
                                    ) : null,
                                ].filter(Boolean)}
                            >
                                <List.Item.Meta
                                    avatar={<CloudUploadOutlined className="text-xl text-blue-500 mt-1" />}
                                    title={
                                        <Space wrap>
                                            {!item.readFlag && <Badge status="processing" />}
                                            <span>{item.title}</span>
                                            {getTypeTag(item.type)}
                                        </Space>
                                    }
                                    description={
                                        <div>
                                            {item.content && <Paragraph className="!mb-1">{item.content}</Paragraph>}
                                            <Text type="secondary">
                                                {item.createdAt ? new Date(item.createdAt).toLocaleString() : '-'}
                                            </Text>
                                        </div>
                                    }
                                />
                            </List.Item>
                        )}
                    />
                </Card>
            </div>
        </div>
    );
}
