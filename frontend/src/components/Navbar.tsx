'use client';

import React, { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { App, Avatar, Badge, Button, Drawer, Dropdown, Form, Input, MenuProps, Modal, Select, Space, Switch, Tag } from 'antd';
import {
    BellOutlined, CloudUploadOutlined, CommentOutlined, ExclamationCircleOutlined, FireOutlined, HeartOutlined, HomeOutlined,
    LoginOutlined, LogoutOutlined, MenuOutlined, MessageOutlined,
    PlaySquareOutlined, DesktopOutlined, SwapOutlined, UserOutlined, VideoCameraOutlined,
} from '@ant-design/icons';
import { useRouter, useSearchParams } from 'next/navigation';
import { useTranslation } from 'react-i18next';
import { useTheme } from './ThemeProvider';
import { useAuthStore } from '../store/authStore';
import { api } from '../lib/api';

const ADMIN_BACKUP_KEY = 'admin-auth-backup';

interface SwitchableUser {
    id: number;
    username: string;
    role: string;
    enabled: boolean;
}

export default function Navbar() {
    const { user, token, login, logout } = useAuthStore();
    const { theme, toggleTheme } = useTheme();
    const { t, i18n } = useTranslation();
    const { message } = App.useApp();
    const router = useRouter();
    const searchParams = useSearchParams();
    const keyword = searchParams.get('keyword') || '';
    const [drawerOpen, setDrawerOpen] = useState(false);
    const [unreadCount, setUnreadCount] = useState(0);
    const [mounted, setMounted] = useState(false);
    const [switchModalOpen, setSwitchModalOpen] = useState(false);
    const [switchingAccount, setSwitchingAccount] = useState(false);
    const [switchUsers, setSwitchUsers] = useState<SwitchableUser[]>([]);
    const [loadingSwitchUsers, setLoadingSwitchUsers] = useState(false);
    const [hasAdminBackup, setHasAdminBackup] = useState(false);
    const [switchForm] = Form.useForm();

    const fetchUnreadCount = useCallback(async () => {
        if (!user || !token) {
            setUnreadCount(0);
            return;
        }
        try {
            const res = await api('/api/notifications/unread-count', {
                headers: { Authorization: `Bearer ${token}` },
            });
            if (res.ok) {
                const data = await res.json();
                setUnreadCount(Number(data.count || 0));
            }
        } catch {
            setUnreadCount(0);
        }
    }, [token, user]);

    useEffect(() => {
        setMounted(true);
        setHasAdminBackup(Boolean(window.localStorage.getItem(ADMIN_BACKUP_KEY)));
    }, []);

    useEffect(() => {
        fetchUnreadCount();
        window.addEventListener('notifications:refresh', fetchUnreadCount);
        return () => window.removeEventListener('notifications:refresh', fetchUnreadCount);
    }, [fetchUnreadCount]);

    const handleLogout = () => {
        logout();
        setDrawerOpen(false);
        router.push('/');
    };

    const fetchSwitchUsers = async () => {
        if (!token || user?.role !== 'ADMIN') return;
        setLoadingSwitchUsers(true);
        try {
            const res = await api('/api/admin/users?page=1&size=100', {
                headers: { Authorization: `Bearer ${token}` },
            });
            if (res.ok) {
                const data = await res.json();
                setSwitchUsers((data.records || []).filter((item: SwitchableUser) => item.enabled));
            } else {
                message.error(t('usersLoadFailed'));
            }
        } catch {
            message.error(t('networkError'));
        } finally {
            setLoadingSwitchUsers(false);
        }
    };

    const openSwitchModal = () => {
        setSwitchModalOpen(true);
        fetchSwitchUsers();
    };

    const handleSwitchAccount = async (values: { userId: number }) => {
        if (!token || !user || user.role !== 'ADMIN') return;
        setSwitchingAccount(true);
        try {
            const res = await api(`/api/admin/users/${values.userId}/impersonate`, {
                method: 'POST',
                headers: { Authorization: `Bearer ${token}` },
            });
            const data = await res.json();
            if (!res.ok) {
                message.error(data.error || t('accountSwitchFailed'));
                return;
            }

            window.localStorage.setItem(ADMIN_BACKUP_KEY, JSON.stringify({ token, user }));
            setHasAdminBackup(true);
            login(data.token, data.user);
            setSwitchModalOpen(false);
            setDrawerOpen(false);
            switchForm.resetFields();
            message.success(t('accountSwitched'));
            router.refresh();
        } catch {
            message.error(t('networkError'));
        } finally {
            setSwitchingAccount(false);
        }
    };

    const handleReturnAdmin = () => {
        const rawBackup = window.localStorage.getItem(ADMIN_BACKUP_KEY);
        if (!rawBackup) return;
        const backup = JSON.parse(rawBackup);
        login(backup.token, backup.user);
        window.localStorage.removeItem(ADMIN_BACKUP_KEY);
        setHasAdminBackup(false);
        setDrawerOpen(false);
        message.success(t('returnedToAdmin'));
        router.refresh();
    };

    const closeDrawer = () => setDrawerOpen(false);

    if (!mounted) {
        return (
            <nav className="flex items-center justify-between px-4 sm:px-6 py-3 bg-white/90 text-gray-900 border-b border-gray-200 dark:bg-[#141414]/90 dark:text-white dark:border-[#1f1f1f] sticky top-0 z-50 backdrop-blur-md">
                <Link href="/" className="text-xl sm:text-2xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-blue-500 to-teal-400">
                    GYING
                </Link>
                <div className="h-8 w-28 rounded bg-gray-100 dark:bg-white/10" />
            </nav>
        );
    }

    const navLinks = [
        { href: '/', label: t('home'), icon: <HomeOutlined /> },
        { href: '/?category=mv', label: t('movies'), icon: <PlaySquareOutlined /> },
        { href: '/?category=tv', label: t('tvShows'), icon: <DesktopOutlined /> },
        { href: '/?category=ac', label: t('anime'), icon: <VideoCameraOutlined /> },
        { href: '/hot', label: t('hot'), icon: <FireOutlined /> },
        { href: '/messages', label: t('messageBoard'), icon: <MessageOutlined /> },
    ];

    const userMenuItems: MenuProps['items'] = [
        {
            key: 'profile',
            label: <Link href="/profile" onClick={closeDrawer}>{t('myProfile')}</Link>,
            icon: <UserOutlined />,
        },
        {
            key: 'notifications',
            label: (
                <Link href="/notifications" onClick={closeDrawer} className="flex items-center gap-2">
                    <span>{t('notifications')}</span>
                    <Badge count={unreadCount} size="small" />
                </Link>
            ),
            icon: <BellOutlined />,
        },
        {
            key: 'favorites',
            label: <Link href="/favorites" onClick={closeDrawer}>{t('myFavorites')}</Link>,
            icon: <HeartOutlined />,
        },
        {
            key: 'myResources',
            label: <Link href="/my-resources" onClick={closeDrawer}>{t('myResources')}</Link>,
            icon: <CloudUploadOutlined />,
        },
        ...(hasAdminBackup ? [{
            key: 'returnAdmin',
            label: t('returnToAdmin'),
            icon: <SwapOutlined />,
            onClick: handleReturnAdmin,
        }] : []),
        ...(user?.role === 'ADMIN' ? [{
            key: 'switchAccount',
            label: t('switchAccount'),
            icon: <SwapOutlined />,
            onClick: openSwitchModal,
        }] : []),
        ...(user?.role === 'ADMIN' ? [
            {
                key: 'settings',
                label: <Link href="/admin/settings" onClick={closeDrawer}>{t('systemSettings')}</Link>,
                icon: <UserOutlined />,
            },
            {
                key: 'users',
                label: <Link href="/admin/users" onClick={closeDrawer}>{t('userManagement')}</Link>,
                icon: <UserOutlined />,
            },
            {
                key: 'resources',
                label: <Link href="/admin/audit" onClick={closeDrawer}>{t('resourceManagement')}</Link>,
                icon: <UserOutlined />,
            },
            {
                key: 'reports',
                label: <Link href="/admin/reports" onClick={closeDrawer}>{t('resourceReports')}</Link>,
                icon: <ExclamationCircleOutlined />,
            },
            {
                key: 'comments',
                label: <Link href="/admin/comments" onClick={closeDrawer}>{t('commentManagement')}</Link>,
                icon: <CommentOutlined />,
            },
        ] : []),
        {
            key: 'logout',
            label: t('logout'),
            icon: <LogoutOutlined />,
            onClick: handleLogout,
        },
    ];

    const onSearch = (value: string) => {
        const trimmed = value.trim();
        router.push(trimmed ? `/?keyword=${encodeURIComponent(trimmed)}` : '/');
    };

    return (
        <>
            <nav className="flex items-center justify-between px-4 sm:px-6 py-3 bg-white/90 text-gray-900 border-b border-gray-200 dark:bg-[#141414]/90 dark:text-white dark:border-[#1f1f1f] sticky top-0 z-50 backdrop-blur-md">
                <div className="flex items-center gap-4 sm:gap-8">
                    {/* Hamburger for mobile */}
                    <Button
                        type="text"
                        icon={<MenuOutlined />}
                        className="md:hidden !text-lg"
                        onClick={() => setDrawerOpen(true)}
                    />
                    <Link href="/" className="text-xl sm:text-2xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-blue-500 to-teal-400">
                        GYING
                    </Link>
                    {/* Desktop nav links */}
                    <div className="hidden md:flex gap-6 text-gray-600 dark:text-gray-400 font-medium">
                        <Link href="/" className="hover:text-blue-600 dark:hover:text-white transition-colors">{t('home')}</Link>
                        <Link href="/?category=mv" className="hover:text-blue-600 dark:hover:text-white transition-colors">{t('movies')}</Link>
                        <Link href="/?category=tv" className="hover:text-blue-600 dark:hover:text-white transition-colors">{t('tvShows')}</Link>
                        <Link href="/?category=ac" className="hover:text-blue-600 dark:hover:text-white transition-colors">{t('anime')}</Link>
                        <Link href="/hot" className="hover:text-orange-500 dark:hover:text-orange-400 transition-colors inline-flex items-center gap-1">
                            <FireOutlined /> {t('hot')}
                        </Link>
                        <Link href="/messages" className="hover:text-blue-600 dark:hover:text-white transition-colors inline-flex items-center gap-1">
                            <MessageOutlined /> {t('messageBoard')}
                        </Link>
                    </div>
                </div>

                <div className="flex items-center gap-3 sm:gap-6">
                    {/* Desktop search */}
                    <Input.Search
                        key={keyword}
                        placeholder={t('searchMovies')}
                        defaultValue={keyword}
                        onSearch={onSearch}
                        style={{ width: 250 }}
                        className="hidden md:block"
                        allowClear
                    />

                    {/* Theme toggle */}
                    <Switch
                        checkedChildren={t('darkMode')}
                        unCheckedChildren={t('lightMode')}
                        checked={theme === 'dark'}
                        onChange={toggleTheme}
                        size="small"
                    />

                    {/* Language toggle */}
                    <Switch
                        checkedChildren="中"
                        unCheckedChildren="En"
                        checked={i18n.language === 'zh'}
                        onChange={(checked) => i18n.changeLanguage(checked ? 'zh' : 'en')}
                        size="small"
                    />

                    {/* User area */}
                    {user ? (
                        <Space size={8}>
                            <Link href="/notifications" aria-label={t('notifications')}>
                                <Badge count={unreadCount} size="small">
                                    <Button type="text" shape="circle" icon={<BellOutlined />} />
                                </Badge>
                            </Link>
                            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight" arrow>
                                <Space className="cursor-pointer hover:bg-gray-100 dark:hover:bg-white/10 p-1.5 sm:p-2 rounded-lg transition-colors">
                                    <Avatar size={28} icon={<UserOutlined />} className="bg-gradient-to-r from-blue-600 to-teal-500" />
                                    <span className="text-gray-700 dark:text-gray-300 font-medium hidden sm:block">{user.username}</span>
                                </Space>
                            </Dropdown>
                        </Space>
                    ) : (
                        <Space size={4}>
                            <Link href="/login">
                                <Button type="text" size="small">{t('signIn')}</Button>
                            </Link>
                            <Link href="/register" className="hidden sm:inline">
                                <Button type="primary" icon={<LoginOutlined />} size="small" className="bg-blue-600">{t('signUp')}</Button>
                            </Link>
                        </Space>
                    )}
                </div>
            </nav>

            {/* Mobile Drawer - use styles.wrapper instead of deprecated width */}
            <Drawer
                title={
                    <div className="flex items-center gap-2">
                        <span className="text-xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-blue-500 to-teal-400">GYING</span>
                    </div>
                }
                placement="left"
                open={drawerOpen}
                onClose={closeDrawer}
                styles={{ wrapper: { width: 280 }, body: { padding: '8px 0' } }}
                className="md:hidden"
            >
                {/* Mobile search */}
                <div className="px-4 mb-4">
                    <Input.Search
                        placeholder={t('searchMovies')}
                        defaultValue={keyword}
                        onSearch={(v) => { onSearch(v); closeDrawer(); }}
                        allowClear
                    />
                </div>

                {/* Nav links */}
                <div className="flex flex-col">
                    {navLinks.map(link => (
                        <Link
                            key={link.href}
                            href={link.href}
                            onClick={closeDrawer}
                            className="flex items-center gap-3 px-6 py-3 text-gray-700 dark:text-gray-300 hover:bg-blue-50 dark:hover:bg-blue-900/20 hover:text-blue-600 transition-colors text-base"
                        >
                            {link.icon}
                            <span>{link.label}</span>
                        </Link>
                    ))}
                </div>

                {/* User section at bottom */}
                {user && (
                    <>
                        <div className="border-t border-gray-200 dark:border-gray-700 my-2" />
                        <Link
                            href="/notifications"
                            onClick={closeDrawer}
                            className="flex items-center gap-3 px-6 py-3 text-blue-500 hover:bg-blue-50 dark:hover:bg-blue-900/20 transition-colors text-base"
                        >
                            <BellOutlined />
                            <span>{t('notifications')}</span>
                            <Badge count={unreadCount} size="small" />
                        </Link>
                        <Link
                            href="/favorites"
                            onClick={closeDrawer}
                            className="flex items-center gap-3 px-6 py-3 text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors text-base"
                        >
                            <HeartOutlined />
                            <span>{t('myFavorites')}</span>
                        </Link>
                        <Link
                            href="/my-resources"
                            onClick={closeDrawer}
                            className="flex items-center gap-3 px-6 py-3 text-blue-500 hover:bg-blue-50 dark:hover:bg-blue-900/20 transition-colors text-base"
                        >
                            <CloudUploadOutlined />
                            <span>{t('myResources')}</span>
                        </Link>
                        {user.role === 'ADMIN' && (
                            <button
                                type="button"
                                onClick={openSwitchModal}
                                className="flex items-center gap-3 px-6 py-3 text-gray-700 dark:text-gray-300 hover:bg-blue-50 dark:hover:bg-blue-900/20 hover:text-blue-600 transition-colors text-base text-left w-full"
                            >
                                <SwapOutlined />
                                <span>{t('switchAccount')}</span>
                            </button>
                        )}
                        {hasAdminBackup && (
                            <button
                                type="button"
                                onClick={handleReturnAdmin}
                                className="flex items-center gap-3 px-6 py-3 text-gray-700 dark:text-gray-300 hover:bg-blue-50 dark:hover:bg-blue-900/20 hover:text-blue-600 transition-colors text-base text-left w-full"
                            >
                                <SwapOutlined />
                                <span>{t('returnToAdmin')}</span>
                            </button>
                        )}
                    </>
                )}
            </Drawer>

            <Modal
                title={t('switchAccount')}
                open={switchModalOpen}
                onCancel={() => setSwitchModalOpen(false)}
                footer={null}
                destroyOnHidden
            >
                <Form
                    form={switchForm}
                    layout="vertical"
                    onFinish={handleSwitchAccount}
                >
                    <Form.Item name="userId" label={t('switchTargetUser')} rules={[{ required: true }]}>
                        <Select
                            showSearch
                            loading={loadingSwitchUsers}
                            placeholder={t('selectUser')}
                            optionFilterProp="label"
                        >
                            {switchUsers.map(item => (
                                <Select.Option
                                    key={item.id}
                                    value={item.id}
                                    label={item.username}
                                    disabled={item.id === user?.id}
                                >
                                    <div className="flex items-center justify-between gap-3">
                                        <span>{item.username}</span>
                                        <Tag color={item.role === 'ADMIN' ? 'red' : item.role === 'PUBLISHER' ? 'blue' : 'default'}>
                                            {item.role}
                                        </Tag>
                                    </div>
                                </Select.Option>
                            ))}
                        </Select>
                    </Form.Item>
                    <div className="flex justify-end gap-3">
                        <Button onClick={() => setSwitchModalOpen(false)}>{t('cancel')}</Button>
                        <Button type="primary" htmlType="submit" loading={switchingAccount}>
                            {t('switchAccount')}
                        </Button>
                    </div>
                </Form>
            </Modal>
        </>
    );
}
