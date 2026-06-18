'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { App, Button, Card, Empty, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import { CloudUploadOutlined, DeleteOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { api } from '@/lib/api';
import { ResourceLink } from '@/types';
import { useAuthStore } from '@/store/authStore';

const { Title, Text } = Typography;
const { Option } = Select;

type MyResource = ResourceLink & {
    movieTitle?: string;
};

export default function MyResourcesPage() {
    const { token } = useAuthStore();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [loading, setLoading] = useState(true);
    const [resources, setResources] = useState<MyResource[]>([]);
    const [page, setPage] = useState(1);
    const [total, setTotal] = useState(0);
    const [statusFilter, setStatusFilter] = useState<number | undefined>();
    const [linkStatusFilter, setLinkStatusFilter] = useState<string | undefined>();

    const fetchResources = useCallback(async () => {
        if (!token) {
            setLoading(false);
            return;
        }

        setLoading(true);
        try {
            const query = new URLSearchParams({
                page: String(page),
                size: '20',
            });
            if (statusFilter !== undefined && statusFilter !== -1) query.set('status', String(statusFilter));
            if (linkStatusFilter) query.set('linkStatus', linkStatusFilter);

            const res = await api(`/api/resources/mine?${query.toString()}`, {
                headers: { Authorization: `Bearer ${token}` },
            });
            if (res.ok) {
                const data = await res.json();
                setResources(data.records);
                setTotal(data.total);
            } else {
                message.error(t('myResourcesLoadFailed'));
            }
        } catch {
            message.error(t('networkError'));
        } finally {
            setLoading(false);
        }
    }, [linkStatusFilter, message, page, statusFilter, t, token]);

    useEffect(() => {
        fetchResources();
    }, [fetchResources]);

    const handleDelete = async (id: number) => {
        if (!token) return;
        try {
            const res = await api(`/api/resources/${id}`, {
                method: 'DELETE',
                headers: { Authorization: `Bearer ${token}` },
            });
            if (res.ok) {
                message.success(t('resourceDeleted'));
                fetchResources();
            } else {
                message.error(t('resourceDeleteFailed'));
            }
        } catch {
            message.error(t('networkError'));
        }
    };

    const getAuditStatusTag = (status: number) => {
        switch (status) {
            case 0: return <Tag color="orange">{t('pending')}</Tag>;
            case 1: return <Tag color="green">{t('approved')}</Tag>;
            case 2: return <Tag color="red">{t('rejected')}</Tag>;
            default: return <Tag>{t('unknown')}</Tag>;
        }
    };

    const getLinkStatusTag = (status?: string) => {
        switch (status) {
            case 'NORMAL': return <Tag color="green">{t('normal')}</Tag>;
            case 'SUSPECTED_INVALID': return <Tag color="orange">{t('suspectedInvalid')}</Tag>;
            case 'INVALID': return <Tag color="red">{t('invalid')}</Tag>;
            default: return <Tag>{t('unknown')}</Tag>;
        }
    };

    const columns: ColumnsType<MyResource> = [
        {
            title: t('movieTitle'),
            dataIndex: 'movieTitle',
            key: 'movieTitle',
            width: 180,
            render: (_: string, record) => (
                <Link href={`/movie/${record.movieId}`} className="text-blue-500 hover:underline">
                    {record.movieTitle || record.movieId}
                </Link>
            ),
        },
        {
            title: t('resourceNameColumn'),
            dataIndex: 'name',
            key: 'name',
            ellipsis: true,
            render: (name: string) => name || t('resource'),
        },
        {
            title: t('type'),
            dataIndex: 'type',
            key: 'type',
            width: 100,
            render: (type: string) => (
                <Tag color={type === 'DISK' ? 'green' : type === 'MAGNET' ? 'purple' : 'blue'}>{type}</Tag>
            ),
        },
        {
            title: t('provider'),
            dataIndex: 'provider',
            key: 'provider',
            width: 120,
        },
        {
            title: t('url'),
            dataIndex: 'url',
            key: 'url',
            ellipsis: true,
            render: (url: string) => (
                <a href={url} target="_blank" rel="noopener noreferrer" className="text-blue-500">{url}</a>
            ),
        },
        {
            title: t('status'),
            dataIndex: 'auditStatus',
            key: 'auditStatus',
            width: 100,
            render: getAuditStatusTag,
        },
        {
            title: t('linkStatus'),
            dataIndex: 'linkStatus',
            key: 'linkStatus',
            width: 140,
            render: getLinkStatusTag,
        },
        {
            title: t('reportCount'),
            dataIndex: 'reportCount',
            key: 'reportCount',
            width: 90,
        },
        {
            title: t('submitted'),
            dataIndex: 'createdAt',
            key: 'createdAt',
            width: 180,
            render: (date: string) => date ? new Date(date).toLocaleString() : '-',
        },
        {
            title: t('actions'),
            key: 'actions',
            width: 90,
            fixed: 'right',
            render: (_: unknown, record) => (
                <Popconfirm
                    title={t('deleteResourceTitle')}
                    description={t('deleteResourceDescription')}
                    onConfirm={() => handleDelete(record.id)}
                    okText={t('delete')}
                    cancelText={t('cancel')}
                    okType="danger"
                >
                    <Button danger icon={<DeleteOutlined />} size="small" />
                </Popconfirm>
            ),
        },
    ];

    if (!token) {
        return (
            <div className="min-h-screen bg-[#f5f7fa] dark:bg-black flex items-center justify-center">
                <div className="text-center">
                    <CloudUploadOutlined className="text-5xl text-gray-300 dark:text-gray-600 mb-4" />
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
                    <div className="mb-6 flex items-center gap-3">
                        <CloudUploadOutlined className="text-3xl text-blue-500" />
                        <div>
                            <Title level={2} className="!mb-1">{t('myResources')}</Title>
                            <Text type="secondary">{t('myResourcesHint')}</Text>
                        </div>
                    </div>

                    <Space className="mb-4" wrap>
                        <Select
                            placeholder={t('filterByStatus')}
                            style={{ width: 150 }}
                            allowClear
                            value={statusFilter}
                            onChange={(value) => {
                                setStatusFilter(value);
                                setPage(1);
                            }}
                        >
                            <Option value={-1}>{t('all')}</Option>
                            <Option value={0}>{t('pending')}</Option>
                            <Option value={1}>{t('approved')}</Option>
                            <Option value={2}>{t('rejected')}</Option>
                        </Select>
                        <Select
                            placeholder={t('linkStatus')}
                            style={{ width: 180 }}
                            allowClear
                            value={linkStatusFilter}
                            onChange={(value) => {
                                setLinkStatusFilter(value);
                                setPage(1);
                            }}
                        >
                            <Option value="NORMAL">{t('normal')}</Option>
                            <Option value="SUSPECTED_INVALID">{t('suspectedInvalid')}</Option>
                            <Option value="INVALID">{t('invalid')}</Option>
                        </Select>
                    </Space>

                    <Table
                        columns={columns}
                        dataSource={resources}
                        rowKey="id"
                        loading={loading}
                        scroll={{ x: 1300 }}
                        locale={{
                            emptyText: (
                                <Empty description={t('noMyResources')}>
                                    <Link href="/">
                                        <Button type="primary">{t('discoverMovies')}</Button>
                                    </Link>
                                </Empty>
                            ),
                        }}
                        pagination={{
                            current: page,
                            pageSize: 20,
                            total,
                            onChange: setPage,
                            showTotal: (totalCount) => t('totalResources', { count: totalCount }),
                        }}
                    />
                </Card>
            </div>
        </div>
    );
}
