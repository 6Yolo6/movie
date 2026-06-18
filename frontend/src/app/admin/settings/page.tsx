'use client';

import { useCallback, useEffect, useState } from 'react';
import { Card, Switch, App, Typography, Divider, Spin, InputNumber, Space, Tag } from 'antd';
import { useRouter } from 'next/navigation';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/store/authStore';
import { api } from '@/lib/api';

const { Title, Text } = Typography;

interface ConfigItem {
    configKey: string;
    configValue: string;
    description: string;
}

const DEFAULTS = {
    auditEnabled: true,
    maxResources: 100,
    submitInterval: 60,
};

export default function SystemSettingsPage() {
    const { user, token } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState<string | null>(null);

    const [auditEnabled, setAuditEnabled] = useState(DEFAULTS.auditEnabled);
    const [maxResources, setMaxResources] = useState(DEFAULTS.maxResources);
    const [submitInterval, setSubmitInterval] = useState(DEFAULTS.submitInterval);

    const fetchConfig = useCallback(async () => {
        if (!token) return;

        setLoading(true);
        try {
            const res = await api('/api/admin/config', {
                headers: {
                    Authorization: `Bearer ${token}`,
                },
            });

            if (res.ok) {
                const configs: ConfigItem[] = await res.json();
                configs.forEach(config => {
                    switch (config.configKey) {
                        case 'resource.audit.enabled':
                            setAuditEnabled(config.configValue === 'true');
                            break;
                        case 'resource.max.per.user':
                            setMaxResources(parseInt(config.configValue));
                            break;
                        case 'resource.submit.interval.seconds':
                            setSubmitInterval(parseInt(config.configValue));
                            break;
                    }
                });
            } else {
                message.error(t('configurationLoadFailed'));
            }
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

    const updateConfig = async (key: string, value: string): Promise<boolean> => {
        setSaving(key);
        try {
            const res = await api(`/api/admin/config/${key}`, {
                method: 'PUT',
                headers: {
                    Authorization: `Bearer ${token}`,
                    'Content-Type': 'text/plain',
                },
                body: value,
            });

            if (res.ok) {
                message.success(t('configurationUpdated'));
                return true;
            } else {
                message.error(t('configurationUpdateFailed'));
                return false;
            }
        } catch {
            message.error(t('networkError'));
            return false;
        } finally {
            setSaving(null);
        }
    };

    const handleToggleAudit = async (checked: boolean) => {
        const prev = auditEnabled;
        setAuditEnabled(checked);
        const ok = await updateConfig('resource.audit.enabled', checked.toString());
        if (!ok) setAuditEnabled(prev);
    };

    const handleMaxResourcesChange = async (value: number | null) => {
        if (value !== null) {
            const prev = maxResources;
            setMaxResources(value);
            const ok = await updateConfig('resource.max.per.user', value.toString());
            if (!ok) setMaxResources(prev);
        }
    };

    const handleIntervalChange = async (value: number | null) => {
        if (value !== null) {
            const prev = submitInterval;
            setSubmitInterval(value);
            const ok = await updateConfig('resource.submit.interval.seconds', value.toString());
            if (!ok) setSubmitInterval(prev);
        }
    };

    if (loading) {
        return (
            <div className="container mx-auto px-4 py-8 flex justify-center">
                <Spin size="large" />
            </div>
        );
    }

    return (
        <div className="container mx-auto px-4 py-8">
            <Card>
                <Title level={2}>{t('systemSettings')}</Title>
                <Text type="secondary">{t('settingsDescription')}</Text>

                <Divider />

                <div className="space-y-6">
                    <div className="flex items-center justify-between p-4 border rounded-lg">
                        <div className="flex-1">
                            <div className="flex items-center gap-2 mb-2">
                                <Title level={5} className="!m-0">{t('resourceAudit')}</Title>
                                <Tag color="blue">Default: ON</Tag>
                            </div>
                            <Text type="secondary">{t('resourceAuditHelp')}</Text>
                        </div>
                        <Switch
                            checked={auditEnabled}
                            onChange={handleToggleAudit}
                            loading={saving === 'resource.audit.enabled'}
                            checkedChildren="ON"
                            unCheckedChildren="OFF"
                            className="ml-4"
                        />
                    </div>

                    <div className="flex items-center justify-between p-4 border rounded-lg">
                        <div className="flex-1">
                            <div className="flex items-center gap-2 mb-2">
                                <Title level={5} className="!m-0">{t('maxResourcesPerUser')}</Title>
                                <Tag color="blue">Default: {DEFAULTS.maxResources}</Tag>
                            </div>
                            <Text type="secondary">{t('maxResourcesHelp')}</Text>
                        </div>
                        <Space>
                            <InputNumber
                                min={1}
                                max={1000}
                                value={maxResources}
                                onChange={handleMaxResourcesChange}
                                disabled={saving === 'resource.max.per.user'}
                                className="ml-4"
                                style={{ width: 120 }}
                            />
                            <Text type="secondary">{t('resourcesUnit')}</Text>
                        </Space>
                    </div>

                    <div className="flex items-center justify-between p-4 border rounded-lg">
                        <div className="flex-1">
                            <div className="flex items-center gap-2 mb-2">
                                <Title level={5} className="!m-0">{t('submitRateLimit')}</Title>
                                <Tag color="blue">Default: {DEFAULTS.submitInterval}s</Tag>
                            </div>
                            <Text type="secondary">{t('submitRateHelp')}</Text>
                        </div>
                        <Space>
                            <InputNumber
                                min={0}
                                max={3600}
                                value={submitInterval}
                                onChange={handleIntervalChange}
                                disabled={saving === 'resource.submit.interval.seconds'}
                                className="ml-4"
                                style={{ width: 120 }}
                            />
                            <Text type="secondary">{t('secondsUnit')}</Text>
                        </Space>
                    </div>

                    <div className="p-4 bg-blue-50 dark:bg-blue-900/20 rounded-lg">
                        <Title level={5} className="!mt-0 !mb-2">{t('additionalProtections')}</Title>
                        <ul className="list-disc list-inside space-y-1">
                            <li>{t('newUsersPublisher')}</li>
                            <li>{t('duplicateBlocked')}</li>
                            <li>{t('publisherOnly')}</li>
                        </ul>
                    </div>
                </div>
            </Card>
        </div>
    );
}
