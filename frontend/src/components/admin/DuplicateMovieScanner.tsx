'use client';

import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { MergeCellsOutlined, SearchOutlined } from '@ant-design/icons';
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
interface Pair { fromMovieId: string; toMovieId: string; }
interface ReviewItem extends Pair {
    fromTitle: string; toTitle: string; eligible: boolean;
    blockers: { code: string; reference: string; count: number }[];
    impact: { reference: string; fromCount: number; toCount: number; action: string }[];
}
interface Review { reviewId: string; expiresAt: string; canMerge: boolean; items: ReviewItem[]; }
interface MergeResult {
    reviewId: string; idempotencyKey: string; status: 'SUCCEEDED';
    merged: number; items: Pair[]; replayed: boolean;
}
interface BulkPlan {
    reviews: Review[]; rows: Candidate[];
    skipped: { row: Candidate; reason: string; blockers?: ReviewItem['blockers'] }[];
    scanned: number; candidates: number;
}
const count = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0;
const identifier = (value: unknown): value is string => typeof value === 'string' && value.length > 0 && value.length <= 128 && !/[\x00-\x1f]/.test(value);
const pairKey = (pair: Pair) => pair.fromMovieId + ':' + pair.toMovieId;
const samePairs = (actual: Pair[], expected: Pair[]) => actual.length === expected.length
    && actual.every((pair, index) => pair.fromMovieId === expected[index].fromMovieId && pair.toMovieId === expected[index].toMovieId);
function validScan(value: unknown): value is Scan {
    if (!value || typeof value !== 'object') return false;
    const scan = value as Scan;
    return scan.scanMode === 'FULL_LIBRARY' && scan.dryRun === true && scan.mergeEnabled === false
        && typeof scan.complete === 'boolean' && count(scan.scannedMovies) && count(scan.candidates)
        && count(scan.conflicts) && count(scan.excludedMovies) && count(scan.page) && scan.page >= 1
        && count(scan.pageSize) && scan.pageSize >= 1 && scan.pageSize <= 200
        && Array.isArray(scan.items) && scan.items.length <= scan.pageSize
        && scan.items.every(item => item && identifier(item.fromMovieId) && identifier(item.toMovieId)
            && item.fromMovieId !== item.toMovieId && typeof item.fromTitle === 'string' && typeof item.toTitle === 'string'
            && count(item.fromResources) && count(item.toResources) && count(item.year) && count(item.season)
            && ['mv', 'tv', 'ac'].includes(item.category) && ['NAME_AND_PEOPLE', 'NAME_ONLY'].includes(item.evidence)
            && item.reviewRequired === true);
}
function validReview(value: unknown, pairs: Pair[]): value is Review {
    if (!value || typeof value !== 'object') return false;
    const review = value as Review;
    return identifier(review.reviewId) && typeof review.expiresAt === 'string'
        && /(?:Z|[+-]\d{2}:\d{2})$/.test(review.expiresAt) && Number.isFinite(Date.parse(review.expiresAt))
        && typeof review.canMerge === 'boolean' && Array.isArray(review.items) && samePairs(review.items, pairs)
        && review.items.every(item => item && typeof item.fromTitle === 'string' && typeof item.toTitle === 'string'
            && typeof item.eligible === 'boolean' && Array.isArray(item.blockers) && Array.isArray(item.impact)
            && item.blockers.every(blocker => blocker && identifier(blocker.code) && identifier(blocker.reference) && count(blocker.count))
            && item.impact.every(impact => impact && identifier(impact.reference) && identifier(impact.action) && count(impact.fromCount) && count(impact.toCount))
            && (!item.eligible || item.blockers.length === 0))
        && review.canMerge === review.items.every(item => item.eligible && item.blockers.length === 0);
}
function validMerge(value: unknown, review: Review, key: string): value is MergeResult {
    if (!value || typeof value !== 'object') return false;
    const merge = value as MergeResult;
    return merge.reviewId === review.reviewId && merge.idempotencyKey === key && merge.status === 'SUCCEEDED'
        && count(merge.merged) && merge.merged === review.items.length && typeof merge.replayed === 'boolean'
        && Array.isArray(merge.items) && samePairs(merge.items, review.items);
}

