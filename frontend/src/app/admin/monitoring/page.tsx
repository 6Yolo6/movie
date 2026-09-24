'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import {
    App,
    Button,
    Card,
    Col,
    Empty,
    Input,
    Progress,
    Row,
    Segmented,
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
    ClockCircleOutlined,
    CloudUploadOutlined,
    EyeOutlined,
    FireOutlined,
    ReloadOutlined,
    SearchOutlined,
    TeamOutlined,
    WarningOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { format } from 'date-fns';
import { api, readApiError } from '@/lib/api';

const { Title, Text } = Typography;
const { Search } = Input;

type Numeric = number | string | null | undefined;

interface TrafficPoint {
    day?: unknown;
    requests?: Numeric;
    visitors?: Numeric;
    pageViews?: Numeric;
    serverErrors?: Numeric;
}

interface HotSearch {
    keyword?: string;
    count?: Numeric;
}

interface Overview {
    requestsToday?: Numeric;
    visitorsToday?: Numeric;
    pageViewsToday?: Numeric;
    clientErrorsToday?: Numeric;
    serverErrorsToday?: Numeric;
    searchesToday?: Numeric;
    resourceOperationsToday?: Numeric;
    socialPostedToday?: Numeric;
    socialFailedToday?: Numeric;
    traffic?: TrafficPoint[];
    hotSearches?: HotSearch[];
}

type LogRow = Record<string, unknown>;
type LogKey = 'access' | 'searches' | 'resourceOperations' | 'qqSearches' | 'socialPosts';
type LogsPayload = Partial<Record<LogKey, LogRow[]>>;
type StatusFilter = 'all' | 'success' | 'failure';

const SUCCESS_STATUS = ['SUCCESS', 'OK', 'POSTED', 'SAVED', 'NORMAL', 'DONE', 'ACTIVE', 'PUBLISHED'];
const FAILURE_STATUS = ['FAILED', 'FAIL', 'ERROR', 'REJECTED', 'DENIED', 'TIMEOUT', 'INVALID', 'NOT_FOUND', 'NO_RESULT'];

const STATUS_LABELS: Record<string, string> = {
    SUCCESS: '成功', OK: '成功', POSTED: '已发布', SAVED: '已转存', NORMAL: '正常', DONE: '完成', ACTIVE: '有效',
    PUBLISHED: '已发布', FAILED: '失败', FAIL: '失败', ERROR: '错误', REJECTED: '被拒绝', DENIED: '无权限',
    TIMEOUT: '超时', INVALID: '已失效', NOT_FOUND: '未找到', NO_RESULT: '无结果', PENDING: '等待中',
    PROCESSING: '处理中', SKIPPED: '已跳过', FILTERED: '已过滤', EMPTY: '空结果',
};

const EVENT_LABELS: Record<string, string> = { PAGE_VIEW: '页面浏览', VIEW: '页面浏览', API: '接口调用' };
const SOURCE_LABELS: Record<string, string> = { WEB: '网页端', QQ: 'QQ 机器人', API: '开放接口', IMPORT: '导入' };
const PLATFORM_LABELS: Record<string, string> = {
    weibo: '微博', 'qq-channel': 'QQ 频道', qq: 'QQ 群', xunlei: '迅雷', quark: '夸克', bilibili: '哔哩哔哩',
};
const PROVIDER_LABELS: Record<string, string> = {
    quark: '夸克网盘', xunlei: '迅雷网盘', aliyun: '阿里云盘', baidu: '百度网盘', magnet: '磁力链接',
    torrent: '种子文件', online: '在线播放', '115': '115 网盘', uc: 'UC 网盘',
};
const OPERATION_LABELS: Record<string, string> = {
    VIEW: '查看资源', COPY: '复制链接', DOWNLOAD: '下载', OPEN: '打开网盘', SAVE: '转存', SHARE: '生成分享',
    SUBMIT: '提交资源', REPAIR: '修复资源', DELETE: '删除资源',
};

