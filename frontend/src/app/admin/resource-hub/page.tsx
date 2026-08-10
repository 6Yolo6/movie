'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
    Alert,
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
    Tooltip,
    Typography,
} from 'antd';
import {
    ApiOutlined,
    CloudSyncOutlined,
    DatabaseOutlined,
    PlayCircleOutlined,
    ReloadOutlined,
    SaveOutlined,
    SearchOutlined,
    ShareAltOutlined,
    QuestionCircleOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { api, readApiError } from '@/lib/api';
import { useAuthStore } from '@/store/authStore';

const { Title, Text } = Typography;
const MISSING_RESOURCE_BATCH_LIMIT = 20;

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
    gyingDiscoveryEnabled: boolean;
    gyingAutoSyncEnabled: boolean;
    gyingAutoSyncSources: string;
    gyingAutoSyncPage: number;
    gyingAutoSyncMaxItems: number;
    gyingAutoSyncIntervalHours: number;
    workerEnabled: boolean;
    workerFixedDelayMs: number;
    workerTaskLimit: number;
    workerQuarkLimit: number;
    workerPublishLimit: number;
}

type ResourceHubConfigFormValues = Omit<ResourceHubConfig, 'tmdbAutoSyncSources' | 'gyingAutoSyncSources'> & {
    tmdbAutoSyncSources: string[];
    gyingAutoSyncSources: string[];
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
    pansouApiBaseUrl: string;
    pansouApiConfigured: boolean;
    quarkBaseUrl: string;
    config: ResourceHubConfig;
    worker: WorkerStatus;
    taskStatusCounts: Record<string, number>;
    discoveredCount: number;
    savedDiscoveryCount: number;
    pendingQuarkTransfers: number;
    collectionStats: {
        tmdbMovies: number;
        tmdbSyncedLast24Hours: number;
        tmdbCreatedLast24Hours: number;
        tmdbTasksLast24Hours: number;
        tmdbSucceededLast24Hours: number;
        tmdbFailedLast24Hours: number;
        discoveriesLast24Hours: number;
        savedDiscoveriesLast24Hours: number;
        resourcesSavedLast24Hours: number;
        latestTmdbTaskAt?: string;
        latestTmdbTaskSource?: string;
        latestTmdbTaskStatus?: string;
        nextTmdbRunAt?: string;
    };
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
    attempts?: number;
    lastError?: string;
    scheduledAt?: string;
    createdAt?: string;
}

interface DiscoveryResult {
    id: number;
    movieId: string;
    source: string;
    title?: string;
    provider?: string;
    quality?: string;
    fileSize?: string;
    status: string;
    failureReason?: string;
    originalUrl?: string;
    shareUrl?: string;
    resourceLinkId?: number;
    createdAt?: string;
}

interface TmdbSyncResult {
    status: string;
    requested: number;
    processed: number;
    inserted: number;
    updated: number;
    failed: number;
    errors?: string[];
}

interface MissingResourceMovie {
    id: string;
    title: string;
    category?: string;
    year?: number;
    resourceStatus?: string;
    updatedAt?: string;
}

interface InvalidResourceCheck {
    id: number;
    movieId?: string;
    movieTitle?: string;
    status?: string;
    linkStatus?: string;
    transferStatus?: string;
    folderState?: string;
    folderItemCount?: number;
    savedPath?: string;
    lastCheckError?: string;
    nextAction?: string;
    validatedAt?: string;
}

interface RepairInvalidJob {
    jobId?: string;
    status?: string;
    checked?: number;
    restored?: number;
    reshared?: number;
    rediscovered?: number;
    invalid?: number;
    skipped?: number;
}

interface DiscoveryPipelineJob {
    jobId?: string;
    status?: string;
    taskId?: number;
    result?: Record<string, unknown>;
    errors?: string[];
}

interface TmdbFormValues {
    source: string;
    page: number;
    maxItems: number;
    runNow: boolean;
}

interface DiscoveryFormValues {
    movieTitle: string;
    keyword?: string;
    maxResults: number;
    refresh: boolean;
    runNow: boolean;
}

const TMDB_SOURCE_KEYS = [
    'TRENDING_MOVIE_DAY',
    'TRENDING_TV_DAY',
    'POPULAR_MOVIE',
    'POPULAR_TV',
    'TOP_RATED_MOVIE',
    'TOP_RATED_TV',
    'UPCOMING_MOVIE',
];

const GYING_SOURCE_KEYS = ['HITS_MOVIE', 'HITS_TV', 'HITS_ANIME'];

const TASK_TYPE_OPTIONS = ['METADATA_SYNC', 'RESOURCE_DISCOVERY'];
const TASK_STATUS_OPTIONS = ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED'];
const DISCOVERY_STATUS_OPTIONS = ['DISCOVERED', 'SAVED', 'DUPLICATE', 'IGNORED', 'FAILED'];

function unwrap<T>(payload: ApiEnvelope<T> | T): T {
    if (payload && typeof payload === 'object' && 'data' in payload) {
        return (payload as ApiEnvelope<T>).data as T;
    }
    return payload as T;
}

function formatDate(value?: string) {
    return value ? new Date(value).toLocaleString() : '-';
}

