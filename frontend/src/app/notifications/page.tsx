'use client';

import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import Link from 'next/link';
import { Alert, App, Badge, Button, Card, Empty, List, Space, Switch, Tag, Typography } from 'antd';
import { BellOutlined, CheckOutlined, CloudUploadOutlined, CommentOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { api, readApiError } from '@/lib/api';
import { UserNotification } from '@/types';
import { useAuthStore } from '@/store/authStore';

const { Title, Text, Paragraph } = Typography;
const PAGE_SIZE = 20;
const subscribeHydration = () => () => {};
const clientHydration = () => true;
const serverHydration = () => false;

export default function NotificationsPage() {
    const { token } = useAuthStore();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [page, setPage] = useState(1);
    const [unreadOnly, setUnreadOnly] = useState(false);
    const mounted = useSyncExternalStore(subscribeHydration, clientHydration, serverHydration);
    const [refresh, setRefresh] = useState(0);
    const mutationController = useRef<AbortController | null>(null);
    const query = '/api/notifications?' + new URLSearchParams({
        page: String(page), size: String(PAGE_SIZE), unreadOnly: String(unreadOnly),
    }).toString();
    const request = useMemo(() => ({ page, query, token, refresh, t }), [page, query, token, refresh, t]);
    const [snapshot, setSnapshot] = useState<{
        request: typeof request | null;
        notifications: UserNotification[];
        total: number;
        error: string | null;
    }>({ request: null, notifications: [], total: 0, error: null });
    // Only retain rows/totals from the same account and query during a refresh.
    const sameQuery = !!token && snapshot.request?.query === query && snapshot.request?.token === token;
    const notifications = sameQuery ? snapshot.notifications : [];
    const total = sameQuery ? snapshot.total : 0;
    const loading = !!token && snapshot.request !== request;
    const error = snapshot.request === request ? snapshot.error : null;

    const refreshNavbarCount = () => {
        window.dispatchEvent(new Event('notifications:refresh'));
    };

    useEffect(() => {
        const controller = new AbortController();
        mutationController.current = controller;
        return () => controller.abort();
    }, [token]);

    useEffect(() => {
        const { query, token, page, t } = request;
        if (!token) return;
        const controller = new AbortController();
        let active = true;
        const fail = (error: string) => {
            if (active) setSnapshot(previous => {
                const sameQuery = previous.request?.query === query && previous.request?.token === token;
                return { request, notifications: sameQuery ? previous.notifications : [],
                    total: sameQuery ? previous.total : 0, error };
            });
        };
        void (async () => {
            try {
                const response = await api(query, {
                    signal: controller.signal,
                    headers: { Authorization: 'Bearer ' + token },
                });
                if (!response.ok) {
                    fail(await readApiError(response, t('notificationsLoadFailed')));
                    return;
                }
                const data = await response.json();
                if (!active) return;
                const total = data.total || 0;
                const lastPage = Math.max(1, Math.ceil(total / PAGE_SIZE));
                if (page > lastPage) {
                    setPage(lastPage);
                    return;
                }
                setSnapshot({ request, notifications: data.records || [], total, error: null });
            } catch {
                fail(t('networkError'));
            }
        })();
        return () => { active = false; controller.abort(); };
    }, [request]);

    const markRead = async (id: number) => {
        const signal = mutationController.current?.signal;
        if (!token || !signal || signal.aborted) return;
        try {
            const res = await api('/api/notifications/' + id + '/read', {
                method: 'PUT', signal,
                headers: { Authorization: 'Bearer ' + token },
            });
            if (signal.aborted) return;
            if (res.ok) {
                message.success(t('notificationMarkedRead'));
                setRefresh(value => value + 1);
                refreshNavbarCount();
            } else {
                message.error(t('operationFailed'));
            }
        } catch {
            if (!signal.aborted) message.error(t('networkError'));
        }
    };

    const markAllRead = async () => {
        const signal = mutationController.current?.signal;
        if (!token || !signal || signal.aborted) return;
        try {
            const res = await api('/api/notifications/read-all', {
                method: 'PUT', signal,
                headers: { Authorization: 'Bearer ' + token },
            });
            if (signal.aborted) return;
            if (res.ok) {
                message.success(t('notificationsMarkedRead'));
                setPage(1);
                // Refresh through the effect, never through a captured old-page callback.
                setRefresh(value => value + 1);
                refreshNavbarCount();
            } else {
                message.error(t('operationFailed'));
            }
        } catch {
            if (!signal.aborted) message.error(t('networkError'));
        }
    };

    const getTypeTag = (type: string) => {
        switch (type) {
            case 'RESOURCE_AUDIT':
                return <Tag color="blue">{t('resourceAudit')}</Tag>;
            case 'RESOURCE_LINK_STATUS':
                return <Tag color="orange">{t('linkStatus')}</Tag>;
            case 'COMMENT_REPLY':
                return <Tag color="green">{t('reply')}</Tag>;
            default:
                return <Tag>{type}</Tag>;
        }
    };

    const getTargetAction = (item: UserNotification) => {
        if (item.targetType === 'RESOURCE') {
            return <Link key="target" href="/my-resources">{t('viewMyResources')}</Link>;
        }
        if (item.targetType === 'COMMENT' && item.targetId) {
            return <Link key="target" href={`/movie/${item.targetId}`}>{t('viewRelatedMovie')}</Link>;
        }
        return null;
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

                    {error && <Alert className="!mb-4" type="error" showIcon title={error}
                        action={<Button onClick={() => setRefresh(value => value + 1)}>{t('retry')}</Button>} />}
                    {(!error || notifications.length > 0) && <List<UserNotification>
                        loading={loading}
                        dataSource={notifications}
                        locale={{ emptyText: <Empty description={t('noNotifications')} /> }}
                        pagination={{
                            current: page,
                            pageSize: PAGE_SIZE,
                            showSizeChanger: false,
                            total,
                            onChange: setPage,
                            showTotal: (totalCount) => t('totalNotifications', { count: totalCount }),
                        }}
                        renderItem={(item) => (
                            <List.Item
                                className={!item.readFlag ? 'bg-blue-50/70 dark:bg-blue-950/20 px-4 rounded-md' : 'px-4'}
                                actions={[
                                    getTargetAction(item),
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
                                    avatar={item.targetType === 'COMMENT'
                                        ? <CommentOutlined className="text-xl text-green-500 mt-1" />
                                        : <CloudUploadOutlined className="text-xl text-blue-500 mt-1" />}
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
                    />}
                </Card>
            </div>
        </div>
    );
}
