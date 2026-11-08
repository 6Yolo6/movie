'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Space, Tag, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { baiduSessionStatus, isBaiduLoginPending, type BaiduSessionStatus } from '@/lib/cloudDriveWorkflows';

interface Props {
    onReadSession: (signal: AbortSignal) => Promise<BaiduSessionStatus>;
    onStartLogin: (signal: AbortSignal) => Promise<BaiduSessionStatus>;
    onConnected: (signal: AbortSignal) => Promise<void>;
}

const POLL_INTERVAL_MS = 5000;

export default function BaiduAccountConnection({ onReadSession, onStartLogin, onConnected }: Props) {
    const { t } = useTranslation();
    const [status, setStatus] = useState<BaiduSessionStatus | null>(null);
    const [loading, setLoading] = useState(true);
    const [starting, setStarting] = useState(false);
    const [error, setError] = useState<'readFailed' | 'startFailed' | 'refreshFailed' | null>(null);
    const mounted = useRef(false);
    const generation = useRef(0);
    const activeRequest = useRef<AbortController | null>(null);
    const busy = useRef(false);
    const notifiedConnection = useRef<string | null>(null);

    const acceptStatus = useCallback(async (value: BaiduSessionStatus, current: number) => {
        const next = baiduSessionStatus(value);
        if (!mounted.current || generation.current !== current) return;
        setStatus(next);
        if (next.state === 'CONNECTED' && next.configured) {
            const connection = `${next.ticketId ?? ''}:${next.lastValidatedAt ?? ''}`;
            if (notifiedConnection.current !== connection) {
                try {
                    await onConnected(activeRequest.current!.signal);
                    if (mounted.current && generation.current === current) notifiedConnection.current = connection;
                } catch {
                    if (mounted.current && generation.current === current) {
                        notifiedConnection.current = null;
                        setError('refreshFailed');
                    }
                }
            }
        }
    }, [onConnected]);

    const readStatus = useCallback(async () => {
        if (busy.current) return;
        const current = ++generation.current;
        activeRequest.current?.abort();
        const request = new AbortController();
        activeRequest.current = request;
        setLoading(true);
        setError(null);
        try {
            await acceptStatus(await onReadSession(request.signal), current);
        } catch {
            if (mounted.current && generation.current === current && !request.signal.aborted) setError('readFailed');
        } finally {
            if (mounted.current && generation.current === current) setLoading(false);
        }
    }, [acceptStatus, onReadSession]);

    useEffect(() => {
        mounted.current = true;
        void readStatus();
        return () => {
            mounted.current = false;
            generation.current += 1;
            activeRequest.current?.abort();
        };
    }, [readStatus]);

    useEffect(() => {
        if (!isBaiduLoginPending(status) || error || loading || starting) return;
        const remaining = (status?.expiresAt ?? 0) - Date.now();
        if (remaining <= 0) {
            setStatus(current => current ? { ...current, state: 'EXPIRED' } : current);
            return;
        }
        const timer = window.setTimeout(() => {
            if ((status?.expiresAt ?? 0) <= Date.now()) {
                setStatus(current => current ? { ...current, state: 'EXPIRED' } : current);
            } else {
                void readStatus();
            }
        }, Math.min(POLL_INTERVAL_MS, remaining));
        return () => window.clearTimeout(timer);
    }, [status, error, loading, starting, readStatus]);

    const startLogin = async () => {
        if (busy.current || isBaiduLoginPending(status)) return;
        busy.current = true;
        const current = ++generation.current;
        activeRequest.current?.abort();
        const request = new AbortController();
        activeRequest.current = request;
        setStarting(true);
        setLoading(false);
        setError(null);
        try {
            await acceptStatus(await onStartLogin(request.signal), current);
        } catch {
            if (mounted.current && generation.current === current && !request.signal.aborted) setError('startFailed');
        } finally {
            busy.current = false;
            if (mounted.current && generation.current === current) setStarting(false);
        }
    };

    const pending = isBaiduLoginPending(status);
    const failureKeys: Record<string, string> = {
        ACCOUNT_CHANGED: 'accountChanged', SESSION_INVALID: 'sessionInvalid', LOGIN_EXPIRED: 'expired',
        HELPER_UNAVAILABLE: 'helperUnavailable', LOGIN_FAILED: 'failed',
    };
    const failure = status?.failureCode ? failureKeys[status.failureCode] ?? 'failed' : 'failed';
    const state = status?.state ?? 'DISCONNECTED';
    return <Space orientation="vertical" size={12} className="mb-4 w-full" data-testid="baidu-account-connection">
        <Space wrap>
            <Tag color={status?.configured ? 'blue' : 'default'}>
                {t(status?.configured ? 'cloudDriveWorkflows.baiduAccount.sessionPresent' : 'cloudDriveWorkflows.baiduAccount.sessionMissing')}
            </Tag>
            <Typography.Text role="status" aria-live="polite" data-testid="baidu-login-state">
                {t(`cloudDriveWorkflows.baiduAccount.states.${state}`)}
            </Typography.Text>
        </Space>
        {status?.configured && status.accountName && <Typography.Text type="secondary">
            {t('cloudDriveWorkflows.baiduAccount.account', { name: status.accountName })}
        </Typography.Text>}
        <Typography.Text type="secondary">{t('cloudDriveWorkflows.baiduAccount.sessionHelp')}</Typography.Text>
        {pending && <Alert type="info" showIcon title={t('cloudDriveWorkflows.baiduAccount.waitingTitle')}
            description={t('cloudDriveWorkflows.baiduAccount.waitingHelp')} />}
        {state === 'FAILED' && <Alert type="warning" showIcon title={t(`cloudDriveWorkflows.baiduAccount.${failure}`)} />}
        {state === 'EXPIRED' && <Alert type="warning" showIcon title={t('cloudDriveWorkflows.baiduAccount.expired')} />}
        {error && <Alert type="error" showIcon role="alert" title={t(`cloudDriveWorkflows.baiduAccount.${error}`)} />}
        <Space wrap>
            <Button className="min-h-11" onClick={() => void startLogin()} loading={starting}
                disabled={loading || pending || starting} data-testid="baidu-start-login">
                {t('cloudDriveWorkflows.baiduAccount.scanLogin')}
            </Button>
            <Button className="min-h-11" icon={<ReloadOutlined />} onClick={() => void readStatus()}
                loading={loading} disabled={loading || starting} data-testid="baidu-refresh-session">
                {t('cloudDriveWorkflows.baiduAccount.refresh')}
            </Button>
        </Space>
    </Space>;
}
