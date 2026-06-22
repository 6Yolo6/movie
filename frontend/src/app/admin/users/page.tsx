'use client';

import { useCallback, useEffect, useState } from 'react';
import { App, Button, Card, Input, Select, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useRouter } from 'next/navigation';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/store/authStore';
import { api } from '@/lib/api';

const { Title } = Typography;
const { Option } = Select;
const { Search } = Input;

interface User {
    id: number;
    username: string;
    email: string;
    role: string;
    enabled: boolean;
    createdAt: string;
}

export default function UserManagementPage() {
    const { user, token } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [loading, setLoading] = useState(true);
    const [users, setUsers] = useState<User[]>([]);
    const [page, setPage] = useState(1);
    const [total, setTotal] = useState(0);
    const [updating, setUpdating] = useState<number | null>(null);
    const [keyword, setKeyword] = useState('');
    const [roleFilter, setRoleFilter] = useState<string | undefined>();
    const [enabledFilter, setEnabledFilter] = useState<boolean | undefined>();

    const fetchUsers = useCallback(async () => {
        if (!token) return;

        setLoading(true);
        try {
            const query = new URLSearchParams({
                page: String(page),
                size: '20',
            });
            if (keyword) query.set('keyword', keyword);
            if (roleFilter) query.set('role', roleFilter);
            if (enabledFilter !== undefined) query.set('enabled', String(enabledFilter));

            const res = await api(`/api/admin/users?${query.toString()}`, {
                headers: {
                    Authorization: `Bearer ${token}`,
                },
            });

            if (res.ok) {
                const data = await res.json();
                setUsers(data.records);
                setTotal(data.total);
            } else {
                message.error(t('usersLoadFailed'));
            }
        } catch {
            message.error(t('networkError'));
        } finally {
            setLoading(false);
        }
    }, [enabledFilter, keyword, message, page, roleFilter, t, token]);

    useEffect(() => {
        const timer = setTimeout(() => {
            if (!user || user.role !== 'ADMIN') {
                message.error(t('adminAccessRequired'));
                router.push('/');
                return;
            }
            fetchUsers();
        }, 100);
        return () => clearTimeout(timer);
    }, [fetchUsers, message, router, t, user]);

    const handleRoleChange = async (userId: number, newRole: string) => {
        setUpdating(userId);
        try {
            const res = await api(`/api/admin/users/${userId}/role`, {
                method: 'PUT',
                headers: {
                    Authorization: `Bearer ${token}`,
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ role: newRole }),
            });

            if (res.ok) {
                message.success(t('userRoleUpdated'));
                fetchUsers();
            } else {
                const msg = await res.text();
                message.error(msg || t('roleUpdateFailed'));
            }
        } catch {
            message.error(t('networkError'));
        } finally {
            setUpdating(null);
        }
    };

    const handleEnabledChange = async (userId: number, enabled: boolean) => {
        setUpdating(userId);
        try {
            const res = await api(`/api/admin/users/${userId}/enabled?enabled=${enabled}`, {
                method: 'PUT',
                headers: {
                    Authorization: `Bearer ${token}`,
                },
            });
            if (res.ok) {
                message.success(t('userStatusUpdated'));
                fetchUsers();
            } else {
                const msg = await res.text();
                message.error(msg || t('operationFailed'));
            }
        } catch {
            message.error(t('networkError'));
        } finally {
            setUpdating(null);
        }
    };

    const getRoleColor = (role: string) => {
        switch (role) {
            case 'ADMIN': return 'red';
            case 'PUBLISHER': return 'blue';
            case 'USER': return 'default';
            default: return 'default';
        }
    };

    const columns: ColumnsType<User> = [
        { title: t('id'), dataIndex: 'id', key: 'id', width: 80 },
        { title: t('username'), dataIndex: 'username', key: 'username' },
        { title: t('email'), dataIndex: 'email', key: 'email' },
        {
            title: t('currentRole'),
            dataIndex: 'role',
            key: 'currentRole',
            render: (role: string) => <Tag color={getRoleColor(role)}>{role}</Tag>,
        },
        {
            title: t('status'),
            dataIndex: 'enabled',
            key: 'enabled',
            render: (enabled: boolean) => (
                <Tag color={enabled ? 'green' : 'red'}>{enabled ? t('enabled') : t('disabled')}</Tag>
            ),
        },
        {
            title: t('changeRole'),
            key: 'roleAction',
            width: 180,
            render: (_: unknown, record: User) => (
                <Select
                    value={record.role}
                    onChange={(value) => handleRoleChange(record.id, value)}
                    loading={updating === record.id}
                    disabled={updating === record.id || record.id === user?.id}
                    style={{ width: 150 }}
                >
                    <Option value="USER">USER</Option>
                    <Option value="PUBLISHER">PUBLISHER</Option>
                </Select>
            ),
        },
        {
            title: t('actions'),
            key: 'enabledAction',
            width: 120,
            render: (_: unknown, record: User) => (
                <Button
                    danger={record.enabled}
                    disabled={updating === record.id || record.id === user?.id}
                    loading={updating === record.id}
                    onClick={() => handleEnabledChange(record.id, !record.enabled)}
                >
                    {record.enabled ? t('disable') : t('enable')}
                </Button>
            ),
        },
        {
            title: t('createdAt'),
            dataIndex: 'createdAt',
            key: 'createdAt',
            render: (date: string) => date ? new Date(date).toLocaleString() : '-',
        },
    ];

    return (
        <div className="container mx-auto px-4 py-8">
            <Card>
                <Title level={2}>{t('userManagement')}</Title>
                <p className="text-gray-600 dark:text-gray-400 mb-4">{t('userManagementHint')}</p>

                <Space className="mb-4" wrap>
                    <Search
                        placeholder={t('searchUsers')}
                        allowClear
                        onSearch={(value) => {
                            setKeyword(value.trim());
                            setPage(1);
                        }}
                        style={{ width: 260 }}
                    />
                    <Select
                        placeholder={t('filterByRole')}
                        allowClear
                        value={roleFilter}
                        onChange={(value) => {
                            setRoleFilter(value);
                            setPage(1);
                        }}
                        style={{ width: 160 }}
                    >
                        <Option value="USER">USER</Option>
                        <Option value="PUBLISHER">PUBLISHER</Option>
                        <Option value="ADMIN">ADMIN</Option>
                    </Select>
                    <Select
                        placeholder={t('filterByEnabled')}
                        allowClear
                        value={enabledFilter}
                        onChange={(value) => {
                            setEnabledFilter(value);
                            setPage(1);
                        }}
                        style={{ width: 160 }}
                    >
                        <Option value={true}>{t('enabled')}</Option>
                        <Option value={false}>{t('disabled')}</Option>
                    </Select>
                </Space>

                <Table
                    columns={columns}
                    dataSource={users}
                    rowKey="id"
                    loading={loading}
                    pagination={{
                        current: page,
                        pageSize: 20,
                        total,
                        onChange: setPage,
                        showTotal: (totalCount) => t('totalUsers', { count: totalCount }),
                    }}
                />
            </Card>
        </div>
    );
}
