'use client';

import { useEffect, useState } from 'react';
import { Alert, Button, Card, Skeleton, Switch, Tag, Tooltip } from 'antd';
import { ApiOutlined, CloudUploadOutlined, EyeOutlined, LineChartOutlined, ReloadOutlined, SearchOutlined, TeamOutlined, WarningOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { MetricCard, TrafficChart, HotSearchList, ResponseDistribution, SocialSummary } from '@/components/admin/MonitoringCharts';
import { MonitoringLogs } from '@/components/admin/MonitoringLogs';
import { useMonitoringData } from '@/hooks/useMonitoringData';
import { formatMonitoringTime, logUrl, parseLogPage, parseOverview } from '@/lib/monitoring';
import type { LogKey, LogQuery, StatusFilter } from '@/lib/monitoring';

export default function MonitoringPage() {
    const { t } = useTranslation();
    const [query, setQuery] = useState<LogQuery>({ type: 'access', q: '', status: 'all', page: 1, size: 20 });
    const [overviewRefresh, setOverviewRefresh] = useState(0);
    const [logsRefresh, setLogsRefresh] = useState(0);
    const [autoRefresh, setAutoRefresh] = useState(false);
    const overview = useMonitoringData('/api/admin/monitoring/overview', overviewRefresh, parseOverview);
    const logs = useMonitoringData(logUrl(query), logsRefresh, parseLogPage);
    const busy = overview.loading || logs.loading;
    const refreshAll = () => { setOverviewRefresh(value => value + 1); setLogsRefresh(value => value + 1); };

    useEffect(() => {
        if (!autoRefresh || busy) return;
        const refresh = () => {
            if (document.visibilityState !== 'visible') return;
            setOverviewRefresh(value => value + 1);
            setLogsRefresh(value => value + 1);
        };
        const timer = window.setInterval(refresh, 30000);
        document.addEventListener('visibilitychange', refresh);
        return () => { window.clearInterval(timer); document.removeEventListener('visibilitychange', refresh); };
    }, [autoRefresh, busy]);

    const showLogs = (type: LogKey, status: StatusFilter = 'all', q = '') => {
        setQuery(previous => ({ ...previous, type, q, status, page: 1 }));
        document.getElementById('monitoring-logs')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    };
    const data = overview.data;
    const metrics = [
        { title: 'pageViews', value: data?.pageViewsToday, hint: 'pageViewsHint', icon: <EyeOutlined /> },
        { title: 'visitors', value: data?.visitorsToday, hint: 'visitorsHint', icon: <TeamOutlined /> },
        { title: 'requests', value: data?.requestsToday, hint: 'requestsHint', icon: <ApiOutlined /> },
        { title: 'searches', value: data?.searchesToday, hint: 'searchesHint', icon: <SearchOutlined /> },
        { title: 'operations', value: data?.resourceOperationsToday, hint: 'operationsHint', icon: <CloudUploadOutlined /> },
        { title: 'serverErrors', value: data?.serverErrorsToday, hint: 'errorsHint', icon: <WarningOutlined />, danger: true },
    ];
    const placeholder = overview.loading ? <Skeleton active paragraph={{ rows: 4 }} /> : <div className="py-8 text-center text-sm text-slate-400">{t('monitoring.notUpdated')}</div>;
    return <main className="mx-auto min-w-0 max-w-[1440px] space-y-5 px-3 py-5 sm:px-6 lg:px-8 lg:py-8" data-monitoring-page>
        <header className="flex flex-col justify-between gap-5 xl:flex-row xl:items-end">
            <div className="min-w-0">
                <div className="mb-2 flex flex-wrap items-center gap-2 text-[11px] font-semibold tracking-widest text-blue-600 dark:text-blue-400"><LineChartOutlined /> {t('monitoring.eyebrow')}</div>
                <h1 className="text-2xl font-semibold tracking-tight text-slate-900 dark:text-zinc-100 sm:text-3xl">{t('monitoring.title')}</h1>
                <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-500 dark:text-zinc-400">{t('monitoring.subtitle')}</p>
                {data?.date && <p className="mt-1 text-xs text-slate-400 dark:text-zinc-500">{t('monitoring.today', { date: data.date })}</p>}
            </div>
            <div className="flex flex-col gap-3 xl:items-end">
                <div className="flex flex-wrap items-center gap-2 text-xs text-slate-500 dark:text-zinc-400">
                    <Tag className="!m-0" color={overview.error || logs.error ? 'orange' : busy ? 'processing' : 'default'}>{t(overview.error || logs.error ? 'monitoring.partial' : busy ? 'monitoring.refreshing' : 'monitoring.live')}</Tag>
                    <span data-overview-updated>{overview.updatedAt ? t('monitoring.updatedAt', { time: formatMonitoringTime(overview.updatedAt, true) }) : t('monitoring.notUpdated')}</span>
                </div>
                <div className="flex flex-wrap items-center gap-x-4 gap-y-3">
                    <Tooltip title={t('monitoring.autoHint')}><label className="flex cursor-pointer items-center gap-2 text-xs text-slate-500 dark:text-zinc-400">
                        <Switch checked={autoRefresh} onChange={setAutoRefresh} aria-label={t('monitoring.autoRefresh')} /><span>{t('monitoring.autoRefresh')}</span>
                    </label></Tooltip>
                    <Button size="large" className="!min-h-11" aria-label={t('monitoring.refresh')} icon={<ReloadOutlined />} loading={busy} onClick={refreshAll}>{t('monitoring.refresh')}</Button>
                </div>
            </div>
        </header>
        {overview.error && <Alert type="error" showIcon title={t('monitoring.overviewError')} description={t(data ? 'monitoring.staleHint' : 'monitoring.loadHint')}
            action={<Button aria-label={t('monitoring.retry')} onClick={() => setOverviewRefresh(value => value + 1)}>{t('monitoring.retry')}</Button>} />}
        <section className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6" aria-label={t('monitoring.eyebrow')}>
            {metrics.map(metric => <MetricCard key={metric.title} {...metric} title={t(`monitoring.${metric.title}`)} hint={t(`monitoring.${metric.hint}`)} loading={overview.loading} available={data !== null} />)}
        </section>
        <section className="grid min-w-0 grid-cols-1 gap-4 xl:grid-cols-3" aria-label={t('monitoring.trendTitle')}>
            <Card className="min-w-0 !rounded-2xl xl:col-span-2" styles={{ body: { padding: 20 } }}>
                <div className="mb-4 flex flex-wrap items-center justify-between gap-2"><h2 className="text-base font-semibold">{t('monitoring.trendTitle')}</h2><span className="text-[11px] text-slate-400">{t('monitoring.trendHint')}</span></div>
                {data ? <TrafficChart points={data.traffic ?? []} date={data.date} /> : placeholder}
            </Card>
            <Card className="min-w-0 !rounded-2xl" styles={{ body: { padding: 20 } }}><h2 className="mb-5 text-base font-semibold">{t('monitoring.responseTitle')}</h2>{data ? <ResponseDistribution overview={data} /> : placeholder}</Card>
            <Card className="min-w-0 !rounded-2xl xl:col-span-2" styles={{ body: { padding: 20 } }}>
                <h2 className="text-base font-semibold">{t('monitoring.hotTitle')}</h2><p className="mt-1 mb-3 text-[11px] text-slate-400">{t('monitoring.hotHint')}</p>
                {data ? <HotSearchList items={data.hotSearches ?? []} onSearch={keyword => showLogs('searches', 'all', keyword)} /> : placeholder}
            </Card>
            <Card className="min-w-0 !rounded-2xl" styles={{ body: { padding: 20 } }}><h2 className="mb-4 text-base font-semibold">{t('monitoring.socialTitle')}</h2>
                {data ? <SocialSummary overview={data} onFilter={status => showLogs('socialPosts', status)} /> : placeholder}
            </Card>
        </section>
        <MonitoringLogs query={query} onQuery={setQuery} {...logs} onRetry={() => setLogsRefresh(value => value + 1)} />
    </main>;
}
