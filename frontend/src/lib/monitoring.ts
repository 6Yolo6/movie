export type Numeric = number | string | null | undefined;
export type LogKey = 'access' | 'searches' | 'resourceOperations' | 'qqSearches' | 'socialPosts';
export type StatusFilter = 'all' | 'success' | 'failure' | 'pending' | 'attention';
export type LogRow = Record<string, unknown>;
export const LOG_KEYS: LogKey[] = ['access', 'searches', 'resourceOperations', 'qqSearches', 'socialPosts'];

export interface TrafficPoint {
    day?: unknown;
    requests?: Numeric;
    visitors?: Numeric;
    pageViews?: Numeric;
    serverErrors?: Numeric;
}
export interface Overview {
    date?: string;
    requestsToday?: Numeric;
    visitorsToday?: Numeric;
    pageViewsToday?: Numeric;
    clientErrorsToday?: Numeric;
    serverErrorsToday?: Numeric;
    searchesToday?: Numeric;
    resourceOperationsToday?: Numeric;
    socialPostedToday?: Numeric;
    socialFailedToday?: Numeric;
    socialPendingToday?: Numeric;
    socialUnknownToday?: Numeric;
    traffic?: TrafficPoint[];
    hotSearches?: { keyword?: string; count?: Numeric }[];
}
export interface LogPage {
    records: LogRow[];
    total: number;
    page: number;
    size: number;
}
export interface LogQuery { type: LogKey; q: string; status: StatusFilter; page: number; size: number }

export function count(value: Numeric): number {
    const parsed = Number(value ?? 0);
    return Number.isFinite(parsed) ? Math.max(0, Math.trunc(parsed)) : 0;
}

export function logUrl(query: LogQuery): string {
    const params = new URLSearchParams({ type: query.type, q: query.q.trim(), status: query.status,
        page: String(query.page), size: String(query.size) });
    return '/api/admin/monitoring/logs?' + params;
}

export function parseOverview(value: unknown): Overview {
    if (!value || typeof value !== 'object' || Array.isArray(value)
        || !('requestsToday' in value) || !Array.isArray((value as Overview).traffic)
        || !Array.isArray((value as Overview).hotSearches)) throw new Error('Invalid monitoring overview');
    return value as Overview;
}

export function parseLogPage(value: unknown): LogPage {
    const page = value as LogPage;
    if (!page || !Array.isArray(page.records) || !Number.isSafeInteger(page.total) || page.total < 0
        || !Number.isInteger(page.page) || page.page < 1 || !Number.isInteger(page.size) || page.size < 1
        || page.records.some(row => !row || typeof row !== 'object' || row.id == null)) {
        throw new Error('Invalid monitoring log page');
    }
    return page;
}

// DATETIME and array timestamps represent Beijing time; explicit offsets are respected.
export function monitoringDate(value: unknown): Date | null {
    if (value == null || value === '') return null;
    if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
    if (Array.isArray(value)) {
        if (value.length < 3 || !value.slice(0, 6).every(v => Number.isFinite(Number(v)))) return null;
        const [year, month, day, hour = 0, minute = 0, second = 0] = value.map(Number);
        value = String(year).padStart(4, '0') + '-' + String(month).padStart(2, '0') + '-'
            + String(day).padStart(2, '0') + 'T' + String(hour).padStart(2, '0') + ':'
            + String(minute).padStart(2, '0') + ':' + String(second).padStart(2, '0') + '+08:00';
    }
    if (typeof value === 'string') {
        value = value.trim().replace(' ', 'T');
        if (/^\d{4}-\d{2}-\d{2}$/.test(value as string)) value += 'T00:00:00+08:00';
        else if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/.test(value as string)) value += '+08:00';
    }
    if (typeof value !== 'string' && typeof value !== 'number') return null;
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? null : date;
}

export function formatMonitoringTime(value: unknown, timeOnly = false): string {
    const date = monitoringDate(value);
    if (!date) return '—';
    return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai',
        ...(!timeOnly ? { year: 'numeric' as const, month: '2-digit' as const, day: '2-digit' as const } : {}),
        hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(date);
}

function dayKey(value: unknown): string | null {
    const date = monitoringDate(value);
    if (!date) return null;
    return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(date);
}

export function trafficSeries(points: TrafficPoint[], days: 7 | 14, date?: string) {
    const byDay = new Map(points.map(point => [dayKey(point.day), point]));
    const end = dayKey(date) ?? [...byDay.keys()].filter((key): key is string => key !== null).sort().at(-1) ?? dayKey(new Date())!;
    const endUtc = new Date(end + 'T00:00:00Z').getTime();
    return Array.from({ length: days }, (_, index) => {
        const day = new Date(endUtc - (days - index - 1) * 86400000).toISOString().slice(0, 10);
        const point = byDay.get(day);
        return { day, requests: count(point?.requests), visitors: count(point?.visitors),
            pageViews: count(point?.pageViews), serverErrors: count(point?.serverErrors) };
    });
}

const SUCCESS = new Set(['SUCCESS', 'SUCCEEDED', 'OK', 'POSTED', 'SAVED', 'NORMAL', 'DONE', 'ACTIVE', 'PUBLISHED', 'LIBRARY_RESOURCE', 'RESOURCE_SELECTION']);
const FAILURE = new Set(['FAILED', 'FAIL', 'ERROR', 'REJECTED', 'DENIED', 'TIMEOUT', 'INVALID', 'NOT_FOUND', 'NO_RESULT', 'NO_METADATA', 'NO_RESOURCE', 'INVALID_RESOURCE', 'TRANSFER_FAILED', 'EXPIRED', 'RATE_LIMITED', 'UNSUPPORTED_PROVIDER', 'PREPARE_FAILED']);
const PENDING = new Set(['PENDING', 'PROCESSING', 'RUNNING', 'PREPARING', 'PUBLISHING']);
export function statusColor(value: unknown, type?: LogKey): string {
    const status = String(value ?? '').trim().toUpperCase();
    if (type === 'socialPosts' && status === 'FAILED') return 'orange';
    if (SUCCESS.has(status)) return 'green';
    if (FAILURE.has(status)) return 'red';
    if (PENDING.has(status)) return 'processing';
    return status ? 'orange' : 'default';
}
