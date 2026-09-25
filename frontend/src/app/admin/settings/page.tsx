'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
    App,
    Button,
    Card,
    Empty,
    Input,
    InputNumber,
    Space,
    Spin,
    Switch,
    Tag,
    Typography,
} from 'antd';
import { ReloadOutlined, SaveOutlined, SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/store/authStore';
import { api } from '@/lib/api';

const { Title, Text } = Typography;

const CONFIG_DESCRIPTIONS_ZH: Record<string, string> = {
    'auth.register.enabled': '是否允许访客自行注册；关闭后仍可使用有效邀请码，管理员也可在用户管理中创建账号。',
    'auth.register.max_users': '允许公开自助注册的最大用户总数；0 表示不限制。达到上限后仅停止公开注册，仍可使用邀请码注册，管理员在用户管理中建号不受影响。',
    'resource.audit.enabled': '用户提交的资源是否需要管理员审核后才公开。',
    'resource.max.per.user': '每个普通发布者最多可保留的有效资源数量；管理员不受此总数限制。',
    'resource.search.rate_limit_per_minute': '网页资源搜索：每用户每分钟最多搜索次数（1–60），默认 5；保存后立即生效。资源序号选择和翻页不计入，重新搜索或查看其他资源计入；QQ 频率独立配置。',
    'comment.rate_limit_per_minute': '留言与评论：每个账号每分钟最多提交次数（1–120），默认 5；保存后立即对新留言生效。',
    'resource.report.threshold': '同一资源达到该举报次数后标记为疑似失效。',
    'resource.submit.interval.seconds': '同一发布账号两次提交资源之间的最短间隔（秒）。',
    'resource.form.quick_params': '资源标题表单可一键插入的快捷参数，支持逗号或换行分隔。',
    'resource.hub.enabled': '影视资源中心总开关；关闭后停止发现、转存和发布流水线。',
    'resource.hub.auto_approve': '影视资源中心自动入库的资源是否直接通过审核。',
    'resource.hub.discovery.max_attempts': '单个资源发现任务允许重试的最大次数。',
    'resource.hub.validation.enabled': '是否定期检查已入库网盘链接的有效性。',
    'resource.hub.gying.discovery_enabled': '自动发现资源时是否优先从 GYING 获取候选。',
    'resource.hub.gying.auto_sync_enabled': '是否按计划从 GYING 自动同步影片元数据。',
    'resource.hub.gying.auto_sync_interval_hours': 'GYING 自动同步任务之间的最小间隔（小时）。',
    'resource.hub.gying.auto_sync_max_items': '每轮 GYING 自动同步最多处理的影片数。',
    'resource.hub.gying.auto_sync_page': 'GYING 自动同步读取的目录页码。',
    'resource.hub.gying.auto_sync_sources': 'GYING 自动同步的数据源类型列表。',
    'resource.hub.tmdb.auto_sync_enabled': '是否按计划从 TMDB 自动同步影片元数据。',
    'resource.hub.tmdb.auto_sync_interval_hours': 'TMDB 自动同步任务之间的最小间隔（小时）。',
    'resource.hub.tmdb.auto_sync_max_items': '每轮 TMDB 自动同步最多处理的影片数。',
    'resource.hub.tmdb.auto_sync_page': 'TMDB 自动同步读取的目录页码。',
    'resource.hub.tmdb.auto_sync_sources': 'TMDB 自动同步的数据源类型列表。',
    'resource.hub.tmdb.auto_discovery_enabled': 'TMDB 同步影片后是否自动创建资源发现任务。',
    'resource.hub.tmdb.discovery_cooldown_hours': '同一影片再次自动发现资源前的冷却时间（小时）。',
    'resource.hub.tmdb.discovery_max_results': '单次 PanSou 资源发现最多保留的候选数量。',
    'resource.hub.worker.enabled': '影视资源中心后台 Worker 开关。',
    'resource.hub.worker.task_limit': 'Worker 每轮最多执行的资源发现任务数。',
    'resource.hub.worker.quark_limit': 'Worker 每轮最多提交的夸克转存任务数。',
    'resource.hub.worker.xunlei_limit': 'Worker 每轮最多提交的迅雷转存任务数。',
    'resource.hub.worker.publish_limit': 'Worker 每轮最多发布到正式资源库的发现结果数。',
    'resource.hub.worker.discovered_retry_enabled': '是否启用已发现但未完成转存资源的定时重试。',
    'resource.hub.worker.discovered_retry_cron': '已发现资源定时重试的 Cron 表达式。',
    'resource.hub.worker.discovered_retry_delay_ms': '批量重试每条资源之间的等待时间（毫秒）。',
    'resource.hub.worker.discovered_retry_limit': '每轮定时重试最多处理的发现结果数。',
    'qq.bot.min_keyword_length': 'QQ群机器人接受的最短搜索关键词字数。',
    'qq.bot.rate_limit_per_minute': '每个群成员每分钟最多可发起的搜索次数；至少为 1。',
    'qq.bot.max_results': 'QQ群机器人单次回复展示的资源候选数量。',
    'qq.bot.blocked_keywords': 'QQ群机器人拒绝搜索的关键词，支持逗号、分号或换行分隔。',
    'qq.bot.transfer_cleanup.enabled': '是否自动清理QQ群用户搜索后临时转存的网盘文件；不影响正式资源库。',
    'qq.bot.transfer_cleanup.delay_minutes': 'QQ群临时转存成功后延迟多少分钟删除文件和临时资源链接。',
    'qq.bot.transfer_cleanup.quark_root': 'QQ群夸克临时转存专用根目录；安全清理只允许发生在此目录下。',
    'qq.bot.transfer_cleanup.xunlei_root': 'QQ群迅雷临时转存专用根目录；安全清理只允许发生在此目录下。',
    'qq.bot.daily_recommendation.enabled': '是否开启QQ群每日影片推荐。',
    'qq.bot.daily_recommendation.time': 'QQ群每日推荐的执行时间，格式为 HH:mm。',
    'qq.bot.daily_recommendation.count': '每个群每天推荐的影片数量。',
    'qq.bot.daily_recommendation.group_ids': '接收每日推荐的 QQ 群号，多个群用逗号分隔。',
    'qq.bot.daily_recommendation.template': 'QQ群每日推荐消息模板，支持模板变量。',
    'qq.channel.auto_post.enabled': '是否开启 QQ 频道自动发布。',
    'qq.channel.auto_post.interval_minutes': 'QQ 频道自动发布批次之间的间隔（分钟）。',
    'qq.channel.auto_post.max_posts_per_run': 'QQ 频道每轮最多发布的帖子数。',
    'qq.channel.auto_post.daily_time': 'QQ 频道每日自动发布的开始时间，格式为 HH:mm。',
    'qq.channel.auto_post.post_total': 'QQ 频道每天计划发布的帖子总数。',
    'qq.channel.auto_post.post_interval_seconds': 'QQ 频道连续两篇帖子之间的等待时间（秒）。',
    'qq.channel.auto_post.template': 'QQ 频道帖子正文模板，支持标题、链接和简介等变量。',
    'qq.channel.auto_post.candidate_limit': 'QQ 频道每轮选取的候选资源数量上限。',
    'qq.channel.guild_id': '用于自动发布的 QQ 频道（Guild）ID。',
    'qq.channel.movie_channel_id': '电影内容发布到的 QQ 频道子频道 ID。',
    'qq.channel.tv_channel_id': '剧集和动漫内容发布到的 QQ 频道子频道 ID。',
};

const GROUP_LABELS_ZH: Record<string, string> = {
    auth: '注册与账号',
    comment: '留言与评论',
    resource: '资源与自动化',
    qq: 'QQ 自动化',
};

const configDescription = (config: ConfigItem) => {
    if (config.configKey.startsWith('qq.bot.daily_recommendation.last_run.')) {
        return '指定 QQ 群每日推荐最近一次成功执行日期，由系统自动维护。';
    }
    return CONFIG_DESCRIPTIONS_ZH[config.configKey]
        || config.description
        || `系统配置项 ${config.configKey}，修改前请确认对应功能用途。`;
};

interface ConfigItem {
    id: number;
    configKey: string;
    configValue: string;
    description?: string;
    updatedAt?: string;
    sensitive?: boolean;
}

const isBooleanValue = (value: string) => value === 'true' || value === 'false';
const isSensitiveConfig = (config: ConfigItem) => config.sensitive === true || config.configValue === '[REDACTED]';

const isNumericConfig = (config: ConfigItem) => {
    if (!/^-?\d+(\.\d+)?$/.test(config.configValue)) return false;
    return /(min|max|limit|count|total|page|items|seconds|minutes|hours|interval|per\.user)/i
        .test(config.configKey);
};

const numericRange = (key: string): [number | undefined, number | undefined] => {
    if (key === 'auth.register.max_users') return [0, 100000];
    if (key === 'resource.search.rate_limit_per_minute') return [1, 60];
    if (key === 'comment.rate_limit_per_minute') return [1, 120];
    return [undefined, undefined];
};

const isMultilineConfig = (config: ConfigItem) => (
    config.configValue.includes('\n')
    || /(template|blocked.keywords|description)/i.test(config.configKey)
);

export default function SystemSettingsPage() {
    const { user, token } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState<string>();
    const [configs, setConfigs] = useState<ConfigItem[]>([]);
    const [draftValues, setDraftValues] = useState<Record<string, string>>({});
    const [keyword, setKeyword] = useState('');
    const [quickParamDraft, setQuickParamDraft] = useState('');

    const fetchConfig = useCallback(async () => {
        if (!token) return;

        setLoading(true);
        try {
            const response = await api('/api/admin/config', {
                headers: { Authorization: `Bearer ${token}` },
            });
            if (!response.ok) {
                message.error(t('configurationLoadFailed'));
                return;
            }

            const items: ConfigItem[] = await response.json();
            const sorted = [...items].sort((left, right) => left.configKey.localeCompare(right.configKey));
            setConfigs(sorted);
            setDraftValues(Object.fromEntries(sorted.map(item => [item.configKey, item.configValue])));
        } catch {
            message.error(t('networkError'));
        } finally {
            setLoading(false);
        }
    }, [message, t, token]);

    useEffect(() => {
        const timer = setTimeout(() => {
            if (!user || user.role !== 'ADMIN') {
                message.error(t('adminAccessRequired'));
                router.push('/');
                return;
            }
            fetchConfig();
        }, 100);

        return () => clearTimeout(timer);
    }, [fetchConfig, message, router, t, user]);

    const groupedConfigs = useMemo(() => {
        const normalizedKeyword = keyword.trim().toLowerCase();
        const filtered = normalizedKeyword
            ? configs.filter(config => (
                config.configKey.toLowerCase().includes(normalizedKeyword)
                || configDescription(config).toLowerCase().includes(normalizedKeyword)
            ))
            : configs;

        return filtered.reduce<Record<string, ConfigItem[]>>((groups, config) => {
            const group = config.configKey.split('.')[0] || 'other';
            groups[group] = [...(groups[group] || []), config];
            return groups;
        }, {});
    }, [configs, keyword]);

    const updateDraft = (key: string, value: string) => {
        setDraftValues(current => ({ ...current, [key]: value }));
    };

    const updateConfig = async (config: ConfigItem) => {
        if (isSensitiveConfig(config)) {
            message.warning('敏感配置只能通过受保护的环境变量或 Docker Secret 管理。');
            return;
        }
        const value = draftValues[config.configKey] ?? '';
        setSaving(config.configKey);
        try {
            const response = await api(`/api/admin/config/${encodeURIComponent(config.configKey)}`, {
                method: 'PUT',
                headers: {
                    Authorization: `Bearer ${token}`,
                    'Content-Type': 'text/plain',
                },
                body: value,
            });
            if (!response.ok) {
                message.error(t('configurationUpdateFailed'));
                return;
            }

            setConfigs(current => current.map(item => (
                item.configKey === config.configKey ? { ...item, configValue: value } : item
            )));
            message.success(t('configurationUpdated'));
        } catch {
            message.error(t('networkError'));
        } finally {
            setSaving(undefined);
        }
    };

    const renderEditor = (config: ConfigItem) => {
        const value = draftValues[config.configKey] ?? '';
        if (isSensitiveConfig(config)) {
            return (
                <Text type="secondary">
                    由受保护的环境变量或 Docker Secret 管理，不在后台展示或修改。
                </Text>
            );
        }
        if (config.configKey === 'resource.form.quick_params') {
            const params = value.split(/[,，\n]+/).map(item => item.trim()).filter(Boolean);
            return (
                <div className="space-y-2">
                    <Space wrap>
                        {params.map(param => (
                            <Tag
                                key={param}
                                closable
                                onClose={() => updateDraft(config.configKey, params.filter(item => item !== param).join(','))}
                            >
                                {param}
                            </Tag>
                        ))}
                    </Space>
                    <Input.Search
                        allowClear
                        value={quickParamDraft}
                        placeholder="添加快速参数"
                        enterButton="添加"
                        onChange={event => setQuickParamDraft(event.target.value)}
                        onSearch={next => {
                            const param = next.trim();
                            if (!param || params.includes(param)) return;
                            updateDraft(config.configKey, [...params, param].join(','));
                            setQuickParamDraft('');
                        }}
                    />
                </div>
            );
        }
        if (isBooleanValue(config.configValue)) {
            return (
                <Switch
                    checked={value === 'true'}
                    checkedChildren="ON"
                    unCheckedChildren="OFF"
                    onChange={checked => updateDraft(config.configKey, String(checked))}
                />
            );
        }
        if (isNumericConfig(config)) {
            const [min, max] = numericRange(config.configKey);
            const integerOnly = min !== undefined;
            return (
                <InputNumber
                    min={min}
                    max={max}
                    precision={integerOnly ? 0 : undefined}
                    value={value === '' ? null : Number(value)}
                    onChange={next => updateDraft(config.configKey, next === null ? '' : String(next))}
                    style={{ width: '100%' }}
                />
            );
        }
        if (isMultilineConfig(config)) {
            return (
                <Input.TextArea
                    autoSize={{ minRows: 2, maxRows: 8 }}
                    value={value}
                    onChange={event => updateDraft(config.configKey, event.target.value)}
                />
            );
        }
        return <Input value={value} onChange={event => updateDraft(config.configKey, event.target.value)} />;
    };

    if (loading) {
        return (
            <div className="container mx-auto flex justify-center px-4 py-8">
                <Spin size="large" />
            </div>
        );
    }

    return (
        <div className="container mx-auto px-4 py-8">
            <Card>
                <div className="flex flex-wrap items-start justify-between gap-4">
                    <div>
                        <Title level={2} className="!mb-1">{t('systemSettings')}</Title>
                        <Text type="secondary">{t('settingsDescription')}</Text>
                    </div>
                    <Space wrap>
                        <Tag color="blue">{t('systemConfigCount', { count: configs.length })}</Tag>
                        <Button icon={<ReloadOutlined />} onClick={fetchConfig}>
                            {t('refresh')}
                        </Button>
                    </Space>
                </div>

                <Input
                    allowClear
                    className="my-6"
                    prefix={<SearchOutlined />}
                    placeholder={t('systemConfigSearchPlaceholder')}
                    value={keyword}
                    onChange={event => setKeyword(event.target.value)}
                />

                {Object.keys(groupedConfigs).length === 0 ? (
                    <Empty description={t('systemConfigEmpty')} />
                ) : (
                    <div className="space-y-8">
                        {Object.entries(groupedConfigs).map(([group, items]) => (
                            <section key={group}>
                                <div className="mb-3 flex items-center gap-2">
                                    <Title level={4} className="!m-0">{GROUP_LABELS_ZH[group] || group.toUpperCase()}</Title>
                                    <Tag>{items.length}</Tag>
                                </div>
                                <div className="divide-y rounded-lg border">
                                    {items.map(config => {
                                        const sensitive = isSensitiveConfig(config);
                                        const changed = !sensitive && (draftValues[config.configKey] ?? '') !== config.configValue;
                                        return (
                                            <div
                                                key={config.configKey}
                                                className="grid gap-4 p-4 lg:grid-cols-[minmax(240px,1fr)_minmax(320px,1.4fr)_44px] lg:items-center"
                                            >
                                                <div className="min-w-0">
                                                    <Text code className="break-all">{config.configKey}</Text>
                                                    <div className="mt-1">
                                                        <Text type="secondary">
                                                            {configDescription(config)}
                                                        </Text>
                                                    </div>
                                                </div>
                                                <div className="min-w-0">{renderEditor(config)}</div>
                                                <Button
                                                    aria-label={t('save')}
                                                    type={changed ? 'primary' : 'default'}
                                                    icon={<SaveOutlined />}
                                                    disabled={sensitive || !changed}
                                                    loading={saving === config.configKey}
                                                    onClick={() => updateConfig(config)}
                                                />
                                            </div>
                                        );
                                    })}
                                </div>
                            </section>
                        ))}
                    </div>
                )}
            </Card>
        </div>
    );
}
