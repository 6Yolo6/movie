'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, App, Button, Col, Form, InputNumber, Row, Space, Switch, Tag, Typography } from 'antd';
import { CloudSyncOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
    baiduBackfillSettings, baiduBackfillStatus, baiduBackfillValidationError,
    type BaiduBackfillSettings, type BaiduBackfillStatus, type CloudDriveWorkflow,
} from '@/lib/cloudDriveWorkflows';

interface Props {
    workflow: CloudDriveWorkflow;
    workflowEdited: boolean;
    workflowBusy: boolean;
    hubEnabled: boolean;
    onReadStatus: (signal: AbortSignal) => Promise<BaiduBackfillStatus>;
    onSaveSettings: (settings: BaiduBackfillSettings, signal: AbortSignal) => Promise<BaiduBackfillStatus>;
    onRun: (signal: AbortSignal) => Promise<BaiduBackfillStatus>;
}

const POLL_INTERVAL_MS = 10000;
const DEFAULT_SETTINGS: BaiduBackfillSettings = { enabled: false, intervalMinutes: 10, batchLimit: 1 };

export default function BaiduBackfillControls({ workflow, workflowEdited, workflowBusy, hubEnabled,
    onReadStatus, onSaveSettings, onRun }: Props) {
    const { t } = useTranslation();
    const { message } = App.useApp();
    const [status, setStatus] = useState<BaiduBackfillStatus | null>(null);
    const [edited, setEdited] = useState<BaiduBackfillSettings | null>(null);
    const [loading, setLoading] = useState(true);
    const [operation, setOperation] = useState<'save' | 'run' | null>(null);
    const [error, setError] = useState<'readFailed' | 'saveFailed' | 'runFailed' | null>(null);
    const mounted = useRef(false);
    const generation = useRef(0);
    const activeRequest = useRef<AbortController | null>(null);
    const busy = useRef(false);
    const draft = edited || status || DEFAULT_SETTINGS;
    const controlsDisabled = !status || Boolean(operation) || workflowBusy;
    const runBlocked = workflowEdited || Boolean(edited) ? 'saveBeforeRun'
        : !hubEnabled ? 'HUB_DISABLED'
        : !workflow.transferEnabled ? 'TRANSFER_DISABLED'
        : !workflow.shareEnabled ? 'SHARE_DISABLED'
        : !workflow.torrentEnabled ? 'TORRENT_DISABLED'
        : !workflow.credentialConfigured ? 'SESSION_REQUIRED' : null;
    const cannotRun = controlsDisabled || Boolean(error) || Boolean(runBlocked) || status?.status === 'RUNNING' || status?.status === 'COMPLETE' || status?.phase === 'COMPLETE';
    const cannotSave = controlsDisabled || (draft.enabled && workflowEdited);

    const readStatus = useCallback(async () => {
        if (busy.current) return;
        const current = ++generation.current;
        activeRequest.current?.abort();
        const request = new AbortController();
        activeRequest.current = request;
        setLoading(true);
        setError(null);
        try {
            const next = baiduBackfillStatus(await onReadStatus(request.signal));
            if (mounted.current && generation.current === current && !request.signal.aborted) setStatus(next);
        } catch {
            if (mounted.current && generation.current === current && !request.signal.aborted) setError('readFailed');
        } finally {
            if (mounted.current && generation.current === current) setLoading(false);
        }
    }, [onReadStatus]);

    useEffect(() => {
        mounted.current = true;
        return () => {
            mounted.current = false;
            generation.current += 1;
            activeRequest.current?.abort();
        };
    }, []);

    useEffect(() => { void readStatus(); }, [readStatus]);

    useEffect(() => {
        if (!status || error || loading || operation || status.status === 'COMPLETE' || status.phase === 'COMPLETE'
            || (status.status !== 'RUNNING' && (!status.enabled || status.status === 'PAUSED'))) return;
        const timer = window.setTimeout(() => void readStatus(), POLL_INTERVAL_MS);
        return () => window.clearTimeout(timer);
    }, [status, error, loading, operation, readStatus]);

    const change = <K extends keyof BaiduBackfillSettings>(key: K, value: BaiduBackfillSettings[K]) => {
        if (!controlsDisabled) setEdited({ enabled: draft.enabled, intervalMinutes: draft.intervalMinutes, batchLimit: draft.batchLimit, [key]: value });
    };

    const mutate = async (kind: 'save' | 'run') => {
        if (busy.current || (kind === 'save' ? cannotSave : cannotRun)) return;
        const settings = { enabled: draft.enabled, intervalMinutes: draft.intervalMinutes, batchLimit: draft.batchLimit };
        if (kind === 'save') {
            const invalid = baiduBackfillValidationError(settings);
            if (invalid) { message.error(t(`cloudDriveWorkflows.baiduBackfill.validation.${invalid}`)); return; }
        }
        busy.current = true;
        const current = ++generation.current;
        activeRequest.current?.abort();
        const request = new AbortController();
        activeRequest.current = request;
        setLoading(false);
        setOperation(kind);
        setError(null);
        try {
            const next = baiduBackfillStatus(await (kind === 'save'
                ? onSaveSettings(baiduBackfillSettings(settings), request.signal) : onRun(request.signal)));
            if (!mounted.current || generation.current !== current || request.signal.aborted) return;
            setStatus(next);
            if (kind === 'save') setEdited(null);
            message.success(t(`cloudDriveWorkflows.baiduBackfill.${kind === 'save' ? 'saved' : 'runFinished'}`));
        } catch {
            if (mounted.current && generation.current === current && !request.signal.aborted) setError(kind === 'save' ? 'saveFailed' : 'runFailed');
        } finally {
            busy.current = false;
            if (mounted.current && generation.current === current) setOperation(null);
        }
    };

    return <Space orientation="vertical" size={12} className="mt-6 w-full" data-testid="baidu-backfill-controls">
        <Typography.Text strong>{t('cloudDriveWorkflows.baiduBackfill.title')}</Typography.Text>
        <Typography.Paragraph type="secondary">{t('cloudDriveWorkflows.baiduBackfill.help')}</Typography.Paragraph>
        {status && <>
            <Space wrap>
                <Tag color={status.status === 'COMPLETE' ? 'green' : status.status === 'BLOCKED' ? 'orange' : 'default'}>
                    {t(`cloudDriveWorkflows.baiduBackfill.states.${status.status}`)}
                </Tag>
                <Typography.Text role="status" aria-live="polite" data-testid="baidu-backfill-phase">
                    {t(`cloudDriveWorkflows.baiduBackfill.phases.${status.phase}`)}
                </Typography.Text>
            </Space>
            <Row gutter={[12, 8]} className="w-full">
                {(['processed', 'succeeded', 'skipped', 'failed'] as const).map(key => <Col xs={12} key={key}>
                    <Typography.Text data-testid={`baidu-backfill-${key}`}>
                        {t(`cloudDriveWorkflows.baiduBackfill.counts.${key}`, { count: status[key] })}
                    </Typography.Text>
                </Col>)}
            </Row>
            <Space orientation="vertical" size={6} className="w-full" data-testid="baidu-backfill-current-item">
                <Typography.Text strong>{t('cloudDriveWorkflows.baiduBackfill.currentItem.title')}</Typography.Text>
                {status.currentIdentityId !== null ? <>
                    <Typography.Text data-testid="baidu-backfill-identity-id">
                        {t('cloudDriveWorkflows.baiduBackfill.currentItem.identityId')} {status.currentIdentityId}
                    </Typography.Text>
                    {status.currentMovieId && <Typography.Text data-testid="baidu-backfill-movie-id" className="break-all">
                        {t('cloudDriveWorkflows.baiduBackfill.currentItem.movieId')} {status.currentMovieId}
                    </Typography.Text>}
                    {status.currentGyingType && status.currentGyingId && <Typography.Text data-testid="baidu-backfill-gying-id">
                        {t('cloudDriveWorkflows.baiduBackfill.currentItem.gyingId')} {status.currentGyingType}/{status.currentGyingId}
                    </Typography.Text>}
                    <Typography.Text data-testid="baidu-backfill-seed-count">
                        {t('cloudDriveWorkflows.baiduBackfill.currentItem.seedCount', { count: status.currentSeedCount })}
                    </Typography.Text>
                </> : <Typography.Text type="secondary" data-testid="baidu-backfill-no-current-item">
                    {t('cloudDriveWorkflows.baiduBackfill.currentItem.none')}
                </Typography.Text>}
                <Typography.Text role="status" aria-live="polite" data-testid="baidu-backfill-current-action">
                    {t('cloudDriveWorkflows.baiduBackfill.currentItem.action')} {t(`cloudDriveWorkflows.baiduBackfill.actions.${status.currentAction}`)}
                </Typography.Text>
                {status.currentChildId !== null && <Typography.Text data-testid="baidu-backfill-child-id">
                    {t('cloudDriveWorkflows.baiduBackfill.currentItem.childId')} {status.currentChildId}
                </Typography.Text>}
                {status.currentChildStatus && <Typography.Text data-testid="baidu-backfill-child-status">
                    {t('cloudDriveWorkflows.baiduBackfill.currentItem.childStatus')} {t(`cloudDriveWorkflows.baiduBackfill.childStates.${status.currentChildStatus}`)}
                </Typography.Text>}
                {status.currentChildError && <Typography.Text type="secondary" data-testid="baidu-backfill-child-error">
                    {t('cloudDriveWorkflows.baiduBackfill.currentItem.childError')} {t(`cloudDriveWorkflows.baiduBackfill.childErrors.${status.currentChildError}`)}
                </Typography.Text>}
                {status.currentSeedNames.length > 0 && <Space orientation="vertical" size={2} className="w-full">
                    <Typography.Text type="secondary">{t('cloudDriveWorkflows.baiduBackfill.currentItem.seedNames')}</Typography.Text>
                    {status.currentSeedNames.map((name, index) => <Typography.Text key={index} className="break-all" data-testid={`baidu-backfill-seed-name-${index}`}>
                        {name}
                    </Typography.Text>)}
                </Space>}
                {status.currentResourceIds.length > 0 && <Typography.Text type="secondary" data-testid="baidu-backfill-resource-ids" className="break-all">
                    {t('cloudDriveWorkflows.baiduBackfill.currentItem.resourceIds')} {status.currentResourceIds.join(', ')}
                </Typography.Text>}
                {status.lastResult && <Typography.Text type="secondary" data-testid="baidu-backfill-last-result">
                    {t('cloudDriveWorkflows.baiduBackfill.currentItem.lastResult')} {t(`cloudDriveWorkflows.baiduBackfill.results.${status.lastResult}`)}
                </Typography.Text>}
            </Space>
            <Typography.Text type="secondary" data-testid="baidu-backfill-next-run">
                {t('cloudDriveWorkflows.baiduBackfill.nextRun', { time: status.nextRunAt
                    ? status.nextRunAt.replace('T', ' ').slice(0, 19) : t('cloudDriveWorkflows.baiduBackfill.noNextRun') })}
            </Typography.Text>
            {status.blockedReason && <Alert type="warning" showIcon data-testid="baidu-backfill-blocked"
                title={t(`cloudDriveWorkflows.baiduBackfill.blocked.${status.blockedReason}`)} />}
        </>}
        {status?.legacyTaskId !== null && status?.legacyTaskId !== undefined && <Alert type="info" showIcon data-testid="baidu-backfill-legacy-task"
            title={t('cloudDriveWorkflows.baiduBackfill.legacyTask', { taskId: status.legacyTaskId })} />}
        {error && <Alert type="error" showIcon role="alert" title={t(`cloudDriveWorkflows.baiduBackfill.${error}`)} />}
        <Form layout="vertical" className="w-full">
            <Form.Item label={t('cloudDriveWorkflows.baiduBackfill.enabled')}>
                <Switch checked={draft.enabled} disabled={controlsDisabled} data-testid="baidu-backfill-enabled"
                    aria-label={t('cloudDriveWorkflows.baiduBackfill.enabled')} onChange={value => change('enabled', value)} />
            </Form.Item>
            <Row gutter={12}>
                <Col xs={24} sm={12}>
                    <Form.Item label={t('cloudDriveWorkflows.baiduBackfill.intervalMinutes')}>
                        <InputNumber className="w-full" value={draft.intervalMinutes} min={1} max={1440} precision={0}
                            disabled={controlsDisabled} data-testid="baidu-backfill-intervalMinutes"
                            aria-label={t('cloudDriveWorkflows.baiduBackfill.intervalMinutes')} onChange={value => change('intervalMinutes', value ?? 0)} />
                    </Form.Item>
                </Col>
                <Col xs={24} sm={12}>
                    <Form.Item label={t('cloudDriveWorkflows.baiduBackfill.batchLimit')}>
                        <InputNumber className="w-full" value={draft.batchLimit} min={1} max={5} precision={0}
                            disabled={controlsDisabled} data-testid="baidu-backfill-batchLimit"
                            aria-label={t('cloudDriveWorkflows.baiduBackfill.batchLimit')} onChange={value => change('batchLimit', value ?? 0)} />
                    </Form.Item>
                </Col>
            </Row>
            <Space wrap>
                <Button className="min-h-11" icon={<SaveOutlined />} loading={operation === 'save'} disabled={cannotSave}
                    data-testid="baidu-backfill-save" onClick={() => void mutate('save')}>
                    {t('cloudDriveWorkflows.baiduBackfill.save')}
                </Button>
                <Button className="min-h-11" icon={<CloudSyncOutlined />} loading={operation === 'run'} disabled={cannotRun}
                    data-testid="baidu-backfill-run" onClick={() => void mutate('run')}>
                    {t('cloudDriveWorkflows.baiduBackfill.run')}
                </Button>
                <Button className="min-h-11" icon={<ReloadOutlined />} loading={loading} disabled={Boolean(operation)}
                    data-testid="baidu-backfill-refresh" onClick={() => void readStatus()}>
                    {t('cloudDriveWorkflows.baiduBackfill.refresh')}
                </Button>
            </Space>
            {runBlocked && <div className="mt-2"><Typography.Text type="secondary">
                {t(runBlocked === 'saveBeforeRun' ? 'cloudDriveWorkflows.baiduBackfill.saveBeforeRun'
                    : `cloudDriveWorkflows.baiduBackfill.blocked.${runBlocked}`)}
            </Typography.Text></div>}
        </Form>
    </Space>;
}