const LOG_TABS: { key: LogKey; label: string; description: string; searchHint: string }[] = [
    { key: 'access', label: '访问日志', description: '页面浏览与接口调用', searchHint: '按请求路径过滤，例如 /api/movies' },
    { key: 'searches', label: '站内搜索', description: '网页端影片搜索记录', searchHint: '按搜索关键词过滤' },
    { key: 'resourceOperations', label: '资源操作', description: '用户对资源链接的操作', searchHint: '按影片 ID、操作类型过滤' },
    { key: 'qqSearches', label: 'QQ 搜索', description: 'QQ 机器人搜索请求', searchHint: '按关键词、处理状态过滤' },
    { key: 'socialPosts', label: '平台发布', description: '多平台发布结果', searchHint: '按标题、发布状态过滤' },
];

function num(value: Numeric): number {
    const parsed = Number(value ?? 0);
    return Number.isFinite(parsed) ? parsed : 0;
}

function toDate(value: unknown): Date | null {
    if (value === null || value === undefined || value === '') return null;
    if (value instanceof Date) return value;
    if (typeof value === 'number') {
        const fromNumber = new Date(value);
        return Number.isNaN(fromNumber.getTime()) ? null : fromNumber;
    }
    if (typeof value === 'string') {
        const normalized = value.includes('T') ? value : value.replace(' ', 'T');
        const parsed = new Date(normalized);
        return Number.isNaN(parsed.getTime()) ? null : parsed;
    }
    if (Array.isArray(value) && value.length >= 3) {
        const [year, month, day, hour, minute, second] = value as number[];
        const parsed = new Date(Number(year), Number(month ?? 1) - 1, Number(day ?? 1), Number(hour ?? 0), Number(minute ?? 0), Number(second ?? 0));
        return Number.isNaN(parsed.getTime()) ? null : parsed;
    }
    return null;
}

function formatDateTime(value: unknown): string {
    const parsed = toDate(value);
    if (!parsed) return dash(value);
    return format(parsed, 'yyyy-MM-dd HH:mm:ss');
}

function formatDay(value: unknown): string {
    const parsed = toDate(value);
    if (!parsed) return dash(value);
    return format(parsed, 'MM-dd');
}

function dash(value: unknown): string {
    if (value === null || value === undefined || value === '') return '—';
    return String(value);
}

function statusLabel(value: unknown): string {
    const raw = String(value ?? '').trim();
    if (!raw) return '—';
    const upper = raw.toUpperCase();
    return STATUS_LABELS[upper] ?? raw;
}

function statusColor(value: unknown): string {
    const upper = String(value ?? '').trim().toUpperCase();
    if (SUCCESS_STATUS.includes(upper)) return 'green';
    if (FAILURE_STATUS.includes(upper)) return 'red';
    if (upper === 'PENDING' || upper === 'PROCESSING') return 'gold';
    if (!upper) return 'default';
    return 'blue';
}

function statusTag(value: unknown) {
    if (value === null || value === undefined || value === '') return <Tag>—</Tag>;
    return <Tag color={statusColor(value)}>{statusLabel(value)}</Tag>;
}

function httpStatusTag(value: unknown) {
    const code = Number(value ?? 0);
    if (!Number.isFinite(code) || code <= 0) return <Tag>—</Tag>;
    const color = code >= 500 ? 'red' : code >= 400 ? 'orange' : code >= 300 ? 'blue' : 'green';
    return <Tag color={color}>{code}</Tag>;
}

function durationText(value: unknown) {
    const ms = Number(value ?? 0);
    if (!Number.isFinite(ms) || ms < 0) return <Text type="secondary">—</Text>;
    const color = ms >= 1000 ? '#cf1322' : ms >= 300 ? '#d46b08' : undefined;
    return <span style={{ color }}>{ms} ms</span>;
}

