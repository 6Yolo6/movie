'use client';

import { useCallback, useEffect, useState } from 'react';
import { App, Button, Card, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import { CheckOutlined, CloseOutlined, DeleteOutlined, ExclamationCircleOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useRouter } from 'next/navigation';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/store/authStore';
import { api } from '@/lib/api';
import { ResourceLink } from '@/types';

const { Title } = Typography;
const { Option } = Select;
const { Search } = Input;
const { confirm } = Modal;

type AdminResource = ResourceLink & {
    movieTitle?: string;
    uploaderName?: string;
};

export default function ResourceManagementPage() {
    const { user, token } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [loading, setLoading] = useState(true);
    const [resources, setResources] = useState<AdminResource[]>([]);
    const [page, setPage] = useState(1);
    const [total, setTotal] = useState(0);
    const [statusFilter, setStatusFilter] = useState<number | undefined>();
    const [linkStatusFilter, setLinkStatusFilter] = useState<string | undefined>();
    const [keyword, setKeyword] = useState('');
    const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);

    const fetchResources = useCallback(async () => {
        if (!token) return;

        setLoading(true);
        try {
            const query = new URLSearchParams({
                page: String(page),
                size: '20',
            });
            if (statusFilter !== undefined && statusFilter !== -1) query.set('status', String(statusFilter));
            if (linkStatusFilter) query.set('linkStatus', linkStatusFilter);
            if (keyword) query.set('keyword', keyword);

            const res = await api(`/api/resources/admin/all?${query.toString()}`, {
                headers: {
                    Authorization: `Bearer ${token}`,
                },
            });

            if (res.ok) {
                const data = await res.json();
                setResources(data.records);
                setTotal(data.total);
            } else {
                message.error(t('resourcesLoadFailed'));
            }
        } catch {
            message.error(t('networkError'));
        } finally {
            setLoading(false);
        }
    }, [keyword, linkStatusFilter, message, page, statusFilter, t, token]);

    useEffect(() => {
        const timer = setTimeout(() => {
            if (!user || user.role !== 'ADMIN') {
                message.error(t('adminAccessRequired'));
                router.push('/');
                return;
            }
            fetchResources();
        }, 100);
        return () => clearTimeout(timer);
    }, [fetchResources, message, router, t, user]);

    const handleAudit = async (id: number, status: number) => {
        try {
            const res = await api(`/api/resources/${id}/audit?status=${status}`, {
                method: 'PUT',
                headers: {
                    Authorization: `Bearer ${token}`,
                },
            });

            if (res.ok) {
                message.success(status === 1 ? t('resourceApproved') : t('resourceRejected'));
                fetchResources();
            } else {
                const msg = await res.text();
                message.error(msg || t('operationFailed'));
            }
        } catch {
            message.error(t('networkError'));
        }
    };

    const handleLinkStatus = async (id: number, status: string) => {
        try {
            const res = await api(`/api/resources/admin/${id}/link-status?status=${status}`, {
                method: 'PUT',
                headers: {
                    Authorization: `Bearer ${token}`,
                },
            });
            if (res.ok) {
                message.success(t('linkStatusUpdated'));
                fetchResources();
            } else {
                message.error(t('operationFailed'));
            }
        } catch {
            message.error(t('networkError'));
        }
    };

    const handleBatchAudit = (status: number) => {
        if (selectedRowKeys.length === 0) {
            message.warning(t('selectResourcesFirst'));
            return;
        }

        const actionText = status === 1 ? t('approve') : t('reject');
        confirm({
            title: t('batchActionTitle', { action: actionText }),
            icon: <ExclamationCircleOutlined />,
            content: t('batchActionContent', { action: actionText, count: selectedRowKeys.length }),
            onOk: async () => {
                try {
                    const res = await api('/api/resources/batch/audit', {
                        method: 'PUT',
                        headers: {
                            Authorization: `Bearer ${token}`,
                            'Content-Type': 'application/json',
                        },
                        body: JSON.stringify({
                            ids: selectedRowKeys,
                            status,
                        }),
                    });

                    if (res.ok) {
                        const msg = await res.text();
                        message.success(msg);
                        setSelectedRowKeys([]);
                        fetchResources();
                    } else {
                        message.error(t('batchOperationFailed'));
                    }
                } catch {
                    message.error(t('networkError'));
                }
            },
        });
    };

    const handleDelete = async (id: number) => {
        try {
            const res = await api(`/api/resources/admin/${id}`, {
                method: 'DELETE',
                headers: {
                    Authorization: `Bearer ${token}`,
                },
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

    const handleBatchDelete = () => {
        if (selectedRowKeys.length === 0) {
            message.warning(t('selectResourcesFirst'));
            return;
        }

        confirm({
            title: t('batchDeleteTitle'),
            icon: <ExclamationCircleOutlined />,
            content: t('batchDeleteContent', { count: selectedRowKeys.length }),
            okText: t('delete'),
            okType: 'danger',
            onOk: async () => {
                try {
                    const res = await api('/api/resources/admin/batch', {
                        method: 'DELETE',
                        headers: {
                            Authorization: `Bearer ${token}`,
                            'Content-Type': 'application/json',
                        },
                        body: JSON.stringify(selectedRowKeys),
                    });

                    if (res.ok) {
                        const msg = await res.text();
                        message.success(msg);
                        setSelectedRowKeys([]);
                        fetchResources();
                    } else {
                        message.error(t('batchDeleteFailed'));
                    }
                } catch {
                    message.error(t('networkError'));
                }
            },
        });
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

    const columns: ColumnsType<AdminResource> = [
        {
            title: t('movieTitle'),
            dataIndex: 'movieTitle',
            key: 'movieTitle',
            width: 180,
            render: (_: string, record) => record.movieTitle || record.movieId,
        },
        {
            title: t('resourceNameColumn'),
            dataIndex: 'name',
            key: 'name',
            ellipsis: true,
        },
        {
            title: t('uploader'),
            dataIndex: 'uploaderName',
            key: 'uploaderName',
            width: 120,
            render: (_: string, record) => record.uploaderName || record.uploaderId || '-',
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
            width: 260,
            fixed: 'right' as const,
            render: (_: unknown, record) => (
                <Space wrap>
                    {record.auditStatus === 0 && (
                        <>
                            <Button type="primary" icon={<CheckOutlined />} onClick={() => handleAudit(record.id, 1)} size="small" />
                            <Button danger icon={<CloseOutlined />} onClick={() => handleAudit(record.id, 2)} size="small" />
                        </>
                    )}
                    <Button size="small" onClick={() => handleLinkStatus(record.id, 'NORMAL')}>{t('markNormal')}</Button>
                    <Button size="small" danger onClick={() => handleLinkStatus(record.id, 'INVALID')}>{t('markInvalid')}</Button>
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
                </Space>
            ),
        },
    ];

    return (
        <div className="container mx-auto px-4 py-8">
            <Card>
                <Title level={2}>{t('resourceManagement')}</Title>

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
                    <Search
                        placeholder={t('searchResources')}
                        onSearch={(value) => {
                            setKeyword(value.trim());
                            setPage(1);
                        }}
                        style={{ width: 340 }}
                        allowClear
                    />

                    {selectedRowKeys.length > 0 && (
                        <>
                            <span className="text-gray-600 dark:text-gray-400">{t('selectedCount', { count: selectedRowKeys.length })}</span>
                            <Button type="primary" onClick={() => handleBatchAudit(1)}>{t('batchApprove')}</Button>
                            <Button onClick={() => handleBatchAudit(2)}>{t('batchReject')}</Button>
                            <Button danger onClick={handleBatchDelete}>{t('batchDelete')}</Button>
                        </>
                    )}
                </Space>

                <Table
                    rowSelection={{
                        selectedRowKeys,
                        onChange: setSelectedRowKeys,
                    }}
                    columns={columns}
                    dataSource={resources}
                    rowKey="id"
                    loading={loading}
                    scroll={{ x: 1700 }}
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
    );
}
