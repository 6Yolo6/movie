'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
    App,
    Button,
    Card,
    Col,
    Descriptions,
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
import {
    CloudSyncOutlined,
    DatabaseOutlined,
    PlayCircleOutlined,
    ReloadOutlined,
    SaveOutlined,
    SearchOutlined,
    ShareAltOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { api, readApiError } from '@/lib/api';
import { useAuthStore } from '@/store/authStore';

const { Title, Text } = Typography;

interface ApiEnvelope<T> {
    code?: string;
    message?: string;
    data?: T;
}

interface ResourceHubConfig {
    enabled: boolean;
    autoApprove: boolean;
    tmdbConfigured: boolean;
    tmdbAutoSyncEnabled: boolean;
    tmdbAutoSyncSources: string;
    tmdbAutoSyncPage: number;
    tmdbAutoSyncMaxItems: number;
    tmdbAutoSyncIntervalHours: number;
    tmdbAutoDiscoveryEnabled: boolean;
    tmdbDiscoveryMaxResults: number;
    tmdbDiscoveryCooldownHours: number;
    workerEnabled: boolean;
    workerFixedDelayMs: number;
    workerTaskLimit: number;
    workerQuarkLimit: number;
    workerPublishLimit: number;
}

type ResourceHubConfigFormValues = Omit<ResourceHubConfig, 'tmdbAutoSyncSources'> & {
    tmdbAutoSyncSources: string[];
};

interface WorkerStatus {
    enabled: boolean;
    running: boolean;
    fixedDelayMs: number;
    taskLimit: number;
    quarkLimit: number;
    publishLimit: number;
}

interface Overview {
    enabled: boolean;
    autoApprove: boolean;
    tmdbConfigured: boolean;
    pansouBaseUrl: string;
    quarkBaseUrl: string;
    config: ResourceHubConfig;
    worker: WorkerStatus;
    taskStatusCounts: Record<string, number>;
    discoveredCount: number;
    savedDiscoveryCount: number;
    pendingQuarkTransfers: number;
}

interface PageResult<T> {
    records: T[];
    total: number;
    current: number;
    size: number;
}

interface ResourceHubTask {
    id: number;
    taskType: string;
    movieId?: string;
    keyword?: string;
    source?: string;
    status: string;
    priority?: number;
    attempts?: number;
    lastError?: string;
    scheduledAt?: string;
    createdAt?: string;
}

interface DiscoveryResult {
    id: number;
    taskId?: number;
    movieId: string;
    source: string;
    title?: string;
    provider?: string;
    shareUrl?: string;
    quality?: string;
    fileSize?: string;
    status: string;
    failureReason?: string;
    resourceLinkId?: number;
    createdAt?: string;
}

interface TmdbFormValues {
    source: string;
    page: number;
    maxItems: number;
    runNow: boolean;
}

interface DiscoveryFormValues {
    movieId: string;
    keyword?: string;
    maxResults: number;
    refresh: boolean;
    runNow: boolean;
}

const TMDB_SOURCE_OPTIONS = [
    { value: 'TRENDING_MOVIE_DAY', label: '电影日趋势' },
    { value: 'TRENDING_TV_DAY', label: '剧集日趋势' },
    { value: 'POPULAR_MOVIE', label: '热门电影' },
    { value: 'POPULAR_TV', label: '热门剧集' },
    { value: 'TOP_RATED_MOVIE', label: '高分电影' },
    { value: 'TOP_RATED_TV', label: '高分剧集' },
    { value: 'UPCOMING_MOVIE', label: '即将上映' },
];

const TASK_TYPE_OPTIONS = [
    { value: 'METADATA_SYNC', label: '元数据同步' },
    { value: 'RESOURCE_DISCOVERY', label: '资源搜索' },
];

const TASK_STATUS_OPTIONS = ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED'];
const DISCOVERY_STATUS_OPTIONS = ['DISCOVERED', 'SAVED', 'DUPLICATE', 'IGNORED', 'FAILED'];

function unwrap<T>(payload: ApiEnvelope<T> | T): T {
    if (payload && typeof payload === 'object' && 'data' in payload) {
        return (payload as ApiEnvelope<T>).data as T;
    }
    return payload as T;
}

function statusTag(value?: string) {
    const status = value || 'UNKNOWN';
    const colorMap: Record<string, string> = {
        PENDING: 'orange',
        RUNNING: 'blue',
        SUCCEEDED: 'green',
        FAILED: 'red',
        CANCELED: 'default',
        DISCOVERED: 'cyan',
        SAVED: 'green',
        DUPLICATE: 'default',
        IGNORED: 'default',
    };
    return <Tag color={colorMap[status] || 'default'}>{status}</Tag>;
}

function boolTag(value: boolean, yes = '开启', no = '关闭') {
    return <Tag color={value ? 'green' : 'default'}>{value ? yes : no}</Tag>;
}

function formatDate(value?: string) {
    return value ? new Date(value).toLocaleString() : '-';
}

function formatDelay(ms?: number) {
    if (!ms) return '-';
    if (ms < 60000) return `${Math.round(ms / 1000)} 秒`;
    return `${Math.round(ms / 60000)} 分钟`;
}

export default function ResourceHubAdminPage() {
    const { user, token } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const [configForm] = Form.useForm<ResourceHubConfigFormValues>();
    const [tmdbForm] = Form.useForm<TmdbFormValues>();
    const [discoveryForm] = Form.useForm<DiscoveryFormValues>();

    const [overview, setOverview] = useState<Overview | null>(null);
    const [loading, setLoading] = useState(true);
    const [savingConfig, setSavingConfig] = useState(false);
    const [runningAction, setRunningAction] = useState<string | null>(null);
    const [tasks, setTasks] = useState<ResourceHubTask[]>([]);
    const [tasksLoading, setTasksLoading] = useState(false);
    const [taskPage, setTaskPage] = useState(1);
    const [taskTotal, setTaskTotal] = useState(0);
    const [taskType, setTaskType] = useState<string | undefined>();
    const [taskStatus, setTaskStatus] = useState<string | undefined>();
    const [discoveries, setDiscoveries] = useState<DiscoveryResult[]>([]);
    const [discoveriesLoading, setDiscoveriesLoading] = useState(false);
    const [discoveryPage, setDiscoveryPage] = useState(1);
    const [discoveryTotal, setDiscoveryTotal] = useState(0);
    const [discoveryMovieId, setDiscoveryMovieId] = useState('');
    const [discoveryStatus, setDiscoveryStatus] = useState<string | undefined>();
    const [publishLimit, setPublishLimit] = useState(20);
    const [quarkLimit, setQuarkLimit] = useState(5);

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
            throw new Error(await readApiError(res, '操作失败'));
        }
        return unwrap<T>(await res.json());
    }, [authHeaders]);

    const fetchOverview = useCallback(async () => {
        if (!token) {
            setLoading(false);
            return;
        }
        setLoading(true);
        try {
            const data = await requestJson<Overview>('/api/admin/resource-hub/overview');
            setOverview(data);
            configForm.setFieldsValue({
                ...data.config,
                tmdbAutoSyncSources: data.config.tmdbAutoSyncSources
                    ? data.config.tmdbAutoSyncSources.split(',').map((item) => item.trim()).filter(Boolean)
                    : [],
            });
            setPublishLimit(data.worker.publishLimit || 20);
            setQuarkLimit(data.worker.quarkLimit || 5);
        } catch (error) {
            message.error(error instanceof Error ? error.message : '加载 Resource Hub 概览失败');
        } finally {
            setLoading(false);
        }
    }, [configForm, message, requestJson, token]);

    const fetchTasks = useCallback(async () => {
        if (!token) return;
        setTasksLoading(true);
        try {
            const query = new URLSearchParams({ page: String(taskPage), size: '20' });
            if (taskType) query.set('taskType', taskType);
            if (taskStatus) query.set('status', taskStatus);
            const data = await requestJson<PageResult<ResourceHubTask>>(`/api/admin/resource-hub/tasks?${query.toString()}`);
            setTasks(data.records || []);
            setTaskTotal(data.total || 0);
        } catch (error) {
            message.error(error instanceof Error ? error.message : '加载任务失败');
        } finally {
            setTasksLoading(false);
        }
    }, [message, requestJson, taskPage, taskStatus, taskType, token]);

    const fetchDiscoveries = useCallback(async () => {
        if (!token) return;
        setDiscoveriesLoading(true);
        try {
            const query = new URLSearchParams({ page: String(discoveryPage), size: '20' });
            if (discoveryMovieId.trim()) query.set('movieId', discoveryMovieId.trim());
            if (discoveryStatus) query.set('status', discoveryStatus);
            const data = await requestJson<PageResult<DiscoveryResult>>(`/api/admin/resource-hub/discoveries?${query.toString()}`);
            setDiscoveries(data.records || []);
            setDiscoveryTotal(data.total || 0);
        } catch (error) {
            message.error(error instanceof Error ? error.message : '加载发现结果失败');
        } finally {
            setDiscoveriesLoading(false);
        }
    }, [discoveryMovieId, discoveryPage, discoveryStatus, message, requestJson, token]);

    useEffect(() => {
        if (!user) return;
        if (user.role !== 'ADMIN') {
            message.error('需要管理员权限');
            router.push('/');
            return;
        }
        fetchOverview();
    }, [fetchOverview, message, router, user]);

    useEffect(() => {
        fetchTasks();
    }, [fetchTasks]);

    useEffect(() => {
        fetchDiscoveries();
    }, [fetchDiscoveries]);

    const refreshAll = async () => {
        await Promise.all([fetchOverview(), fetchTasks(), fetchDiscoveries()]);
    };

    const saveConfig = async (values: ResourceHubConfigFormValues) => {
        setSavingConfig(true);
        try {
            const data = await requestJson<ResourceHubConfig>('/api/admin/resource-hub/config', {
                method: 'PUT',
                body: JSON.stringify({
                    ...values,
                    tmdbAutoSyncSources: values.tmdbAutoSyncSources.join(','),
                }),
            });
            configForm.setFieldsValue({
                ...data,
                tmdbAutoSyncSources: data.tmdbAutoSyncSources
                    ? data.tmdbAutoSyncSources.split(',').map((item) => item.trim()).filter(Boolean)
                    : [],
            });
            message.success('Resource Hub 配置已保存');
            await fetchOverview();
        } catch (error) {
            message.error(error instanceof Error ? error.message : '保存配置失败');
        } finally {
            setSavingConfig(false);
        }
    };

    const runAction = async (key: string, path: string) => {
        setRunningAction(key);
        try {
            await requestJson(path, { method: 'POST' });
            message.success('操作已完成');
            await refreshAll();
        } catch (error) {
            message.error(error instanceof Error ? error.message : '操作失败');
        } finally {
            setRunningAction(null);
        }
    };

    const submitTmdbSync = async (values: TmdbFormValues) => {
        setRunningAction('tmdb');
        try {
            await requestJson('/api/admin/resource-hub/tmdb/metadata-sync', {
                method: 'POST',
                body: JSON.stringify(values),
            });
            message.success(values.runNow ? 'TMDB 采集已执行' : 'TMDB 采集任务已创建');
            await refreshAll();
        } catch (error) {
            message.error(error instanceof Error ? error.message : '创建 TMDB 采集任务失败');
        } finally {
            setRunningAction(null);
        }
    };

    const submitDiscovery = async (values: DiscoveryFormValues) => {
        setRunningAction('discover');
        try {
            await requestJson('/api/admin/resource-hub/discover', {
                method: 'POST',
                body: JSON.stringify({
                    ...values,
                    source: 'PANSOU',
                }),
            });
            message.success(values.runNow ? '资源搜索已执行' : '资源搜索任务已创建');
            await refreshAll();
        } catch (error) {
            message.error(error instanceof Error ? error.message : '创建资源搜索任务失败');
        } finally {
            setRunningAction(null);
        }
    };

    const taskColumns: ColumnsType<ResourceHubTask> = [
        { title: 'ID', dataIndex: 'id', width: 90 },
        { title: '类型', dataIndex: 'taskType', width: 150, render: (value: string) => <Tag>{value}</Tag> },
        { title: '影片 ID', dataIndex: 'movieId', width: 140, render: (value?: string) => value || '-' },
        { title: '关键词', dataIndex: 'keyword', ellipsis: true, render: (value?: string) => value || '-' },
        { title: '来源', dataIndex: 'source', width: 110, render: (value?: string) => value || '-' },
        { title: '状态', dataIndex: 'status', width: 120, render: statusTag },
        { title: '尝试', dataIndex: 'attempts', width: 80, render: (value?: number) => value ?? 0 },
        { title: '计划时间', dataIndex: 'scheduledAt', width: 180, render: formatDate },
        { title: '创建时间', dataIndex: 'createdAt', width: 180, render: formatDate },
        { title: '错误', dataIndex: 'lastError', ellipsis: true, render: (value?: string) => value || '-' },
        {
            title: '操作',
            key: 'actions',
            fixed: 'right',
            width: 120,
            render: (_: unknown, record) => record.status === 'PENDING' && record.taskType === 'METADATA_SYNC' ? (
                <Button
                    size="small"
                    icon={<PlayCircleOutlined />}
                    loading={runningAction === `task-${record.id}`}
                    onClick={() => runAction(`task-${record.id}`, `/api/admin/resource-hub/tmdb/metadata-sync/${record.id}/run`)}
                >
                    执行
                </Button>
            ) : '-',
        },
    ];

    const discoveryColumns: ColumnsType<DiscoveryResult> = [
        { title: 'ID', dataIndex: 'id', width: 90 },
        { title: '影片 ID', dataIndex: 'movieId', width: 140 },
        { title: '标题', dataIndex: 'title', ellipsis: true, render: (value?: string) => value || '-' },
        { title: '来源', dataIndex: 'source', width: 110 },
        { title: '网盘', dataIndex: 'provider', width: 100, render: (value?: string) => value || '-' },
        { title: '清晰度', dataIndex: 'quality', width: 100, render: (value?: string) => value || '-' },
        { title: '大小', dataIndex: 'fileSize', width: 100, render: (value?: string) => value || '-' },
        { title: '状态', dataIndex: 'status', width: 120, render: statusTag },
        { title: '入库资源', dataIndex: 'resourceLinkId', width: 100, render: (value?: number) => value || '-' },
        { title: '创建时间', dataIndex: 'createdAt', width: 180, render: formatDate },
        { title: '失败原因', dataIndex: 'failureReason', ellipsis: true, render: (value?: string) => value || '-' },
        {
            title: '操作',
            key: 'actions',
            fixed: 'right',
            width: 120,
            render: (_: unknown, record) => record.status === 'SAVED' ? (
                <Button
                    size="small"
                    icon={<ShareAltOutlined />}
                    loading={runningAction === `publish-${record.id}`}
                    onClick={() => runAction(`publish-${record.id}`, `/api/admin/resource-hub/discoveries/${record.id}/publish`)}
                >
                    发布
                </Button>
            ) : '-',
        },
    ];

    const counts = overview?.taskStatusCounts || {};

    return (
        <div className="min-h-screen bg-[#f5f7fa] dark:bg-black">
            <div className="container mx-auto px-4 lg:px-8 py-8">
                <div className="mb-6 flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
                    <div className="flex items-center gap-3">
                        <CloudSyncOutlined className="text-3xl text-blue-500" />
                        <div>
                            <Title level={2} className="!mb-1">Resource Hub</Title>
                            <Text type="secondary">监控自动采集、资源搜索、夸克转存和发布入库状态</Text>
                        </div>
                    </div>
                    <Button icon={<ReloadOutlined />} onClick={refreshAll} loading={loading}>
                        刷新
                    </Button>
                </div>

                <Row gutter={[16, 16]} className="mb-6">
                    <Col xs={12} md={6}>
                        <Card loading={loading}>
                            <Statistic title="待处理任务" value={counts.PENDING || 0} prefix={<DatabaseOutlined />} />
                        </Card>
                    </Col>
                    <Col xs={12} md={6}>
                        <Card loading={loading}>
                            <Statistic title="已发现资源" value={overview?.discoveredCount || 0} />
                        </Card>
                    </Col>
                    <Col xs={12} md={6}>
                        <Card loading={loading}>
                            <Statistic title="待转存" value={overview?.pendingQuarkTransfers || 0} />
                        </Card>
                    </Col>
                    <Col xs={12} md={6}>
                        <Card loading={loading}>
                            <Statistic title="待发布" value={overview?.savedDiscoveryCount || 0} />
                        </Card>
                    </Col>
                </Row>

                <Tabs
                    items={[
                        {
                            key: 'dashboard',
                            label: '监控与控制',
                            children: (
                                <Row gutter={[16, 16]}>
                                    <Col xs={24} xl={12}>
                                        <Card title="运行状态" loading={loading}>
                                            <Descriptions column={1} size="small">
                                                <Descriptions.Item label="Resource Hub">{boolTag(Boolean(overview?.enabled))}</Descriptions.Item>
                                                <Descriptions.Item label="自动审核">{boolTag(Boolean(overview?.autoApprove), '自动通过', '走审核')}</Descriptions.Item>
                                                <Descriptions.Item label="TMDB Key">{boolTag(Boolean(overview?.tmdbConfigured), '已配置', '未配置')}</Descriptions.Item>
                                                <Descriptions.Item label="Worker">{boolTag(Boolean(overview?.worker.enabled))}</Descriptions.Item>
                                                <Descriptions.Item label="运行中">{boolTag(Boolean(overview?.worker.running), '是', '否')}</Descriptions.Item>
                                                <Descriptions.Item label="调度间隔">{formatDelay(overview?.worker.fixedDelayMs)}</Descriptions.Item>
                                                <Descriptions.Item label="PanSou">{overview?.pansouBaseUrl || '-'}</Descriptions.Item>
                                                <Descriptions.Item label="Quark Auto Save">{overview?.quarkBaseUrl || '-'}</Descriptions.Item>
                                            </Descriptions>
                                            <Space className="mt-4" wrap>
                                                <Button
                                                    type="primary"
                                                    icon={<PlayCircleOutlined />}
                                                    loading={runningAction === 'worker'}
                                                    onClick={() => runAction('worker', '/api/admin/resource-hub/worker/run-once?force=true')}
                                                >
                                                    立即跑一轮
                                                </Button>
                                                <InputNumber min={1} max={100} value={publishLimit} onChange={(value) => setPublishLimit(value || 20)} />
                                                <Button
                                                    icon={<ShareAltOutlined />}
                                                    loading={runningAction === 'publish'}
                                                    onClick={() => runAction('publish', `/api/admin/resource-hub/discoveries/publish?limit=${publishLimit}`)}
                                                >
                                                    发布待入库
                                                </Button>
                                                <InputNumber min={1} max={20} value={quarkLimit} onChange={(value) => setQuarkLimit(value || 5)} />
                                                <Button
                                                    icon={<CloudSyncOutlined />}
                                                    loading={runningAction === 'quark'}
                                                    onClick={() => runAction('quark', `/api/admin/resource-hub/quark/transfers/submit?limit=${quarkLimit}`)}
                                                >
                                                    提交转存
                                                </Button>
                                            </Space>
                                        </Card>
                                    </Col>
                                    <Col xs={24} xl={12}>
                                        <Card title="自动采集设置" loading={loading}>
                                            <Form
                                                form={configForm}
                                                layout="vertical"
                                                onFinish={saveConfig}
                                            >
                                                <Row gutter={12}>
                                                    <Col xs={24} md={12}>
                                                        <Form.Item name="enabled" label="Resource Hub 总开关" valuePropName="checked">
                                                            <Switch />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={24} md={12}>
                                                        <Form.Item name="workerEnabled" label="定时 Worker" valuePropName="checked">
                                                            <Switch />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={24} md={12}>
                                                        <Form.Item name="tmdbAutoSyncEnabled" label="TMDB 自动采集" valuePropName="checked">
                                                            <Switch />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={24} md={12}>
                                                        <Form.Item name="tmdbAutoDiscoveryEnabled" label="采集后自动搜索资源" valuePropName="checked">
                                                            <Switch />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={24}>
                                                        <Form.Item name="tmdbAutoSyncSources" label="TMDB 来源">
                                                            <Select mode="multiple" options={TMDB_SOURCE_OPTIONS} />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="tmdbAutoSyncIntervalHours" label="采集间隔（小时）">
                                                            <InputNumber min={1} max={720} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="tmdbAutoSyncPage" label="采集页码">
                                                            <InputNumber min={1} max={20} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="tmdbAutoSyncMaxItems" label="每次条数">
                                                            <InputNumber min={1} max={100} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="tmdbDiscoveryMaxResults" label="搜索结果上限">
                                                            <InputNumber min={1} max={50} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="workerTaskLimit" label="任务处理上限">
                                                            <InputNumber min={1} max={20} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="workerPublishLimit" label="发布上限">
                                                            <InputNumber min={1} max={100} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                </Row>
                                                <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={savingConfig}>
                                                    保存设置
                                                </Button>
                                            </Form>
                                        </Card>
                                    </Col>
                                </Row>
                            ),
                        },
                        {
                            key: 'manual',
                            label: '手动采集',
                            children: (
                                <Row gutter={[16, 16]}>
                                    <Col xs={24} lg={12}>
                                        <Card title="TMDB 热门采集">
                                            <Form
                                                form={tmdbForm}
                                                layout="vertical"
                                                initialValues={{ source: 'TRENDING_MOVIE_DAY', page: 1, maxItems: 20, runNow: true }}
                                                onFinish={submitTmdbSync}
                                            >
                                                <Form.Item name="source" label="来源" rules={[{ required: true }]}>
                                                    <Select options={TMDB_SOURCE_OPTIONS} />
                                                </Form.Item>
                                                <Row gutter={12}>
                                                    <Col span={12}>
                                                        <Form.Item name="page" label="页码" rules={[{ required: true }]}>
                                                            <InputNumber min={1} max={20} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col span={12}>
                                                        <Form.Item name="maxItems" label="条数" rules={[{ required: true }]}>
                                                            <InputNumber min={1} max={100} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                </Row>
                                                <Form.Item name="runNow" label="立即执行" valuePropName="checked">
                                                    <Switch />
                                                </Form.Item>
                                                <Button type="primary" htmlType="submit" icon={<CloudSyncOutlined />} loading={runningAction === 'tmdb'}>
                                                    创建采集
                                                </Button>
                                            </Form>
                                        </Card>
                                    </Col>
                                    <Col xs={24} lg={12}>
                                        <Card title="单片资源搜索">
                                            <Form
                                                form={discoveryForm}
                                                layout="vertical"
                                                initialValues={{ maxResults: 10, refresh: true, runNow: true }}
                                                onFinish={submitDiscovery}
                                            >
                                                <Form.Item name="movieId" label="影片 ID" rules={[{ required: true }]}>
                                                    <Input placeholder="例如 tmdb-movie-12345 或现有 movie_metadata.id" />
                                                </Form.Item>
                                                <Form.Item name="keyword" label="搜索关键词">
                                                    <Input placeholder="为空时后端按影片信息生成关键词" />
                                                </Form.Item>
                                                <Form.Item name="maxResults" label="结果上限" rules={[{ required: true }]}>
                                                    <InputNumber min={1} max={50} className="w-full" />
                                                </Form.Item>
                                                <Space size="large">
                                                    <Form.Item name="refresh" label="忽略冷却" valuePropName="checked">
                                                        <Switch />
                                                    </Form.Item>
                                                    <Form.Item name="runNow" label="立即执行" valuePropName="checked">
                                                        <Switch />
                                                    </Form.Item>
                                                </Space>
                                                <div>
                                                    <Button type="primary" htmlType="submit" icon={<SearchOutlined />} loading={runningAction === 'discover'}>
                                                        创建搜索
                                                    </Button>
                                                </div>
                                            </Form>
                                        </Card>
                                    </Col>
                                </Row>
                            ),
                        },
                        {
                            key: 'tasks',
                            label: '任务队列',
                            children: (
                                <Card>
                                    <Space className="mb-4" wrap>
                                        <Select
                                            allowClear
                                            placeholder="任务类型"
                                            options={TASK_TYPE_OPTIONS}
                                            value={taskType}
                                            style={{ width: 180 }}
                                            onChange={(value) => {
                                                setTaskType(value);
                                                setTaskPage(1);
                                            }}
                                        />
                                        <Select
                                            allowClear
                                            placeholder="状态"
                                            options={TASK_STATUS_OPTIONS.map((value) => ({ value, label: value }))}
                                            value={taskStatus}
                                            style={{ width: 160 }}
                                            onChange={(value) => {
                                                setTaskStatus(value);
                                                setTaskPage(1);
                                            }}
                                        />
                                        <Button icon={<ReloadOutlined />} onClick={fetchTasks}>刷新任务</Button>
                                    </Space>
                                    <Table
                                        columns={taskColumns}
                                        dataSource={tasks}
                                        rowKey="id"
                                        loading={tasksLoading}
                                        scroll={{ x: 1300 }}
                                        locale={{ emptyText: <Empty description="暂无任务" /> }}
                                        pagination={{
                                            current: taskPage,
                                            pageSize: 20,
                                            total: taskTotal,
                                            onChange: setTaskPage,
                                            showTotal: (total) => `共 ${total} 条任务`,
                                        }}
                                    />
                                </Card>
                            ),
                        },
                        {
                            key: 'discoveries',
                            label: '发现结果',
                            children: (
                                <Card>
                                    <Space className="mb-4" wrap>
                                        <Input.Search
                                            placeholder="影片 ID"
                                            allowClear
                                            style={{ width: 240 }}
                                            onSearch={(value) => {
                                                setDiscoveryMovieId(value);
                                                setDiscoveryPage(1);
                                            }}
                                        />
                                        <Select
                                            allowClear
                                            placeholder="状态"
                                            options={DISCOVERY_STATUS_OPTIONS.map((value) => ({ value, label: value }))}
                                            value={discoveryStatus}
                                            style={{ width: 160 }}
                                            onChange={(value) => {
                                                setDiscoveryStatus(value);
                                                setDiscoveryPage(1);
                                            }}
                                        />
                                        <Button icon={<ReloadOutlined />} onClick={fetchDiscoveries}>刷新结果</Button>
                                    </Space>
                                    <Table
                                        columns={discoveryColumns}
                                        dataSource={discoveries}
                                        rowKey="id"
                                        loading={discoveriesLoading}
                                        scroll={{ x: 1300 }}
                                        locale={{ emptyText: <Empty description="暂无发现结果" /> }}
                                        pagination={{
                                            current: discoveryPage,
                                            pageSize: 20,
                                            total: discoveryTotal,
                                            onChange: setDiscoveryPage,
                                            showTotal: (total) => `共 ${total} 条结果`,
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