function methodTag(value: unknown) {
    const upper = String(value ?? '').trim().toUpperCase();
    if (!upper) return <Tag>—</Tag>;
    const color = upper === 'GET' ? 'blue' : upper === 'POST' ? 'green' : upper === 'PUT' || upper === 'PATCH' ? 'gold' : upper === 'DELETE' ? 'red' : 'default';
    return <Tag color={color}>{upper}</Tag>;
}

function TextCell({ value, className }: { value: unknown; className?: string }) {
    const text = dash(value);
    return <span className={`block max-w-[320px] truncate ${className ?? ''}`} title={text}>{text}</span>;
}

function LabelCell({ value, dictionary }: { value: unknown; dictionary: Record<string, string> }) {
    const text = dash(value);
    return <span title={text}>{dictionary[text] ?? dictionary[text.toUpperCase()] ?? text}</span>;
}

const COLUMNS: Record<LogKey, ColumnsType<LogRow>> = {
    access: [
        { title: '时间', dataIndex: 'created_at', key: 'created_at', width: 170, render: formatDateTime },
        { title: '方法', dataIndex: 'method', key: 'method', width: 90, render: methodTag },
        { title: '请求路径', dataIndex: 'request_path', key: 'request_path', render: value => <TextCell value={value} className="font-mono text-xs" /> },
        { title: '状态码', dataIndex: 'status_code', key: 'status_code', width: 100, render: httpStatusTag },
        { title: '耗时', dataIndex: 'duration_ms', key: 'duration_ms', width: 110, render: durationText },
        {
            title: '事件类型', dataIndex: 'event_type', key: 'event_type', width: 120,
            render: value => <Tag color={String(value ?? '') === 'PAGE_VIEW' ? 'geekblue' : 'cyan'}>{EVENT_LABELS[String(value ?? '')] ?? dash(value)}</Tag>,
        },
    ],
    searches: [
        { title: '时间', dataIndex: 'created_at', key: 'created_at', width: 170, render: formatDateTime },
        { title: '搜索关键词', dataIndex: 'keyword', key: 'keyword', render: value => <TextCell value={value} className="font-medium" /> },
        { title: '来源', dataIndex: 'source', key: 'source', width: 120, render: value => <LabelCell value={value} dictionary={SOURCE_LABELS} /> },
        { title: '结果数', dataIndex: 'result_count', key: 'result_count', width: 110, render: value => <span>{num(value as Numeric)}</span> },
        { title: '记录 ID', dataIndex: 'id', key: 'id', width: 100, render: value => <Text type="secondary">{dash(value)}</Text> },
    ],
    resourceOperations: [
        { title: '时间', dataIndex: 'created_at', key: 'created_at', width: 170, render: formatDateTime },
        { title: '影片 ID', dataIndex: 'movie_id', key: 'movie_id', width: 110, render: value => <TextCell value={value} /> },
        { title: '资源 ID', dataIndex: 'resource_link_id', key: 'resource_link_id', width: 110, render: value => <TextCell value={value} /> },
        { title: '操作', dataIndex: 'operation_type', key: 'operation_type', width: 130, render: value => <LabelCell value={value} dictionary={OPERATION_LABELS} /> },
        { title: '网盘', dataIndex: 'provider', key: 'provider', width: 110, render: value => <Tag bordered={false}>{PROVIDER_LABELS[String(value ?? '').toLowerCase()] ?? dash(value)}</Tag> },
        { title: '状态', dataIndex: 'status', key: 'status', width: 110, render: statusTag },
        { title: '错误信息', dataIndex: 'error_message', key: 'error_message', render: value => <TextCell value={value} className="text-red-500" /> },
    ],
    qqSearches: [
        { title: '时间', dataIndex: 'created_at', key: 'created_at', width: 170, render: formatDateTime },
        { title: '搜索关键词', dataIndex: 'keyword', key: 'keyword', render: value => <TextCell value={value} className="font-medium" /> },
        { title: '处理状态', dataIndex: 'status', key: 'status', width: 120, render: statusTag },
        { title: '匹配影片 ID', dataIndex: 'movie_id', key: 'movie_id', width: 130, render: value => <TextCell value={value} /> },
        { title: '候选资源数', dataIndex: 'resource_count', key: 'resource_count', width: 120, render: value => <span>{num(value as Numeric)}</span> },
        { title: '失败原因', dataIndex: 'failure_reason', key: 'failure_reason', render: value => <TextCell value={value} className="text-red-500" /> },
    ],
    socialPosts: [
        { title: '时间', dataIndex: 'created_at', key: 'created_at', width: 170, render: formatDateTime },
        { title: '平台', dataIndex: 'platform', key: 'platform', width: 120, render: value => <LabelCell value={value} dictionary={PLATFORM_LABELS} /> },
        { title: '标题', dataIndex: 'title', key: 'title', render: value => <TextCell value={value} /> },
        { title: '状态', dataIndex: 'status', key: 'status', width: 110, render: statusTag },
        { title: '发布时间', dataIndex: 'posted_at', key: 'posted_at', width: 170, render: formatDateTime },
        { title: '错误信息', dataIndex: 'error_message', key: 'error_message', render: value => <TextCell value={value} className="text-red-500" /> },
    ],
};

