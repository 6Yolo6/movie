'use client';

import { useRef, useState } from 'react';
import { Alert, App, Button, Card, Col, Empty, Form, Input, InputNumber, Row, Space, Switch, Tag, Typography } from 'antd';
import { CloudSyncOutlined, SaveOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import BaiduAccountConnection from '@/components/admin/BaiduAccountConnection';
import BaiduBackfillControls from '@/components/admin/BaiduBackfillControls';
import {
    canConfigureCloudDrive, CLOUD_DRIVE_PROVIDERS, cloudDriveValidationError, cloudDriveWorkflowUpdate,
    type BaiduBackfillSettings, type BaiduBackfillStatus, type BaiduSessionStatus, type CloudDriveProvider, type CloudDriveTransferResult, type CloudDriveWorkflow, type CloudDriveWorkflowUpdate,
} from '@/lib/cloudDriveWorkflows';

interface Props {
    workflows?: CloudDriveWorkflow[];
    loading?: boolean;
    hubEnabled: boolean;
    onSave: (update: CloudDriveWorkflowUpdate) => Promise<CloudDriveWorkflow>;
    onRunTransfers: (provider: CloudDriveProvider, limit: number) => Promise<CloudDriveTransferResult>;
    onReadBaiduSession: (signal: AbortSignal) => Promise<BaiduSessionStatus>;
    onStartBaiduLogin: (signal: AbortSignal) => Promise<BaiduSessionStatus>;
    onBaiduConnected: (signal: AbortSignal) => Promise<void>;
    onReadBaiduBackfill: (signal: AbortSignal) => Promise<BaiduBackfillStatus>;
    onSaveBaiduBackfill: (settings: BaiduBackfillSettings, signal: AbortSignal) => Promise<BaiduBackfillStatus>;
    onRunBaiduBackfill: (signal: AbortSignal) => Promise<BaiduBackfillStatus>;
}

function DriveWorkflowCard({ workflow, hubEnabled, onSave, onRunTransfers, onReadBaiduSession, onStartBaiduLogin, onBaiduConnected,
    onReadBaiduBackfill, onSaveBaiduBackfill, onRunBaiduBackfill }: Omit<Props, 'workflows' | 'loading'> & { workflow: CloudDriveWorkflow }) {
    const { t } = useTranslation();
    const { message } = App.useApp();
    const [edited, setEdited] = useState<CloudDriveWorkflowUpdate | null>(null);
    const [saving, setSaving] = useState(false);
    const [running, setRunning] = useState(false);
    const busy = useRef(false);
    const draft = edited || cloudDriveWorkflowUpdate(workflow);
    const available = canConfigureCloudDrive(workflow);
    const disabled = !available || saving || running;
    const sessionMissing = workflow.provider === 'BAIDU' && !workflow.credentialConfigured;
    const noFilesSelected = !draft.videoEnabled && !draft.torrentEnabled;
    const providerLabel = t(`cloudDriveWorkflows.providers.${workflow.provider}`);
    const change = <K extends keyof CloudDriveWorkflowUpdate>(key: K, value: CloudDriveWorkflowUpdate[K]) => {
        if (!disabled) setEdited({ ...draft, [key]: value });
    };

    const save = async () => {
        if (!available || busy.current) return;
        const update = cloudDriveWorkflowUpdate(draft);
        const error = cloudDriveValidationError(update);
        if (error) {
            message.error(t(`cloudDriveWorkflows.validation.${error}`));
            return;
        }
        busy.current = true;
        setSaving(true);
        try {
            const saved = await onSave(update);
            if (saved.provider !== workflow.provider) throw new Error(t('cloudDriveWorkflows.invalidResponse'));
            setEdited(null);
            message.success(t('cloudDriveWorkflows.saved', { provider: providerLabel }));
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('cloudDriveWorkflows.saveFailed'));
        } finally {
            busy.current = false;
            setSaving(false);
        }
    };

    const runTransfers = async () => {
        if (!available || !workflow.transferEnabled || sessionMissing || noFilesSelected || busy.current || edited) return;
        busy.current = true;
        setRunning(true);
        try {
            const result = await onRunTransfers(workflow.provider, workflow.batchLimit ?? 5);
            const text = t('cloudDriveWorkflows.transferResult', {
                submitted: result.submitted || 0, skipped: result.skipped || 0, failed: result.failed || 0,
            });
            if (result.failed) message.warning(text);
            else message.success(text);
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('cloudDriveWorkflows.transferFailed'));
        } finally {
            busy.current = false;
            setRunning(false);
        }
    };

    return <Card title={providerLabel} data-testid={`drive-card-${workflow.provider}`}
        extra={<Tag color={available ? 'green' : 'default'}>{t(available ? 'cloudDriveWorkflows.available' : 'cloudDriveWorkflows.planned')}</Tag>}>
        {!available ? <Alert type="info" showIcon title={t(workflow.provider === 'BAIDU'
            ? 'cloudDriveWorkflows.baiduPlanned' : 'cloudDriveWorkflows.unavailable')} /> : <>
            {workflow.provider === 'BAIDU' ? <BaiduAccountConnection onReadSession={onReadBaiduSession}
                onStartLogin={onStartBaiduLogin} onConnected={onBaiduConnected} /> : <Space wrap className="mb-4">
                <Tag color={workflow.credentialConfigured ? 'blue' : 'default'}>
                    {t(workflow.credentialConfigured ? 'cloudDriveWorkflows.credentialPresent' : 'cloudDriveWorkflows.credentialMissing')}
                </Tag>
                <Typography.Text type="secondary">{t('cloudDriveWorkflows.credentialHelp')}</Typography.Text>
            </Space>}
            <Form layout="vertical">
                <Typography.Text strong>{t('cloudDriveWorkflows.fileTypes')}</Typography.Text>
                <Row gutter={12} className="mt-3">
                    {(['videoEnabled', 'torrentEnabled'] as const).map(key => <Col xs={12} key={key}>
                        <Form.Item label={t(`cloudDriveWorkflows.fields.${key}`)}>
                            <Switch checked={draft[key]} disabled={disabled}
                                aria-label={`${providerLabel} ${t(`cloudDriveWorkflows.fields.${key}`)}`}
                                data-testid={`drive-${workflow.provider}-${key}`} onChange={value => change(key, value)} />
                        </Form.Item>
                    </Col>)}
                </Row>
                <Typography.Paragraph type="secondary">{t('cloudDriveWorkflows.torrentHelp')}</Typography.Paragraph>
                {noFilesSelected && <Alert className="mb-4" type="info" showIcon title={t('cloudDriveWorkflows.noFilesSelected')} />}
                <Row gutter={12}>
                    {(['discoveryEnabled', 'transferEnabled', 'shareEnabled', 'gyingPublishEnabled'] as const).map(key =>
                        <Col xs={12} key={key}>
                            <Form.Item label={t(`cloudDriveWorkflows.fields.${key}`)}>
                                <Switch checked={draft[key]} disabled={disabled}
                                    aria-label={`${providerLabel} ${t(`cloudDriveWorkflows.fields.${key}`)}`}
                                    data-testid={`drive-${workflow.provider}-${key}`} onChange={value => change(key, value)} />
                            </Form.Item>
                        </Col>)}
                </Row>
                {!draft.shareEnabled && <Alert className="mb-4" type="warning" showIcon title={t('cloudDriveWorkflows.shareDisabledHelp')} />}
                <Form.Item label={t('cloudDriveWorkflows.fields.savePath')} extra={t('cloudDriveWorkflows.savePathHelp')}>
                    <Input value={draft.savePath} maxLength={300} disabled={disabled} autoComplete="off"
                        aria-label={`${providerLabel} ${t('cloudDriveWorkflows.fields.savePath')}`}
                        data-testid={`drive-${workflow.provider}-savePath`} onChange={event => change('savePath', event.target.value)} />
                </Form.Item>
                <Form.Item label={t('cloudDriveWorkflows.fields.batchLimit')}>
                    <InputNumber value={draft.batchLimit} min={1} max={20} precision={0} disabled={disabled}
                        aria-label={`${providerLabel} ${t('cloudDriveWorkflows.fields.batchLimit')}`}
                        data-testid={`drive-${workflow.provider}-batchLimit`} onChange={value => change('batchLimit', value ?? 0)} />
                </Form.Item>
                <Space wrap>
                    <Button className="min-h-11" type="primary" icon={<SaveOutlined />} loading={saving} disabled={disabled}
                        data-testid={`drive-save-${workflow.provider}`} onClick={() => void save()}>
                        {t('cloudDriveWorkflows.saveProvider', { provider: providerLabel })}
                    </Button>
                    <Button className="min-h-11" icon={<CloudSyncOutlined />} loading={running}
                        disabled={disabled || !workflow.transferEnabled || sessionMissing || noFilesSelected || Boolean(edited)}
                        data-testid={`drive-run-${workflow.provider}`} onClick={() => void runTransfers()}>
                        {t('cloudDriveWorkflows.runTransfers')}
                    </Button>
                </Space>
                {edited && <div className="mt-2"><Typography.Text type="secondary">{t('cloudDriveWorkflows.saveBeforeRun')}</Typography.Text></div>}
                {sessionMissing && <div className="mt-2"><Typography.Text type="secondary">{t('cloudDriveWorkflows.baiduAccount.loginBeforeRun')}</Typography.Text></div>}
            </Form>
            {workflow.provider === 'BAIDU' && <BaiduBackfillControls workflow={workflow} workflowEdited={Boolean(edited)}
                workflowBusy={saving || running} hubEnabled={hubEnabled} onReadStatus={onReadBaiduBackfill}
                onSaveSettings={onSaveBaiduBackfill} onRun={onRunBaiduBackfill} />}
        </>}
    </Card>;
}

