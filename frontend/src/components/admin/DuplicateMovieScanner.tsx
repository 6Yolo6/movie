'use client';

import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Space, Table, Tag, Typography } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { api } from '@/lib/api';
import { useAuthStore } from '@/store/authStore';

interface Candidate {
    fromMovieId: string; fromTitle: string; fromResources: number;
    toMovieId: string; toTitle: string; toResources: number;
    category: 'mv' | 'tv' | 'ac'; year: number; season: number;
    evidence: 'NAME_AND_PEOPLE' | 'NAME_ONLY'; reviewRequired: boolean;
}
interface Scan {
    scanMode: 'FULL_LIBRARY'; dryRun: true; mergeEnabled: false; complete: boolean;
    scannedMovies: number; candidates: number; conflicts: number; excludedMovies: number;
    page: number; pageSize: number; items: Candidate[];
}
const count = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0;
function validScan(value: unknown): value is Scan {
    if (!value || typeof value !== 'object') return false;
    const scan = value as Scan;
    return scan.scanMode === 'FULL_LIBRARY' && scan.dryRun === true && scan.mergeEnabled === false
        && typeof scan.complete === 'boolean' && count(scan.scannedMovies) && count(scan.candidates)
        && count(scan.conflicts) && count(scan.excludedMovies) && count(scan.page) && scan.page >= 1
        && count(scan.pageSize) && scan.pageSize >= 1 && scan.pageSize <= 200
        && Array.isArray(scan.items) && scan.items.length <= scan.pageSize
        && scan.items.every(item => item && typeof item.fromMovieId === 'string' && typeof item.toMovieId === 'string'
            && typeof item.fromTitle === 'string' && typeof item.toTitle === 'string'
            && count(item.fromResources) && count(item.toResources) && count(item.year) && count(item.season)
            && ['mv', 'tv', 'ac'].includes(item.category) && ['NAME_AND_PEOPLE', 'NAME_ONLY'].includes(item.evidence)
            && item.reviewRequired === true);
}

export default function DuplicateMovieScanner() {
    const { t } = useTranslation();
    const { token } = useAuthStore();
    const [result, setResult] = useState<Scan | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(false);
    const busy = useRef(false);
    const active = useRef<AbortController | null>(null);
    const generation = useRef(0);
    useEffect(() => () => { generation.current++; active.current?.abort(); busy.current = false; }, []);

    const scan = async (page = 1) => {
        if (busy.current) return;
        busy.current = true;
        const current = ++generation.current;
        const request = new AbortController();
        active.current = request;
        setLoading(true); setError(false);
        try {
            const response = await api(`/api/admin/resource-hub/cleanup/duplicate-tmdb?dryRun=true&limit=100&page=${page}`,
                { method: 'POST', signal: request.signal, headers: token ? { Authorization: `Bearer ${token}` } : undefined });
            if (!response.ok) throw new Error('scan failed');
            const body = await response.json();
            if (!validScan(body.data)) throw new Error('invalid scan');
            if (generation.current === current && !request.signal.aborted) setResult(body.data);
        } catch {
            if (generation.current === current && !request.signal.aborted) setError(true);
        } finally {
            if (generation.current === current && !request.signal.aborted) { busy.current = false; setLoading(false); }
        }
    };
    const columns: ColumnsType<Candidate> = [
        { title: t('resourceHubDuplicates.records'), key: 'records', width: 280, render: (_, row) =>
            <Space orientation="vertical" size={4}>
                <Typography.Text>{row.fromTitle} <Typography.Text type="secondary">({row.fromMovieId})</Typography.Text></Typography.Text>
                <Typography.Text>{row.toTitle} <Typography.Text type="secondary">({row.toMovieId})</Typography.Text></Typography.Text>
            </Space> },
        { title: t('resourceHubDuplicates.identity'), key: 'identity', width: 140, render: (_, row) =>
            <Space orientation="vertical" size={4}><Tag>{t(`resourceHubDuplicates.categories.${row.category}`)}</Tag>
                <Typography.Text>{row.year}{row.season > 0 ? ` · ${t('resourceHubDuplicates.season', { season: row.season })}` : ''}</Typography.Text></Space> },
        { title: t('resourceHubDuplicates.resources'), key: 'resources', width: 130, render: (_, row) =>
            <Typography.Text>{row.fromResources} / {row.toResources}</Typography.Text> },
        { title: t('resourceHubDuplicates.evidence'), key: 'evidence', width: 180, render: (_, row) =>
            <Typography.Text>{t(row.evidence === 'NAME_AND_PEOPLE' ? 'resourceHubDuplicates.peopleMatch' : 'resourceHubDuplicates.nameMatch')}</Typography.Text> },
    ];
    return <Space orientation="vertical" className="w-full" size={12} data-testid="duplicate-scanner">
        <Button block icon={<SearchOutlined />} loading={loading} onClick={() => void scan()} data-testid="duplicate-scan">
            {t('resourceHubCleanupDryRun')}
        </Button>
        <Typography.Text type="secondary">{t('resourceHubDuplicates.help')}</Typography.Text>
        {error && <Alert type="error" showIcon title={t('resourceHubDuplicates.failed')} data-testid="duplicate-scan-error" />}
        {result && <>
            <Alert type={result.complete ? 'info' : 'warning'} showIcon data-testid="duplicate-scan-summary"
                title={t(result.complete ? 'resourceHubDuplicates.summary' : 'resourceHubDuplicates.partial',
                    { scanned: result.scannedMovies, count: result.candidates })}
                description={t('resourceHubDuplicates.excluded', { conflicts: result.conflicts, excluded: result.excludedMovies })} />
            <Table<Candidate> size="small" columns={columns} dataSource={result.items} loading={loading}
                rowKey={row => `${row.fromMovieId}:${row.toMovieId}`} scroll={{ x: 730 }} data-testid="duplicate-candidates"
                pagination={{ current: result.page, pageSize: result.pageSize, total: result.candidates, showSizeChanger: false,
                    onChange: page => void scan(page) }} />
        </>}
    </Space>;
}