function rowStatusKey(key: LogKey, row: LogRow): string {
    if (key === 'access') {
        const code = Number(row.status_code ?? 0);
        return code >= 400 ? 'failure' : 'success';
    }
    return String(row.status ?? '').trim().toUpperCase();
}

function MetricCard({ title, value, suffix, icon, hint, accent }: {
    title: string; value: number; suffix?: string; icon: ReactNode; hint?: string; accent: string;
}) {
    return <Card className="h-full" data-metric-card styles={{ body: { padding: 16 } }}>
        <div className="flex items-start justify-between gap-2">
            <Statistic title={title} value={value} suffix={suffix} valueStyle={{ fontSize: 26, fontWeight: 600 }} />
            <span className="flex h-9 w-9 items-center justify-center rounded-lg text-base" style={{ backgroundColor: `${accent}1a`, color: accent }}>{icon}</span>
        </div>
        {hint && <Text type="secondary" className="mt-2 block text-xs">{hint}</Text>}
    </Card>;
}

function TrafficChart({ points }: { points: TrafficPoint[] }) {
    const [metric, setMetric] = useState<'requests' | 'visitors' | 'pageViews'>('requests');
    const labels: Record<string, string> = { requests: '请求数', visitors: '访客数', pageViews: '页面浏览' };
    const series = useMemo(() => points.map(point => ({ day: point.day, value: num(point[metric] as Numeric) })), [points, metric]);
    const max = Math.max(1, ...series.map(item => item.value));
    if (!series.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无流量数据（访问日志中还没有记录）" />;
    return <div>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2" data-trend-metric>
            <Segmented size="small" value={metric} onChange={value => setMetric(value as typeof metric)}
                options={[{ label: '请求数', value: 'requests' }, { label: '访客数', value: 'visitors' }, { label: '页面浏览', value: 'pageViews' }]} />
            <Text type="secondary" className="text-xs">近 14 天 · 峰值 {max} {labels[metric]}</Text>
        </div>
        <div className="flex h-40 items-end gap-1.5">
            {series.map((item, index) => {
                const height = Math.max(2, Math.round((item.value / max) * 100));
                return <Tooltip key={index} title={`${formatDay(item.day)} · ${labels[metric]} ${item.value}`}>
                    <div className="flex h-full flex-1 flex-col justify-end gap-1" data-trend-bar>
                        <div className="w-full rounded-t bg-blue-500/80 transition-all hover:bg-blue-600" style={{ height: `${height}%` }} />
                        <span className="text-center text-[10px] leading-3 text-slate-400">{index % 2 === 0 ? formatDay(item.day) : ''}</span>
                    </div>
                </Tooltip>;
            })}
        </div>
    </div>;
}

function HotSearchList({ items }: { items: HotSearch[] }) {
    const max = Math.max(1, ...items.map(item => num(item.count)));
    if (!items.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="今天还没有搜索记录" />;
    return <div className="space-y-3" data-hot-searches>
        {items.slice(0, 10).map((item, index) => <div key={`${item.keyword}-${index}`}>
            <div className="mb-1 flex items-center justify-between gap-3 text-sm">
                <span className="flex min-w-0 items-center gap-2">
                    <span className={`flex h-5 w-5 shrink-0 items-center justify-center rounded text-[11px] ${index < 3 ? 'bg-blue-100 text-blue-600' : 'bg-slate-100 text-slate-500'}`}>{index + 1}</span>
                    <span className="truncate" title={dash(item.keyword)}>{dash(item.keyword)}</span>
                </span>
                <span className="shrink-0 text-xs text-slate-500">{num(item.count)} 次</span>
            </div>
            <div className="h-1.5 w-full overflow-hidden rounded bg-slate-100">
                <div className="h-full rounded bg-blue-500/70" style={{ width: `${Math.max(4, (num(item.count) / max) * 100)}%` }} />
            </div>
        </div>)}
    </div>;
}

function ResponseDistribution({ requests, clientErrors, serverErrors }: { requests: number; clientErrors: number; serverErrors: number }) {
    const success = Math.max(0, requests - clientErrors - serverErrors);
    const segments = [
        { label: '正常响应', value: success, color: '#52c41a' },
        { label: '客户端错误 4xx', value: clientErrors, color: '#faad14' },
        { label: '服务端错误 5xx', value: serverErrors, color: '#ff4d4f' },
    ];
    const total = segments.reduce((sum, item) => sum + item.value, 0);
    if (!total) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="今天还没有接口请求" />;
    return <div className="space-y-4" data-response-distribution>
        <div className="flex h-3 w-full overflow-hidden rounded-full bg-slate-100">
            {segments.map(item => item.value > 0 && <div key={item.label} className="h-full" style={{ width: `${(item.value / total) * 100}%`, backgroundColor: item.color }} />)}
        </div>
        <div className="space-y-2">
            {segments.map(item => <div key={item.label} className="flex items-center justify-between gap-3 text-sm">
                <span className="flex items-center gap-2"><span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: item.color }} />{item.label}</span>
                <span className="text-slate-500">{item.value} 次 · {total ? Math.round((item.value / total) * 100) : 0}%</span>
            </div>)}
        </div>
        <Text type="secondary" className="text-xs">统计口径：今日已记录的接口请求（含 4xx/5xx 明细）</Text>
    </div>;
}