export default function DriveWorkflowSettings({ workflows, loading, hubEnabled, onSave, onRunTransfers, onReadBaiduSession, onStartBaiduLogin, onBaiduConnected,
    onReadBaiduBackfill, onSaveBaiduBackfill, onRunBaiduBackfill }: Props) {
    const { t } = useTranslation();
    if (loading) return <Card loading />;
    if (!workflows?.length) return <Card><Empty description={t('cloudDriveWorkflows.unavailable')} /></Card>;
    return <Space orientation="vertical" size={16} className="w-full" data-testid="drive-workflow-settings">
        <Alert type="info" showIcon title={t('cloudDriveWorkflows.help')} />
        <Row gutter={[16, 16]} className="w-full">
            {CLOUD_DRIVE_PROVIDERS.map(provider => {
                const workflow = workflows.find(item => item.provider === provider);
                return workflow ? <Col key={provider} xs={24} lg={12} xl={8}>
                    <DriveWorkflowCard workflow={workflow} hubEnabled={hubEnabled} onSave={onSave} onRunTransfers={onRunTransfers}
                        onReadBaiduSession={onReadBaiduSession} onStartBaiduLogin={onStartBaiduLogin} onBaiduConnected={onBaiduConnected}
                        onReadBaiduBackfill={onReadBaiduBackfill} onSaveBaiduBackfill={onSaveBaiduBackfill} onRunBaiduBackfill={onRunBaiduBackfill} />
                </Col> : null;
            })}
        </Row>
    </Space>;
}
