'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
    App,
    Button,
    Card,
    Empty,
    Input,
    Modal,
    Popconfirm,
    Select,
    Space,
    Table,
    Tag,
    Tooltip,
    Typography,
} from 'antd';
import {
    CheckCircleOutlined,
    CheckOutlined,
    CloseOutlined,
    DeleteOutlined,
    EditOutlined,
    ExclamationCircleOutlined,
    EyeOutlined,
    PlusOutlined,
    ReloadOutlined,
    StopOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/store/authStore';
import { api, readApiError } from '@/lib/api';
import type { MovieMetadata, ResourceLink } from '@/types';
import AdminMovieModal from '@/components/admin/AdminMovieModal';
import AdminResourceModal from '@/components/admin/AdminResourceModal';

const { Title, Text } = Typography;
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
    const [resourceModalOpen, setResourceModalOpen] = useState(false);
    const [editingResource, setEditingResource] = useState<AdminResource | null>(null);
    const [movieModalOpen, setMovieModalOpen] = useState(false);
    const [createdMovie, setCreatedMovie] = useState<MovieMetadata | null>(null);

    const fetchResources = useCallback(async () => {
        if (!token) {
            setLoading(false);
            return;
        }
        setLoading(true);
        try {
            const query = new URLSearchParams({ page: String(page), size: '20' });
            if (statusFilter !== undefined && statusFilter !== -1) query.set('status', String(statusFilter));
            if (linkStatusFilter) query.set('linkStatus', linkStatusFilter);
            if (keyword) query.set('keyword', keyword);
            const res = await api(`/api/resources/admin/all?${query}`, {
                headers: { Authorization: `Bearer ${token}` },
            });
            if (!res.ok) {
                message.error(await readApiError(res, t('resourcesLoadFailed')));
                return;
            }
            const data = await res.json();
            setResources(data.records || []);
            setTotal(data.total || 0);
        } catch {
            message.error(t('networkError'));
        } finally {
            setLoading(false);
        }
    }, [keyword, linkStatusFilter, message, page, statusFilter, t, token]);

    useEffect(() => {
        if (!user) return;
        if (user.role !== 'ADMIN') {
            message.error(t('adminAccessRequired'));
            router.push('/');
            return;
        }
        fetchResources();
    }, [fetchResources, message, router, t, user]);

    const handleAudit = async (id: number, status: number) => {
        const res = await api(`/api/resources/${id}/audit?status=${status}`, {
            method: 'PUT',
            headers: { Authorization: `Bearer ${token}` },
        });
        if (!res.ok) {
            message.error(await readApiError(res, t('operationFailed')));
            return;
        }
        message.success(status === 1 ? t('resourceApproved') : t('resourceRejected'));
        fetchResources();
    };

    const handleLinkStatus = async (id: number, status: string) => {
        const res = await api(`/api/resources/admin/${id}/link-status?status=${status}`, {
            method: 'PUT',
            headers: { Authorization: `Bearer ${token}` },
        });
        if (!res.ok) {
            message.error(await readApiError(res, t('operationFailed')));
            return;
        }
        message.success(t('linkStatusUpdated'));
        fetchResources();
    };

    const handleDelete = async (id: number) => {
        const res = await api(`/api/resources/admin/${id}`, {
            method: 'DELETE',
            headers: { Authorization: `Bearer ${token}` },
        });
        if (!res.ok) {
            message.error(await readApiError(res, t('resourceDeleteFailed')));
            return;
        }
        message.success(t('resourceDeleted'));
        fetchResources();
    };

    const handleBatchAudit = (status: number) => {
        if (!selectedRowKeys.length) {
            message.warning(t('selectResourcesFirst'));
            return;
        }
        const actionText = status === 1 ? t('approve') : t('reject');
        confirm({
            title: t('batchActionTitle', { action: actionText }),
            icon: <ExclamationCircleOutlined />,
            content: t('batchActionContent', { action: actionText, count: selectedRowKeys.length }),
            onOk: async () => {
                const res = await api('/api/resources/batch/audit', {
                    method: 'PUT',
                    headers: { Authorization: `Bearer ${token}` },
                    body: JSON.stringify({ ids: selectedRowKeys, status }),
                });
                if (!res.ok) {
                    message.error(await readApiError(res, t('batchOperationFailed')));
                    return;
                }
                message.success(await res.text());
                setSelectedRowKeys([]);
                fetchResources();
            },
        });
    };

    const handleBatchDelete = () => {
        if (!selectedRowKeys.length) {
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
                const res = await api('/api/resources/admin/batch', {
                    method: 'DELETE',
                    headers: { Authorization: `Bearer ${token}` },
                    body: JSON.stringify(selectedRowKeys),
                });
                if (!res.ok) {
                    message.error(await readApiError(res, t('batchDeleteFailed')));
                    return;
                }
                message.success(await res.text());
                setSelectedRowKeys([]);
                fetchResources();
            },
        });
    };

    const auditStatusTag = (status: number) => {
        if (status === 0) return <Tag color="orange">{t('pending')}</Tag>;
        if (status === 1) return <Tag color="green">{t('approved')}</Tag>;
        if (status === 2) return <Tag color="red">{t('rejected')}</Tag>;
        return <Tag>{t('unknown')}</Tag>;
    };

    const linkStatusTag = (status?: string) => {
        if (status === 'NORMAL') return <Tag color="green">{t('normal')}</Tag>;
        if (status === 'SUSPECTED_INVALID') return <Tag color="orange">{t('suspectedInvalid')}</Tag>;
        if (status === 'INVALID') return <Tag color="red">{t('invalid')}</Tag>;
        return <Tag>{t('unknown')}</Tag>;
    };

    const ellipsisText = (value?: string) => (
        <Tooltip title={value || undefined}>
            <span className="block truncate">{value || '-'}</span>
        </Tooltip>
    );

    const columns: ColumnsType<AdminResource> = [
        {
            title: t('movieTitle'),
            dataIndex: 'movieTitle',
            width: 210,
            fixed: 'left',
            render: (_: string, record) => (
                <Tooltip title={record.movieTitle || record.movieId}>
                    <Link href={`/movie/${record.movieId}`} className="block truncate font-medium text-blue-600 hover:underline">
                        {record.movieTitle || record.movieId}
                    </Link>
                </Tooltip>
            ),
        },
        {
            title: t('resourceNameColumn'),
            dataIndex: 'name',
            width: 240,
            render: (value: string, record) => (
                <Tooltip title={value || t('resource')}>
                    <Link href={`/movie/${record.movieId}`} className="block truncate text-blue-600 hover:underline">
                        {value || t('resource')}
                    </Link>
                </Tooltip>
            ),
        },
        {
            title: t('resourceURL'),
            dataIndex: 'url',
            width: 330,
            render: (url: string) => (
                <Tooltip title={url}>
                    <a
                        href={url}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="block truncate text-blue-600 hover:underline"
                    >
                        {url}
                    </a>
                </Tooltip>
            ),
        },
        { title: t('uploader'), dataIndex: 'uploaderName', width: 130, render: (_: string, record) => ellipsisText(record.uploaderName || String(record.uploaderId || '')) },
        {
            title: t('type'),
            dataIndex: 'type',
            width: 100,
            render: (value: string) => <Tag color={value === 'DISK' ? 'green' : value === 'MAGNET' ? 'purple' : 'blue'}>{value}</Tag>,
        },
        { title: t('provider'), dataIndex: 'provider', width: 120, render: ellipsisText },
        { title: t('quality'), dataIndex: 'quality', width: 100, render: ellipsisText },
        { title: t('subtitle'), dataIndex: 'subtitle', width: 110, render: ellipsisText },
        { title: t('fileSize'), dataIndex: 'fileSize', width: 100, render: ellipsisText },
        { title: t('versionNote'), dataIndex: 'versionNote', width: 190, render: ellipsisText },
        { title: t('status'), dataIndex: 'auditStatus', width: 100, render: auditStatusTag },
        { title: t('linkStatus'), dataIndex: 'linkStatus', width: 140, render: linkStatusTag },
        { title: t('reportCount'), dataIndex: 'reportCount', width: 90 },
        {
            title: t('submitted'),
            dataIndex: 'createdAt',
            width: 180,
            render: (date?: string) => date ? new Date(date).toLocaleString() : '-',
        },
        {
            title: t('actions'),
            key: 'actions',
            width: 260,
            fixed: 'right',
            render: (_: unknown, record) => (
                <Space size={2}>
                    <Tooltip title={t('viewDetails')}>
                        <Button type="text" icon={<EyeOutlined />} aria-label={t('viewDetails')} onClick={() => router.push(`/movie/${record.movieId}`)} />
                    </Tooltip>
                    <Tooltip title={t('editResource')}>
                        <Button
                            type="text"
                            icon={<EditOutlined />}
                            aria-label={t('editResource')}
                            onClick={() => {
                                setEditingResource(record);
                                setCreatedMovie(null);
                                setResourceModalOpen(true);
                            }}
                        />
                    </Tooltip>
                    {record.auditStatus === 0 && (
                        <>
                            <Tooltip title={t('approve')}>
                                <Button type="text" icon={<CheckOutlined />} aria-label={t('approve')} onClick={() => handleAudit(record.id, 1)} />
                            </Tooltip>
                            <Tooltip title={t('reject')}>
                                <Button type="text" danger icon={<CloseOutlined />} aria-label={t('reject')} onClick={() => handleAudit(record.id, 2)} />
                            </Tooltip>
                        </>
                    )}
                    <Tooltip title={t('markNormal')}>
                        <Button
                            type="text"
                            icon={<CheckCircleOutlined />}
                            aria-label={t('markNormal')}
                            onClick={() => handleLinkStatus(record.id, 'NORMAL')}
                        />
                    </Tooltip>
                    <Tooltip title={t('markInvalid')}>
                        <Button
                            type="text"
                            danger
                            icon={<StopOutlined />}
                            aria-label={t('markInvalid')}
                            onClick={() => handleLinkStatus(record.id, 'INVALID')}
                        />
                    </Tooltip>
                    <Popconfirm
                        title={t('deleteResourceTitle')}
                        description={t('deleteResourceDescription')}
                        onConfirm={() => handleDelete(record.id)}
                        okText={t('delete')}
                        cancelText={t('cancel')}
                        okType="danger"
                    >
                        <Tooltip title={t('delete')}>
                            <Button type="text" danger icon={<DeleteOutlined />} aria-label={t('delete')} />
                        </Tooltip>
                    </Popconfirm>
                </Space>
            ),
        },
    ];

    return (
        <div className="min-h-screen bg-[#f5f7fa] dark:bg-black">
            <div className="container mx-auto px-4 py-8 lg:px-8">
                <Card>
                    <div className="mb-5 flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
                        <div>
                            <Title level={2} className="!mb-1">{t('resourceManagement')}</Title>
                            <Text type="secondary">{t('resourceManagementHint')}</Text>
                        </div>
                        <Space wrap>
                            <Button icon={<ReloadOutlined />} onClick={fetchResources} loading={loading}>{t('refresh')}</Button>
                            <Button
                                type="primary"
                                icon={<PlusOutlined />}
                                onClick={() => {
                                    setEditingResource(null);
                                    setCreatedMovie(null);
                                    setResourceModalOpen(true);
                                }}
                            >
                                {t('createResource')}
                            </Button>
                        </Space>
                    </div>

                    <div className="mb-4 grid grid-cols-1 gap-2 sm:flex sm:flex-wrap sm:items-center">
                        <Select
                            placeholder={t('filterByStatus')}
                            className="w-full sm:!w-[150px]"
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
                            className="w-full sm:!w-[180px]"
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
                            className="w-full sm:!w-[360px]"
                            allowClear
                            enterButton
                        />

                        {selectedRowKeys.length > 0 && (
                            <>
                                <Text type="secondary">{t('selectedCount', { count: selectedRowKeys.length })}</Text>
                                <Button type="primary" onClick={() => handleBatchAudit(1)}>{t('batchApprove')}</Button>
                                <Button onClick={() => handleBatchAudit(2)}>{t('batchReject')}</Button>
                                <Button danger onClick={handleBatchDelete}>{t('batchDelete')}</Button>
                            </>
                        )}
                    </div>

                    <Table
                        rowSelection={{ selectedRowKeys, onChange: setSelectedRowKeys }}
                        columns={columns}
                        dataSource={resources}
                        rowKey="id"
                        loading={loading}
                        scroll={{ x: 2600 }}
                        locale={{ emptyText: <Empty description={t('noResources')} /> }}
                        onRow={(record) => ({
                            onDoubleClick: () => router.push(`/movie/${record.movieId}`),
                        })}
                        pagination={{
                            current: page,
                            pageSize: 20,
                            total,
                            onChange: setPage,
                            showTotal: (count) => t('totalResources', { count }),
                        }}
                    />
                </Card>
            </div>

            <AdminResourceModal
                open={resourceModalOpen}
                resource={editingResource}
                token={token || ''}
                createdMovie={createdMovie}
                onCreateMovie={() => setMovieModalOpen(true)}
                onCancel={() => setResourceModalOpen(false)}
                onSaved={() => {
                    setResourceModalOpen(false);
                    fetchResources();
                }}
            />
            <AdminMovieModal
                open={movieModalOpen}
                movie={null}
                token={token || ''}
                onCancel={() => setMovieModalOpen(false)}
                onSaved={(movie) => {
                    setCreatedMovie(movie);
                    setMovieModalOpen(false);
                }}
            />
        </div>
    );
}
