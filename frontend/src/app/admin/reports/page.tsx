'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { App, Button, Card, Empty, Input, Select, Space, Table, Tag, Typography } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, ExclamationCircleOutlined, ToolOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { api, readApiError } from '@/lib/api';
import { useAuthStore } from '@/store/authStore';
import type { ResourceReport } from '@/types';

const { Title, Text } = Typography;
const { Option } = Select;

export default function ResourceReportsPage() {
    const { user, token } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [reports, setReports] = useState<ResourceReport[]>([]);
    const [loading, setLoading] = useState(true);
    const [savingId, setSavingId] = useState<number | null>(null);
    const [page, setPage] = useState(1);
    const [total, setTotal] = useState(0);
    const [keyword, setKeyword] = useState('');
    const [status, setStatus] = useState<string | undefined>('PENDING');
    const [linkStatus, setLinkStatus] = useState<string | undefined>();

    const fetchReports = useCallback(async () => {
        if (!token) {
            setLoading(false);
            return;
        }
        setLoading(true);
        try {
            const query = new URLSearchParams({ page: String(page), size: '20' });
            if (keyword.trim()) query.set('keyword', keyword.trim());
            if (status) query.set('status', status);
            if (linkStatus) query.set('linkStatus', linkStatus);
            const res = await api(`/api/admin/resource-reports?${query.toString()}`, {
                headers: { Authorization: `Bearer ${token}` },
            });
            if (!res.ok) {
                message.error(await readApiError(res, t('reportsLoadFailed')));
                return;
            }
            const data = await res.json();
            setReports(data.records || []);
            setTotal(data.total || 0);
        } catch {
            message.error(t('networkError'));
        } finally {
            setLoading(false);
        }
    }, [keyword, linkStatus, message, page, status, t, token]);

    useEffect(() => {
        if (!user) return;
        if (user.role !== 'ADMIN') {
            message.error(t('adminAccessRequired'));
            router.push('/');
            return;
        }
        fetchReports();
    }, [fetchReports, message, router, t, user]);

    const updateStatus = async (id: number, nextStatus: string) => {
        if (!token) return;
        setSavingId(id);
        try {
            const res = await api(`/api/admin/resource-reports/${id}/status?status=${nextStatus}`, {
                method: 'PUT',
                headers: { Authorization: `Bearer ${token}` },
            });
            if (!res.ok) {
                message.error(await readApiError(res, t('operationFailed')));
                return;
            }
            message.success(t('reportStatusUpdated'));
            fetchReports();
        } catch {
            message.error(t('networkError'));
        } finally {
            setSavingId(null);
        }
    };

    const statusTag = (value?: string) => {
        switch (value) {
            case 'PENDING': return <Tag color="orange">{t('pending')}</Tag>;
            case 'HANDLED': return <Tag color="green">{t('handled')}</Tag>;
            case 'FALSE_REPORT': return <Tag>{t('falseReport')}</Tag>;
            default: return <Tag>{value || t('unknown')}</Tag>;
        }
    };

    const linkStatusTag = (value?: string) => {
        switch (value) {
            case 'NORMAL': return <Tag color="green">{t('normal')}</Tag>;
            case 'SUSPECTED_INVALID': return <Tag color="orange">{t('suspectedInvalid')}</Tag>;
            case 'INVALID': return <Tag color="red">{t('invalid')}</Tag>;
            default: return <Tag>{value || t('unknown')}</Tag>;
        }
    };

    const columns: ColumnsType<ResourceReport> = [
        {
            title: t('movieTitle'),
            dataIndex: 'movieTitle',
            key: 'movieTitle',
            width: 180,
            render: (_: string, record) => record.movieId ? (
                <Link href={`/movie/${record.movieId}`} className="text-blue-500 hover:underline">
                    {record.movieTitle || record.movieId}
                </Link>
            ) : '-',
        },
        {
            title: t('resourceNameColumn'),
            dataIndex: 'resourceName',
            key: 'resourceName',
            ellipsis: true,
            render: (value: string) => value || t('resource'),
        },
        {
            title: t('reporter'),
            dataIndex: 'reporterName',
            key: 'reporterName',
            width: 120,
            render: (value: string) => value || '-',
        },
        {
            title: t('uploader'),
            dataIndex: 'uploaderName',
            key: 'uploaderName',
            width: 120,
            render: (value: string) => value || '-',
        },
        {
            title: t('reportReason'),
            dataIndex: 'reason',
            key: 'reason',
            ellipsis: true,
            render: (value: string) => value || t('noReason'),
        },
        {
            title: t('linkStatus'),
            dataIndex: 'linkStatus',
            key: 'linkStatus',
            width: 140,
            render: linkStatusTag,
        },
        {
            title: t('status'),
            dataIndex: 'status',
            key: 'status',
            width: 120,
            render: statusTag,
        },
        {
            title: t('createdAt'),
            dataIndex: 'createdAt',
            key: 'createdAt',
            width: 180,
            render: (value: string) => value ? new Date(value).toLocaleString() : '-',
        },
        {
            title: t('actions'),
            key: 'actions',
            width: 220,
            fixed: 'right',
            render: (_: unknown, record) => (
                <Space wrap>
                    <Button size="small" icon={<CheckCircleOutlined />} loading={savingId === record.id} onClick={() => updateStatus(record.id, 'HANDLED')}>
                        {t('handled')}
                    </Button>
                    <Button size="small" icon={<CloseCircleOutlined />} loading={savingId === record.id} onClick={() => updateStatus(record.id, 'FALSE_REPORT')}>
                        {t('falseReport')}
                    </Button>
                    <Button danger size="small" icon={<ExclamationCircleOutlined />} loading={savingId === record.id} onClick={() => updateStatus(record.id, 'INVALID')}>
                        {t('markInvalid')}
                    </Button>
                </Space>
            ),
        },
    ];

    return (
        <div className="min-h-screen bg-[#f5f7fa] dark:bg-black">
            <div className="container mx-auto px-4 lg:px-8 py-8">
                <Card>
                    <div className="mb-6 flex items-center gap-3">
                        <ToolOutlined className="text-3xl text-orange-500" />
                        <div>
                            <Title level={2} className="!mb-1">{t('resourceReports')}</Title>
                            <Text type="secondary">{t('resourceReportsHint')}</Text>
                        </div>
                    </div>

                    <Space className="mb-4" wrap>
                        <Input.Search
                            placeholder={t('searchReports')}
                            allowClear
                            enterButton
                            style={{ width: 320 }}
                            onSearch={(value) => {
                                setKeyword(value);
                                setPage(1);
                            }}
                        />
                        <Select
                            placeholder={t('filterByStatus')}
                            allowClear
                            value={status}
                            style={{ width: 160 }}
                            onChange={(value) => {
                                setStatus(value);
                                setPage(1);
                            }}
                        >
                            <Option value="PENDING">{t('pending')}</Option>
                            <Option value="HANDLED">{t('handled')}</Option>
                            <Option value="FALSE_REPORT">{t('falseReport')}</Option>
                        </Select>
                        <Select
                            placeholder={t('linkStatus')}
                            allowClear
                            value={linkStatus}
                            style={{ width: 180 }}
                            onChange={(value) => {
                                setLinkStatus(value);
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
                        dataSource={reports}
                        rowKey="id"
                        loading={loading}
                        scroll={{ x: 1400 }}
                        locale={{ emptyText: <Empty description={t('noReports')} /> }}
                        pagination={{
                            current: page,
                            pageSize: 20,
                            total,
                            onChange: setPage,
                            showTotal: (count) => t('totalReports', { count }),
                        }}
                    />
                </Card>
            </div>
        </div>
    );
}