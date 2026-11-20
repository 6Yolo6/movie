'use client';

import { useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Card, Empty, Progress, Segmented, Skeleton, Tooltip } from 'antd';
import { ArrowRightOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { count, trafficSeries } from '@/lib/monitoring';
import type { Numeric, Overview, StatusFilter, TrafficPoint } from '@/lib/monitoring';

export function MetricCard({ title, value, icon, hint, loading, available, danger = false }: {
    title: string; value: Numeric; icon: ReactNode; hint: string; loading: boolean; available: boolean; danger?: boolean;
}) {
    return <Card data-metric-card className="h-full !rounded-2xl" styles={{ body: { padding: 16 } }}>
        <div className="flex items-start justify-between gap-2">
            <span className="min-w-0 text-xs font-medium text-slate-500 dark:text-zinc-400">{title}</span>
            <span aria-hidden className={`shrink-0 ${danger ? 'text-rose-500' : 'text-blue-500'}`}>{icon}</span>
        </div>
        <div className={`my-2 text-[28px] leading-9 font-semibold tracking-tight tabular-nums ${danger && count(value) > 0 ? 'text-rose-500' : 'text-slate-900 dark:text-zinc-100'}`} data-metric-value>
            {loading && !available ? <Skeleton.Input active size="small" style={{ width: 72, minWidth: 72 }} /> : available ? count(value).toLocaleString() : '—'}
        </div>
        <div className="text-[11px] leading-4 text-slate-400 dark:text-zinc-500">{hint}</div>
    </Card>;
}

export function TrafficChart({ points, date }: { points: TrafficPoint[]; date?: string }) {
    const { t } = useTranslation();
    const [metric, setMetric] = useState<'requests' | 'visitors' | 'pageViews'>('requests');
    const [days, setDays] = useState<7 | 14>(14);
    const series = useMemo(() => trafficSeries(points, days, date), [points, days, date]);
    const total = series.reduce((sum, point) => sum + point[metric], 0);
    const max = Math.max(0, ...series.map(point => point[metric]));
    const metricLabel = t(`monitoring.${metric === 'requests' ? 'metricRequests' : metric === 'visitors' ? 'metricVisitors' : 'metricViews'}`);
    return <div data-traffic-chart>
        <div className="mb-5 flex flex-wrap items-center justify-between gap-3" data-trend-metric>
            <Segmented size="small" value={metric} onChange={value => setMetric(value as typeof metric)} options={[
                { label: t('monitoring.metricRequests'), value: 'requests' },
                { label: t('monitoring.metricVisitors'), value: 'visitors' },
                { label: t('monitoring.metricViews'), value: 'pageViews' },
            ]} />
            <Segmented size="small" value={days} onChange={value => setDays(value as 7 | 14)} options={[7, 14].map(value => ({ label: t('monitoring.days', { count: value }), value }))} />
        </div>
        <div className="mb-4 flex flex-wrap items-end justify-between gap-2">
            <div><div className="text-xs text-slate-400 dark:text-zinc-500">{t('monitoring.periodTotal')} · {metricLabel}</div>
                <div className="mt-1 text-3xl font-semibold tracking-tight tabular-nums" data-trend-total>{total.toLocaleString()}</div></div>
            <span className="text-xs text-slate-500 dark:text-zinc-400">{t('monitoring.peak', { count: max })}</span>
        </div>
        {total === 0 ? <div className="flex h-44 items-center justify-center"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('monitoring.noTraffic')} /></div> :
            <div className="flex h-44 gap-3" role="group" aria-label={`${t('monitoring.trendTitle')} · ${metricLabel}`}>
                <div aria-hidden className="flex w-10 shrink-0 flex-col justify-between pb-6 text-right text-[10px] text-slate-400"><span>{max.toLocaleString()}</span><span>{Math.round(max / 2).toLocaleString()}</span><span>0</span></div>
                <div className="flex min-w-0 flex-1 gap-1.5 border-b border-slate-100 dark:border-zinc-800">
                    {series.map((point, index) => <Tooltip key={point.day} trigger={['hover', 'focus']} title={`${point.day} · ${metricLabel} ${point[metric]}`}>
                        <button type="button" data-trend-bar data-value={point[metric]} aria-label={`${point.day} · ${metricLabel} ${point[metric]}`}
                            className="group flex h-full min-w-0 flex-1 flex-col rounded-t focus-visible:outline-2 focus-visible:outline-blue-500">
                            <span className="flex w-full flex-1 items-end"><span className="block w-full rounded-t-sm bg-blue-500/80 transition-colors group-hover:bg-blue-600 dark:bg-blue-400/80" style={{ height: `${point[metric] / max * 100}%` }} /></span>
                            <span aria-hidden className="h-6 pt-2 text-[9px] leading-3 whitespace-nowrap text-slate-400">{index % 2 === 0 || index === series.length - 1 ? point.day.slice(5) : ''}</span>
                        </button>
                    </Tooltip>)}
                </div>
            </div>}
    </div>;
}

