'use client';

import { useState } from 'react';
import type { ReactNode } from 'react';
import { Alert, Button, Card, Empty, Input, Pagination, Segmented, Select, Skeleton, Table, Tabs, Tag, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ClockCircleOutlined, SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { count, formatMonitoringTime, LOG_KEYS, statusColor } from '@/lib/monitoring';
import type { LogKey, LogPage, LogQuery, LogRow, Numeric, StatusFilter } from '@/lib/monitoring';

const FIELDS: Record<LogKey, string[]> = {
    access: ['created_at', 'method', 'request_path', 'status_code', 'duration_ms', 'event_type'],
    searches: ['created_at', 'keyword', 'source', 'result_count', 'id'],
    resourceOperations: ['created_at', 'movie_id', 'resource_link_id', 'operation_type', 'provider', 'status', 'error_message'],
    qqSearches: ['created_at', 'keyword', 'status', 'movie_id', 'resource_count', 'failure_reason'],
    socialPosts: ['created_at', 'platform', 'title', 'status', 'posted_at', 'error_message'],
};
const TITLES: Record<LogKey, string> = { access: 'request_path', searches: 'keyword', resourceOperations: 'operation_type', qqSearches: 'keyword', socialPosts: 'title' };
const isError = (field: string) => field === 'error_message' || field === 'failure_reason';
const isLong = (field: string) => ['request_path', 'keyword', 'title'].includes(field) || isError(field);

export function MonitoringLogs({ query, onQuery, data, loading, error, updatedAt, onRetry }: {
    query: LogQuery; onQuery: (query: LogQuery) => void; data: LogPage | null;
    loading: boolean; error: boolean; updatedAt: Date | null; onRetry: () => void;
}) {
    const { t } = useTranslation();
    const [draft, setDraft] = useState({ type: query.type, committed: query.q, value: query.q });
    // Shortcuts and tab changes update the input immediately, without triggering a request on each keystroke.
    const searchText = draft.type === query.type && draft.committed === query.q ? draft.value : query.q;
    const changeSearch = (value: string) => {
        setDraft({ type: query.type, committed: query.q, value });
        if (!value && query.q) onQuery({ ...query, q: '', page: 1 });
    };
    const submitSearch = (value: string) => {
        const q = value.trim();
        setDraft({ type: query.type, committed: q, value: q });
        onQuery({ ...query, q, page: 1 });
    };
    const reset = () => { setDraft({ type: query.type, committed: '', value: '' }); onQuery({ ...query, q: '', status: 'all', page: 1 }); };
    const filterOptions = [
        { label: t('monitoring.all'), value: 'all' },
        { label: t(`monitoring.${query.type === 'searches' ? 'hasResults' : query.type === 'access' ? 'normalFilter' : 'success'}`), value: 'success' },
        { label: t(`monitoring.${query.type === 'searches' ? 'noResults' : query.type === 'access' ? 'errorFilter' : 'failure'}`), value: 'failure' },
        ...(query.type === 'socialPosts' ? [{ label: t('monitoring.pendingFilter'), value: 'pending' }, { label: t('monitoring.attentionFilter'), value: 'attention' }] : []),
    ];
    const filter = (status: StatusFilter) => onQuery({ ...query, status, page: 1 });
    const label = (field: string) => t(`monitoring.columns.${field}`);
    const render = (field: string, row: LogRow): ReactNode => {
        const value = row[field];
        if (value == null || value === '') return <span className="text-slate-400">—</span>;
        const text = String(value);
        if (field === 'created_at' || field === 'posted_at') return <span className="whitespace-nowrap text-xs tabular-nums">{formatMonitoringTime(value)}</span>;
        if (field === 'status') return <Tag className="!m-0 !whitespace-normal !break-words" color={statusColor(value, query.type)}>{t(`monitoring.statuses.${text.toUpperCase()}`, { defaultValue: text })}</Tag>;
        if (field === 'status_code') return <Tag className="!m-0" color={Number(value) >= 500 ? 'red' : Number(value) >= 400 ? 'orange' : Number(value) >= 200 ? 'green' : 'default'}>{text}</Tag>;
        if (field === 'duration_ms') return <span className={`tabular-nums ${Number(value) >= 1000 ? 'text-rose-500' : ''}`}>{text} ms</span>;
        if (field === 'method') return <Tag className="!m-0" color={text === 'GET' ? 'blue' : 'cyan'}>{text}</Tag>;
        if (['provider', 'source', 'platform', 'operation_type', 'event_type'].includes(field)) {
            const key = field === 'event_type' && text === 'VIEW' ? 'PAGE_VIEW' : text.toUpperCase();
            return <span>{t(`monitoring.labels.${key}`, { defaultValue: text })}</span>;
        }
        if (field === 'result_count' || field === 'resource_count') return <span className="tabular-nums">{count(value as Numeric).toLocaleString()}</span>;
        if (isLong(field)) return <Tooltip title={text}><span className={`block max-w-sm truncate ${isError(field) ? 'text-rose-500' : field === 'request_path' ? 'font-mono text-xs' : ''}`}>{text}</span></Tooltip>;
        return <span className="break-all">{text}</span>;
    };
    const columns: ColumnsType<LogRow> = FIELDS[query.type].map(field => ({ title: label(field), key: field,
        dataIndex: field, width: isLong(field) ? undefined : field.endsWith('_at') ? 172 : 115,
        render: (_value, row) => render(field, row) }));
    const filtered = Boolean(query.q || query.status !== 'all');
    const empty = <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t(filtered ? 'monitoring.noMatches' : 'monitoring.empty')}>
        {filtered && <Button onClick={reset}>{t('monitoring.reset')}</Button>}
    </Empty>;
    const records = data?.records ?? [];
    const content = <div aria-busy={loading} data-log-stream={query.type}>
        <p className="mb-4 text-xs leading-5 text-slate-500 dark:text-zinc-400">{t(`monitoring.logs.${query.type}.description`)}</p>
        <div className="mb-4 flex min-w-0 flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
            <Input.Search value={searchText} onChange={event => changeSearch(event.target.value)} onSearch={submitSearch}
                maxLength={100} allowClear placeholder={t(`monitoring.logs.${query.type}.hint`)} aria-label={t('monitoring.search')}
                enterButton={<SearchOutlined aria-label={t('monitoring.search')} />} size="large" className="w-full lg:max-w-md [&_.ant-input-affix-wrapper]:!min-h-11 [&_.ant-input-search-button]:!min-h-11" />
            <div className="hidden shrink-0 md:block" data-status-filter>
                <Segmented value={query.status} onChange={value => filter(value as StatusFilter)} options={filterOptions} />
            </div>
            <Select className="!h-11 md:!hidden" size="large" aria-label={t('monitoring.filter')} value={query.status} options={filterOptions} onChange={filter} />
        </div>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2 text-xs text-slate-500 dark:text-zinc-400">
            <div className="flex min-w-0 flex-wrap items-center gap-2">
                <span data-log-total>{data ? t('monitoring.resultTotal', { count: data.total }) : '—'}</span>
                {query.q && <Tag closable className="!m-0 max-w-full !whitespace-normal !break-all" onClose={() => submitSearch('')}>{t('monitoring.keyword', { keyword: query.q })}</Tag>}
            </div>
            {updatedAt && <span data-log-updated><ClockCircleOutlined /> {t('monitoring.logsUpdatedAt', { time: formatMonitoringTime(updatedAt, true) })}</span>}
        </div>
        {error && <Alert className="!mb-4" type="error" showIcon title={t('monitoring.logsError')}
            description={t(data ? 'monitoring.staleHint' : 'monitoring.loadHint')} action={<Button aria-label={t('monitoring.retry')} onClick={onRetry}>{t('monitoring.retry')}</Button>} />}
        {loading && !data ? <Skeleton active paragraph={{ rows: 5 }} /> : data && <>
            <div className="hidden min-w-0 md:block">
                <Table<LogRow> size="middle" rowKey={row => `${query.type}:${row.id}`} loading={loading} dataSource={records}
                    columns={columns} scroll={{ x: 920 }} locale={{ emptyText: empty }} pagination={false} />
            </div>
            <div className="space-y-3 md:hidden" data-mobile-logs>{records.length ? records.map(row => {
                const titleField = TITLES[query.type];
                return <article key={`${query.type}:${row.id}`} data-log-card className="min-w-0 rounded-xl border border-slate-200 p-3 dark:border-zinc-800">
                    <div className="mb-2 flex flex-wrap items-center justify-between gap-2 text-xs text-slate-400">
                        <span>{render('created_at', row)}</span><span>#{String(row.id)}</span>
                    </div>
                    <div className="mb-3 flex flex-wrap items-start justify-between gap-2">
                        <span className={`min-w-0 flex-1 break-all font-medium ${query.type === 'access' ? 'font-mono text-xs leading-5' : 'text-sm'}`}>
                            {titleField === 'operation_type' ? render(titleField, row) : String(row[titleField] || '—')}
                        </span>
                        {row.status != null ? render('status', row) : row.status_code != null ? render('status_code', row) : null}
                    </div>
                    <dl className="grid grid-cols-2 gap-x-3 gap-y-3 text-xs">{FIELDS[query.type].filter(field => ![titleField, 'created_at', 'id', 'status', 'status_code'].includes(field) && !isError(field)).map(field =>
                        <div key={field} className="min-w-0"><dt className="mb-1 text-[11px] text-slate-400 dark:text-zinc-500">{label(field)}</dt><dd className="break-all">{render(field, row)}</dd></div>
                    )}</dl>
                    {['error_message', 'failure_reason'].map(field => row[field] ? <details key={field} className="mt-3 border-t border-slate-100 pt-2 dark:border-zinc-800">
                        <summary className="cursor-pointer py-1 text-xs text-rose-500">{t('monitoring.errorDetail')}</summary>
                        <p className="mt-2 text-xs leading-5 whitespace-pre-wrap break-all text-rose-500">{String(row[field])}</p>
                    </details> : null)}
                </article>;
            }) : empty}</div>
        </>}
        {data && data.total > 0 && <div className="mt-5 flex flex-wrap items-center justify-between gap-3" data-log-pagination>
            <span className="text-xs text-slate-400 dark:text-zinc-500">{t('monitoring.pageSummary', { page: data.page, count: records.length })}</span>
            <div className="flex max-w-full flex-wrap items-center gap-2">
                <Select aria-label={t('monitoring.recordsPerPage', { count: query.size })} value={query.size} disabled={loading}
                    options={[20, 50, 100].map(size => ({ value: size, label: t('monitoring.recordsPerPage', { count: size }) }))}
                    onChange={size => onQuery({ ...query, size, page: 1 })} />
                <Pagination current={data.page} total={data.total} pageSize={query.size} showSizeChanger={false} showLessItems responsive
                    disabled={loading} onChange={page => onQuery({ ...query, page })} />
            </div>
        </div>}
    </div>;
    return <Card id="monitoring-logs" className="min-w-0 scroll-mt-20 !rounded-2xl" styles={{ body: { padding: 20 } }}>
        <h2 className="text-base font-semibold">{t('monitoring.logsTitle')}</h2>
        <p className="mt-1 mb-2 text-xs leading-5 text-slate-400 dark:text-zinc-500">{t('monitoring.logsSubtitle')}</p>
        <Tabs activeKey={query.type} onChange={type => onQuery({ type: type as LogKey, q: '', status: 'all', page: 1, size: query.size })}
            items={LOG_KEYS.map(key => ({ key, label: t(`monitoring.logs.${key}.title`), children: key === query.type ? content : null }))} />
    </Card>;
}
