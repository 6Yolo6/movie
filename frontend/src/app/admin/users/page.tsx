'use client';

import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { App, Button, Card, Form, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useRouter } from 'next/navigation';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/store/authStore';
import { api, readApiError } from '@/lib/api';

const { Title } = Typography;
const { Option } = Select;
const { Search } = Input;
const subscribeHydration = () => () => {};
const clientHydration = () => true;
const serverHydration = () => false;
interface CreateUserValues { username: string; email: string; password: string; confirm: string; role: 'USER' | 'PUBLISHER'; }

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
    const mounted = useSyncExternalStore(subscribeHydration, clientHydration, serverHydration);
    const [createOpen, setCreateOpen] = useState(false);
    const [creating, setCreating] = useState(false);
    const [createForm] = Form.useForm<CreateUserValues>();
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

    const createUser = async (values: CreateUserValues) => {
        if (creating) return;
        setCreating(true);
        try {
            const response = await api('/api/admin/users', {
                method: 'POST', body: JSON.stringify({ username: values.username.trim(), email: values.email.trim(), password: values.password, role: values.role }),
            });
            if (!response.ok) { message.error(await readApiError(response, '新建用户失败')); return; }
            message.success('用户已创建，请安全地向用户交付初始密码');
            createForm.resetFields(); setCreateOpen(false);
            await fetchUsers();
        } catch { message.error('网络异常，请查询用户列表确认是否已创建，勿重复提交'); }
        finally { setCreating(false); }
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

    if (!mounted) return <main className="p-8" aria-busy="true">正在加载用户管理…</main>;

    return (
        <div className="container mx-auto px-4 py-8">
            <Card>
                <Title level={2}>{t('userManagement')}</Title>
                <p className="text-gray-600 dark:text-gray-400 mb-4">{t('userManagementHint')}</p>

                <Space className="mb-4" wrap>
                    <Button type="primary" onClick={() => { createForm.resetFields(); setCreateOpen(true); }}>新建用户</Button>
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
            <Modal title="新建用户" open={createOpen} footer={null} closable={!creating} maskClosable={!creating}
                onCancel={() => { if (!creating) { setCreateOpen(false); createForm.resetFields(); } }}>
                <Typography.Paragraph type="secondary">管理员直接创建账号，不受公开注册开关限制，也不消耗邀请码。默认创建普通用户；需要发布资源时选择发布者。</Typography.Paragraph>
                <Form form={createForm} layout="vertical" initialValues={{ role: 'USER' }} onFinish={createUser}>
                    <Form.Item name="username" label="用户名" rules={[{ required: true, whitespace: true }, { min: 3, max: 50 }]}><Input autoComplete="off" /></Form.Item>
                    <Form.Item name="email" label="邮箱" rules={[{ required: true }, { type: 'email' }, { max: 200 }]}><Input autoComplete="off" /></Form.Item>
                    <Form.Item name="role" label="角色"><Select options={[{ value: 'USER', label: '普通用户（浏览、收藏、评论）' }, { value: 'PUBLISHER', label: '发布者（可发布资源）' }]} /></Form.Item>
                    <Form.Item name="password" label="初始密码" rules={[{ required: true }, { min: 12, max: 72, message: '至少 12 个字符，最多 72 个 UTF-8 字节' }, { validator: (_, value) => !value || new TextEncoder().encode(value).length <= 72 ? Promise.resolve() : Promise.reject(new Error('密码最多 72 个 UTF-8 字节')) }]}><Input.Password autoComplete="new-password" /></Form.Item>
                    <Form.Item name="confirm" label="确认密码" dependencies={['password']} rules={[{ required: true }, ({ getFieldValue }) => ({ validator: (_, value) => !value || value === getFieldValue('password') ? Promise.resolve() : Promise.reject(new Error('两次密码不一致')) })]}><Input.Password autoComplete="new-password" /></Form.Item>
                    <div className="flex justify-end gap-2"><Button disabled={creating} onClick={() => { setCreateOpen(false); createForm.resetFields(); }}>取消</Button><Button type="primary" htmlType="submit" loading={creating}>创建用户</Button></div>
                </Form>
            </Modal>
        </div>
    );
}
