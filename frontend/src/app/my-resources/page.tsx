'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { App, Button, Card, Divider, Empty, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import { CloudUploadOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { api, readApiError } from '@/lib/api';
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
    const [editingResource, setEditingResource] = useState<MyResource | null>(null);
    const [savingEdit, setSavingEdit] = useState(false);
    const [editForm] = Form.useForm();
    const editResourceType = Form.useWatch('type', editForm) || 'DISK';

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

    const openEditModal = (resource: MyResource) => {
        setEditingResource(resource);
        editForm.setFieldsValue({
            name: resource.name,
            type: resource.type || 'DISK',
            url: resource.url,
            code: resource.code,
            provider: resource.provider || 'BAIDU',
            quality: resource.quality,
            subtitle: resource.subtitle,
            fileSize: resource.fileSize,
            versionNote: resource.versionNote,
        });
    };

    const getUrlRules = () => {
        if (editResourceType === 'MAGNET') {
            return [
                { required: true },
                {
                    validator: (_: unknown, value?: string) => {
                        if (!value || value.toLowerCase().startsWith('magnet:?xt=urn:btih:')) {
                            return Promise.resolve();
                        }
                        return Promise.reject(new Error(t('magnetLinkRequired')));
                    },
                },
            ];
        }
        if (editResourceType === 'TORRENT') {
            return [
                { required: true },
                {
                    validator: (_: unknown, value?: string) => {
                        const lowerValue = value?.toLowerCase() || '';
                        if (!value || ((lowerValue.startsWith('http://') || lowerValue.startsWith('https://')) && lowerValue.includes('.torrent'))) {
                            return Promise.resolve();
                        }
                        return Promise.reject(new Error(t('torrentLinkRequired')));
                    },
                },
            ];
        }
        return [{ required: true, type: 'url' as const }];
    };

    const handleEditSubmit = async (values: {
        name: string;
        type: string;
        url: string;
        code?: string;
        provider?: string;
        quality?: string;
        subtitle?: string;
        fileSize?: string;
        versionNote?: string;
    }) => {
        if (!token || !editingResource) return;
        setSavingEdit(true);
        try {
            const res = await api(`/api/resources/${editingResource.id}`, {
                method: 'PUT',
                headers: { Authorization: `Bearer ${token}` },
                body: JSON.stringify({
                    ...values,
                    movieId: editingResource.movieId,
                    provider: values.type === 'DISK' ? (values.provider || 'OTHER') : 'OTHER',
                    code: values.type === 'DISK' ? (values.code || '') : '',
                }),
            });
            if (res.ok) {
                message.success(t('resourceUpdated'));
                setEditingResource(null);
                editForm.resetFields();
                fetchResources();
            } else {
                const msg = await readApiError(res, t('resourceUpdateFailed'));
                message.error(msg || t('resourceUpdateFailed'));
            }
        } catch {
            message.error(t('networkError'));
        } finally {
            setSavingEdit(false);
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
            title: t('quality'),
            dataIndex: 'quality',
            key: 'quality',
            width: 100,
            render: (value: string) => value || '-',
        },
        {
            title: t('rejectReason'),
            dataIndex: 'rejectReason',
            key: 'rejectReason',
            width: 180,
            ellipsis: true,
            render: (value: string, record) => record.auditStatus === 2 ? (value || '-') : '-',
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
            width: 130,
            fixed: 'right',
            render: (_: unknown, record) => (
                <Space>
                    <Button icon={<EditOutlined />} size="small" onClick={() => openEditModal(record)} />
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
            <Modal
                title={t('editResource')}
                open={!!editingResource}
                onCancel={() => setEditingResource(null)}
                footer={null}
                destroyOnHidden
            >
                <Form form={editForm} layout="vertical" onFinish={handleEditSubmit} requiredMark={false}>
                    <Form.Item name="name" label={t('resourceName')} rules={[{ required: true }]}>
                        <Input />
                    </Form.Item>
                    <Form.Item name="type" label={t('resourceType')} rules={[{ required: true }]}>
                        <Select>
                            <Option value="DISK">{t('cloudDisk')}</Option>
                            <Option value="MAGNET">{t('magnet')}</Option>
                            <Option value="TORRENT">{t('torrent')}</Option>
                            <Option value="ONLINE">{t('onlinePlay')}</Option>
                        </Select>
                    </Form.Item>
                    <Form.Item name="url" label={t('linkUrl')} rules={getUrlRules()}>
                        <Input placeholder={editResourceType === 'MAGNET' ? 'magnet:?xt=urn:btih:...' : editResourceType === 'TORRENT' ? 'https://example.com/movie.torrent' : 'https://...'} />
                    </Form.Item>
                    {editResourceType === 'DISK' && (
                        <>
                            <Form.Item name="code" label={t('accessCode')}>
                                <Input placeholder={t('optional')} />
                            </Form.Item>
                            <Form.Item name="provider" label={t('provider')} rules={[{ required: true }]}>
                                <Select>
                                    <Option value="BAIDU">{t('baiduNetdisk')}</Option>
                                    <Option value="QUARK">{t('quarkCloud')}</Option>
                                    <Option value="XUNLEI">{t('xunleiCloud')}</Option>
                                    <Option value="ALIYUN">{t('aliyunDrive')}</Option>
                                    <Option value="OTHER">{t('other')}</Option>
                                </Select>
                            </Form.Item>
                        </>
                    )}
                    <Space className="w-full" size="middle">
                        <Form.Item name="quality" label={t('quality')} className="flex-1">
                            <Input placeholder="4K / 1080P" />
                        </Form.Item>
                        <Form.Item name="subtitle" label={t('subtitle')} className="flex-1">
                            <Input placeholder={t('optional')} />
                        </Form.Item>
                        <Form.Item name="fileSize" label={t('fileSize')} className="flex-1">
                            <Input placeholder="8.5GB" />
                        </Form.Item>
                    </Space>
                    <Form.Item name="versionNote" label={t('versionNote')}>
                        <Input placeholder={t('versionNotePlaceholder')} />
                    </Form.Item>
                    <Divider />
                    <div className="flex justify-end gap-3">
                        <Button onClick={() => setEditingResource(null)}>{t('cancel')}</Button>
                        <Button type="primary" htmlType="submit" loading={savingEdit}>
                            {t('submit')}
                        </Button>
                    </div>
                </Form>
            </Modal>
        </div>
    );
}
