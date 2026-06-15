'use client';

import { useCallback, useEffect, useState } from 'react';
import { App, Button, Card, Input, Select, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useRouter } from 'next/navigation';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/store/authStore';
import { api } from '@/lib/api';

const { Title } = Typography;
const { Search } = Input;
const { Option } = Select;

interface AdminComment {
    id: number;
    relateId: string;
    userId?: number;
    nickname?: string;
    content: string;
    status: number;
    upvotes: number;
    parentId: number;
    createdAt: string;
}

export default function CommentManagementPage() {
    const { user, token } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [loading, setLoading] = useState(true);
    const [comments, setComments] = useState<AdminComment[]>([]);
    const [page, setPage] = useState(1);
    const [total, setTotal] = useState(0);
    const [statusFilter, setStatusFilter] = useState<number | undefined>();
    const [relateId, setRelateId] = useState('');
    const [keyword, setKeyword] = useState('');

    const fetchComments = useCallback(async () => {
        if (!token) return;
        setLoading(true);
        try {
            const query = new URLSearchParams({
                page: String(page),
                size: '20',
            });
            if (statusFilter !== undefined) query.set('status', String(statusFilter));
            if (relateId) query.set('relateId', relateId);
            if (keyword) query.set('keyword', keyword);
            const res = await api(`/api/admin/comments?${query.toString()}`, {
                headers: {
                    Authorization: `Bearer ${token}`,
                },
            });
            if (res.ok) {
                const data = await res.json();
                setComments(data.records);
                setTotal(data.total);
            } else {
                message.error(t('commentsLoadFailed'));
            }
        } catch {
            message.error(t('networkError'));
        } finally {
            setLoading(false);
        }
    }, [keyword, message, page, relateId, statusFilter, t, token]);

    useEffect(() => {
        const timer = setTimeout(() => {
            if (!user || user.role !== 'ADMIN') {
                message.error(t('adminAccessRequired'));
                router.push('/');
                return;
            }
            fetchComments();
        }, 100);
        return () => clearTimeout(timer);
    }, [fetchComments, message, router, t, user]);

    const updateStatus = async (id: number, status: number) => {
        try {
            const res = await api(`/api/admin/comments/${id}/status?status=${status}`, {
                method: 'PUT',
                headers: {
                    Authorization: `Bearer ${token}`,
                },
            });
            if (res.ok) {
                message.success(t('commentStatusUpdated'));
                fetchComments();
            } else {
                message.error(t('operationFailed'));
            }
        } catch {
            message.error(t('networkError'));
        }
    };

    const getStatusTag = (status: number) => {
        switch (status) {
            case 0: return <Tag color="orange">{t('pending')}</Tag>;
            case 1: return <Tag color="green">{t('published')}</Tag>;
            case 2: return <Tag color="red">{t('hidden')}</Tag>;
            default: return <Tag>{t('unknown')}</Tag>;
        }
    };

    const columns: ColumnsType<AdminComment> = [
        { title: t('id'), dataIndex: 'id', key: 'id', width: 80 },
        { title: t('relateId'), dataIndex: 'relateId', key: 'relateId', width: 160 },
        { title: t('nickname'), dataIndex: 'nickname', key: 'nickname', width: 140 },
        {
            title: t('commentContent'),
            dataIndex: 'content',
            key: 'content',
            ellipsis: true,
        },
        {
            title: t('status'),
            dataIndex: 'status',
            key: 'status',
            width: 110,
            render: getStatusTag,
        },
        { title: t('score'), dataIndex: 'upvotes', key: 'upvotes', width: 90 },
        {
            title: t('createdAt'),
            dataIndex: 'createdAt',
            key: 'createdAt',
            width: 180,
            render: (date: string) => date ? new Date(date).toLocaleString() : '-',
        },
        {
            title: t('actions'),
            key: 'actions',
            width: 160,
            render: (_: unknown, record) => (
                <Space>
                    {record.status !== 1 && <Button size="small" onClick={() => updateStatus(record.id, 1)}>{t('restore')}</Button>}
                    {record.status !== 2 && <Button size="small" danger onClick={() => updateStatus(record.id, 2)}>{t('hide')}</Button>}
                </Space>
            ),
        },
    ];

    return (
        <div className="container mx-auto px-4 py-8">
            <Card>
                <Title level={2}>{t('commentManagement')}</Title>
                <Space className="mb-4" wrap>
                    <Select
                        placeholder={t('filterByStatus')}
                        allowClear
                        value={statusFilter}
                        onChange={(value) => {
                            setStatusFilter(value);
                            setPage(1);
                        }}
                        style={{ width: 160 }}
                    >
                        <Option value={0}>{t('pending')}</Option>
                        <Option value={1}>{t('published')}</Option>
                        <Option value={2}>{t('hidden')}</Option>
                    </Select>
                    <Search
                        placeholder={t('filterByRelateId')}
                        allowClear
                        onSearch={(value) => {
                            setRelateId(value.trim());
                            setPage(1);
                        }}
                        style={{ width: 220 }}
                    />
                    <Search
                        placeholder={t('searchComments')}
                        allowClear
                        onSearch={(value) => {
                            setKeyword(value.trim());
                            setPage(1);
                        }}
                        style={{ width: 260 }}
                    />
                </Space>
                <Table
                    columns={columns}
                    dataSource={comments}
                    rowKey="id"
                    loading={loading}
                    pagination={{
                        current: page,
                        pageSize: 20,
                        total,
                        onChange: setPage,
                    }}
                />
            </Card>
        </div>
    );
}