export default function DuplicateMovieScanner() {
    const { t } = useTranslation();
    const { token } = useAuthStore();
    const [result, setResult] = useState<Scan | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [selected, setSelected] = useState<Record<string, Candidate>>({});
    const [retained, setRetained] = useState<Record<string, string>>({});
    const [review, setReview] = useState<Review | null>(null);
    const [confirmed, setConfirmed] = useState<Record<string, boolean>>({});
    const [expired, setExpired] = useState(false);
    const [success, setSuccess] = useState<number | null>(null);
    const [uncertain, setUncertain] = useState(false);
    const [bulkPlan, setBulkPlan] = useState<BulkPlan | null>(null);
    const [bulkConfirmed, setBulkConfirmed] = useState(false);
    const [bulkExpired, setBulkExpired] = useState(false);
    const [bulkStopped, setBulkStopped] = useState(false);
    const [bulkIndex, setBulkIndex] = useState(0);
    const [bulkMerged, setBulkMerged] = useState(0);
    const bulkPending = useRef<{ review: Review; key: string } | null>(null);
    const busy = useRef(false);
    const active = useRef<AbortController | null>(null);
    const generation = useRef(0);
    const mutationKey = useRef<string | null>(null);
    useEffect(() => () => { generation.current++; active.current?.abort(); busy.current = false; }, []);
    useEffect(() => {
        if (!review) return;
        const timer = window.setTimeout(() => setExpired(true), Math.max(0, Date.parse(review.expiresAt) - Date.now()));
        return () => window.clearTimeout(timer);
    }, [review]);

    useEffect(() => {
        if (!bulkPlan?.reviews.length) return;
        const expires = Math.min(...bulkPlan.reviews.map(item => Date.parse(item.expiresAt)));
        const timer = window.setTimeout(() => setBulkExpired(true), Math.max(0, expires - Date.now()));
        return () => window.clearTimeout(timer);
    }, [bulkPlan]);

    const invalidateReview = () => { setReview(null); setConfirmed({}); setExpired(false); setUncertain(false); mutationKey.current = null; };
    const pairs = Object.values(selected).map(row => retained[pairKey(row)] === row.fromMovieId
        ? { fromMovieId: row.toMovieId, toMovieId: row.fromMovieId }
        : { fromMovieId: row.fromMovieId, toMovieId: row.toMovieId });
    const repeatedId = new Set(pairs.flatMap(pair => [pair.fromMovieId, pair.toMovieId])).size !== pairs.length * 2;
    const selectionValid = pairs.length > 0 && pairs.length <= 20 && !repeatedId;
    const headers = token ? { Authorization: 'Bearer ' + token } : undefined;
    const begin = () => {
        busy.current = true; setLoading(true); setError(null);
        const current = ++generation.current, request = new AbortController();
        active.current = request; return { current, request };
    };
    const isCurrent = (current: number, request: AbortController) => generation.current === current && !request.signal.aborted;
    const finish = (current: number, request: AbortController) => {
        if (isCurrent(current, request)) { busy.current = false; setLoading(false); }
    };
    const scan = async (page = 1, reset = false, afterMerge = false) => {
        if (busy.current || (uncertain && !afterMerge)) return;
        if (reset) { setSelected({}); setRetained({}); invalidateReview(); }
        const { current, request } = begin();
        try {
            const response = await api('/api/admin/resource-hub/cleanup/duplicate-tmdb?dryRun=true&limit=100&page=' + page,
                { method: 'POST', signal: request.signal, headers });
            if (!response.ok) throw new Error('scan failed');
            const body = await response.json();
            if (!validScan(body.data)) throw new Error('invalid scan');
            if (isCurrent(current, request)) setResult(body.data);
        } catch {
            if (isCurrent(current, request)) setError('failed');
        } finally { finish(current, request); }
    };
    const select = (row: Candidate, checked: boolean) => {
        if (busy.current || uncertain || !result?.complete) return;
        invalidateReview(); setError(null);
        setSelected(previous => {
            const next = { ...previous };
            if (checked) next[pairKey(row)] = row; else delete next[pairKey(row)];
            return next;
        });
    };
    const choose = (row: Candidate, id: string) => {
        if (busy.current || uncertain || ![row.fromMovieId, row.toMovieId].includes(id)) return;
        invalidateReview(); setError(null); setRetained(previous => ({ ...previous, [pairKey(row)]: id }));
    };
    const preview = async () => {
        if (busy.current || uncertain || !selectionValid || !result?.complete) return;
        invalidateReview();
        const requested = pairs;
        const { current, request } = begin();
        try {
            const response = await api('/api/admin/resource-hub/duplicates/preview',
                { method: 'POST', signal: request.signal, headers, body: JSON.stringify({ pairs: requested }) });
            if (!response.ok) throw new Error('preview failed');
            const body = await response.json();
            if (!validReview(body.data, requested)) throw new Error('invalid review');
            if (isCurrent(current, request)) { setReview(body.data); setExpired(Date.parse(body.data.expiresAt) <= Date.now()); }
        } catch {
            if (isCurrent(current, request)) setError('previewFailed');
        } finally { finish(current, request); }
    };
    const allReviewed = !!review && review.items.every(item => confirmed[pairKey(item)] === true);
    const merge = async () => {
        if (busy.current || !review?.canMerge || !allReviewed) return;
        if (!uncertain && (expired || Date.parse(review.expiresAt) <= Date.now())) { setExpired(true); setError('expired'); return; }
        const reviewed = review;
        const key = mutationKey.current || crypto.randomUUID(); mutationKey.current = key;
        const { current, request } = begin();
        let rescan = false;
        try {
            const response = await api('/api/admin/resource-hub/duplicates/merge', {
                method: 'POST', signal: request.signal, headers,
                body: JSON.stringify({ reviewId: reviewed.reviewId, idempotencyKey: key, confirmed: true }),
            });
            if (!isCurrent(current, request)) return;
            if ([400, 403, 409].includes(response.status)) { invalidateReview(); setError('reviewChanged'); return; }
            if (!response.ok) throw new Error('merge failed');
            const body = await response.json();
            if (!validMerge(body.data, reviewed, key)) throw new Error('invalid merge');
            if (isCurrent(current, request)) {
                setSuccess(body.data.merged); setSelected({}); setRetained({}); invalidateReview(); rescan = true;
            }
        } catch {
            if (isCurrent(current, request)) { setUncertain(true); setError('mergeUncertain'); }
        } finally { finish(current, request); }
        if (rescan) void scan(1, false, true);
    };
    const closeBulk = () => {
        if (busy.current || uncertain) return;
        setBulkPlan(null); setBulkConfirmed(false); setBulkExpired(false); setBulkStopped(false);
        setBulkIndex(0); setBulkMerged(0); bulkPending.current = null;
        if (bulkMerged > 0) { setSuccess(bulkMerged); void scan(1, false, true); }
    };
    const prepareBulk = async () => {
        if (busy.current || uncertain) return;
        invalidateReview(); setBulkPlan(null); setBulkConfirmed(false); setBulkExpired(false);
        setBulkStopped(false); setBulkIndex(0); setBulkMerged(0); setSuccess(null); bulkPending.current = null;
        const { current, request } = begin();
        try {
            const readPage = async (page: number): Promise<Scan> => {
                const response = await api('/api/admin/resource-hub/cleanup/duplicate-tmdb?dryRun=true&limit=100&page=' + page,
                    { method: 'POST', signal: request.signal, headers });
                if (!response.ok) throw new Error('failed');
                const body = await response.json();
                if (!validScan(body.data)) throw new Error('failed');
                if (!body.data.complete) throw new Error('bulkIncomplete');
                if (body.data.page !== page) throw new Error('bulkChanged');
                return body.data;
            };
            const first = await readPage(1);
            if (!isCurrent(current, request)) return;
            setResult(first); setSelected({}); setRetained({});
            const rows = [...first.items];
            for (let page = 2; page <= Math.ceil(first.candidates / first.pageSize); page++) {
                const next = await readPage(page);
                if (!isCurrent(current, request)) return;
                if (next.candidates !== first.candidates || next.scannedMovies !== first.scannedMovies
                    || next.pageSize !== first.pageSize || next.conflicts !== first.conflicts || next.excludedMovies !== first.excludedMovies)
                    throw new Error('bulkChanged');
                rows.push(...next.items);
            }
            if (rows.length !== first.candidates || new Set(rows.map(pairKey)).size !== rows.length) throw new Error('bulkChanged');
            const frequencies = new Map<string, number>();
            for (const row of rows) for (const id of [row.fromMovieId, row.toMovieId]) frequencies.set(id, (frequencies.get(id) || 0) + 1);
            const skipped: BulkPlan['skipped'] = [], proposed: Candidate[] = [], reviews: Review[] = [];
            for (const row of rows) {
                if (row.evidence !== 'NAME_AND_PEOPLE') skipped.push({ row, reason: 'bulkNameOnly' });
                else if (frequencies.get(row.fromMovieId)! > 1 || frequencies.get(row.toMovieId)! > 1) skipped.push({ row, reason: 'overlapping' });
                else proposed.push(row);
            }
            const readReview = async (requested: Pair[]): Promise<Review> => {
                const response = await api('/api/admin/resource-hub/duplicates/preview',
                    { method: 'POST', signal: request.signal, headers, body: JSON.stringify({ pairs: requested }) });
                if (!response.ok) throw new Error('previewFailed');
                const body = await response.json();
                if (!validReview(body.data, requested)) throw new Error('previewFailed');
                if (body.data.items.some((item: ReviewItem) => {
                    const row = proposed.find(row => pairKey(row) === pairKey(item));
                    return !row || row.fromTitle.trim() !== item.fromTitle.trim() || row.toTitle.trim() !== item.toTitle.trim();
                })) throw new Error('bulkChanged');
                return body.data;
            };
            for (let offset = 0; offset < proposed.length; offset += 20) {
                const batch = proposed.slice(offset, offset + 20);
                let checked = await readReview(batch.map(row => ({ fromMovieId: row.fromMovieId, toMovieId: row.toMovieId })));
                if (!isCurrent(current, request)) return;
                if (!checked.canMerge) {
                    checked.items.forEach((item, index) => { if (!item.eligible) skipped.push({ row: batch[index], reason: 'blocked', blockers: item.blockers }); });
                    const allowed = checked.items.filter(item => item.eligible);
                    if (!allowed.length) continue;
                    checked = await readReview(allowed.map(item => ({ fromMovieId: item.fromMovieId, toMovieId: item.toMovieId })));
                    if (!isCurrent(current, request)) return;
                    if (!checked.canMerge) throw new Error('bulkChanged');
                }
                reviews.push(checked);
            }
            if (isCurrent(current, request)) {
                const accepted = new Set(reviews.flatMap(review => review.items.map(pairKey)));
                setBulkPlan({ reviews, rows: proposed.filter(row => accepted.has(pairKey(row))), skipped,
                    scanned: first.scannedMovies, candidates: first.candidates });
            }
        } catch (failure) {
            if (isCurrent(current, request)) setError(failure instanceof Error && ['bulkIncomplete', 'bulkChanged', 'failed', 'previewFailed'].includes(failure.message)
                ? failure.message : 'previewFailed');
        } finally { finish(current, request); }
    };
    const mergeBulk = async () => {
        if (busy.current || !bulkPlan || !bulkConfirmed || bulkStopped || !bulkPlan.reviews.length) return;
        if (!uncertain && bulkExpired) { setError('expired'); return; }
        const { current, request } = begin();
        let index = bulkIndex, merged = bulkMerged, rescan = false;
        try {
            while (index < bulkPlan.reviews.length) {
                const reviewed = bulkPending.current?.review || bulkPlan.reviews[index];
                if (!bulkPending.current && Date.parse(reviewed.expiresAt) <= Date.now()) {
                    setBulkExpired(true); setError('expired'); return;
                }
                const key = bulkPending.current?.key || crypto.randomUUID();
                bulkPending.current = { review: reviewed, key };
                const response = await api('/api/admin/resource-hub/duplicates/merge', {
                    method: 'POST', signal: request.signal, headers,
                    body: JSON.stringify({ reviewId: reviewed.reviewId, idempotencyKey: key, confirmed: true }),
                });
                if (!isCurrent(current, request)) return;
                if ([400, 403, 409].includes(response.status)) {
                    bulkPending.current = null; setUncertain(false); setBulkStopped(true); setError('reviewChanged'); return;
                }
                if (!response.ok) throw new Error('merge failed');
                const body = await response.json();
                if (!validMerge(body.data, reviewed, key)) throw new Error('invalid merge');
                if (!isCurrent(current, request)) return;
                bulkPending.current = null; merged += body.data.merged; index++;
                setBulkIndex(index); setBulkMerged(merged); setUncertain(false);
            }
            setSuccess(merged); setBulkPlan(null); setBulkConfirmed(false); rescan = true;
        } catch {
            if (isCurrent(current, request)) { setUncertain(true); setError('mergeUncertain'); }
        } finally { finish(current, request); }
        if (rescan) void scan(1, false, true);
    };
    const referenceName = (reference: string) => t('resourceHubDuplicates.references.' + reference, { defaultValue: t('resourceHubDuplicates.otherReference') });
    const columns: ColumnsType<Candidate> = [
        { title: t('resourceHubDuplicates.records'), key: 'records', width: 280, render: (_, row) =>
            <Space orientation="vertical" size={4}>
                <Typography.Text>{row.fromTitle} <Typography.Text type="secondary">({row.fromMovieId})</Typography.Text></Typography.Text>
                <Typography.Text>{row.toTitle} <Typography.Text type="secondary">({row.toMovieId})</Typography.Text></Typography.Text>
            </Space> },
        { title: t('resourceHubDuplicates.identity'), key: 'identity', width: 140, render: (_, row) =>
            <Space orientation="vertical" size={4}><Tag>{t('resourceHubDuplicates.categories.' + row.category)}</Tag>
                <Typography.Text>{row.year}{row.season > 0 ? ' · ' + t('resourceHubDuplicates.season', { season: row.season }) : ''}</Typography.Text></Space> },
        { title: t('resourceHubDuplicates.resources'), key: 'resources', width: 130, render: (_, row) =>
            <Typography.Text>{row.fromResources} / {row.toResources}</Typography.Text> },
        { title: t('resourceHubDuplicates.evidence'), key: 'evidence', width: 180, render: (_, row) =>
            <Typography.Text>{t(row.evidence === 'NAME_AND_PEOPLE' ? 'resourceHubDuplicates.peopleMatch' : 'resourceHubDuplicates.nameMatch')}</Typography.Text> },
        { title: t('resourceHubDuplicates.retain'), key: 'retain', width: 250, render: (_, row) =>
            <Select className="w-full" value={retained[pairKey(row)] || row.toMovieId} disabled={loading || uncertain}
                aria-label={t('resourceHubDuplicates.retain')} data-testid={'duplicate-retain-' + pairKey(row)}
                onChange={id => choose(row, id)} options={[
                    { value: row.fromMovieId, label: row.fromTitle + ' (' + row.fromMovieId + ')' },
                    { value: row.toMovieId, label: row.toTitle + ' (' + row.toMovieId + ')' },
                ]} /> },
    ];
    return <Space orientation="vertical" className="w-full" size={12} data-testid="duplicate-scanner">
        <Button type="primary" block icon={<MergeCellsOutlined />} loading={loading} disabled={loading || uncertain}
            onClick={() => void prepareBulk()} data-testid="duplicate-deduplicate">{t('resourceHubDuplicates.oneClick')}</Button>
        <Button block icon={<SearchOutlined />} loading={loading} disabled={uncertain} onClick={() => void scan(1, true)} data-testid="duplicate-scan">
            {t('resourceHubCleanupDryRun')}
        </Button>
        <Typography.Text type="secondary">{t('resourceHubDuplicates.help')}</Typography.Text>
        {error && <Alert type="error" showIcon title={t('resourceHubDuplicates.' + error)} data-testid="duplicate-scan-error" />}
        {success !== null && <Alert type="success" showIcon title={t('resourceHubDuplicates.merged', { count: success })} data-testid="duplicate-merge-success" />}
        {result && <>
            <Alert type={result.complete ? 'info' : 'warning'} showIcon data-testid="duplicate-scan-summary"
                title={t(result.complete ? 'resourceHubDuplicates.summary' : 'resourceHubDuplicates.partial',
                    { scanned: result.scannedMovies, count: result.candidates })}
                description={t('resourceHubDuplicates.excluded', { conflicts: result.conflicts, excluded: result.excludedMovies })} />
            <Space wrap>
                <Button type="primary" onClick={() => void preview()} disabled={loading || uncertain || !selectionValid || !result.complete} data-testid="duplicate-preview">
                    {t('resourceHubDuplicates.previewSelected', { count: pairs.length })}
                </Button>
                <Typography.Text type="secondary">{t('resourceHubDuplicates.selectionHelp')}</Typography.Text>
            </Space>
            {(pairs.length > 20 || repeatedId) && <Alert type="warning" showIcon data-testid="duplicate-selection-warning"
                title={t(repeatedId ? 'resourceHubDuplicates.overlapping' : 'resourceHubDuplicates.tooMany')} />}
            <Table<Candidate> size="small" columns={columns} dataSource={result.items} loading={loading}
                rowKey={pairKey} scroll={{ x: 1020 }} data-testid="duplicate-candidates"
                rowSelection={{ selectedRowKeys: Object.keys(selected), preserveSelectedRowKeys: true, hideSelectAll: true,
                    onSelect: (row, checked) => select(row, checked), getCheckboxProps: () => ({ disabled: loading || uncertain || !result.complete }) }}
                pagination={{ current: result.page, pageSize: result.pageSize, total: result.candidates, showSizeChanger: false,
                    onChange: page => void scan(page) }} />
        </>}
        <Modal open={!!bulkPlan} width={1000} title={t('resourceHubDuplicates.bulkTitle')} footer={null}
            closable={!loading && !uncertain} maskClosable={false} keyboard={!loading && !uncertain}
            onCancel={closeBulk} data-testid="duplicate-bulk-review">
            {bulkPlan && <Space orientation="vertical" size={16} className="w-full">
                <Alert type="info" showIcon title={t('resourceHubDuplicates.bulkSummary',
                    { scanned: bulkPlan.scanned, count: bulkPlan.rows.length, skipped: bulkPlan.skipped.length })}
                    description={t('resourceHubDuplicates.bulkHelp')} />
                <Table<Candidate> size="small" rowKey={pairKey} dataSource={bulkPlan.rows} scroll={{ x: 780 }}
                    pagination={{ pageSize: 20, showSizeChanger: false }} data-testid="duplicate-bulk-candidates"
                    columns={[
                        { title: t('resourceHubDuplicates.records'), key: 'direction', width: 390, render: (_, row) =>
                            <Typography.Text>{t('resourceHubDuplicates.moveTo', { from: row.fromTitle + ' (' + row.fromMovieId + ')',
                                to: row.toTitle + ' (' + row.toMovieId + ')' })}</Typography.Text> },
                        ...columns.filter(column => ['identity', 'resources', 'evidence'].includes(String(column.key))),
                    ]}
                    expandable={{ expandedRowRender: row => {
                        const item = bulkPlan.reviews.flatMap(review => review.items).find(item => pairKey(item) === pairKey(row));
                        return <Space orientation="vertical">{item?.impact.map(impact => <Typography.Text key={impact.reference}>
                            {referenceName(impact.reference) + ': ' + impact.fromCount + ' / ' + impact.toCount}
                        </Typography.Text>)}</Space>;
                    } }} />
                {bulkPlan.skipped.length > 0 && <Table size="small" rowKey={item => pairKey(item.row)} dataSource={bulkPlan.skipped}
                    pagination={{ pageSize: 10, showSizeChanger: false }} scroll={{ x: 620 }} data-testid="duplicate-bulk-skipped"
                    columns={[
                        { title: t('resourceHubDuplicates.bulkSkipped'), key: 'records', render: (_, item) => item.row.fromTitle + ' / ' + item.row.toTitle },
                        { title: t('resourceHubDuplicates.bulkReason'), key: 'reason', render: (_, item) => item.blockers?.length
                            ? item.blockers.map(blocker => t('resourceHubDuplicates.blockers.' + blocker.code,
                                { defaultValue: t('resourceHubDuplicates.blockerUnknown') }) + ' · ' + referenceName(blocker.reference)).join('；')
                            : t('resourceHubDuplicates.' + item.reason) },
                    ]} />}
                {bulkMerged > 0 && <Alert type="success" showIcon data-testid="duplicate-bulk-progress"
                    title={t('resourceHubDuplicates.bulkProgress', { count: bulkMerged, total: bulkPlan.rows.length })} />}
                {bulkExpired && !uncertain && <Alert type="warning" showIcon title={t('resourceHubDuplicates.expired')} />}
                {bulkStopped && <Alert type="warning" showIcon title={t('resourceHubDuplicates.reviewChanged')} />}
                {uncertain && <Alert type="warning" showIcon title={t('resourceHubDuplicates.mergeUncertain')} />}
                <Checkbox checked={bulkConfirmed} disabled={loading || uncertain || bulkExpired || bulkStopped || !bulkPlan.rows.length}
                    onChange={event => setBulkConfirmed(event.target.checked)} data-testid="duplicate-bulk-confirm">
                    {t('resourceHubDuplicates.bulkConfirm')}
                </Checkbox>
                <Space wrap>
                    <Button type="primary" loading={loading} disabled={!bulkConfirmed || bulkStopped || !bulkPlan.rows.length || (bulkExpired && !uncertain)}
                        onClick={() => void mergeBulk()} data-testid="duplicate-bulk-merge">
                        {t(uncertain ? 'resourceHubDuplicates.retryMerge' : 'resourceHubDuplicates.bulkMerge', { count: bulkPlan.rows.length - bulkMerged })}
                    </Button>
                    {!uncertain && <Button disabled={loading} onClick={closeBulk} data-testid="duplicate-bulk-cancel">{t('resourceHubDuplicates.cancelReview')}</Button>}
                </Space>
            </Space>}
        </Modal>
        <Modal open={!!review} width={900} title={t('resourceHubDuplicates.reviewTitle')} footer={null}
            closable={!loading && !uncertain} maskClosable={false} keyboard={!loading && !uncertain}
            onCancel={() => { if (!busy.current && !uncertain) invalidateReview(); }} data-testid="duplicate-review">
            {review && <Space orientation="vertical" size={16} className="w-full">
                <Alert type={review.canMerge ? 'info' : 'warning'} showIcon title={t('resourceHubDuplicates.reviewHelp')}
                    description={t('resourceHubDuplicates.reviewExpires', { time: new Date(review.expiresAt).toLocaleString() })} />
                {review.items.map(item => <Space key={pairKey(item)} orientation="vertical" size={8} className="w-full" data-testid={'duplicate-review-item-' + pairKey(item)}>
                    <Typography.Text strong>{t('resourceHubDuplicates.moveTo', { from: item.fromTitle + ' (' + item.fromMovieId + ')', to: item.toTitle + ' (' + item.toMovieId + ')' })}</Typography.Text>
                    {item.blockers.length > 0 && <Alert type="warning" showIcon data-testid={'duplicate-blockers-' + pairKey(item)}
                        title={t('resourceHubDuplicates.blocked')}
                        description={<Space orientation="vertical" size={4}>{item.blockers.map((blocker, index) =>
                            <Typography.Text key={index}>{t('resourceHubDuplicates.blockers.' + blocker.code, { defaultValue: t('resourceHubDuplicates.blockerUnknown') })}
                                {' · ' + referenceName(blocker.reference) + ' (' + blocker.count + ')'}</Typography.Text>)}</Space>} />}
                    <Table size="small" pagination={false} rowKey="reference" dataSource={item.impact} data-testid={'duplicate-impact-' + pairKey(item)}
                        columns={[
                            { title: t('resourceHubDuplicates.reference'), dataIndex: 'reference', render: reference => referenceName(reference) },
                            { title: t('resourceHubDuplicates.referenceCount'), key: 'counts', render: (_, impact) => impact.fromCount + ' / ' + impact.toCount },
                            { title: t('resourceHubDuplicates.referenceAction'), dataIndex: 'action', render: action => t('resourceHubDuplicates.actions.' + action, { defaultValue: t('resourceHubDuplicates.actionUnknown') }) },
                        ]} />
                    <Checkbox checked={confirmed[pairKey(item)] === true} disabled={loading || uncertain || !item.eligible || expired}
                        onChange={event => setConfirmed(previous => ({ ...previous, [pairKey(item)]: event.target.checked }))}
                        data-testid={'duplicate-confirm-' + pairKey(item)}>{t('resourceHubDuplicates.confirmPair')}</Checkbox>
                </Space>)}
                {!review.canMerge && <Alert type="warning" showIcon title={t('resourceHubDuplicates.removeBlocked')} />}
                {expired && !uncertain && <Alert type="warning" showIcon title={t('resourceHubDuplicates.expired')} />}
                {uncertain && <Alert type="warning" showIcon title={t('resourceHubDuplicates.mergeUncertain')} />}
                <Space wrap>
                    <Button type="primary" loading={loading} disabled={!review.canMerge || !allReviewed || (expired && !uncertain)}
                        onClick={() => void merge()} data-testid="duplicate-merge">
                        {t(uncertain ? 'resourceHubDuplicates.retryMerge' : 'resourceHubDuplicates.mergeReviewed', { count: review.items.length })}
                    </Button>
                    {!uncertain && <Button disabled={loading} onClick={() => invalidateReview()} data-testid="duplicate-review-cancel">{t('resourceHubDuplicates.cancelReview')}</Button>}
                </Space>
            </Space>}
        </Modal>
    </Space>;
}
