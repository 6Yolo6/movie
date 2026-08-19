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

interface ConfigItem {
    id: number;
    configKey: string;
    configValue: string;
    description?: string;
    updatedAt?: string;
}

const isBooleanValue = (value: string) => value === 'true' || value === 'false';

const isNumericConfig = (config: ConfigItem) => {
    if (!/^-?\d+(\.\d+)?$/.test(config.configValue)) return false;
    return /(min|max|limit|count|total|page|items|seconds|minutes|hours|interval|per\.user)/i
        .test(config.configKey);
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
                || (config.description || '').toLowerCase().includes(normalizedKeyword)
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
            return (
                <InputNumber
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
                                    <Title level={4} className="!m-0">{group.toUpperCase()}</Title>
                                    <Tag>{items.length}</Tag>
                                </div>
                                <div className="divide-y rounded-lg border">
                                    {items.map(config => {
                                        const changed = (draftValues[config.configKey] ?? '') !== config.configValue;
                                        return (
                                            <div
                                                key={config.configKey}
                                                className="grid gap-4 p-4 lg:grid-cols-[minmax(240px,1fr)_minmax(320px,1.4fr)_44px] lg:items-center"
                                            >
                                                <div className="min-w-0">
                                                    <Text code className="break-all">{config.configKey}</Text>
                                                    <div className="mt-1">
                                                        <Text type="secondary">
                                                            {config.description || t('systemConfigNoDescription')}
                                                        </Text>
                                                    </div>
                                                </div>
                                                <div className="min-w-0">{renderEditor(config)}</div>
                                                <Button
                                                    aria-label={t('save')}
                                                    type={changed ? 'primary' : 'default'}
                                                    icon={<SaveOutlined />}
                                                    disabled={!changed}
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
