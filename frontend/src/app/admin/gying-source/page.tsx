'use client';

import { useCallback, useEffect, useState } from 'react';
import type { Key } from 'react';
import { useRouter } from 'next/navigation';
import {
    Alert,
    App,
    Button,
    Card,
    Form,
    Input,
    InputNumber,
    Select,
    Space,
    Switch,
    Table,
    Tabs,
    Tag,
    Typography,
} from 'antd';
import {
    CloudDownloadOutlined,
    CloudSyncOutlined,
    DatabaseOutlined,
    ReloadOutlined,
    SafetyCertificateOutlined,
    ToolOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { api, readApiError } from '@/lib/api';
import { useAuthStore } from '@/store/authStore';

const { Title } = Typography;

type Candidate = {
    typeCode: string;
    mid: string;
    title: string;
    year?: number;
    localMovie?: boolean;
    localMovieId?: string;
    resourceStatus?: string;
    activeResourceCount?: number;
    score?: number;
    seriesName?: string;
    season?: number;
};

type PublishedResource = {
    source_id: string;
    mid: string;
    type_code: string;
    title: string;
    movie_title?: string;
    url: string;
    provider?: string;
    checkStatus?: string;
    checkMessage?: string;
    resourceId?: number;
    repairable?: boolean;
    repairStatus?: string;
    repairMode?: string;
};

type WorkflowJob = {
    jobId: string;
    type: string;
    status: string;
    result?: Record<string, unknown>;
    errors?: string[];
};

type HealthResult = {
    checked: number;
    valid: number;
    invalid: number;
    unclear: number;
    items: PublishedResource[];
};

type AccountStatus = {
    username: string;
    targetUser: string;
    passwordConfigured: boolean;
    cookieConfigured: boolean;
    source: string;
    updatedAt?: string;
};

type AccountFormValues = {
    username: string;
    targetUser: string;
    password?: string;
    cookie?: string;
    clearCookie?: boolean;
};

const delay = (milliseconds: number) => new Promise((resolve) => setTimeout(resolve, milliseconds));

const summarizeResult = (value: Record<string, unknown>) => {
    const { items, ...summary } = value;
    return Array.isArray(items) ? { ...summary, itemCount: items.length } : summary;
};

export default function GyingSourcePage() {
    const { user, token } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [accountForm] = Form.useForm<AccountFormValues>();
    const [recent, setRecent] = useState<Candidate[]>([]);
    const [catalog, setCatalog] = useState<Candidate[]>([]);
    const [catalogType, setCatalogType] = useState('mv');
    const [catalogPage, setCatalogPage] = useState(1);
    const [trailers, setTrailers] = useState<Candidate[]>([]);
    const [health, setHealth] = useState<HealthResult | null>(null);
    const [loadingLists, setLoadingLists] = useState(false);
    const [running, setRunning] = useState('');
    const [result, setResult] = useState<Record<string, unknown> | null>(null);
    const [trailerLimit, setTrailerLimit] = useState(10);
    const [healthLimit, setHealthLimit] = useState(200);
    const [account, setAccount] = useState<AccountStatus | null>(null);
    const [accountLoading, setAccountLoading] = useState(false);
    const [selectedRecentKeys, setSelectedRecentKeys] = useState<Key[]>([]);
    const [healthSourceIds, setHealthSourceIds] = useState('');

    const requestJson = useCallback(async <T,>(path: string, options?: RequestInit): Promise<T> => {
        if (!token) throw new Error(t('adminAccessRequired'));
        const response = await api(path, {
            ...options,
            headers: {
                Authorization: `Bearer ${token}`,
                ...(options?.headers || {}),
            },
        });
        if (!response.ok) {
            throw new Error(await readApiError(response, t('operationFailed')));
        }
        return response.json() as Promise<T>;
    }, [t, token]);

    const loadCandidates = useCallback(async () => {
        if (!token) return;
        setLoadingLists(true);
        try {
            const [recentRows, trailerRows] = await Promise.all([
                requestJson<Candidate[]>('/api/admin/gying-source/candidates/recent?limit=36'),
                requestJson<Candidate[]>('/api/admin/gying-source/candidates/trailers?limit=50'),
            ]);
            setRecent(recentRows);
            setTrailers(trailerRows);
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setLoadingLists(false);
        }
    }, [message, requestJson, t, token]);

    const loadCatalog = useCallback(async (typeCode = catalogType, page = catalogPage) => {
        if (!token) return;
        setLoadingLists(true);
        try {
            const rows = await requestJson<Candidate[]>(
                `/api/admin/gying-source/candidates/catalog?typeCode=${typeCode}&sort=score&page=${page}&limit=30`,
            );
            setCatalog(rows);
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setLoadingLists(false);
        }
    }, [catalogPage, catalogType, message, requestJson, t, token]);
    const loadAccount = useCallback(async () => {
        if (!token) return;
        try {
            const data = await requestJson<AccountStatus>('/api/admin/gying-source/account');
            setAccount(data);
            accountForm.setFieldsValue({
                username: data.username,
                targetUser: data.targetUser,
                password: undefined,
                cookie: undefined,
                clearCookie: false,
            });
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        }
    }, [accountForm, message, requestJson, t, token]);

    useEffect(() => {
        if (!user) return;
        if (user.role !== 'ADMIN') {
            message.error(t('adminAccessRequired'));
            router.push('/');
            return;
        }
        loadCandidates();
        loadCatalog();
        loadAccount();
    }, [loadAccount, loadCandidates, loadCatalog, message, router, t, user]);

    const saveAccount = async (values: AccountFormValues) => {
        setAccountLoading(true);
        try {
            const payload: Record<string, unknown> = {
                username: values.username,
                targetUser: values.targetUser,
                clearCookie: Boolean(values.clearCookie),
            };
            if (values.password) payload.password = values.password;
            if (values.cookie) payload.cookie = values.cookie;
            const data = await requestJson<AccountStatus>('/api/admin/gying-source/account', {
                method: 'PUT',
                body: JSON.stringify(payload),
            });
            setAccount(data);
            accountForm.setFieldsValue({ password: undefined, cookie: undefined, clearCookie: false });
            message.success(t('gyingSourceAccountSaved'));
            await loadCandidates();
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setAccountLoading(false);
        }
    };

    const waitForJob = async (jobId: string) => {
        for (let attempt = 0; attempt < 180; attempt += 1) {
            const job = await requestJson<WorkflowJob>(`/api/admin/gying-source/jobs/${jobId}`);
            if (job.status === 'SUCCEEDED') return job;
            if (job.status === 'FAILED') {
                throw new Error(job.errors?.join('; ') || t('operationFailed'));
            }
            await delay(1500);
        }
        throw new Error(t('gyingSourceJobTimeout'));
    };

    const startJob = async (key: string, path: string, options?: RequestInit) => {
        setRunning(key);
        setResult(null);
        try {
            const started = await requestJson<WorkflowJob>(path, { method: 'POST', ...options });
            const completed = await waitForJob(started.jobId);
            setResult(summarizeResult(completed.result || {}));
            message.success(t('gyingSourceActionSucceeded'));
            await loadCandidates();
            return completed;
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
            return null;
        } finally {
            setRunning('');
        }
    };

    const ensureMovie = (candidate: Pick<Candidate, 'typeCode' | 'mid'>) => startJob(
        `ensure-${candidate.typeCode}-${candidate.mid}`,
        `/api/admin/gying-source/movies/${candidate.typeCode}/${candidate.mid}/ensure`,
    );

    const ensureSelectedRecent = async () => {
        const selected = new Set(selectedRecentKeys.map(String));
        const candidates = recent
            .filter((item) => selected.has(`${item.typeCode}-${item.mid}`))
            .map(({ typeCode, mid }) => ({ typeCode, mid }));
        if (!candidates.length) {
            message.warning(t('gyingSourceSelectRecentFirst'));
            return;
        }
        const completed = await startJob(
            'ensure-recent-batch',
            '/api/admin/gying-source/recent/ensure',
            { body: JSON.stringify(candidates) },
        );
        if (completed) setSelectedRecentKeys([]);
    };

    const ensureSeasons = (movieId: string) => startJob(
        `seasons-${movieId}`,
        `/api/admin/gying-source/movies/${movieId}/seasons/ensure?maxPages=20`,
    );

    const checkPublished = async () => {
        setRunning('check-published');
        setResult(null);
        try {
            const started = await requestJson<WorkflowJob>(
                `/api/admin/gying-source/published-resources/check?limit=${healthLimit}`,
                { method: 'POST' },
            );
            const completed = await waitForJob(started.jobId);
            const data = completed.result as unknown as HealthResult;
            setHealth(data);
            setResult(summarizeResult(completed.result || {}));
            message.success(t('gyingSourceCheckDone', data));
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('operationFailed'));
        } finally {
            setRunning('');
        }
    };

    const repairPublished = async () => {
        const completed = await startJob(
            'repair-published',
            `/api/admin/gying-source/published-resources/repair?limit=${healthLimit}`,
        );
        if (completed?.result) {
            const repair = completed.result as Record<string, unknown>;
            setHealth((current) => current ? {
                ...current,
                items: (repair.items as PublishedResource[]) || current.items,
            } : null);
        }
    };

    const parsedHealthSourceIds = Array.from(new Set(
        healthSourceIds.split(/[\s,，;；]+/).map((value) => value.trim()).filter(Boolean),
    ));

    const repairPublishedByIds = async (single: boolean) => {
        const sourceIds = parsedHealthSourceIds;
        if (!sourceIds.length || (single && sourceIds.length !== 1)) {
            message.warning(t(single ? 'gyingSourceSingleIdRequired' : 'gyingSourceIdsRequired'));
            return;
        }
        const key = single ? 'repair-published-single' : 'repair-published-batch';
        const completed = await startJob(
            key,
            '/api/admin/gying-source/published-resources/repair-by-ids',
            { body: JSON.stringify(sourceIds) },
        );
        if (completed?.result) {
            const repair = completed.result as Record<string, unknown>;
            const items = (repair.items as PublishedResource[]) || [];
            setHealth({
                checked: Number(repair.checked || items.length),
                valid: 0,
                invalid: Number(repair.invalid || 0),
                unclear: 0,
                items,
            });
        }
    };

    const statusTag = (record: Candidate) => {
        if (!record.localMovie) return <Tag>{t('gyingSourceNotInLibrary')}</Tag>;
        if (record.resourceStatus === 'AVAILABLE') return <Tag color="green">{t('movieResourceStatus.AVAILABLE')}</Tag>;
        if (record.resourceStatus === 'TRAILER') return <Tag color="orange">{t('movieResourceStatus.TRAILER')}</Tag>;
        return <Tag>{record.resourceStatus || t('unknown')}</Tag>;
    };

    const candidateColumns: ColumnsType<Candidate> = [
        {
            title: t('movieTitle'),
            dataIndex: 'title',
            render: (value: string, record) => (
                <a href={`https://www.xn--wcv59z.com/${record.typeCode}/${record.mid}`} target="_blank" rel="noreferrer">
                    {value || record.mid}
                </a>
            ),
        },
        {
            title: t('category'),
            dataIndex: 'typeCode',
            width: 90,
            render: (value: string) => <Tag>{value.toUpperCase()}</Tag>,
        },
        {
            title: t('resourceStatus'),
            width: 140,
            render: (_: unknown, record) => statusTag(record),
        },
        {
            title: t('resourceCount'),
            dataIndex: 'activeResourceCount',
            width: 100,
            render: (value?: number) => value ?? 0,
        },
        {
            title: t('actions'),
            width: 150,
            render: (_: unknown, record) => {
                const key = `ensure-${record.typeCode}-${record.mid}`;
                const seasonKey = `seasons-${record.localMovieId}`;
                return (
                    <Space size={4}>
                        <Button
                            type="primary"
                            size="small"
                            icon={<CloudSyncOutlined />}
                            loading={running === key}
                            disabled={Boolean(running && running !== key)}
                            onClick={() => ensureMovie(record)}
                        >
                            {t('gyingSourceEnsureAction')}
                        </Button>
                        {record.localMovieId && ['tv', 'ac'].includes(record.typeCode) && (
                            <Button
                                size="small"
                                icon={<DatabaseOutlined />}
                                loading={running === seasonKey}
                                disabled={Boolean(running && running !== seasonKey)}
                                onClick={() => ensureSeasons(record.localMovieId!)}
                            >
                                {t('gyingSourceEnsureSeasons')}
                            </Button>
                        )}
                    </Space>
                );
            },
        },
    ];

    const healthColumns: ColumnsType<PublishedResource> = [
        {
            title: t('gyingResourceId'),
            dataIndex: 'source_id',
            width: 130,
            render: (value: string) => <Typography.Text copyable>{value}</Typography.Text>,
        },
        {
            title: t('movieTitle'),
            dataIndex: 'movie_title',
            width: 180,
            render: (value: string, record) => value || record.mid,
        },
        {
            title: t('resourceName'),
            dataIndex: 'title',
            ellipsis: true,
        },
        {
            title: t('provider'),
            dataIndex: 'provider',
            width: 110,
            render: (value?: string) => <Tag>{value || '-'}</Tag>,
        },
        {
            title: t('status'),
            dataIndex: 'checkStatus',
            width: 120,
            render: (value?: string) => {
                if (value === 'VALID') return <Tag color="green">{t('normal')}</Tag>;
                if (value === 'INVALID') return <Tag color="red">{t('invalid')}</Tag>;
                return <Tag color="orange">{t('unknown')}</Tag>;
            },
        },
        {
            title: t('gyingSourceRepairability'),
            dataIndex: 'repairable',
            width: 120,
            render: (value: boolean | undefined, record) => (
                <Tag color={record.repairStatus === 'REPAIRED' ? 'green' : value ? 'blue' : 'default'}>
                    {record.repairStatus === 'REPAIRED'
                        ? t('gyingSourceRepaired')
                        : record.repairMode === 'RESHARE'
                            ? t('gyingSourceRepairable')
                            : record.repairMode === 'RETRANSFER'
                                ? t('gyingSourceRetransferable')
                                : t('gyingSourceNotRepairable')}
                </Tag>
            ),
        },
    ];

    const recentTab = (
        <div>
            <Form
                layout="inline"
                initialValues={{ typeCode: 'mv' }}
                onFinish={(values: { typeCode: string; mid: string }) => ensureMovie(values)}
                className="mb-5"
            >
                <Form.Item name="typeCode" rules={[{ required: true }]}>
                    <Select
                        style={{ width: 110 }}
                        options={[
                            { value: 'mv', label: t('movies') },
                            { value: 'tv', label: t('tvShows') },
                            { value: 'ac', label: t('anime') },
                        ]}
                    />
                </Form.Item>
                <Form.Item name="mid" rules={[{ required: true }]}>
                    <Input placeholder={t('gyingMovieId')} maxLength={64} />
                </Form.Item>
                <Form.Item>
                    <Button type="primary" htmlType="submit" icon={<CloudSyncOutlined />} disabled={Boolean(running)}>
                        {t('gyingSourceEnsureAction')}
                    </Button>
                </Form.Item>
                <Button icon={<ReloadOutlined />} loading={loadingLists} onClick={loadCandidates}>
                    {t('refresh')}
                </Button>
            </Form>
            <Space className="mb-4" wrap>
                <Button
                    type="primary"
                    icon={<CloudSyncOutlined />}
                    loading={running === 'ensure-recent-batch'}
                    disabled={!selectedRecentKeys.length || Boolean(running && running !== 'ensure-recent-batch')}
                    onClick={ensureSelectedRecent}
                >
                    {t('gyingSourceEnsureSelectedRecent')}
                </Button>
                <Typography.Text type="secondary">
                    {t('selectedCount', { count: selectedRecentKeys.length })}
                </Typography.Text>
            </Space>
            <Table
                rowKey={(record) => `${record.typeCode}-${record.mid}`}
                columns={candidateColumns}
                dataSource={recent}
                loading={loadingLists}
                rowSelection={{
                    selectedRowKeys: selectedRecentKeys,
                    onChange: setSelectedRecentKeys,
                    preserveSelectedRowKeys: true,
                }}
                pagination={{ pageSize: 12 }}
                scroll={{ x: 760 }}
            />
        </div>
    );

    const catalogTab = (
        <div>
            <Space className="mb-5" wrap>
                <Select
                    value={catalogType}
                    style={{ width: 130 }}
                    options={[
                        { value: 'mv', label: t('movies') },
                        { value: 'tv', label: t('tvShows') },
                        { value: 'ac', label: t('anime') },
                    ]}
                    onChange={(value) => {
                        setCatalogType(value);
                        setCatalogPage(1);
                        loadCatalog(value, 1);
                    }}
                />
                <InputNumber
                    min={1}
                    max={500}
                    value={catalogPage}
                    onChange={(value) => setCatalogPage(value || 1)}
                />
                <Button icon={<ReloadOutlined />} loading={loadingLists} onClick={() => loadCatalog()}>
                    {t('refresh')}
                </Button>
                <Button
                    type="primary"
                    icon={<DatabaseOutlined />}
                    loading={running === 'ensure-catalog'}
                    disabled={Boolean(running && running !== 'ensure-catalog')}
                    onClick={() => startJob(
                        'ensure-catalog',
                        `/api/admin/gying-source/catalog/ensure?typeCode=${catalogType}&sort=score&page=${catalogPage}&limit=30`,
                    )}
                >
                    {t('gyingSourceIngestCatalogPage')}
                </Button>
            </Space>
            <Table
                rowKey={(record) => `${record.typeCode}-${record.mid}`}
                columns={candidateColumns}
                dataSource={catalog}
                loading={loadingLists}
                pagination={false}
                scroll={{ x: 900 }}
            />
        </div>
    );

    const trailerTab = (
        <div>
            <Space className="mb-5" wrap>
                <InputNumber min={1} max={30} value={trailerLimit} onChange={(value) => setTrailerLimit(value || 10)} />
                <Button
                    type="primary"
                    icon={<CloudDownloadOutlined />}
                    loading={running === 'ensure-trailers'}
                    disabled={Boolean(running && running !== 'ensure-trailers')}
                    onClick={() => startJob(
                        'ensure-trailers',
                        `/api/admin/gying-source/trailers/ensure?limit=${trailerLimit}`,
                    )}
                >
                    {t('gyingSourceEnsureTrailers')}
                </Button>
                <Button icon={<ReloadOutlined />} loading={loadingLists} onClick={loadCandidates}>
                    {t('refresh')}
                </Button>
            </Space>
            <Table
                rowKey={(record) => `${record.typeCode}-${record.mid}`}
                columns={candidateColumns}
                dataSource={trailers}
                loading={loadingLists}
                pagination={{ pageSize: 12 }}
                scroll={{ x: 760 }}
            />
        </div>
    );

    const healthTab = (
        <div>
            <Space className="mb-5" wrap>
                <InputNumber min={1} max={500} value={healthLimit} onChange={(value) => setHealthLimit(value || 200)} />
                <Button
                    type="primary"
                    icon={<SafetyCertificateOutlined />}
                    loading={running === 'check-published'}
                    disabled={Boolean(running && running !== 'check-published')}
                    onClick={checkPublished}
                >
                    {t('gyingSourceCheckPublished')}
                </Button>
                <Button
                    danger
                    icon={<ToolOutlined />}
                    loading={running === 'repair-published'}
                    disabled={Boolean(running && running !== 'repair-published')}
                    onClick={repairPublished}
                >
                    {t('gyingSourceRepairPublished')}
                </Button>
            </Space>
            <div className="mb-5 border-t border-gray-200 pt-4 dark:border-gray-800">
                <Typography.Title level={5}>{t('gyingSourceRepairByIdTitle')}</Typography.Title>
                <Space direction="vertical" className="w-full" size="middle">
                    <Input.TextArea
                        value={healthSourceIds}
                        rows={3}
                        maxLength={10000}
                        placeholder={t('gyingSourceRepairIdsPlaceholder')}
                        onChange={(event) => setHealthSourceIds(event.target.value)}
                    />
                    <Space wrap>
                        <Button
                            danger
                            icon={<ToolOutlined />}
                            loading={running === 'repair-published-single'}
                            disabled={parsedHealthSourceIds.length !== 1 || Boolean(running && running !== 'repair-published-single')}
                            onClick={() => repairPublishedByIds(true)}
                        >
                            {t('gyingSourceRepairSingleId')}
                        </Button>
                        <Button
                            danger
                            icon={<ToolOutlined />}
                            loading={running === 'repair-published-batch'}
                            disabled={!parsedHealthSourceIds.length || parsedHealthSourceIds.length > 100 || Boolean(running && running !== 'repair-published-batch')}
                            onClick={() => repairPublishedByIds(false)}
                        >
                            {t('gyingSourceRepairBatchIds', { count: parsedHealthSourceIds.length })}
                        </Button>
                    </Space>
                </Space>
            </div>
            {health && (
                <Space className="mb-4" wrap>
                    <Tag>{t('gyingSourceCheckedCount', { count: health.checked })}</Tag>
                    <Tag color="green">{t('normal')}: {health.valid}</Tag>
                    <Tag color="red">{t('invalid')}: {health.invalid}</Tag>
                    <Tag color="orange">{t('unknown')}: {health.unclear}</Tag>
                </Space>
            )}
            <Table
                rowKey="source_id"
                columns={healthColumns}
                dataSource={health?.items || []}
                loading={running === 'check-published'}
                pagination={{ pageSize: 20 }}
                scroll={{ x: 950 }}
            />
        </div>
    );

    const accountTab = (
        <div className="max-w-2xl">
            {account && (
                <Space className="mb-5" wrap>
                    <Tag color="blue">{account.username || '-'}</Tag>
                    <Tag>{account.targetUser || '-'}</Tag>
                    <Tag color={account.passwordConfigured ? 'green' : 'red'}>
                        {t(account.passwordConfigured ? 'gyingSourcePasswordConfigured' : 'gyingSourcePasswordMissing')}
                    </Tag>
                    <Tag color={account.cookieConfigured ? 'green' : 'default'}>
                        {t(account.cookieConfigured ? 'gyingSourceCookieConfigured' : 'gyingSourceCookieMissing')}
                    </Tag>
                    <Tag>{account.source}</Tag>
                </Space>
            )}
            <Form form={accountForm} layout="vertical" onFinish={saveAccount}>
                <Form.Item name="username" label={t('username')} rules={[{ required: true }]}>
                    <Input autoComplete="off" />
                </Form.Item>
                <Form.Item name="targetUser" label={t('gyingSourceTargetUser')} rules={[{ required: true }]}>
                    <Input autoComplete="off" />
                </Form.Item>
                <Form.Item name="password" label={t('password')}>
                    <Input.Password autoComplete="new-password" placeholder={t('gyingSourceKeepPassword')} />
                </Form.Item>
                <Form.Item name="cookie" label="Cookie">
                    <Input.TextArea rows={3} placeholder={t('gyingSourceKeepCookie')} />
                </Form.Item>
                <Form.Item name="clearCookie" label={t('gyingSourceClearCookie')} valuePropName="checked">
                    <Switch />
                </Form.Item>
                <Button type="primary" htmlType="submit" loading={accountLoading}>
                    {t('save')}
                </Button>
            </Form>
        </div>
    );

    return (
        <div className="min-h-screen bg-[#f5f7fa] dark:bg-black">
            <div className="container mx-auto max-w-6xl px-4 py-8 lg:px-8">
                <Card>
                    <Title level={2} className="!mb-6">{t('gyingSourceTitle')}</Title>
                    <Tabs
                        items={[
                            { key: 'recent', label: t('gyingSourceRecentTab'), children: recentTab },
                            { key: 'catalog', label: t('gyingSourceCatalogTab'), children: catalogTab },
                            { key: 'trailers', label: t('gyingSourceTrailerTab'), children: trailerTab },
                            { key: 'health', label: t('gyingSourceHealthTab'), children: healthTab },
                            { key: 'account', label: t('gyingSourceAccountTab'), children: accountTab },
                        ]}
                    />
                    {result && (
                        <Alert
                            className="mt-6"
                            type="success"
                            showIcon
                            message={t('gyingSourceLastResult')}
                            description={<pre className="m-0 max-h-72 overflow-auto text-xs">{JSON.stringify(result, null, 2)}</pre>}
                        />
                    )}
                </Card>
            </div>
        </div>
    );
}
