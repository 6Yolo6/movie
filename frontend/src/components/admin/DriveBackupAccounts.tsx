'use client';

import { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Card, Input, Select, Space, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import BaiduAccountConnection from '@/components/admin/BaiduAccountConnection';
import { baiduSessionStatus } from '@/lib/cloudDriveWorkflows';

type RequestJson = <T>(path: string, options?: RequestInit) => Promise<T>;
type XunleiStatus = { configured: boolean; expiresAt: number | null; expired: boolean };
const BASE = '/api/admin/resource-hub/backup-accounts';
const NOOP = async () => {};

function XunleiBackup({ slot, request }: { slot: number; request: RequestJson }) {
    const { t } = useTranslation();
    const [status, setStatus] = useState<XunleiStatus | null>(null);
    const [authorization, setAuthorization] = useState('');
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<'readFailed' | 'saveFailed' | null>(null);
    const url = `${BASE}/xunlei/${slot}`;
    useEffect(() => {
        const controller = new AbortController();
        request<XunleiStatus>(url, { signal: controller.signal }).then(value => {
            if (!controller.signal.aborted) setStatus(value);
        }).catch(() => { if (!controller.signal.aborted) setError('readFailed'); });
        return () => controller.abort();
    }, [request, url]);
    const save = async () => {
        if (saving || !status || !authorization.trim()) return;
        setSaving(true);
        setError(null);
        try {
            setStatus(await request<XunleiStatus>(url, {
                method: 'PUT', body: JSON.stringify({ authorization: authorization.trim() }),
            }));
        } catch { setError('saveFailed'); }
        finally { setSaving(false); setAuthorization(''); }
    };
    return <Space orientation="vertical" className="w-full">
        {status && <Tag color={status.configured && !status.expired ? 'blue' : 'default'}>
            {t(`cloudDriveWorkflows.backupAccounts.${status.configured ? (status.expired ? 'expired' : 'stored') : 'empty'}`)}
        </Tag>}
        {error && <Alert type="error" title={t(`cloudDriveWorkflows.backupAccounts.${error}`)} />}
        <Input.Password value={authorization} maxLength={12295} autoComplete="off" disabled={saving || !status}
            onChange={event => setAuthorization(event.target.value)} onPressEnter={() => void save()}
            aria-label={t('cloudDriveWorkflows.backupAccounts.authorization')}
            placeholder={t('cloudDriveWorkflows.backupAccounts.authorization')} />
        <Button loading={saving} disabled={!status || !authorization.trim()} onClick={() => void save()}>
            {t('cloudDriveWorkflows.backupAccounts.save')}
        </Button>
    </Space>;
}

function BaiduBackup({ slot, request }: { slot: number; request: RequestJson }) {
    const url = `${BASE}/baidu/${slot}`;
    const read = useCallback(async (signal: AbortSignal) => baiduSessionStatus(await request<unknown>(url, { signal })), [request, url]);
    const login = useCallback(async (signal: AbortSignal) => baiduSessionStatus(await request<unknown>(`${url}/login`, {
        method: 'POST', body: '{}', signal,
    })), [request, url]);
    return <BaiduAccountConnection onReadSession={read} onStartLogin={login} onConnected={NOOP} />;
}

export default function DriveBackupAccounts({ request }: { request: RequestJson }) {
    const { t } = useTranslation();
    const [xunleiSlot, setXunleiSlot] = useState(1);
    const [baiduSlot, setBaiduSlot] = useState(1);
    const translate = (key: string) => t(`cloudDriveWorkflows.backupAccounts.${key}`);
    const options = [1, 2, 3].map(value => ({ value, label: t('cloudDriveWorkflows.backupAccounts.slot', { number: value }) }));
    return <Card className="mt-4" title={translate('title')}>
        <Space orientation="vertical" className="w-full" size={16}>
            <Typography.Text type="secondary">{translate('help')}</Typography.Text>
            <Space wrap><Typography.Text strong>{t('cloudDriveWorkflows.providers.XUNLEI')}</Typography.Text>
                <Select aria-label={translate('xunleiSlot')} options={options} value={xunleiSlot} onChange={setXunleiSlot} />
            </Space>
            <XunleiBackup key={xunleiSlot} slot={xunleiSlot} request={request} />
            <Space wrap><Typography.Text strong>{t('cloudDriveWorkflows.providers.BAIDU')}</Typography.Text>
                <Select aria-label={translate('baiduSlot')} options={options} value={baiduSlot} onChange={setBaiduSlot} />
            </Space>
            <BaiduBackup key={baiduSlot} slot={baiduSlot} request={request} />
        </Space>
    </Card>;
}