function SocialSummary({ posted, failed }: { posted: number; failed: number }) {
    const total = posted + failed;
    const rate = total ? Math.round((posted / total) * 100) : 0;
    return <div className="space-y-4" data-social-summary>
        <div className="flex items-end justify-between">
            <div><div className="text-xs text-slate-500">今日发布成功</div><div className="text-2xl font-semibold text-emerald-600">{posted}</div></div>
            <div className="text-right"><div className="text-xs text-slate-500">失败</div><div className="text-2xl font-semibold text-rose-500">{failed}</div></div>
        </div>
        <Progress percent={rate} strokeColor="#52c41a" trailColor="#ffe4e6" format={percent => `${percent}% 成功`} />
        <Text type="secondary" className="text-xs">{total ? `今日共 ${total} 次发布尝试` : '今天没有平台发布记录'}</Text>
    </div>;
}

export default function MonitoringPage() {
    const { message } = App.useApp();
    const [overview, setOverview] = useState<Overview>({});
    const [logs, setLogs] = useState<LogsPayload>({});
    const [keyword, setKeyword] = useState('');
    const [logTab, setLogTab] = useState<LogKey>('access');
    const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
    const [autoRefresh, setAutoRefresh] = useState(false);
    const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
    const [loading, setLoading] = useState(true);

    const load = useCallback(async (term: string) => {
        setLoading(true);
        try {
            const [overviewResponse, logsResponse] = await Promise.all([
                api('/api/admin/monitoring/overview'),
                api(`/api/admin/monitoring/logs?q=${encodeURIComponent(term)}&size=50`),
            ]);
            if (overviewResponse.ok) setOverview(await overviewResponse.json() as Overview);
            else message.error(await readApiError(overviewResponse, '监控概览加载失败'));
            if (logsResponse.ok) setLogs(await logsResponse.json() as LogsPayload);
            else message.error(await readApiError(logsResponse, '日志加载失败'));
            setUpdatedAt(new Date());
        } catch {
            message.error('监控数据加载失败，请检查后端服务状态');
        } finally {
            setLoading(false);
        }
    }, [message]);

    useEffect(() => { void load(keyword); }, [load, keyword]);

    useEffect(() => {
        if (!autoRefresh) return;
        const timer = window.setInterval(() => { void load(keyword); }, 30000);
        return () => window.clearInterval(timer);
    }, [autoRefresh, keyword, load]);

    const activeRows = useMemo(() => logs[logTab] ?? [], [logs, logTab]);
    const filteredRows = useMemo(() => {
        if (statusFilter === 'all') return activeRows;
        return activeRows.filter(row => {
            const key = rowStatusKey(logTab, row);
            const isFailure = key === 'failure' || FAILURE_STATUS.includes(key);
            const isSuccess = key === 'success' || SUCCESS_STATUS.includes(key);
            return statusFilter === 'failure' ? isFailure : isSuccess;
        });
    }, [activeRows, logTab, statusFilter]);

    const requestsToday = num(overview.requestsToday);
    const clientErrorsToday = num(overview.clientErrorsToday);
    const serverErrorsToday = num(overview.serverErrorsToday);
    const searchesToday = num(overview.searchesToday);
    const resourceOperationsToday = num(overview.resourceOperationsToday);
    const socialPostedToday = num(overview.socialPostedToday);
    const socialFailedToday = num(overview.socialFailedToday);
    const activeTab = LOG_TABS.find(tab => tab.key === logTab) ?? LOG_TABS[0];

    const tabs = LOG_TABS.map(tab => ({
        key: tab.key,
        label: <span className="flex items-center gap-1.5">{tab.label}<Tag bordered={false} className="!mr-0">{logs[tab.key]?.length ?? 0}</Tag></span>,
        children: (
            <div className="space-y-3">
                <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-slate-500">
                    <span>{tab.description} · 展示最近 {logs[tab.key]?.length ?? 0} 条，按时间倒序</span>
                    <span>{statusFilter === 'all' ? '未启用状态筛选' : `已筛选 ${filteredRows.length} 条`}</span>
                </div>
                <Table<LogRow>
                    size="small"
                    rowKey={row => String(row.id ?? Math.random())}
                    loading={loading}
                    dataSource={filteredRows}
                    columns={COLUMNS[tab.key]}
                    scroll={{ x: 'max-content' }}
                    locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={keyword ? `没有匹配“${keyword}”的${tab.label}` : `暂无${tab.label}`} /> }}
                    pagination={{ pageSize: 20, size: 'small', showSizeChanger: false, showTotal: total => `共 ${total} 条` }}
                />
            </div>
        ),
    }));

    return <main className="mx-auto max-w-7xl p-4 sm:p-8">
        <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
            <div>
                <Title level={2} className="!mb-1">后台监控</Title>
                <Text type="secondary">站内访问、搜索、资源操作与多平台发布的实时概览；数据来源为埋点日志表，默认展示今日与近 14 天。</Text>
            </div>
            <Space wrap>
                <Text type="secondary" className="text-xs">{updatedAt ? `更新于 ${format(updatedAt, 'HH:mm:ss')}` : '加载中…'}</Text>
                <Space size={6}>
                    <Switch size="small" checked={autoRefresh} onChange={setAutoRefresh} />
                    <Text className="text-xs">每 30 秒自动刷新</Text>
                </Space>
                <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load(keyword)}>刷新</Button>
            </Space>
        </div>

        <Row gutter={[16, 16]}>
            <Col xs={12} md={8} xl={4}><MetricCard title="今日页面访问" value={num(overview.pageViewsToday)} icon={<EyeOutlined />} accent="#1677ff" hint="来自站内页面浏览埋点" /></Col>
            <Col xs={12} md={8} xl={4}><MetricCard title="今日访客" value={num(overview.visitorsToday)} icon={<TeamOutlined />} accent="#722ed1" hint="按 IP + UA 去重" /></Col>
            <Col xs={12} md={8} xl={4}><MetricCard title="今日 API 请求" value={requestsToday} icon={<ApiOutlined />} accent="#13c2c2" hint={`4xx ${clientErrorsToday} · 5xx ${serverErrorsToday}`} /></Col>
            <Col xs={12} md={8} xl={4}><MetricCard title="今日搜索" value={searchesToday} icon={<SearchOutlined />} accent="#2f54eb" hint="网页端站内搜索次数" /></Col>
            <Col xs={12} md={8} xl={4}><MetricCard title="资源操作" value={resourceOperationsToday} icon={<CloudUploadOutlined />} accent="#fa8c16" hint="查看、转存、生成分享等" /></Col>
            <Col xs={12} md={8} xl={4}><MetricCard title="服务端错误" value={serverErrorsToday} icon={<WarningOutlined />} accent="#f5222d" hint="5xx 响应，需关注" /></Col>
        </Row>

        <Row gutter={[16, 16]} className="mt-4">
            <Col xs={24} xl={14}><Card title="近 14 天访问趋势" styles={{ body: { paddingTop: 12 } }}><TrafficChart points={overview.traffic ?? []} /></Card></Col>
            <Col xs={24} xl={10}><Card title="今日接口响应分布" styles={{ body: { paddingTop: 12 } }}><ResponseDistribution requests={requestsToday} clientErrors={clientErrorsToday} serverErrors={serverErrorsToday} /></Card></Col>
        </Row>

        <Row gutter={[16, 16]} className="mt-4">
            <Col xs={24} xl={14}><Card title="热门搜索 Top 10（今日）" extra={<Text type="secondary" className="text-xs"><FireOutlined /> 按搜索次数排序</Text>} styles={{ body: { paddingTop: 12 } }}><HotSearchList items={overview.hotSearches ?? []} /></Card></Col>
            <Col xs={24} xl={10}><Card title="今日平台发布" styles={{ body: { paddingTop: 12 } }}><SocialSummary posted={socialPostedToday} failed={socialFailedToday} /></Card></Col>
        </Row>

        <Card
            className="mt-4"
            title="流量与业务日志"
            extra={<Search allowClear placeholder={activeTab.searchHint} onSearch={value => setKeyword(value)} style={{ width: 320 }} enterButton={<SearchOutlined />} />}
        >
            <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
                <Space wrap size={8}>
                    <Text type="secondary" className="text-xs"><ClockCircleOutlined /> 每类日志最多取最近 50 条</Text>
                    <Segmented
                        size="small"
                        value={statusFilter}
                        onChange={value => setStatusFilter(value as StatusFilter)}
                        options={[{ label: '全部', value: 'all' }, { label: '仅成功', value: 'success' }, { label: '仅失败', value: 'failure' }]}
                    />
                </Space>
                {keyword && <Tag closable onClose={() => setKeyword('')} color="blue">搜索：{keyword}</Tag>}
            </div>
            <Tabs activeKey={logTab} onChange={key => setLogTab(key as LogKey)} items={tabs} />
        </Card>
    </main>;
}