export default function ResourceHubAdminPage() {
    const { user, token } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const { t } = useTranslation();
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
    const [discoveryKeyword, setDiscoveryKeyword] = useState('');
    const [discoveryStatus, setDiscoveryStatus] = useState<string | undefined>();
    const [discoverySortOrder, setDiscoverySortOrder] = useState<'asc' | 'desc'>('desc');
    const [selectedDiscoveryIds, setSelectedDiscoveryIds] = useState<React.Key[]>([]);
    const [missingResources, setMissingResources] = useState<MissingResourceMovie[]>([]);
    const [missingLoading, setMissingLoading] = useState(false);
    const [missingPage, setMissingPage] = useState(1);
    const [missingTotal, setMissingTotal] = useState(0);
    const [missingKeyword, setMissingKeyword] = useState('');
    const [missingSortOrder, setMissingSortOrder] = useState<'asc' | 'desc'>('desc');
    const [selectedMissingIds, setSelectedMissingIds] = useState<React.Key[]>([]);
    const [publishLimit, setPublishLimit] = useState(20);
    const [quarkLimit, setQuarkLimit] = useState(5);
    const [invalidChecks, setInvalidChecks] = useState<InvalidResourceCheck[]>([]);
    const [invalidChecksLoading, setInvalidChecksLoading] = useState(false);

    const authHeaders = useMemo(() => ({
        Authorization: `Bearer ${token}`,
    }), [token]);

    const tmdbSourceOptions = useMemo(() => TMDB_SOURCE_KEYS.map((value) => ({
        value,
        label: t(`resourceHubSource.${value}`),
    })), [t]);

    const gyingSourceOptions = useMemo(() => GYING_SOURCE_KEYS.map((value) => ({
        value,
        label: t(`resourceHubGyingSource.${value}`),
    })), [t]);

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

    const normalizeConfigForm = (config: ResourceHubConfig): ResourceHubConfigFormValues => ({
        ...config,
        tmdbAutoSyncSources: config.tmdbAutoSyncSources
            ? config.tmdbAutoSyncSources.split(',').map((item) => item.trim()).filter(Boolean)
            : [],
        gyingAutoSyncSources: config.gyingAutoSyncSources
            ? config.gyingAutoSyncSources.split(',').map((item) => item.trim()).filter(Boolean)
            : [],
    });

    const fetchOverview = useCallback(async () => {
        if (!token) {
            setLoading(false);
            return;
        }
        setLoading(true);
        try {
            const data = await requestJson<Overview>('/api/admin/resource-hub/overview');
            setOverview(data);
            configForm.setFieldsValue(normalizeConfigForm(data.config));
            setPublishLimit(data.worker.publishLimit || 20);
            setQuarkLimit(data.worker.quarkLimit || 5);
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('resourceHubLoadFailed'));
        } finally {
            setLoading(false);
        }
    }, [configForm, message, requestJson, t, token]);

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
            message.error(error instanceof Error ? error.message : t('resourceHubTasksLoadFailed'));
        } finally {
            setTasksLoading(false);
        }
    }, [message, requestJson, t, taskPage, taskStatus, taskType, token]);

    const fetchDiscoveries = useCallback(async () => {
        if (!token) return;
        setDiscoveriesLoading(true);
        try {
            const query = new URLSearchParams({ page: String(discoveryPage), size: '20' });
            if (discoveryMovieId.trim()) query.set('movieId', discoveryMovieId.trim());
            if (discoveryKeyword.trim()) query.set('keyword', discoveryKeyword.trim());
            if (discoveryStatus) query.set('status', discoveryStatus);
            query.set('sortOrder', discoverySortOrder);
            const data = await requestJson<PageResult<DiscoveryResult>>(`/api/admin/resource-hub/discoveries?${query.toString()}`);
            setDiscoveries(data.records || []);
            setDiscoveryTotal(data.total || 0);
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('resourceHubDiscoveriesLoadFailed'));
        } finally {
            setDiscoveriesLoading(false);
        }
    }, [discoveryKeyword, discoveryMovieId, discoveryPage, discoverySortOrder, discoveryStatus, message, requestJson, t, token]);

    const fetchMissingResources = useCallback(async () => {
        if (!token) return;
        setMissingLoading(true);
        try {
            const query = new URLSearchParams({
                page: String(missingPage),
                size: '20',
                sortOrder: missingSortOrder,
            });
            if (missingKeyword.trim()) query.set('keyword', missingKeyword.trim());
            const data = await requestJson<PageResult<MissingResourceMovie>>(
                `/api/admin/resource-hub/missing-resources?${query.toString()}`,
            );
            setMissingResources(data.records || []);
            setMissingTotal(data.total || 0);
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setMissingLoading(false);
        }
    }, [message, missingKeyword, missingPage, missingSortOrder, requestJson, t, token]);

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
        fetchTasks();
    }, [fetchTasks]);

    useEffect(() => {
        fetchDiscoveries();
    }, [fetchDiscoveries]);

    useEffect(() => {
        fetchMissingResources();
    }, [fetchMissingResources]);

    const refreshAll = async () => {
        await Promise.all([fetchOverview(), fetchTasks(), fetchDiscoveries(), fetchMissingResources()]);
    };

    const saveConfig = async (values: ResourceHubConfigFormValues) => {
        setSavingConfig(true);
        try {
            const data = await requestJson<ResourceHubConfig>('/api/admin/resource-hub/config', {
                method: 'PUT',
                body: JSON.stringify({
                    ...values,
                    tmdbAutoSyncSources: values.tmdbAutoSyncSources.join(','),
                    gyingAutoSyncSources: values.gyingAutoSyncSources.join(','),
                }),
            });
            configForm.setFieldsValue(normalizeConfigForm(data));
            message.success(t('resourceHubConfigSaved'));
            await fetchOverview();
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('resourceHubConfigSaveFailed'));
        } finally {
            setSavingConfig(false);
        }
    };

    const runAction = async (key: string, path: string) => {
        setRunningAction(key);
        try {
            const data = await requestJson<Record<string, unknown>>(path, { method: 'POST' });
            message.success(formatActionResult(key, data));
            await refreshAll();
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setRunningAction(null);
        }
    };

    const runResultAction = async (key: string, path: string, successText: (data: Record<string, unknown>) => string) => {
        setRunningAction(key);
        try {
            const data = await requestJson<Record<string, unknown>>(path, { method: 'POST' });
            message.success(successText(data));
            await refreshAll();
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setRunningAction(null);
        }
    };

    const countValue = (data: Record<string, unknown> | undefined, key: string) => {
        const value = data?.[key];
        return typeof value === 'number' ? value : 0;
    };

    const nestedRecord = (data: Record<string, unknown> | undefined, key: string) => {
        const value = data?.[key];
        return value && typeof value === 'object' ? value as Record<string, unknown> : {};
    };

    const formatActionResult = (key: string, data: Record<string, unknown>) => {
        if (key === 'publish' || key.startsWith('publish-')) {
            return t('resourceHubPublishRunDone', {
                published: countValue(data, 'published'),
                updated: countValue(data, 'updated'),
                duplicate: countValue(data, 'duplicate'),
                skipped: countValue(data, 'skipped'),
                failed: countValue(data, 'failed'),
            });
        }
        if (key === 'quark') {
            return t('resourceHubTransferRunDone', {
                submitted: countValue(data, 'submitted'),
                skipped: countValue(data, 'skipped'),
                failed: countValue(data, 'failed'),
            });
        }
        if (key === 'worker') {
            const transfers = nestedRecord(data, 'quarkTransfers');
            const published = nestedRecord(data, 'publishedResources');
            return t('resourceHubWorkerRunDone', {
                tasks: countValue(data, 'tasksProcessed'),
                submitted: countValue(transfers, 'submitted'),
                published: countValue(published, 'published'),
                updated: countValue(published, 'updated'),
                failed: countValue(data, 'tasksFailed') + countValue(transfers, 'failed') + countValue(published, 'failed'),
            });
        }
        return t('operationCompleted');
    };

    const repairInvalidResources = async () => {
        setRunningAction('repair-invalid');
        try {
            let job = await requestJson<RepairInvalidJob>('/api/resources/admin/repair-invalid?limit=50', { method: 'POST' });
            if (!job.jobId) {
                throw new Error(t('operationFailed'));
            }
            message.info(t('resourceHubRepairInvalidStarted'));
            for (let attempt = 0; attempt < 240 && job.status === 'RUNNING'; attempt++) {
                await new Promise((resolve) => setTimeout(resolve, 2000));
                job = await requestJson<RepairInvalidJob>(`/api/resources/admin/repair-invalid/jobs/${job.jobId}`);
            }
            if (job.status === 'FAILED') {
                throw new Error(t('resourceHubRepairInvalidFailed'));
            }
            message.success(t('resourceHubRepairInvalidDone', {
                checked: job.checked || 0,
                reshared: job.reshared || 0,
                restored: job.restored || 0,
                rediscovered: job.rediscovered || 0,
                invalid: job.invalid || 0,
            }));
            await Promise.all([refreshAll(), fetchInvalidChecks()]);
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setRunningAction(null);
        }
    };

    const fetchInvalidChecks = async () => {
        setInvalidChecksLoading(true);
        try {
            const data = await requestJson<{
                records: InvalidResourceCheck[];
                checked?: number;
                normal?: number;
                suspected?: number;
                unclear?: number;
            }>('/api/resources/admin/invalid-checks/scan?limit=100', { method: 'POST' });
            setInvalidChecks(data.records || []);
            message.success(t('resourceHubInvalidChecksLoaded', {
                count: data.records?.length || 0,
                checked: data.checked || 0,
                normal: data.normal || 0,
                suspected: data.suspected || 0,
                unclear: data.unclear || 0,
            }));
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setInvalidChecksLoading(false);
        }
    };

    const submitTmdbSync = async (values: TmdbFormValues) => {
        setRunningAction('tmdb');
        try {
            const data = await requestJson<TmdbSyncResult | ResourceHubTask>('/api/admin/resource-hub/tmdb/metadata-sync', {
                method: 'POST',
                body: JSON.stringify(values),
            });
            if (values.runNow && 'processed' in data) {
                if (data.status === 'FAILED' || data.processed === 0 && data.failed > 0) {
                    throw new Error(data.errors?.join('; ') || t('resourceHubTmdbTaskFailed'));
                }
                message.success(t('resourceHubTmdbRunDoneWithCounts', {
                    processed: data.processed,
                    inserted: data.inserted,
                    updated: data.updated,
                    failed: data.failed,
                }));
            } else {
                message.success(t('resourceHubTmdbTaskCreated'));
            }
            await refreshAll();
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('resourceHubTmdbTaskFailed'));
        } finally {
            setRunningAction(null);
        }
    };

    const runDiscoveryBatch = async (key: string, path: string) => {
        if (selectedDiscoveryIds.length === 0) {
            message.warning(t('resourceHubSelectDiscoveries'));
            return;
        }
        setRunningAction(key);
        try {
            const data = await requestJson<{ selected: number; succeeded: number; failed: number }>(path, {
                method: 'POST',
                body: JSON.stringify(selectedDiscoveryIds),
            });
            if (data.failed > 0) {
                message.warning(t('resourceHubBatchPartialDone', data));
            } else {
                message.success(t('resourceHubBatchDone', data));
            }
            setSelectedDiscoveryIds([]);
            await refreshAll();
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setRunningAction(null);
        }
    };

    const postDiscoveryToQq = async (discoveryResultId: number) => {
        setRunningAction(`qq-post-${discoveryResultId}`);
        try {
            const data = await requestJson<{ status?: string; immediate?: boolean; error?: string }>(
                `/api/admin/resource-hub/discoveries/${discoveryResultId}/qq-channel-post?runNow=true`,
                { method: 'POST' },
            );
            if (data.immediate) {
                message.success(t('resourceHubQqPostedNow'));
            } else {
                message.warning(data.error || t('resourceHubQqQueuedFallback'));
            }
            await refreshAll();
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setRunningAction(null);
        }
    };

    const waitForDiscoveryJob = async (started: DiscoveryPipelineJob, maxAttempts = 1800) => {
        if (!started.jobId) throw new Error(t('operationFailed'));
        let job = started;
        for (let attempt = 0; attempt < maxAttempts && job.status === 'RUNNING'; attempt += 1) {
            await new Promise((resolve) => setTimeout(resolve, 2000));
            job = await requestJson<DiscoveryPipelineJob>(`/api/admin/resource-hub/discover/jobs/${started.jobId}`);
        }
        return job;
    };

    const resolveMissingResource = async (movieId: string, source: 'GYING' | 'PANSOU') => {
        const key = `missing-${source}-${movieId}`;
        setRunningAction(key);
        try {
            const started = await requestJson<DiscoveryPipelineJob>(
                `/api/admin/resource-hub/missing-resources/${encodeURIComponent(movieId)}/resolve?source=${source}`,
                { method: 'POST' },
            );
            const job = await waitForDiscoveryJob(started, 240);
            if (job.status !== 'SUCCEEDED') {
                throw new Error(job.errors?.[0] || t('resourceHubMissingResolveFailed'));
            }
            const resolvedSource = typeof job.result?.fallbackSource === 'string'
                ? `${source} -> ${job.result.fallbackSource}`
                : source;
            message.success(t('resourceHubMissingResolved', { source: resolvedSource }));
            await refreshAll();
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('resourceHubMissingResolveFailed'));
        } finally {
            setRunningAction(null);
        }
    };

    const resolveMissingResourcesBatch = async (source: 'GYING' | 'PANSOU') => {
        if (selectedMissingIds.length === 0) {
            message.warning(t('resourceHubSelectMissingResources'));
            return;
        }
        if (selectedMissingIds.length > MISSING_RESOURCE_BATCH_LIMIT) {
            message.warning(t('resourceHubMissingBatchLimit', {
                limit: MISSING_RESOURCE_BATCH_LIMIT,
            }));
            return;
        }
        const key = `missing-batch-${source}`;
        setRunningAction(key);
        try {
            const started = await requestJson<DiscoveryPipelineJob>(
                `/api/admin/resource-hub/missing-resources/batch/resolve?source=${source}`,
                {
                    method: 'POST',
                    body: JSON.stringify(selectedMissingIds),
                },
            );
            const job = await waitForDiscoveryJob(started);
            if (job.status !== 'SUCCEEDED') {
                throw new Error(job.errors?.[0] || t('resourceHubMissingResolveFailed'));
            }
            const result = job.result as { selected?: number; succeeded?: number; failed?: number };
            const counts = {
                selected: result.selected || selectedMissingIds.length,
                succeeded: result.succeeded || 0,
                failed: result.failed || 0,
            };
            if (counts.failed > 0) {
                message.warning(t('resourceHubMissingBatchPartialDone', counts));
            } else {
                message.success(t('resourceHubMissingBatchDone', counts));
            }
            setSelectedMissingIds([]);
            await refreshAll();
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('resourceHubMissingResolveFailed'));
        } finally {
            setRunningAction(null);
        }
    };

    const reconcileDiscoveries = async () => {
        setRunningAction('reconcile-discoveries');
        try {
            type ReconcileResult = {
                titleRestored: number;
                taskConflictRestored: number;
                failedSynced: number;
                staleReasonCleared: number;
            };
            const preview = await requestJson<ReconcileResult>(
                '/api/admin/resource-hub/discoveries/reconcile?dryRun=true&limit=2000',
                { method: 'POST' },
            );
            const changes = preview.titleRestored
                + preview.taskConflictRestored
                + preview.failedSynced
                + preview.staleReasonCleared;
            const result = changes === 0 ? preview : await requestJson<ReconcileResult>(
                '/api/admin/resource-hub/discoveries/reconcile?dryRun=false&limit=2000',
                { method: 'POST' },
            );
            message.success(t('resourceHubReconcileDone', result));
            await refreshAll();
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setRunningAction(null);
        }
    };

    const submitDiscovery = async (values: DiscoveryFormValues) => {
        setRunningAction('discover');
        try {
            const data = await requestJson<DiscoveryPipelineJob>('/api/admin/resource-hub/discover', {
                method: 'POST',
                body: JSON.stringify({ ...values, source: 'PANSOU' }),
            });
            if (values.runNow) {
                if (!data.jobId) {
                    throw new Error(t('operationFailed'));
                }
                message.info(t('resourceHubDiscoveryStarted'));
                let job = data;
                for (let attempt = 0; attempt < 240 && job.status === 'RUNNING'; attempt++) {
                    await new Promise((resolve) => setTimeout(resolve, 2000));
                    job = await requestJson<DiscoveryPipelineJob>(`/api/admin/resource-hub/discover/jobs/${data.jobId}`);
                }
                if (job.status === 'FAILED') {
                    throw new Error(t('resourceHubDiscoveryFailed'));
                }
                const discovery = (job.result?.discovery || {}) as Record<string, number>;
                const published = (job.result?.published || {}) as Record<string, number>;
                message.success(t('resourceHubDiscoveryRunDoneWithCounts', {
                    discovered: discovery.discovered || 0,
                    duplicate: discovery.duplicate || 0,
                    transfers: discovery.transferTasksCreated || 0,
                    published: published.published || 0,
                    skipped: published.skipped || 0,
                }));
            } else {
                message.success(t('resourceHubDiscoveryTaskCreated'));
            }
            await refreshAll();
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('resourceHubDiscoveryTaskFailed'));
        } finally {
            setRunningAction(null);
        }
    };

    const statusTag = (value?: string) => {
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
        return <Tag color={colorMap[status] || 'default'}>{t(`resourceHubStatus.${status}`, { defaultValue: status })}</Tag>;
    };

    const boolTag = (value: boolean, yes = t('enabled'), no = t('disabled')) => (
        <Tag color={value ? 'green' : 'default'}>{value ? yes : no}</Tag>
    );

    const formatDelay = (ms?: number) => {
        if (!ms) return '-';
        if (ms < 60000) return t('resourceHubSeconds', { count: Math.round(ms / 1000) });
        return t('resourceHubMinutes', { count: Math.round(ms / 60000) });
    };

    const taskColumns: ColumnsType<ResourceHubTask> = [
        { title: 'ID', dataIndex: 'id', width: 90 },
        { title: t('type'), dataIndex: 'taskType', width: 160, render: (value: string) => <Tag>{t(`resourceHubTaskType.${value}`, { defaultValue: value })}</Tag> },
        { title: t('movieId'), dataIndex: 'movieId', width: 190, render: (value?: string) => value || '-' },
        { title: t('resourceHubKeyword'), dataIndex: 'keyword', width: 240, ellipsis: true, render: (value?: string) => value || '-' },
        { title: t('resourceHubSourceLabel'), dataIndex: 'source', width: 110, render: (value?: string) => value || '-' },
        { title: t('status'), dataIndex: 'status', width: 120, render: statusTag },
        { title: t('resourceHubAttempts'), dataIndex: 'attempts', width: 90, render: (value?: number) => value ?? 0 },
        { title: t('resourceHubScheduledAt'), dataIndex: 'scheduledAt', width: 180, render: formatDate },
        { title: t('createdAt'), dataIndex: 'createdAt', width: 180, render: formatDate },
        { title: t('resourceHubError'), dataIndex: 'lastError', width: 300, ellipsis: true, render: (value?: string) => value || '-' },
        {
            title: t('actions'),
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
                    {t('resourceHubRun')}
                </Button>
            ) : '-',
        },
    ];

    const discoveryColumns: ColumnsType<DiscoveryResult> = [
        { title: 'ID', dataIndex: 'id', width: 90 },
        { title: t('movieId'), dataIndex: 'movieId', width: 190 },
        { title: t('movieTitle'), dataIndex: 'title', width: 280, ellipsis: true, render: (value?: string) => value || '-' },
        { title: t('resourceHubSourceLabel'), dataIndex: 'source', width: 110 },
        { title: t('provider'), dataIndex: 'provider', width: 100, render: (value?: string) => value || '-' },
        { title: t('quality'), dataIndex: 'quality', width: 100, render: (value?: string) => value || '-' },
        { title: t('fileSize'), dataIndex: 'fileSize', width: 100, render: (value?: string) => value || '-' },
        { title: t('status'), dataIndex: 'status', width: 120, render: statusTag },
        {
            title: t('resourceHubResourceLink'),
            key: 'resourceUrl',
            width: 260,
            ellipsis: true,
            render: (_: unknown, record) => {
                const url = record.shareUrl || record.originalUrl;
                return url ? (
                    <a href={url} target="_blank" rel="noreferrer" title={url}>
                        {record.shareUrl ? t('resourceHubOwnShareLink') : t('resourceHubSourceLink')}
                    </a>
                ) : '-';
            },
        },
        { title: t('resourceHubPublishedResource'), dataIndex: 'resourceLinkId', width: 130, render: (value?: number) => value || '-' },
        { title: t('createdAt'), dataIndex: 'createdAt', width: 180, render: formatDate },
        { title: t('resourceHubFailureReason'), dataIndex: 'failureReason', width: 320, ellipsis: true, render: (value?: string) => value || '-' },
        {
            title: t('actions'),
            key: 'actions',
            fixed: 'right',
            width: 260,
            render: (_: unknown, record) => {
                const canPublish = record.status === 'DISCOVERED'
                    || record.status === 'FAILED';
                const shouldRetryTransfer = !record.shareUrl
                    || record.status === 'FAILED';
                return (
                    <Space size={6}>
                    {canPublish && (
                        <Button
                            size="small"
                            icon={record.shareUrl ? <ShareAltOutlined /> : <CloudSyncOutlined />}
                            loading={runningAction === `publish-${record.id}`}
                            onClick={() => runAction(
                                `publish-${record.id}`,
                                shouldRetryTransfer
                                    ? `/api/admin/resource-hub/discoveries/${record.id}/retry-share-publish`
                                    : `/api/admin/resource-hub/discoveries/${record.id}/publish`,
                            )}
                        >
                            {shouldRetryTransfer ? t('resourceHubRetrySharePublish') : t('resourceHubPublish')}
                        </Button>
                    )}
                    {(record.resourceLinkId || record.shareUrl || record.status === 'SAVED') && (
                        <Button
                            size="small"
                            icon={<ShareAltOutlined />}
                            loading={runningAction === `qq-post-${record.id}`}
                            onClick={() => postDiscoveryToQq(record.id)}
                        >
                            {t('resourceHubQqChannelPost')}
                        </Button>
                    )}
                </Space>
                );
            },
        },
    ];

    const missingResourceColumns: ColumnsType<MissingResourceMovie> = [
        { title: t('movieId'), dataIndex: 'id', width: 200 },
        { title: t('movieTitle'), dataIndex: 'title', width: 300, ellipsis: true },
        { title: t('category'), dataIndex: 'category', width: 100, render: (value?: string) => <Tag>{value || '-'}</Tag> },
        { title: t('year'), dataIndex: 'year', width: 90, render: (value?: number) => value || '-' },
        { title: t('resourceStatus'), dataIndex: 'resourceStatus', width: 140, render: (value?: string) => <Tag>{value || '-'}</Tag> },
        { title: t('updatedAt'), dataIndex: 'updatedAt', width: 180, render: formatDate },
        {
            title: t('actions'),
            key: 'actions',
            fixed: 'right',
            width: 290,
            render: (_: unknown, record) => (
                <Space size={6}>
                    <Button
                        size="small"
                        icon={<CloudSyncOutlined />}
                        loading={runningAction === `missing-GYING-${record.id}`}
                        disabled={Boolean(runningAction && runningAction !== `missing-GYING-${record.id}`)}
                        onClick={() => resolveMissingResource(record.id, 'GYING')}
                    >
                        {t('resourceHubResolveFromGying')}
                    </Button>
                    <Button
                        type="primary"
                        size="small"
                        icon={<SearchOutlined />}
                        loading={runningAction === `missing-PANSOU-${record.id}`}
                        disabled={Boolean(runningAction && runningAction !== `missing-PANSOU-${record.id}`)}
                        onClick={() => resolveMissingResource(record.id, 'PANSOU')}
                    >
                        {t('resourceHubResolveFromPanSou')}
                    </Button>
                </Space>
            ),
        },
    ];

    const invalidCheckColumns: ColumnsType<InvalidResourceCheck> = [
        { title: 'ID', dataIndex: 'id', width: 90 },
        { title: t('movieTitle'), dataIndex: 'movieTitle', width: 180, ellipsis: true, render: (value?: string) => value || '-' },
        { title: t('movieId'), dataIndex: 'movieId', width: 140, ellipsis: true },
        { title: t('status'), dataIndex: 'status', width: 110, render: (value?: string) => <Tag>{value || '-'}</Tag> },
        { title: t('resourceHubLinkStatus'), dataIndex: 'linkStatus', width: 140, render: (value?: string) => <Tag>{value || '-'}</Tag> },
        { title: t('resourceHubTransferStatus'), dataIndex: 'transferStatus', width: 140, render: (value?: string) => value || '-' },
        { title: t('resourceHubFolderState'), dataIndex: 'folderState', width: 140, render: (value?: string) => <Tag color={value === 'HAS_CONTENT' ? 'green' : value === 'EMPTY' ? 'red' : 'orange'}>{value || '-'}</Tag> },
        { title: t('resourceHubFolderItems'), dataIndex: 'folderItemCount', width: 100, render: (value?: number) => value ?? '-' },
        { title: t('resourceHubNextAction'), dataIndex: 'nextAction', width: 180, render: (value?: string) => value || '-' },
        { title: t('resourceHubError'), dataIndex: 'lastCheckError', ellipsis: true, render: (value?: string) => value || '-' },
    ];

    const counts = overview?.taskStatusCounts || {};
    const collectionStats = overview?.collectionStats;
    const workerEffective = Boolean(overview?.enabled && overview?.worker.enabled);

    return (
        <div className="min-h-screen bg-[#f5f7fa] dark:bg-black">
            <div className="container mx-auto px-4 lg:px-8 py-8">
                <div className="mb-6 flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
                    <div className="flex items-center gap-3">
                        <CloudSyncOutlined className="text-3xl text-blue-500" />
                        <div>
                            <Title level={2} className="!mb-1">{t('resourceHubTitle')}</Title>
                            <Text type="secondary">{t('resourceHubHint')}</Text>
                        </div>
                    </div>
                    <Button icon={<ReloadOutlined />} onClick={refreshAll} loading={loading}>
                        {t('resourceHubRefresh')}
                    </Button>
                </div>

                {overview && !overview.tmdbConfigured && (
                    <Alert
                        className="mb-4"
                        type="warning"
                        showIcon
                        message={t('resourceHubTmdbKeyMissingTitle')}
                        description={t('resourceHubTmdbKeyMissingDesc')}
                    />
                )}

                <Row gutter={[16, 16]} className="mb-6">
                    <Col xs={12} md={6}>
                        <Card loading={loading}>
                            <Statistic
                                title={(
                                    <Tooltip title={t('resourceHubPendingTasksHelp')}>
                                        <span>{t('resourceHubPendingTasks')} <QuestionCircleOutlined /></span>
                                    </Tooltip>
                                )}
                                value={counts.PENDING || 0}
                                prefix={<DatabaseOutlined />}
                            />
                        </Card>
                    </Col>
                    <Col xs={12} md={6}>
                        <Card loading={loading}>
                            <Statistic title={t('resourceHubDiscovered')} value={overview?.discoveredCount || 0} />
                        </Card>
                    </Col>
                    <Col xs={12} md={6}>
                        <Card loading={loading}>
                            <Statistic title={t('resourceHubPendingTransfers')} value={overview?.pendingQuarkTransfers || 0} />
                        </Card>
                    </Col>
                    <Col xs={12} md={6}>
                        <Card loading={loading}>
                            <Statistic title={t('resourceHubPendingPublish')} value={overview?.savedDiscoveryCount || 0} />
                        </Card>
                    </Col>
                </Row>

                <Tabs
                    items={[
                        {
                            key: 'dashboard',
                            label: t('resourceHubDashboard'),
                            children: (
                                <Row gutter={[16, 16]}>
                                    <Col xs={24}>
                                        <Card title={t('resourceHubCollectionStats')} loading={loading}>
                                            <Row gutter={[16, 16]}>
                                                <Col xs={12} md={8} xl={4}>
                                                    <Statistic title={t('resourceHubTmdbMovies')} value={collectionStats?.tmdbMovies || 0} />
                                                </Col>
                                                <Col xs={12} md={8} xl={4}>
                                                    <Statistic title={t('resourceHubTmdbSynced24h')} value={collectionStats?.tmdbSyncedLast24Hours || 0} />
                                                </Col>
                                                <Col xs={12} md={8} xl={4}>
                                                    <Statistic title={t('resourceHubTmdbCreated24h')} value={collectionStats?.tmdbCreatedLast24Hours || 0} />
                                                </Col>
                                                <Col xs={12} md={8} xl={4}>
                                                    <Statistic
                                                        title={t('resourceHubTmdbTasks24h')}
                                                        value={collectionStats?.tmdbTasksLast24Hours || 0}
                                                        suffix={`/ ${collectionStats?.tmdbFailedLast24Hours || 0} ${t('resourceHubFailedShort')}`}
                                                    />
                                                </Col>
                                                <Col xs={12} md={8} xl={4}>
                                                    <Statistic title={t('resourceHubDiscoveries24h')} value={collectionStats?.discoveriesLast24Hours || 0} />
                                                </Col>
                                                <Col xs={12} md={8} xl={4}>
                                                    <Statistic title={t('resourceHubResourcesSaved24h')} value={collectionStats?.resourcesSavedLast24Hours || 0} />
                                                </Col>
                                            </Row>
                                        </Card>
                                    </Col>
                                    <Col xs={24} xl={10}>
                                        <Card title={t('resourceHubRuntime')} loading={loading}>
                                            <Space direction="vertical" size="middle" className="w-full">
                                                <Descriptions column={1} size="small">
                                                    <Descriptions.Item label={t('resourceHubTitle')}>
                                                        {boolTag(Boolean(overview?.enabled))}
                                                    </Descriptions.Item>
                                                    <Descriptions.Item label={t('resourceHubWorkerEffective')}>
                                                        {boolTag(workerEffective)}
                                                    </Descriptions.Item>
                                                    <Descriptions.Item label={t('resourceHubWorkerRunning')}>
                                                        {boolTag(Boolean(overview?.worker.running), t('yes'), t('no'))}
                                                    </Descriptions.Item>
                                                    <Descriptions.Item label={t('resourceHubScheduleDelay')}>
                                                        {formatDelay(overview?.worker.fixedDelayMs)}
                                                    </Descriptions.Item>
                                                    <Descriptions.Item label={t('resourceHubLatestTmdbTask')}>
                                                        {collectionStats?.latestTmdbTaskAt
                                                            ? `${t(`resourceHubSource.${collectionStats.latestTmdbTaskSource}`, { defaultValue: collectionStats.latestTmdbTaskSource })} / ${t(`resourceHubStatus.${collectionStats.latestTmdbTaskStatus}`, { defaultValue: collectionStats.latestTmdbTaskStatus })} / ${formatDate(collectionStats.latestTmdbTaskAt)}`
                                                            : '-'}
                                                    </Descriptions.Item>
                                                    <Descriptions.Item label={t('resourceHubNextTmdbRun')}>
                                                        {formatDate(collectionStats?.nextTmdbRunAt)}
                                                    </Descriptions.Item>
                                                    <Descriptions.Item label={t('resourceHubAutoApprove')}>
                                                        {boolTag(Boolean(overview?.autoApprove), t('resourceHubAutoApproveOn'), t('resourceHubAutoApproveOff'))}
                                                    </Descriptions.Item>
                                                </Descriptions>
                                                <Alert
                                                    type={workerEffective ? 'success' : 'info'}
                                                    showIcon
                                                    message={workerEffective ? t('resourceHubWorkerReady') : t('resourceHubWorkerPaused')}
                                                    description={workerEffective ? t('resourceHubWorkerReadyDesc') : t('resourceHubWorkerPausedDesc')}
                                                />
                                            </Space>
                                        </Card>
                                    </Col>
                                    <Col xs={24} xl={14}>
                                        <Card title={t('resourceHubConnections')} loading={loading}>
                                            <Row gutter={[12, 12]}>
                                                <Col xs={24} md={8}>
                                                    <div className="rounded border border-gray-200 p-3 dark:border-zinc-700">
                                                        <Space direction="vertical" size={4}>
                                                            <Text strong>TMDB</Text>
                                                            {overview?.tmdbConfigured
                                                                ? <Tag color="green">{t('resourceHubConfigured')}</Tag>
                                                                : <Tag color="red">{t('resourceHubNotRead')}</Tag>}
                                                            <Text type="secondary">{t('resourceHubTmdbKeyHelp')}</Text>
                                                        </Space>
                                                    </div>
                                                </Col>
                                                <Col xs={24} md={8}>
                                                    <div className="rounded border border-gray-200 p-3 dark:border-zinc-700">
                                                        <Space direction="vertical" size={4}>
                                                            <Text strong>PanSou</Text>
                                                            <Tag icon={<ApiOutlined />}>{overview?.pansouBaseUrl || '-'}</Tag>
                                                            <Tag color={overview?.pansouApiConfigured ? 'green' : 'default'}>
                                                                {overview?.pansouApiBaseUrl || 'panso.best'}
                                                            </Tag>
                                                            <Text type="secondary">{t('resourceHubPanSouHelp')}</Text>
                                                        </Space>
                                                    </div>
                                                </Col>
                                                <Col xs={24} md={8}>
                                                    <div className="rounded border border-gray-200 p-3 dark:border-zinc-700">
                                                        <Space direction="vertical" size={4}>
                                                            <Text strong>Quark Auto Save</Text>
                                                            <Tag icon={<ApiOutlined />}>{overview?.quarkBaseUrl || '-'}</Tag>
                                                            <Text type="secondary">{t('resourceHubQuarkHelp')}</Text>
                                                        </Space>
                                                    </div>
                                                </Col>
                                            </Row>
                                        </Card>
                                    </Col>
                                    <Col xs={24} xl={10}>
                                        <Card title={t('resourceHubBatchActions')} loading={loading}>
                                            <Space direction="vertical" className="w-full">
                                                <Button
                                                    block
                                                    type="primary"
                                                    icon={<PlayCircleOutlined />}
                                                    loading={runningAction === 'worker'}
                                                    onClick={() => runAction('worker', '/api/admin/resource-hub/worker/run-once?force=true')}
                                                >
                                                    {t('resourceHubRunWorker')}
                                                </Button>
                                                <Text type="secondary">{t('resourceHubRunWorkerHelp')}</Text>
                                                <Space.Compact block>
                                                    <InputNumber min={1} max={100} value={publishLimit} onChange={(value) => setPublishLimit(value || 20)} />
                                                    <Button
                                                        icon={<ShareAltOutlined />}
                                                        loading={runningAction === 'publish'}
                                                        onClick={() => runAction('publish', `/api/admin/resource-hub/discoveries/publish?limit=${publishLimit}`)}
                                                    >
                                                        {t('resourceHubPublishPending')}
                                                    </Button>
                                                </Space.Compact>
                                                <Text type="secondary">{t('resourceHubPublishPendingHelp')}</Text>
                                                <Space.Compact block>
                                                    <InputNumber min={1} max={20} value={quarkLimit} onChange={(value) => setQuarkLimit(value || 5)} />
                                                    <Button
                                                        icon={<CloudSyncOutlined />}
                                                        loading={runningAction === 'quark'}
                                                        onClick={() => runAction('quark', `/api/admin/resource-hub/quark/transfers/submit?limit=${quarkLimit}`)}
                                                    >
                                                        {t('resourceHubSubmitTransfers')}
                                                    </Button>
                                                </Space.Compact>
                                                <Text type="secondary">{t('resourceHubSubmitTransfersHelp')}</Text>
                                                <Button
                                                    block
                                                    icon={<SearchOutlined />}
                                                    loading={invalidChecksLoading}
                                                    onClick={fetchInvalidChecks}
                                                >
                                                    {t('resourceHubDetectInvalid')}
                                                </Button>
                                                <Text type="secondary">{t('resourceHubDetectInvalidHelp')}</Text>
                                                <Button
                                                    block
                                                    icon={<CloudSyncOutlined />}
                                                    loading={runningAction === 'repair-invalid'}
                                                    onClick={repairInvalidResources}
                                                >
                                                    {t('resourceHubRepairInvalid')}
                                                </Button>
                                                <Text type="secondary">{t('resourceHubRepairInvalidHelp')}</Text>
                                                {invalidChecks.length > 0 && (
                                                    <Table
                                                        size="small"
                                                        columns={invalidCheckColumns}
                                                        dataSource={invalidChecks}
                                                        rowKey="id"
                                                        pagination={{ pageSize: 5 }}
                                                        scroll={{ x: 1200 }}
                                                    />
                                                )}
                                                <Space.Compact block>
                                                    <Button
                                                        icon={<SearchOutlined />}
                                                        loading={runningAction === 'cleanup-dry'}
                                                        onClick={() => runResultAction(
                                                            'cleanup-dry',
                                                            '/api/admin/resource-hub/cleanup/duplicate-tmdb?dryRun=true&limit=100',
                                                            (data) => t('resourceHubCleanupDryRunDone', { count: data.candidates || 0 }),
                                                        )}
                                                    >
                                                        {t('resourceHubCleanupDryRun')}
                                                    </Button>
                                                    <Button
                                                        danger
                                                        loading={runningAction === 'cleanup-execute'}
                                                        onClick={() => runResultAction(
                                                            'cleanup-execute',
                                                            '/api/admin/resource-hub/cleanup/duplicate-tmdb?dryRun=false&limit=100',
                                                            (data) => t('resourceHubCleanupDone', {
                                                                merged: data.merged || 0,
                                                                resources: data.movedResources || 0,
                                                            }),
                                                        )}
                                                    >
                                                        {t('resourceHubCleanupExecute')}
                                                    </Button>
                                                </Space.Compact>
                                                <Space.Compact block>
                                                    <Button
                                                        icon={<SearchOutlined />}
                                                        loading={runningAction === 'pollution-dry'}
                                                        onClick={() => runResultAction(
                                                            'pollution-dry',
                                                            '/api/admin/resource-hub/cleanup/mismatched-resources?dryRun=true&limit=500',
                                                            (data) => t('resourceHubPollutionDryRunDone', { count: data.candidates || 0 }),
                                                        )}
                                                    >
                                                        {t('resourceHubPollutionDryRun')}
                                                    </Button>
                                                    <Button
                                                        danger
                                                        loading={runningAction === 'pollution-execute'}
                                                        onClick={() => runResultAction(
                                                            'pollution-execute',
                                                            '/api/admin/resource-hub/cleanup/mismatched-resources?dryRun=false&limit=500',
                                                            (data) => t('resourceHubPollutionDone', { cleaned: data.cleaned || 0 }),
                                                        )}
                                                    >
                                                        {t('resourceHubPollutionExecute')}
                                                    </Button>
                                                </Space.Compact>
                                            </Space>
                                        </Card>
                                    </Col>
                                    <Col xs={24} xl={14}>
                                        <Card title={t('resourceHubAutoSettings')} loading={loading}>
                                            <Form form={configForm} layout="vertical" onFinish={saveConfig}>
                                                <Row gutter={12}>
                                                    <Col xs={24} md={12}>
                                                        <Form.Item name="enabled" label={t('resourceHubMasterSwitch')} valuePropName="checked">
                                                            <Switch />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={24} md={12}>
                                                        <Form.Item name="workerEnabled" label={t('resourceHubWorkerSwitch')} valuePropName="checked">
                                                            <Switch />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={24} md={12}>
                                                        <Form.Item name="tmdbAutoSyncEnabled" label={t('resourceHubTmdbAutoSync')} valuePropName="checked">
                                                            <Switch />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={24} md={12}>
                                                        <Form.Item name="tmdbAutoDiscoveryEnabled" label={t('resourceHubAutoDiscovery')} valuePropName="checked">
                                                            <Switch />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={24} md={12}>
                                                        <Form.Item name="gyingDiscoveryEnabled" label={t('resourceHubGyingDiscovery')} valuePropName="checked">
                                                            <Switch />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={24} md={12}>
                                                        <Form.Item name="gyingAutoSyncEnabled" label={t('resourceHubGyingAutoSync')} valuePropName="checked">
                                                            <Switch />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={24}>
                                                        <Form.Item name="tmdbAutoSyncSources" label={t('resourceHubTmdbSources')}>
                                                            <Select mode="multiple" options={tmdbSourceOptions} />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="tmdbAutoSyncIntervalHours" label={t('resourceHubSyncInterval')}>
                                                            <InputNumber min={1} max={720} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="tmdbAutoSyncPage" label={t('resourceHubSyncPage')}>
                                                            <InputNumber min={1} max={20} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="tmdbAutoSyncMaxItems" label={t('resourceHubSyncItems')}>
                                                            <InputNumber min={1} max={100} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={24}>
                                                        <Text type="secondary">{t('resourceHubSyncIntervalHelp')}</Text>
                                                    </Col>
                                                    <Col xs={24}>
                                                        <Form.Item name="gyingAutoSyncSources" label={t('resourceHubGyingSources')}>
                                                            <Select mode="multiple" options={gyingSourceOptions} />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="gyingAutoSyncIntervalHours" label={t('resourceHubGyingSyncInterval')}>
                                                            <InputNumber min={1} max={720} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="gyingAutoSyncPage" label={t('resourceHubGyingSyncPage')}>
                                                            <InputNumber min={1} max={500} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="gyingAutoSyncMaxItems" label={t('resourceHubGyingSyncItems')}>
                                                            <InputNumber min={1} max={20} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={24}>
                                                        <Text type="secondary">{t('resourceHubGyingSyncHelp')}</Text>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="tmdbDiscoveryMaxResults" label={t('resourceHubDiscoveryLimit')}>
                                                            <InputNumber min={1} max={50} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="workerTaskLimit" label={t('resourceHubTaskLimit')}>
                                                            <InputNumber min={1} max={20} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col xs={12} md={8}>
                                                        <Form.Item name="workerPublishLimit" label={t('resourceHubPublishLimit')}>
                                                            <InputNumber min={1} max={100} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                </Row>
                                                <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={savingConfig}>
                                                    {t('resourceHubSaveSettings')}
                                                </Button>
                                            </Form>
                                        </Card>
                                    </Col>
                                </Row>
                            ),
                        },
                        {
                            key: 'manual',
                            label: t('resourceHubManual'),
                            children: (
                                <Row gutter={[16, 16]}>
                                    <Col xs={24} lg={12}>
                                        <Card title={t('resourceHubTmdbManualTitle')}>
                                            <Form
                                                form={tmdbForm}
                                                layout="vertical"
                                                initialValues={{ source: 'TRENDING_MOVIE_DAY', page: 1, maxItems: 20, runNow: true }}
                                                onFinish={submitTmdbSync}
                                            >
                                                <Form.Item name="source" label={t('resourceHubSourceLabel')} rules={[{ required: true }]}>
                                                    <Select options={tmdbSourceOptions} />
                                                </Form.Item>
                                                <Row gutter={12}>
                                                    <Col span={12}>
                                                        <Form.Item name="page" label={t('resourceHubSyncPage')} rules={[{ required: true }]}>
                                                            <InputNumber min={1} max={20} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                    <Col span={12}>
                                                        <Form.Item name="maxItems" label={t('resourceHubSyncItems')} rules={[{ required: true }]}>
                                                            <InputNumber min={1} max={100} className="w-full" />
                                                        </Form.Item>
                                                    </Col>
                                                </Row>
                                                <Form.Item name="runNow" label={t('resourceHubRunNow')} valuePropName="checked">
                                                    <Switch />
                                                </Form.Item>
                                                <Button type="primary" htmlType="submit" icon={<CloudSyncOutlined />} loading={runningAction === 'tmdb'}>
                                                    {t('resourceHubCreateSync')}
                                                </Button>
                                            </Form>
                                        </Card>
                                    </Col>
                                    <Col xs={24} lg={12}>
                                        <Card title={t('resourceHubDiscoveryManualTitle')}>
                                            <Form
                                                form={discoveryForm}
                                                layout="vertical"
                                                initialValues={{ maxResults: 10, refresh: true, runNow: true }}
                                                onFinish={submitDiscovery}
                                            >
                                                <Form.Item name="movieTitle" label={t('resourceHubMovieLookup')} rules={[{ required: true }]}>
                                                    <Input placeholder={t('resourceHubMovieLookupPlaceholder')} />
                                                </Form.Item>
                                                <Form.Item name="keyword" label={t('resourceHubKeyword')}>
                                                    <Input placeholder={t('resourceHubKeywordPlaceholder')} />
                                                </Form.Item>
                                                <Form.Item name="maxResults" label={t('resourceHubDiscoveryLimit')} rules={[{ required: true }]}>
                                                    <InputNumber min={1} max={50} className="w-full" />
                                                </Form.Item>
                                                <Space size="large">
                                                    <Form.Item name="refresh" label={t('resourceHubIgnoreCooldown')} valuePropName="checked">
                                                        <Switch />
                                                    </Form.Item>
                                                    <Form.Item name="runNow" label={t('resourceHubRunNow')} valuePropName="checked">
                                                        <Switch />
                                                    </Form.Item>
                                                </Space>
                                                <div>
                                                    <Button type="primary" htmlType="submit" icon={<SearchOutlined />} loading={runningAction === 'discover'}>
                                                        {t('resourceHubCreateDiscovery')}
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
                            label: t('resourceHubTasks'),
                            children: (
                                <Card>
                                    <Space className="mb-4" wrap>
                                        <Select
                                            allowClear
                                            placeholder={t('resourceHubTaskTypeFilter')}
                                            options={TASK_TYPE_OPTIONS.map((value) => ({ value, label: t(`resourceHubTaskType.${value}`) }))}
                                            value={taskType}
                                            style={{ width: 180 }}
                                            onChange={(value) => {
                                                setTaskType(value);
                                                setTaskPage(1);
                                            }}
                                        />
                                        <Select
                                            allowClear
                                            placeholder={t('filterByStatus')}
                                            options={TASK_STATUS_OPTIONS.map((value) => ({ value, label: t(`resourceHubStatus.${value}`) }))}
                                            value={taskStatus}
                                            style={{ width: 160 }}
                                            onChange={(value) => {
                                                setTaskStatus(value);
                                                setTaskPage(1);
                                            }}
                                        />
                                        <Button icon={<ReloadOutlined />} onClick={fetchTasks}>{t('resourceHubRefreshTasks')}</Button>
                                    </Space>
                                    <Table
                                        columns={taskColumns}
                                        dataSource={tasks}
                                        rowKey="id"
                                        loading={tasksLoading}
                                        scroll={{ x: 1680 }}
                                        locale={{ emptyText: <Empty description={t('resourceHubNoTasks')} /> }}
                                        pagination={{
                                            current: taskPage,
                                            pageSize: 20,
                                            total: taskTotal,
                                            onChange: setTaskPage,
                                            showTotal: (total) => t('resourceHubTotalTasks', { count: total }),
                                        }}
                                    />
                                </Card>
                            ),
                        },
                        {
                            key: 'discoveries',
                            label: t('resourceHubDiscoveries'),
                            children: (
                                <Card>
                                    <Space className="mb-4" wrap>
                                        <Input.Search
                                            placeholder={t('resourceHubTitleSearch')}
                                            allowClear
                                            style={{ width: 300 }}
                                            onSearch={(value) => {
                                                setDiscoveryKeyword(value);
                                                setDiscoveryPage(1);
                                            }}
                                        />
                                        <Input.Search
                                            placeholder={t('movieId')}
                                            allowClear
                                            style={{ width: 240 }}
                                            onSearch={(value) => {
                                                setDiscoveryMovieId(value);
                                                setDiscoveryPage(1);
                                            }}
                                        />
                                        <Select
                                            allowClear
                                            placeholder={t('filterByStatus')}
                                            options={DISCOVERY_STATUS_OPTIONS.map((value) => ({ value, label: t(`resourceHubStatus.${value}`) }))}
                                            value={discoveryStatus}
                                            style={{ width: 160 }}
                                            onChange={(value) => {
                                                setDiscoveryStatus(value);
                                                setDiscoveryPage(1);
                                            }}
                                        />
                                        <Select
                                            value={discoverySortOrder}
                                            style={{ width: 160 }}
                                            options={[
                                                { value: 'desc', label: t('resourceHubNewestFirst') },
                                                { value: 'asc', label: t('resourceHubOldestFirst') },
                                            ]}
                                            onChange={(value) => {
                                                setDiscoverySortOrder(value);
                                                setDiscoveryPage(1);
                                            }}
                                        />
                                        <Button icon={<ReloadOutlined />} onClick={fetchDiscoveries}>{t('resourceHubRefreshResults')}</Button>
                                        <Button
                                            icon={<CloudSyncOutlined />}
                                            loading={runningAction === 'reconcile-discoveries'}
                                            onClick={reconcileDiscoveries}
                                        >
                                            {t('resourceHubReconcileDiscoveries')}
                                        </Button>
                                    </Space>
                                    <Space className="mb-4" wrap>
                                        <Text type="secondary">
                                            {t('resourceHubSelectedCount', { count: selectedDiscoveryIds.length })}
                                        </Text>
                                        <Button
                                            icon={<ShareAltOutlined />}
                                            disabled={selectedDiscoveryIds.length === 0}
                                            loading={runningAction === 'batch-publish'}
                                            onClick={() => runDiscoveryBatch(
                                                'batch-publish',
                                                '/api/admin/resource-hub/discoveries/batch/publish',
                                            )}
                                        >
                                            {t('resourceHubBatchPublish')}
                                        </Button>
                                        <Button
                                            icon={<CloudSyncOutlined />}
                                            disabled={selectedDiscoveryIds.length === 0}
                                            loading={runningAction === 'batch-retry'}
                                            onClick={() => runDiscoveryBatch(
                                                'batch-retry',
                                                '/api/admin/resource-hub/discoveries/batch/retry-share-publish',
                                            )}
                                        >
                                            {t('resourceHubBatchRetryShare')}
                                        </Button>
                                        <Button
                                            type="primary"
                                            icon={<ShareAltOutlined />}
                                            disabled={selectedDiscoveryIds.length === 0}
                                            loading={runningAction === 'batch-qq'}
                                            onClick={() => runDiscoveryBatch(
                                                'batch-qq',
                                                '/api/admin/resource-hub/discoveries/batch/qq-channel-post?runNow=true',
                                            )}
                                        >
                                            {t('resourceHubBatchQqPost')}
                                        </Button>
                                    </Space>
                                    <Table
                                        columns={discoveryColumns}
                                        dataSource={discoveries}
                                        rowKey="id"
                                        loading={discoveriesLoading}
                                        rowSelection={{
                                            selectedRowKeys: selectedDiscoveryIds,
                                            preserveSelectedRowKeys: true,
                                            onChange: setSelectedDiscoveryIds,
                                        }}
                                        scroll={{ x: 2260 }}
                                        locale={{ emptyText: <Empty description={t('resourceHubNoDiscoveries')} /> }}
                                        pagination={{
                                            current: discoveryPage,
                                            pageSize: 20,
                                            total: discoveryTotal,
                                            onChange: setDiscoveryPage,
                                            showTotal: (total) => t('resourceHubTotalDiscoveries', { count: total }),
                                        }}
                                    />
                                </Card>
                            ),
                        },
                        {
                            key: 'missing-resources',
                            label: t('resourceHubMissingResources'),
                            children: (
                                <Card>
                                    <Space className="mb-4" wrap>
                                        <Input.Search
                                            placeholder={t('resourceHubMissingSearchPlaceholder')}
                                            allowClear
                                            style={{ width: 320 }}
                                            onSearch={(value) => {
                                                setMissingKeyword(value);
                                                setMissingPage(1);
                                            }}
                                        />
                                        <Select
                                            value={missingSortOrder}
                                            style={{ width: 160 }}
                                            options={[
                                                { value: 'desc', label: t('resourceHubNewestFirst') },
                                                { value: 'asc', label: t('resourceHubOldestFirst') },
                                            ]}
                                            onChange={(value) => {
                                                setMissingSortOrder(value);
                                                setMissingPage(1);
                                            }}
                                        />
                                        <Button icon={<ReloadOutlined />} onClick={fetchMissingResources}>
                                            {t('refresh')}
                                        </Button>
                                    </Space>
                                    <Space className="mb-4" wrap>
                                        <Text type="secondary">
                                            {t('resourceHubSelectedCount', { count: selectedMissingIds.length })}
                                        </Text>
                                        <Button
                                            icon={<CloudSyncOutlined />}
                                            disabled={selectedMissingIds.length === 0}
                                            loading={runningAction === 'missing-batch-GYING'}
                                            onClick={() => resolveMissingResourcesBatch('GYING')}
                                        >
                                            {t('resourceHubBatchResolveFromGying')}
                                        </Button>
                                        <Button
                                            type="primary"
                                            icon={<SearchOutlined />}
                                            disabled={selectedMissingIds.length === 0}
                                            loading={runningAction === 'missing-batch-PANSOU'}
                                            onClick={() => resolveMissingResourcesBatch('PANSOU')}
                                        >
                                            {t('resourceHubBatchResolveFromPanSou')}
                                        </Button>
                                    </Space>
                                    <Table
                                        columns={missingResourceColumns}
                                        dataSource={missingResources}
                                        rowKey="id"
                                        loading={missingLoading}
                                        rowSelection={{
                                            selectedRowKeys: selectedMissingIds,
                                            preserveSelectedRowKeys: true,
                                            onChange: setSelectedMissingIds,
                                        }}
                                        scroll={{ x: 1300 }}
                                        locale={{ emptyText: <Empty description={t('resourceHubNoMissingResources')} /> }}
                                        pagination={{
                                            current: missingPage,
                                            pageSize: 20,
                                            total: missingTotal,
                                            onChange: setMissingPage,
                                            showTotal: (total) => t('resourceHubTotalMissingResources', { count: total }),
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
