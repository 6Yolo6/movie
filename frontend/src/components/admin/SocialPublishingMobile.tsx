'use client';

import { Button, Card, Empty, Input, InputNumber, Pagination, Spin, Switch, Tag, Typography } from 'antd';
import { DeleteOutlined, LinkOutlined, RedoOutlined, SaveOutlined, SendOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { canRetrySocialPost, socialPostStatusColor, type SocialPostLog, type SocialPublishTarget } from '@/lib/socialPublishing';

export function SocialPostStatusTag({ status }: { status?: string }) {
    const { t } = useTranslation();
    return <Tag color={socialPostStatusColor(status)} style={{ whiteSpace: 'normal', overflowWrap: 'anywhere' }}>
        {t(`socialPublishingStatus.${status}`, { defaultValue: status || '-' })}
    </Tag>;
}

interface RetryProps {
    log: SocialPostLog;
    targetReady: boolean;
    loading: boolean;
    onRetry: (id: number) => void;
    mobile?: boolean;
}

export function SocialPostRetryButton({ log, targetReady, loading, onRetry, mobile }: RetryProps) {
    const { t } = useTranslation();
    const stateAllowed = canRetrySocialPost(log.status);
    const reason = !stateAllowed ? t('socialPublishingRetryBlocked')
        : !targetReady ? t('socialPublishingTargetUnavailable') : undefined;
    return <Button icon={<RedoOutlined />} loading={loading} disabled={!stateAllowed || !targetReady}
        data-testid={`social-retry-${log.id}`} title={reason} onClick={() => onRetry(log.id)}
        block={mobile} style={mobile ? { minHeight: 44 } : undefined}>
        {t('socialPublishingRetry')}
    </Button>;
}

interface TargetProps {
    targets: SocialPublishTarget[];
    busyId?: number | 'all';
    loading: boolean;
    onChange: <K extends keyof SocialPublishTarget>(id: number, field: K, value: SocialPublishTarget[K]) => void;
    onSave: (target: SocialPublishTarget) => void;
    onPublish: (id: number) => void;
    onRemove: (target: SocialPublishTarget) => void;
}

export function SocialTargetMobileCards({ targets, busyId, loading, onChange, onSave, onPublish, onRemove }: TargetProps) {
    const { t } = useTranslation();
    return <Spin spinning={loading}>
        <div className="grid min-w-0 gap-3" data-testid="social-target-mobile-list">
            {!targets.length && <Empty description={t('socialPublishingNoTargets')} />}
            {targets.map(target => <Card key={target.id} size="small" data-testid={`social-target-mobile-${target.id}`}>
                <div className="min-w-0 space-y-3 [overflow-wrap:anywhere]">
                    <div className="flex flex-wrap items-start justify-between gap-2">
                        <Typography.Text strong className="min-w-0 flex-1">{target.name}</Typography.Text>
                        <Tag color={target.platform === 'WEIBO' ? 'red' : 'blue'}>{target.platform === 'WEIBO' ? t('socialPublishingWeibo') : 'QQ'}</Tag>
                    </div>
                    <div className="text-sm">{t('socialPublishingAccountKey')}: {target.accountKey}</div>
                    <details>
                        <summary className="min-h-11 cursor-pointer py-2 font-medium">{t('socialPublishingEditSettings')}</summary>
                        <div className="grid grid-cols-2 gap-3 pt-2">
                            <label className="col-span-2 min-w-0">
                                <span className="mb-1 block">{t('socialPublishingTarget')}</span>
                                <Input aria-label={t('socialPublishingTarget')} value={target.name} size="large"
                                    onChange={event => onChange(target.id, 'name', event.target.value)} />
                            </label>
                            {target.platform === 'QQ_CHANNEL' && <>
                                <label className="col-span-2 min-w-0">
                                    <span className="mb-1 block">{t('socialPublishingChannelNumber')}</span>
                                    <Input aria-label={t('socialPublishingChannelNumber')} value={target.targetRef} size="large"
                                        onChange={event => onChange(target.id, 'targetRef', event.target.value)} />
                                </label>
                                <label className="col-span-2 min-w-0">
                                    <span className="mb-1 block">{t('socialPublishingBoardId')}</span>
                                    <Input aria-label={t('socialPublishingBoardId')} allowClear value={target.channelRef} size="large"
                                        placeholder={t('socialPublishingDefaultBoard')}
                                        onChange={event => onChange(target.id, 'channelRef', event.target.value)} />
                                </label>
                            </>}
                            <div className="col-span-2 flex min-h-11 flex-wrap items-center justify-between gap-2">
                                <span>{t('enabled')}</span><Switch aria-label={t('enabled')} checked={target.enabled}
                                    onChange={value => onChange(target.id, 'enabled', value)} />
                            </div>
                            <div className="col-span-2 flex min-h-11 flex-wrap items-center justify-between gap-2">
                                <span>{t('socialPublishingAutoPost')}</span><Switch aria-label={t('socialPublishingAutoPost')} checked={target.autoPostEnabled}
                                    onChange={value => onChange(target.id, 'autoPostEnabled', value)} />
                            </div>
                            <label className="min-w-0">
                                <span className="mb-1 block">{t('socialPublishingTime')}</span>
                                <Input aria-label={t('socialPublishingTime')} value={target.scheduleTime} size="large"
                                    onChange={event => onChange(target.id, 'scheduleTime', event.target.value)} />
                            </label>
                            <label className="min-w-0">
                                <span className="mb-1 block">{t('socialPublishingPosts')}</span>
                                <InputNumber aria-label={t('socialPublishingPosts')} min={1} max={20} value={target.postsPerRun} size="large" style={{ width: '100%' }}
                                    onChange={value => onChange(target.id, 'postsPerRun', value || 1)} />
                            </label>
                            <label className="col-span-2 min-w-0">
                                <span className="mb-1 block">{t('socialPublishingInterval')}</span>
                                <InputNumber aria-label={t('socialPublishingInterval')} min={0} max={86400} value={target.postIntervalSeconds} size="large" style={{ width: '100%' }}
                                    onChange={value => onChange(target.id, 'postIntervalSeconds', value || 0)} />
                            </label>
                            <label className="col-span-2 min-w-0">
                                <span className="mb-1 block">{t('socialPublishingTemplate')}</span>
                                <Input.TextArea aria-label={t('socialPublishingTemplate')} rows={3} value={target.template}
                                    onChange={event => onChange(target.id, 'template', event.target.value)} />
                            </label>
                        </div>
                    </details>
                    <div className="grid grid-cols-2 gap-2">
                        <Button data-testid={`social-target-save-${target.id}`} icon={<SaveOutlined />} loading={busyId === target.id} onClick={() => onSave(target)} style={{ minHeight: 44 }}>{t('save')}</Button>
                        <Button type="primary" icon={<SendOutlined />} loading={busyId === target.id} disabled={!target.enabled}
                            onClick={() => onPublish(target.id)} style={{ minHeight: 44 }}>{t('socialPublishingPublish')}</Button>
                        <Button danger icon={<DeleteOutlined />} loading={busyId === target.id} onClick={() => onRemove(target)}
                            className="col-span-2" style={{ minHeight: 44 }}>{t('delete')}</Button>
                    </div>
                </div>
            </Card>)}
        </div>
    </Spin>;
}

interface LogProps {
    logs: SocialPostLog[];
    targets: SocialPublishTarget[];
    loading: boolean;
    retryId?: number;
    page: number;
    total: number;
    onRetry: (id: number) => void;
    onPage: (page: number) => void;
    formatDate: (value?: string) => string;
}

export function SocialLogMobileCards({ logs, targets, loading, retryId, page, total, onRetry, onPage, formatDate }: LogProps) {
    const { t } = useTranslation();
    return <Spin spinning={loading}>
        <div className="grid min-w-0 gap-3" data-testid="social-log-mobile-list">
            {!logs.length && <Empty description={t('socialPublishingNoLogs')} />}
            {logs.map(log => {
                const target = targets.find(item => item.id === log.targetId);
                const ready = Boolean(target?.enabled);
                return <Card key={log.id} size="small" data-testid={`social-log-mobile-${log.id}`}>
                    <div className="min-w-0 space-y-3 [overflow-wrap:anywhere]">
                        <div className="flex flex-wrap items-start justify-between gap-2">
                            <Typography.Text strong className="min-w-0 flex-1">{log.title || log.movieId}</Typography.Text>
                            <SocialPostStatusTag status={log.status} />
                        </div>
                        <div className="text-sm text-gray-500">#{log.id} · {log.platform === 'WEIBO' ? t('socialPublishingWeibo') : 'QQ'} · {target?.name || t('socialPublishingDeletedTarget', { id: log.targetId })}</div>
                        <div className="text-sm">{t('movieId')}: {log.movieId}<br />{t('resourceId')}: {log.resourceLinkId}</div>
                        <div className="text-sm">{t(log.postedAt ? 'socialPublishingPostedAt' : 'createdAt')}: {formatDate(log.postedAt || log.createdAt)}</div>
                        {log.errorMessage && <div className="whitespace-pre-wrap text-sm"><strong>{t('socialPublishingError')}: </strong>{log.errorMessage}</div>}
                        {log.externalUrl && <Typography.Link href={log.externalUrl} target="_blank" rel="noreferrer">
                            <LinkOutlined /> {t('socialPublishingViewPost')}
                        </Typography.Link>}
                        <SocialPostRetryButton log={log} targetReady={ready} loading={retryId === log.id} onRetry={onRetry} mobile />
                        {(!canRetrySocialPost(log.status) || !ready) && <p className="text-sm text-gray-500">
                            {t(!canRetrySocialPost(log.status) ? 'socialPublishingRetryBlocked' : 'socialPublishingTargetUnavailable')}
                        </p>}
                    </div>
                </Card>;
            })}
            {total > 20 && <Pagination simple current={page} pageSize={20} total={total} onChange={onPage} showSizeChanger={false} />}
        </div>
    </Spin>;
}
