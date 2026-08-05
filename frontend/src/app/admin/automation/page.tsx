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
    Modal,
    Row,
    Select,
    Space,
    Statistic,
    Switch,
    Table,
    Tabs,
    Tag,
    Tooltip,
    Typography,
} from 'antd';
import {
    LinkOutlined,
    MessageOutlined,
    NotificationOutlined,
    PlusOutlined,
    RedoOutlined,
    ReloadOutlined,
    SaveOutlined,
    SendOutlined,
} from '@ant-design/icons';
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

interface SocialPublishTarget {
    id: number;
    platform: 'QQ_CHANNEL' | 'WEIBO';
    accountKey: string;
    name: string;
    targetRef?: string;
    channelRef?: string;
    enabled: boolean;
    autoPostEnabled: boolean;
    scheduleTime: string;
    postsPerRun: number;
    postIntervalSeconds: number;
    template?: string;
    lastAutoRunAt?: string;
}

interface SocialPublishingOverview {
    targets: SocialPublishTarget[];
    posted: number;
    failed: number;
    pending: number;
    postedLast24Hours: number;
    publisher?: {
        ok?: boolean;
        qq?: { configured?: boolean; ready?: boolean; tokenSource?: string; error?: string };
        weibo?: { ready?: boolean; error?: string };
        error?: string;
    };
}

interface SocialPostLog {
    id: number;
    targetId: number;
    platform: 'QQ_CHANNEL' | 'WEIBO';
    resourceLinkId: number;
    movieId: string;
    title?: string;
    status: 'PENDING' | 'POSTED' | 'FAILED';
    externalUrl?: string;
    errorMessage?: string;
    postedAt?: string;
    createdAt?: string;
}

interface SocialTargetFormValues {
    platform: 'QQ_CHANNEL' | 'WEIBO';
    accountKey: string;
    name: string;
    targetRef?: string;
    channelRef?: string;
    enabled: boolean;
    autoPostEnabled: boolean;
    scheduleTime: string;
    postsPerRun: number;
    postIntervalSeconds: number;
    template?: string;
}

