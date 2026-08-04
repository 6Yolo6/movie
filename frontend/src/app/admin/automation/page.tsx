'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
    Alert,
    App,
    Button,
    Card,
    Col,
    Empty,
    Form,
    Input,
    InputNumber,
    Row,
    Select,
    Space,
    Statistic,
    Switch,
    Table,
    Tabs,
    Tag,
    Typography,
} from 'antd';
import { MessageOutlined, NotificationOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { api, readApiError } from '@/lib/api';
import { useAuthStore } from '@/store/authStore';

const { Title, Text } = Typography;

interface ApiEnvelope<T> {
    data?: T;
}

interface PageResult<T> {
    records: T[];
    total: number;
    current: number;
    size: number;
}

interface AutomationConfig {
    botMinKeywordLength: number;
    botRateLimitPerMinute: number;
    botMaxResults: number;
    botBlockedKeywords: string;
    channelAutoPostEnabled: boolean;
    channelIntervalMinutes: number;
    channelMaxPostsPerRun: number;
    channelDailyTime: string;
    channelPostTotal: number;
    channelPostIntervalSeconds: number;
    channelPostTemplate: string;
    channelCandidateLimit: number;
    channelGuildId: string;
    channelMovieId: string;
    channelTvId: string;
}

interface AutomationOverview {
    config: AutomationConfig;
    botStatusCounts: Record<string, number>;
    botRecentStatusCounts: Record<string, number>;
    botSummary: {
        total: number;
        last24Hours: number;
        succeeded: number;
        successRate: number;
        noResult: number;
        ambiguous: number;
        blocked: number;
        failed: number;
    };
    channelStatusCounts: Record<string, number>;
    channelRecentStatusCounts: Record<string, number>;
}

interface BotSearchLog {
    id: number;
    userKey?: string;
    keyword?: string;
    status: string;
    movieId?: string;
    resourceCount?: number;
    replyPreview?: string;
    failureReason?: string;
    createdAt?: string;
}

interface ChannelPostLog {
    id: number;
    resourceLinkId?: number;
    movieId?: string;
    title?: string;
    linkUrl?: string;
    channelType?: string;
    channelId?: string;
    status: string;
    errorMessage?: string;
    postedAt?: string;
    createdAt?: string;
}

const BOT_STATUSES = ['SUCCEEDED', 'NO_RESOURCE', 'NO_METADATA', 'TRAILER', 'AMBIGUOUS', 'BLOCKED', 'RATE_LIMITED', 'REJECTED', 'FAILED'];
const CHANNEL_STATUSES = ['POSTED', 'FAILED', 'SKIPPED'];

function unwrap<T>(payload: ApiEnvelope<T> | T): T {
    if (payload && typeof payload === 'object' && 'data' in payload) {
        return (payload as ApiEnvelope<T>).data as T;
    }
    return payload as T;
}

function formatDate(value?: string) {
    return value ? new Date(value).toLocaleString() : '-';
}

export default function QqAutomationAdminPage() {
    const { user, token } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [form] = Form.useForm<AutomationConfig>();
    const [overview, setOverview] = useState<AutomationOverview | null>(null);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [botLogs, setBotLogs] = useState<BotSearchLog[]>([]);
    const [botLoading, setBotLoading] = useState(false);
    const [botPage, setBotPage] = useState(1);
    const [botTotal, setBotTotal] = useState(0);
    const [botStatus, setBotStatus] = useState<string | undefined>();
    const [botKeyword, setBotKeyword] = useState('');
    const [postLogs, setPostLogs] = useState<ChannelPostLog[]>([]);
    const [postLoading, setPostLoading] = useState(false);
    const [postPage, setPostPage] = useState(1);
    const [postTotal, setPostTotal] = useState(0);
    const [postStatus, setPostStatus] = useState<string | undefined>();
    const [postChannelType, setPostChannelType] = useState<string | undefined>();
    const [postKeyword, setPostKeyword] = useState('');

    const authHeaders = useMemo(() => ({
        Authorization: `Bearer ${token}`,
    }), [token]);

    const requestJson = useCallback(async <T,>(path: string, options: RequestInit = {}): Promise<T> => {
        const res = await api(path, {
            ...options,
            headers: {
                ...authHeaders,
                ...(options.headers as Record<string, string> | undefined),
            },
        });
        if (!res.ok) {
            throw new Error(await readApiError(res, t('operationFailed')));
        }
        return unwrap<T>(await res.json());
    }, [authHeaders, t]);

    const statusTag = (value?: string) => {
        const status = value || 'UNKNOWN';
        const colorMap: Record<string, string> = {
            SUCCEEDED: 'green',
            POSTED: 'green',
            NO_RESOURCE: 'orange',
            NO_METADATA: 'orange',
            TRAILER: 'blue',
            BLOCKED: 'red',
            RATE_LIMITED: 'purple',
            REJECTED: 'default',
            FAILED: 'red',
            SKIPPED: 'default',
        };
        return <Tag color={colorMap[status] || 'default'}>{t(`qqAutomationStatus.${status}`, { defaultValue: status })}</Tag>;
    };

    const fetchOverview = useCallback(async () => {
        if (!token) {
            setLoading(false);
            return;
        }
        setLoading(true);
        try {
            const data = await requestJson<AutomationOverview>('/api/admin/qq-automation/overview');
            setOverview(data);
            form.setFieldsValue(data.config);
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('qqAutomationLoadFailed'));
        } finally {
            setLoading(false);
        }
    }, [form, message, requestJson, t, token]);

    const fetchBotLogs = useCallback(async () => {
        if (!token) return;
        setBotLoading(true);
        try {
            const query = new URLSearchParams({ page: String(botPage), size: '20' });
            if (botStatus) query.set('status', botStatus);
            if (botKeyword.trim()) query.set('keyword', botKeyword.trim());
            const data = await requestJson<PageResult<BotSearchLog>>(`/api/admin/qq-automation/bot-searches?${query.toString()}`);
            setBotLogs(data.records || []);
            setBotTotal(data.total || 0);
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('qqAutomationBotLogsLoadFailed'));
        } finally {
            setBotLoading(false);
        }
    }, [botKeyword, botPage, botStatus, message, requestJson, t, token]);

    const fetchPostLogs = useCallback(async () => {
        if (!token) return;
        setPostLoading(true);
        try {
            const query = new URLSearchParams({ page: String(postPage), size: '20' });
            if (postStatus) query.set('status', postStatus);
            if (postChannelType) query.set('channelType', postChannelType);
            if (postKeyword.trim()) query.set('keyword', postKeyword.trim());
            const data = await requestJson<PageResult<ChannelPostLog>>(`/api/admin/qq-automation/channel-posts?${query.toString()}`);
            setPostLogs(data.records || []);
            setPostTotal(data.total || 0);
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('qqAutomationPostLogsLoadFailed'));
        } finally {
            setPostLoading(false);
        }
    }, [message, postChannelType, postKeyword, postPage, postStatus, requestJson, t, token]);

    useEffect(() => {
        if (!user) return;
        if (user.role !== 'ADMIN') {
            message.error(t('adminAccessRequired'));
            router.push('/');
            return;
        }
        fetchOverview();
    }, [fetchOverview, message, router, t, user]);

    useEffect(() => {
        fetchBotLogs();
    }, [fetchBotLogs]);

    useEffect(() => {
        fetchPostLogs();
    }, [fetchPostLogs]);

    const refreshAll = async () => {
        await Promise.all([fetchOverview(), fetchBotLogs(), fetchPostLogs()]);
    };

    const saveConfig = async (values: AutomationConfig) => {
        setSaving(true);
        try {
            const data = await requestJson<AutomationConfig>('/api/admin/qq-automation/config', {
                method: 'PUT',
                body: JSON.stringify(values),
            });
            form.setFieldsValue(data);
            message.success(t('qqAutomationConfigSaved'));
            await fetchOverview();
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setSaving(false);
        }
    };

    const botColumns: ColumnsType<BotSearchLog> = [
        { title: 'ID', dataIndex: 'id', width: 80 },
        { title: t('qqAutomationKeyword'), dataIndex: 'keyword', width: 180, ellipsis: true, render: (value?: string) => value || '-' },
        { title: t('qqAutomationUserKey'), dataIndex: 'userKey', width: 150, ellipsis: true, render: (value?: string) => value || '-' },
        { title: t('status'), dataIndex: 'status', width: 130, render: statusTag },
        { title: t('movieId'), dataIndex: 'movieId', width: 150, ellipsis: true, render: (value?: string) => value || '-' },
        { title: t('qqAutomationResourceCount'), dataIndex: 'resourceCount', width: 110, render: (value?: number) => value ?? 0 },
        { title: t('createdAt'), dataIndex: 'createdAt', width: 180, render: formatDate },
        { title: t('qqAutomationReason'), dataIndex: 'failureReason', width: 220, ellipsis: true, render: (value?: string) => value || '-' },
        { title: t('qqAutomationReplyPreview'), dataIndex: 'replyPreview', ellipsis: true, render: (value?: string) => value || '-' },
    ];

    const postColumns: ColumnsType<ChannelPostLog> = [
        { title: 'ID', dataIndex: 'id', width: 80 },
        { title: t('resourceId'), dataIndex: 'resourceLinkId', width: 110, render: (value?: number) => value || '-' },
        { title: t('movieTitle'), dataIndex: 'title', width: 220, ellipsis: true, render: (value?: string) => value || '-' },
        { title: t('type'), dataIndex: 'channelType', width: 110, render: (value?: string) => value ? <Tag>{value}</Tag> : '-' },
        { title: t('status'), dataIndex: 'status', width: 120, render: statusTag },
        { title: t('qqAutomationChannelId'), dataIndex: 'channelId', width: 140, ellipsis: true, render: (value?: string) => value || '-' },
        { title: t('qqAutomationPostedAt'), dataIndex: 'postedAt', width: 180, render: formatDate },
        { title: t('createdAt'), dataIndex: 'createdAt', width: 180, render: formatDate },
        { title: t('qqAutomationPostError'), dataIndex: 'errorMessage', width: 220, ellipsis: true, render: (value?: string) => value || '-' },
        { title: t('resourceLink'), dataIndex: 'linkUrl', ellipsis: true, render: (value?: string) => value || '-' },
    ];

    const botSummary = overview?.botSummary;
    const channelCounts = overview?.channelStatusCounts || {};

    return (
        <div className="min-h-screen bg-[#f5f7fa] dark:bg-black">
            <div className="container mx-auto px-4 lg:px-8 py-8">
                <div className="mb-6 flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
                    <div className="flex items-center gap-3">
                        <MessageOutlined className="text-3xl text-blue-500" />
                        <div>
                            <Title level={2} className="!mb-1">{t('qqAutomationTitle')}</Title>
                            <Text type="secondary">{t('qqAutomationHint')}</Text>
                        </div>
                    </div>
                    <Button icon={<ReloadOutlined />} onClick={refreshAll} loading={loading}>
                        {t('refresh')}
                    </Button>
                </div>

                <Row gutter={[16, 16]} className="mb-6">
                    <Col xs={12} md={8} xl={4}>
                        <Card loading={loading}>
                            <Statistic title={t('qqAutomationSearchTotal')} value={botSummary?.total || 0} prefix={<MessageOutlined />} />
                        </Card>
                    </Col>
                    <Col xs={12} md={8} xl={4}>
                        <Card loading={loading}>
                            <Statistic title={t('qqAutomationSearch24h')} value={botSummary?.last24Hours || 0} />
                        </Card>
                    </Col>
                    <Col xs={12} md={8} xl={4}>
                        <Card loading={loading}>
                            <Statistic title={t('qqAutomationSearchSuccessRate')} value={botSummary?.successRate || 0} suffix="%" precision={1} />
                        </Card>
                    </Col>
                    <Col xs={12} md={8} xl={4}>
                        <Card loading={loading}>
                            <Statistic title={t('qqAutomationSearchNoResult')} value={botSummary?.noResult || 0} />
                        </Card>
                    </Col>
                    <Col xs={12} md={8} xl={4}>
                        <Card loading={loading}>
                            <Statistic title={t('qqAutomationPostsDone')} value={channelCounts.POSTED || 0} prefix={<NotificationOutlined />} />
                        </Card>
                    </Col>
                    <Col xs={12} md={8} xl={4}>
                        <Card loading={loading}>
                            <Statistic title={t('qqAutomationSearchFailed')} value={(botSummary?.failed || 0) + (botSummary?.ambiguous || 0)} />
                        </Card>
                    </Col>
                </Row>

                <Tabs
                    items={[
                        {
                            key: 'settings',
                            label: t('settings'),
                            children: (
                                <Card title={t('qqAutomationSettings')}>
                                    <Form form={form} layout="vertical" onFinish={saveConfig}>
                                        <Row gutter={16}>
                                            <Col xs={24} lg={12}>
                                                <Card size="small" title={t('qqAutomationBotSettings')}>
                                                    <Row gutter={12}>
                                                        <Col xs={24} md={8}>
                                                            <Form.Item name="botMinKeywordLength" label={t('qqAutomationMinKeywordLength')}>
                                                                <InputNumber min={1} max={20} className="w-full" />
                                                            </Form.Item>
                                                        </Col>
                                                        <Col xs={24} md={8}>
                                                            <Form.Item name="botRateLimitPerMinute" label={t('qqAutomationRateLimit')}>
                                                                <InputNumber min={0} max={100} className="w-full" />
                                                            </Form.Item>
                                                        </Col>
                                                        <Col xs={24} md={8}>
                                                            <Form.Item name="botMaxResults" label={t('qqAutomationMaxResults')}>
                                                                <InputNumber min={1} max={5} className="w-full" />
                                                            </Form.Item>
                                                        </Col>
                                                    </Row>
                                                    <Form.Item name="botBlockedKeywords" label={t('qqAutomationBlockedKeywords')}>
                                                        <Input.TextArea rows={5} placeholder={t('qqAutomationBlockedKeywordsPlaceholder')} />
                                                    </Form.Item>
                                                </Card>
                                            </Col>
                                            <Col xs={24} lg={12}>
                                                <Card size="small" title={t('qqAutomationChannelSettings')}>
                                                    <Alert
                                                        className="mb-4"
                                                        type="info"
                                                        showIcon
                                                        message={t('qqAutomationReloginTitle')}
                                                        description={(
                                                            <Space direction="vertical" size={4}>
                                                                <Text>{t('qqAutomationReloginHelp')}</Text>
                                                                <Text code>tencent-channel-cli login --json</Text>
                                                                <Text code>tencent-channel-cli login poll-token --json</Text>
                                                            </Space>
                                                        )}
                                                    />
                                                    <Row gutter={12}>
                                                        <Col xs={24} md={8}>
                                                            <Form.Item name="channelAutoPostEnabled" label={t('qqAutomationAutoPost')} valuePropName="checked">
                                                                <Switch />
                                                            </Form.Item>
                                                        </Col>
                                                        <Col xs={12} md={8}>
                                                            <Form.Item name="channelDailyTime" label={t('qqAutomationDailyTime')}>
                                                                <Input placeholder="09:00" />
                                                            </Form.Item>
                                                        </Col>
                                                        <Col xs={12} md={8}>
                                                            <Form.Item name="channelPostTotal" label={t('qqAutomationPostTotal')}>
                                                                <InputNumber min={1} max={100} className="w-full" />
                                                            </Form.Item>
                                                        </Col>
                                                        <Col xs={12} md={8}>
                                                            <Form.Item name="channelPostIntervalSeconds" label={t('qqAutomationPostIntervalSeconds')}>
                                                                <InputNumber min={0} max={86400} className="w-full" />
                                                            </Form.Item>
                                                        </Col>
                                                        <Col xs={12}>
                                                            <Form.Item name="channelCandidateLimit" label={t('qqAutomationCandidateLimit')}>
                                                                <InputNumber min={1} max={100} className="w-full" />
                                                            </Form.Item>
                                                        </Col>
                                                        <Col xs={12}>
                                                            <Form.Item name="channelGuildId" label={t('qqAutomationGuildId')}>
                                                                <Input />
                                                            </Form.Item>
                                                        </Col>
                                                        <Col xs={12}>
                                                            <Form.Item name="channelMovieId" label={t('qqAutomationMovieChannelId')}>
                                                                <Input />
                                                            </Form.Item>
                                                        </Col>
                                                        <Col xs={12}>
                                                            <Form.Item name="channelTvId" label={t('qqAutomationTvChannelId')}>
                                                                <Input />
                                                            </Form.Item>
                                                        </Col>
                                                        <Col xs={24}>
                                                            <Form.Item name="channelPostTemplate" label={t('qqAutomationPostTemplate')}>
                                                                <Input.TextArea rows={5} />
                                                            </Form.Item>
                                                            <Text type="secondary">{t('qqAutomationPostTemplateHelp')}</Text>
                                                        </Col>
                                                    </Row>
                                                    <Text type="secondary">{t('qqAutomationChannelIdHelp')}</Text>
                                                </Card>
                                            </Col>
                                        </Row>
                                        <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={saving} className="mt-4">
                                            {t('save')}
                                        </Button>
                                    </Form>
                                </Card>
                            ),
                        },
                        {
                            key: 'bot',
                            label: t('qqAutomationBotLogs'),
                            children: (
                                <Card>
                                    <Space className="mb-4" wrap>
                                        <Input.Search
                                            allowClear
                                            placeholder={t('qqAutomationSearchPlaceholder')}
                                            style={{ width: 260 }}
                                            onSearch={(value) => {
                                                setBotKeyword(value);
                                                setBotPage(1);
                                            }}
                                        />
                                        <Select
                                            allowClear
                                            placeholder={t('filterByStatus')}
                                            options={BOT_STATUSES.map((value) => ({ value, label: t(`qqAutomationStatus.${value}`) }))}
                                            value={botStatus}
                                            style={{ width: 180 }}
                                            onChange={(value) => {
                                                setBotStatus(value);
                                                setBotPage(1);
                                            }}
                                        />
                                        <Button icon={<ReloadOutlined />} onClick={fetchBotLogs}>{t('refresh')}</Button>
                                    </Space>
                                    <Table
                                        rowKey="id"
                                        columns={botColumns}
                                        dataSource={botLogs}
                                        loading={botLoading}
                                        scroll={{ x: 1400 }}
                                        locale={{ emptyText: <Empty description={t('qqAutomationNoBotLogs')} /> }}
                                        pagination={{
                                            current: botPage,
                                            pageSize: 20,
                                            total: botTotal,
                                            onChange: setBotPage,
                                            showTotal: (total) => t('totalItems', { count: total }),
                                        }}
                                    />
                                </Card>
                            ),
                        },
                        {
                            key: 'channel',
                            label: t('qqAutomationPostLogs'),
                            children: (
                                <Card>
                                    <Space className="mb-4" wrap>
                                        <Input.Search
                                            allowClear
                                            placeholder={t('qqAutomationPostSearchPlaceholder')}
                                            style={{ width: 260 }}
                                            onSearch={(value) => {
                                                setPostKeyword(value);
                                                setPostPage(1);
                                            }}
                                        />
                                        <Select
                                            allowClear
                                            placeholder={t('filterByStatus')}
                                            options={CHANNEL_STATUSES.map((value) => ({ value, label: t(`qqAutomationStatus.${value}`) }))}
                                            value={postStatus}
                                            style={{ width: 160 }}
                                            onChange={(value) => {
                                                setPostStatus(value);
                                                setPostPage(1);
                                            }}
                                        />
                                        <Select
                                            allowClear
                                            placeholder={t('type')}
                                            options={[{ value: 'movie', label: t('movies') }, { value: 'tv', label: t('tvShows') }]}
                                            value={postChannelType}
                                            style={{ width: 140 }}
                                            onChange={(value) => {
                                                setPostChannelType(value);
                                                setPostPage(1);
                                            }}
                                        />
                                        <Button icon={<ReloadOutlined />} onClick={fetchPostLogs}>{t('refresh')}</Button>
                                    </Space>
                                    <Table
                                        rowKey="id"
                                        columns={postColumns}
                                        dataSource={postLogs}
                                        loading={postLoading}
                                        scroll={{ x: 1500 }}
                                        locale={{ emptyText: <Empty description={t('qqAutomationNoPostLogs')} /> }}
                                        pagination={{
                                            current: postPage,
                                            pageSize: 20,
                                            total: postTotal,
                                            onChange: setPostPage,
                                            showTotal: (total) => t('totalItems', { count: total }),
                                        }}
                                    />
                                </Card>
                            ),
                        },
                    ]}
                />
            </div>
        </div>
    );
}