export function ResponseDistribution({ overview }: { overview: Overview }) {
    const { t } = useTranslation();
    const requests = count(overview.requestsToday), client = count(overview.clientErrorsToday), server = count(overview.serverErrorsToday);
    const segments = [
        { label: t('monitoring.normal'), value: Math.max(0, requests - client - server), color: '#22c55e' },
        { label: t('monitoring.clientErrors'), value: client, color: '#f59e0b' },
        { label: t('monitoring.responseErrors'), value: server, color: '#f43f5e' },
    ];
    const total = segments.reduce((sum, item) => sum + item.value, 0);
    if (!total) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('monitoring.noRequests')} />;
    return <div className="space-y-5" data-response-distribution>
        <div className="flex items-baseline gap-2"><span className="text-3xl font-semibold tabular-nums">{(segments[0].value / total * 100).toFixed(1)}%</span><span className="text-xs text-slate-500 dark:text-zinc-400">{t('monitoring.normal')}</span></div>
        <div className="flex h-2.5 overflow-hidden rounded-full bg-slate-100 dark:bg-zinc-800" aria-hidden>
            {segments.map(item => <div key={item.label} style={{ width: `${item.value / total * 100}%`, backgroundColor: item.color }} />)}
        </div>
        <div className="space-y-3">{segments.map(item => <div key={item.label} className="flex flex-wrap items-center justify-between gap-2 text-xs">
            <span className="flex items-center gap-2"><span aria-hidden className="h-2 w-2 rounded-full" style={{ backgroundColor: item.color }} />{item.label}</span>
            <span className="tabular-nums text-slate-500 dark:text-zinc-400">{item.value.toLocaleString()} · {(item.value / total * 100).toFixed(1)}%</span>
        </div>)}</div>
        <p className="text-[11px] leading-5 text-slate-400 dark:text-zinc-500">{t('monitoring.responseHint')}</p>
    </div>;
}

export function HotSearchList({ items, onSearch }: { items: NonNullable<Overview['hotSearches']>; onSearch: (keyword: string) => void }) {
    const { t } = useTranslation();
    const max = Math.max(1, ...items.map(item => count(item.count)));
    if (!items.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('monitoring.noSearches')} />;
    return <div className="grid gap-x-6 gap-y-1 sm:grid-cols-2" data-hot-searches>
        {items.slice(0, 10).map((item, index) => <button type="button" key={`${item.keyword}-${index}`} onClick={() => item.keyword && onSearch(item.keyword)}
            className="group min-w-0 rounded-lg px-2 py-3 text-left transition-colors hover:bg-slate-50 focus-visible:outline-2 focus-visible:outline-blue-500 dark:hover:bg-zinc-800/60">
            <span className="mb-2 flex items-center gap-2 text-sm">
                <span className={`flex h-5 w-5 shrink-0 items-center justify-center rounded text-[11px] font-semibold ${index < 3 ? 'bg-blue-50 text-blue-600 dark:bg-blue-500/15 dark:text-blue-400' : 'text-slate-400'}`}>{index + 1}</span>
                <span className="min-w-0 flex-1 truncate" title={item.keyword}>{item.keyword || '—'}</span>
                <span className="shrink-0 text-xs tabular-nums text-slate-500 dark:text-zinc-400">{count(item.count)}</span>
                <ArrowRightOutlined aria-hidden className="text-[10px] text-slate-300 group-hover:text-blue-500" />
            </span>
            <span className="ml-7 block h-1 overflow-hidden rounded-full bg-slate-100 dark:bg-zinc-800" aria-hidden><span className="block h-full rounded-full bg-blue-400/80" style={{ width: `${count(item.count) / max * 100}%` }} /></span>
        </button>)}
    </div>;
}

export function SocialSummary({ overview, onFilter }: { overview: Overview; onFilter: (status: StatusFilter) => void }) {
    const { t } = useTranslation();
    const posted = count(overview.socialPostedToday), failed = count(overview.socialFailedToday);
    const pending = count(overview.socialPendingToday), unknown = count(overview.socialUnknownToday);
    const terminal = posted + failed;
    const states: { label: string; value: number; color: string; filter: StatusFilter }[] = [
        { label: t('monitoring.posted'), value: posted, color: 'text-emerald-600 dark:text-emerald-400', filter: 'success' },
        { label: t('monitoring.failed'), value: failed, color: 'text-rose-500', filter: 'failure' },
        { label: t('monitoring.pending'), value: pending, color: 'text-blue-500', filter: 'pending' },
        { label: t('monitoring.unknown'), value: unknown, color: 'text-amber-600 dark:text-amber-400', filter: 'attention' },
    ];
    return <div data-social-summary>
        <div className="grid grid-cols-2 gap-2">{states.map(state => <button key={state.filter} type="button" data-social-status={state.filter} onClick={() => onFilter(state.filter)}
            className="min-w-0 rounded-xl bg-slate-50 p-3 text-left transition-colors hover:bg-slate-100 focus-visible:outline-2 focus-visible:outline-blue-500 dark:bg-zinc-800/50 dark:hover:bg-zinc-800">
            <span className="block text-[11px] text-slate-500 dark:text-zinc-400">{state.label}</span><span className={`mt-1 block text-2xl font-semibold tabular-nums ${state.color}`}>{state.value.toLocaleString()}</span>
        </button>)}</div>
        <div className="mt-4 flex justify-between gap-2 text-xs text-slate-500 dark:text-zinc-400"><span>{t('monitoring.successRate')}</span><span data-social-rate>{terminal ? `${(posted / terminal * 100).toFixed(1)}%` : '—'}</span></div>
        {terminal > 0 && <Progress percent={Math.round(posted / terminal * 100)} showInfo={false} strokeColor="#22c55e" />}
        <p className="mt-2 text-[11px] leading-5 text-slate-400 dark:text-zinc-500">{posted + failed + pending + unknown ? t('monitoring.socialHint') : t('monitoring.noPosts')}</p>
        {unknown > 0 && <p className="mt-2 text-xs text-amber-600 dark:text-amber-400">{t('monitoring.unknownHint')}</p>}
    </div>;
}