const BOT_STATUSES = ['SUCCEEDED', 'NO_RESOURCE', 'NO_METADATA', 'TRAILER', 'AMBIGUOUS', 'BLOCKED', 'RATE_LIMITED', 'REJECTED', 'FAILED'];
const CHANNEL_STATUSES = ['POSTED', 'FAILED', 'SKIPPED'];
const SOCIAL_STATUSES = ['PENDING', 'POSTED', 'FAILED'];

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
    const [socialTargetForm] = Form.useForm<SocialTargetFormValues>();
    const socialTargetPlatform = Form.useWatch('platform', socialTargetForm);
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
    const [socialOverview, setSocialOverview] = useState<SocialPublishingOverview | null>(null);
    const [socialLoading, setSocialLoading] = useState(false);
    const [socialBusyId, setSocialBusyId] = useState<number | 'all'>();
    const [socialLogs, setSocialLogs] = useState<SocialPostLog[]>([]);
    const [socialLogsLoading, setSocialLogsLoading] = useState(false);
    const [socialLogPage, setSocialLogPage] = useState(1);
    const [socialLogTotal, setSocialLogTotal] = useState(0);
    const [socialLogStatus, setSocialLogStatus] = useState<string | undefined>();
    const [socialLogPlatform, setSocialLogPlatform] = useState<string | undefined>();
    const [socialRetryId, setSocialRetryId] = useState<number>();
    const [socialCreateOpen, setSocialCreateOpen] = useState(false);
    const [socialCreating, setSocialCreating] = useState(false);

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
            PENDING: 'gold',
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

    const fetchSocialOverview = useCallback(async () => {
        if (!token) return;
        setSocialLoading(true);
        try {
            setSocialOverview(await requestJson<SocialPublishingOverview>('/api/admin/social-publishing/overview'));
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('socialPublishingLoadFailed'));
        } finally {
            setSocialLoading(false);
        }
    }, [message, requestJson, t, token]);

    const fetchSocialLogs = useCallback(async () => {
        if (!token) return;
        setSocialLogsLoading(true);
        try {
            const query = new URLSearchParams({
                page: String(socialLogPage),
                size: '20',
            });
            if (socialLogStatus) query.set('status', socialLogStatus);
            if (socialLogPlatform) query.set('platform', socialLogPlatform);
            const data = await requestJson<PageResult<SocialPostLog>>(
                `/api/admin/social-publishing/logs?${query.toString()}`,
            );
            setSocialLogs(data.records || []);
            setSocialLogTotal(data.total || 0);
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('socialPublishingLogsLoadFailed'));
        } finally {
            setSocialLogsLoading(false);
        }
    }, [
        message,
        requestJson,
        socialLogPage,
        socialLogPlatform,
        socialLogStatus,
        t,
        token,
    ]);

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

    useEffect(() => {
        fetchSocialOverview();
    }, [fetchSocialOverview]);

    useEffect(() => {
        fetchSocialLogs();
    }, [fetchSocialLogs]);

    const refreshAll = async () => {
        await Promise.all([
            fetchOverview(),
            fetchBotLogs(),
            fetchPostLogs(),
            fetchSocialOverview(),
            fetchSocialLogs(),
        ]);
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

    const updateSocialTargetValue = <K extends keyof SocialPublishTarget>(
        id: number,
        field: K,
        value: SocialPublishTarget[K],
    ) => {
        setSocialOverview(current => current ? {
            ...current,
            targets: current.targets.map(target => target.id === id ? { ...target, [field]: value } : target),
        } : current);
    };

    const saveSocialTarget = async (target: SocialPublishTarget) => {
        setSocialBusyId(target.id);
        try {
            await requestJson(`/api/admin/social-publishing/targets/${target.id}`, {
                method: 'PUT',
                body: JSON.stringify(target),
            });
            message.success(t('socialPublishingTargetSaved'));
            await fetchSocialOverview();
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setSocialBusyId(undefined);
        }
    };

    const openCreateSocialTarget = () => {
        socialTargetForm.resetFields();
        socialTargetForm.setFieldsValue({
            platform: 'QQ_CHANNEL',
            accountKey: 'secondary',
            enabled: true,
            autoPostEnabled: false,
            scheduleTime: '10:00',
            postsPerRun: 1,
            postIntervalSeconds: 60,
            template: t('socialPublishingDefaultQqTemplate'),
        });
        setSocialCreateOpen(true);
    };

    const createSocialTarget = async (values: SocialTargetFormValues) => {
        setSocialCreating(true);
        try {
            await requestJson('/api/admin/social-publishing/targets', {
                method: 'POST',
                body: JSON.stringify(values),
            });
            message.success(t('socialPublishingTargetCreated'));
            setSocialCreateOpen(false);
            await fetchSocialOverview();
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setSocialCreating(false);
        }
    };

    const publishSocialTarget = async (targetId?: number) => {
        setSocialBusyId(targetId || 'all');
        try {
            const path = targetId
                ? `/api/admin/social-publishing/targets/${targetId}/publish-next?runNow=true`
                : '/api/admin/social-publishing/publish-next?runNow=true';
            await requestJson(path, {
                method: 'POST',
                body: targetId ? undefined : JSON.stringify([]),
            });
            message.success(t('socialPublishingPublished'));
            await Promise.all([fetchSocialOverview(), fetchSocialLogs()]);
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setSocialBusyId(undefined);
        }
    };

    const retrySocialLog = async (logId: number) => {
        setSocialRetryId(logId);
        try {
            await requestJson(`/api/admin/social-publishing/logs/${logId}/retry`, {
                method: 'POST',
            });
            message.success(t('socialPublishingRetrySubmitted'));
            await Promise.all([fetchSocialOverview(), fetchSocialLogs()]);
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setSocialRetryId(undefined);
        }
    };

    const socialColumns: ColumnsType<SocialPublishTarget> = [
        {
            title: t('socialPublishingPlatform'),
            dataIndex: 'platform',
            width: 120,
            render: (value: string) => <Tag color={value === 'WEIBO' ? 'red' : 'blue'}>{value === 'WEIBO' ? t('socialPublishingWeibo') : 'QQ'}</Tag>,
        },
        {
            title: t('socialPublishingAccountKey'),
            dataIndex: 'accountKey',
            width: 130,
            render: (value: string) => <Text code>{value}</Text>,
        },
        {
            title: t('socialPublishingTarget'),
            dataIndex: 'name',
            width: 180,
            render: (value: string, target) => (
                <Input value={value} onChange={event => updateSocialTargetValue(target.id, 'name', event.target.value)} />
            ),
        },
        {
            title: t('socialPublishingChannelNumber'),
            dataIndex: 'targetRef',
            width: 150,
            render: (value: string | undefined, target) => target.platform === 'QQ_CHANNEL'
                ? <Input value={value} onChange={event => updateSocialTargetValue(target.id, 'targetRef', event.target.value)} />
                : '-',
        },
        {
            title: t('socialPublishingBoardId'),
            dataIndex: 'channelRef',
            width: 150,
            render: (value: string | undefined, target) => target.platform === 'QQ_CHANNEL'
                ? <Input allowClear placeholder={t('socialPublishingDefaultBoard')} value={value} onChange={event => updateSocialTargetValue(target.id, 'channelRef', event.target.value)} />
                : '-',
        },
        {
            title: t('enabled'),
            dataIndex: 'enabled',
            width: 90,
            render: (value: boolean, target) => (
                <Switch checked={value} onChange={checked => updateSocialTargetValue(target.id, 'enabled', checked)} />
            ),
        },
        {
            title: t('socialPublishingAutoPost'),
            dataIndex: 'autoPostEnabled',
            width: 100,
            render: (value: boolean, target) => (
                <Switch checked={value} onChange={checked => updateSocialTargetValue(target.id, 'autoPostEnabled', checked)} />
            ),
        },
        {
            title: t('socialPublishingTime'),
            dataIndex: 'scheduleTime',
            width: 110,
            render: (value: string, target) => (
                <Input value={value} onChange={event => updateSocialTargetValue(target.id, 'scheduleTime', event.target.value)} />
            ),
        },
        {
            title: t('socialPublishingPosts'),
            dataIndex: 'postsPerRun',
            width: 100,
            render: (value: number, target) => (
                <InputNumber min={1} max={20} value={value} onChange={next => updateSocialTargetValue(target.id, 'postsPerRun', next || 1)} />
            ),
        },
        {
            title: t('socialPublishingInterval'),
            dataIndex: 'postIntervalSeconds',
            width: 120,
            render: (value: number, target) => (
                <InputNumber
                    min={0}
                    max={86400}
                    value={value}
                    onChange={next => updateSocialTargetValue(target.id, 'postIntervalSeconds', next || 0)}
                />
            ),
        },
        {
            title: t('socialPublishingTemplate'),
            dataIndex: 'template',
            width: 320,
            render: (value: string | undefined, target) => (
                <Input.TextArea
                    rows={2}
                    value={value}
                    onChange={event => updateSocialTargetValue(target.id, 'template', event.target.value)}
                />
            ),
        },
        {
            title: t('actions'),
            key: 'actions',
            fixed: 'right',
            width: 190,
            render: (_, target) => (
                <Space>
                    <Button
                        icon={<SaveOutlined />}
                        loading={socialBusyId === target.id}
                        onClick={() => saveSocialTarget(target)}
                    >
                        {t('save')}
                    </Button>
                    <Button
                        type="primary"
                        icon={<SendOutlined />}
                        loading={socialBusyId === target.id}
                        disabled={!target.enabled}
                        onClick={() => publishSocialTarget(target.id)}
                    >
                        {t('socialPublishingPublish')}
                    </Button>
                </Space>
            ),
        },
    ];

    const socialLogColumns: ColumnsType<SocialPostLog> = [
        { title: 'ID', dataIndex: 'id', width: 80 },
        {
            title: t('socialPublishingPlatform'),
            dataIndex: 'platform',
            width: 110,
            render: (value: string) => (
                <Tag color={value === 'WEIBO' ? 'red' : 'blue'}>
                    {value === 'WEIBO' ? t('socialPublishingWeibo') : 'QQ'}
                </Tag>
            ),
        },
        {
            title: t('socialPublishingTarget'),
            dataIndex: 'targetId',
            width: 180,
            render: (value: number) => socialOverview?.targets.find(target => target.id === value)?.name || `#${value}`,
        },
        { title: t('movieTitle'), dataIndex: 'title', width: 220, ellipsis: true, render: (value?: string) => value || '-' },
        { title: t('status'), dataIndex: 'status', width: 110, render: statusTag },
        { title: t('movieId'), dataIndex: 'movieId', width: 160, ellipsis: true },
        { title: t('resourceId'), dataIndex: 'resourceLinkId', width: 110 },
        {
            title: t('socialPublishingExternalUrl'),
            dataIndex: 'externalUrl',
            width: 180,
            render: (value?: string) => value ? (
                <Typography.Link href={value} target="_blank" rel="noreferrer">
                    <LinkOutlined /> {t('socialPublishingViewPost')}
                </Typography.Link>
            ) : '-',
        },
        { title: t('socialPublishingPostedAt'), dataIndex: 'postedAt', width: 180, render: formatDate },
        { title: t('createdAt'), dataIndex: 'createdAt', width: 180, render: formatDate },
        {
            title: t('socialPublishingError'),
            dataIndex: 'errorMessage',
            width: 260,
            ellipsis: true,
            render: (value?: string) => value ? <Tooltip title={value}>{value}</Tooltip> : '-',
        },
        {
            title: t('actions'),
            key: 'actions',
            fixed: 'right',
            width: 110,
            render: (_, log) => (
                <Button
                    icon={<RedoOutlined />}
                    loading={socialRetryId === log.id}
                    disabled={log.status === 'POSTED'}
                    onClick={() => retrySocialLog(log.id)}
                >
                    {t('socialPublishingRetry')}
                </Button>
            ),
        },
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
                        {
                            key: 'social',
                            label: t('socialPublishingTab'),
                            children: (
                                <Space direction="vertical" size={16} className="w-full">
                                    <Row gutter={[16, 16]}>
                                        <Col xs={12} md={6}>
                                            <Card loading={socialLoading}>
                                                <Statistic title={t('socialPublishingPosted')} value={socialOverview?.posted || 0} />
                                            </Card>
                                        </Col>
                                        <Col xs={12} md={6}>
                                            <Card loading={socialLoading}>
                                                <Statistic title={t('socialPublishingPosted24h')} value={socialOverview?.postedLast24Hours || 0} />
                                            </Card>
                                        </Col>
                                        <Col xs={12} md={6}>
                                            <Card loading={socialLoading}>
                                                <Statistic title={t('socialPublishingPending')} value={socialOverview?.pending || 0} />
                                            </Card>
                                        </Col>
                                        <Col xs={12} md={6}>
                                            <Card loading={socialLoading}>
                                                <Statistic title={t('socialPublishingFailed')} value={socialOverview?.failed || 0} />
                                            </Card>
                                        </Col>
                                    </Row>
                                    <Alert
                                        type={socialOverview?.publisher?.qq?.ready && socialOverview?.publisher?.weibo?.ready ? 'success' : 'warning'}
                                        showIcon
                                        message={t('socialPublishingAuthStatus')}
                                        description={t('socialPublishingAuthHelp', {
                                            qq: socialOverview?.publisher?.qq?.ready ? t('socialPublishingReady') : t('socialPublishingNeedsAuth'),
                                            weibo: socialOverview?.publisher?.weibo?.ready ? t('socialPublishingReady') : t('socialPublishingNeedsAuth'),
                                        })}
                                    />
                                    <Card
                                        title={t('socialPublishingTargets')}
                                        extra={(
                                            <Space>
                                                <Button icon={<PlusOutlined />} onClick={openCreateSocialTarget}>
                                                    {t('socialPublishingAddAccount')}
                                                </Button>
                                                <Button
                                                    type="primary"
                                                    icon={<SendOutlined />}
                                                    loading={socialBusyId === 'all'}
                                                    onClick={() => publishSocialTarget()}
                                                >
                                                    {t('socialPublishingPublishAll')}
                                                </Button>
                                            </Space>
                                        )}
                                    >
                                        <Table
                                            rowKey="id"
                                            columns={socialColumns}
                                            dataSource={socialOverview?.targets || []}
                                            loading={socialLoading}
                                            pagination={false}
                                            scroll={{ x: 1900 }}
                                        />
                                    </Card>
                                    <Card title={t('socialPublishingHistory')}>
                                        <Space className="mb-4" wrap>
                                            <Select
                                                allowClear
                                                placeholder={t('socialPublishingPlatform')}
                                                options={[
                                                    { value: 'QQ_CHANNEL', label: 'QQ' },
                                                    { value: 'WEIBO', label: t('socialPublishingWeibo') },
                                                ]}
                                                value={socialLogPlatform}
                                                style={{ width: 150 }}
                                                onChange={(value) => {
                                                    setSocialLogPlatform(value);
                                                    setSocialLogPage(1);
                                                }}
                                            />
                                            <Select
                                                allowClear
                                                placeholder={t('filterByStatus')}
                                                options={SOCIAL_STATUSES.map(value => ({
                                                    value,
                                                    label: t(`qqAutomationStatus.${value}`, { defaultValue: value }),
                                                }))}
                                                value={socialLogStatus}
                                                style={{ width: 160 }}
                                                onChange={(value) => {
                                                    setSocialLogStatus(value);
                                                    setSocialLogPage(1);
                                                }}
                                            />
                                            <Button icon={<ReloadOutlined />} onClick={fetchSocialLogs}>
                                                {t('refresh')}
                                            </Button>
                                        </Space>
                                        <Table
                                            rowKey="id"
                                            columns={socialLogColumns}
                                            dataSource={socialLogs}
                                            loading={socialLogsLoading}
                                            scroll={{ x: 1900 }}
                                            locale={{ emptyText: <Empty description={t('socialPublishingNoLogs')} /> }}
                                            pagination={{
                                                current: socialLogPage,
                                                pageSize: 20,
                                                total: socialLogTotal,
                                                onChange: setSocialLogPage,
                                                showTotal: total => t('totalItems', { count: total }),
                                            }}
                                        />
                                    </Card>
                                </Space>
                            ),
                        },
                    ]}
                />
                <Modal
                    title={t('socialPublishingAddAccount')}
                    open={socialCreateOpen}
                    confirmLoading={socialCreating}
                    okText={t('save')}
                    cancelText={t('cancel')}
                    onOk={() => socialTargetForm.submit()}
                    onCancel={() => setSocialCreateOpen(false)}
                    destroyOnHidden
                >
                    <Alert
                        className="mb-4"
                        type="info"
                        showIcon
                        message={t('socialPublishingAccountScope')}
                    />
                    <Form
                        form={socialTargetForm}
                        layout="vertical"
                        onFinish={createSocialTarget}
                    >
                        <Row gutter={12}>
                            <Col span={12}>
                                <Form.Item
                                    name="platform"
                                    label={t('socialPublishingPlatform')}
                                    rules={[{ required: true }]}
                                >
                                    <Select
                                        options={[
                                            { value: 'QQ_CHANNEL', label: 'QQ' },
                                            { value: 'WEIBO', label: t('socialPublishingWeibo') },
                                        ]}
                                        onChange={(platform: 'QQ_CHANNEL' | 'WEIBO') => {
                                            socialTargetForm.setFieldsValue({
                                                accountKey: platform === 'QQ_CHANNEL' ? 'secondary' : 'default',
                                                targetRef: platform === 'QQ_CHANNEL' ? undefined : 'default',
                                                channelRef: undefined,
                                                template: platform === 'QQ_CHANNEL'
                                                    ? t('socialPublishingDefaultQqTemplate')
                                                    : t('socialPublishingDefaultWeiboTemplate'),
                                            });
                                        }}
                                    />
                                </Form.Item>
                            </Col>
                            <Col span={12}>
                                <Form.Item
                                    name="accountKey"
                                    label={t('socialPublishingAccountKey')}
                                    rules={[{ required: true }]}
                                >
                                    <Select
                                        options={socialTargetPlatform === 'WEIBO'
                                            ? [{ value: 'default', label: 'default' }]
                                            : [{ value: 'secondary', label: 'secondary' }]}
                                    />
                                </Form.Item>
                            </Col>
                        </Row>
                        <Form.Item
                            name="name"
                            label={t('socialPublishingTarget')}
                            rules={[{ required: true, whitespace: true }]}
                        >
                            <Input />
                        </Form.Item>
                        {socialTargetPlatform !== 'WEIBO' && (
                            <Row gutter={12}>
                                <Col span={12}>
                                    <Form.Item
                                        name="targetRef"
                                        label={t('socialPublishingChannelNumber')}
                                        rules={[{ required: true, whitespace: true }]}
                                    >
                                        <Input placeholder="pd..." />
                                    </Form.Item>
                                </Col>
                                <Col span={12}>
                                    <Form.Item name="channelRef" label={t('socialPublishingBoardId')}>
                                        <Input allowClear placeholder={t('socialPublishingDefaultBoard')} />
                                    </Form.Item>
                                </Col>
                            </Row>
                        )}
                        <Row gutter={12}>
                            <Col span={8}>
                                <Form.Item name="enabled" label={t('enabled')} valuePropName="checked">
                                    <Switch />
                                </Form.Item>
                            </Col>
                            <Col span={8}>
                                <Form.Item
                                    name="autoPostEnabled"
                                    label={t('socialPublishingAutoPost')}
                                    valuePropName="checked"
                                >
                                    <Switch />
                                </Form.Item>
                            </Col>
                            <Col span={8}>
                                <Form.Item
                                    name="scheduleTime"
                                    label={t('socialPublishingTime')}
                                    rules={[{ required: true, pattern: /^([01]\d|2[0-3]):[0-5]\d$/ }]}
                                >
                                    <Input placeholder="10:00" />
                                </Form.Item>
                            </Col>
                        </Row>
                        <Row gutter={12}>
                            <Col span={12}>
                                <Form.Item
                                    name="postsPerRun"
                                    label={t('socialPublishingPosts')}
                                    rules={[{ required: true }]}
                                >
                                    <InputNumber min={1} max={20} className="w-full" />
                                </Form.Item>
                            </Col>
                            <Col span={12}>
                                <Form.Item
                                    name="postIntervalSeconds"
                                    label={t('socialPublishingInterval')}
                                    rules={[{ required: true }]}
                                >
                                    <InputNumber min={0} max={86400} className="w-full" />
                                </Form.Item>
                            </Col>
                        </Row>
                        <Form.Item name="template" label={t('socialPublishingTemplate')}>
                            <Input.TextArea rows={6} />
                        </Form.Item>
                        <Text type="secondary">{t('qqAutomationPostTemplateHelp')}</Text>
                    </Form>
                </Modal>
            </div>
        </div>
    );
}
