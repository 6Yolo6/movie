'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
    Alert,
    App,
    Button,
    Card,
    Form,
    Input,
    InputNumber,
    Select,
    Space,
    Typography,
} from 'antd';
import {
    CloudDownloadOutlined,
    CloudUploadOutlined,
    SyncOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { api, readApiError } from '@/lib/api';
import { useAuthStore } from '@/store/authStore';

const { Title, Text } = Typography;

export default function GyingSourcePage() {
    const { user, token } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [resourceForm] = Form.useForm<{ resourceId: number }>();
    const [running, setRunning] = useState('');
    const [result, setResult] = useState<Record<string, unknown> | null>(null);

    useEffect(() => {
        if (!user) return;
        if (user.role !== 'ADMIN') {
            message.error(t('adminAccessRequired'));
            router.push('/');
        }
    }, [message, router, t, user]);

    const run = async (key: string, path: string, body?: Record<string, unknown>) => {
        if (!token) return;
        setRunning(key);
        setResult(null);
        try {
            const response = await api(path, {
                method: 'POST',
                headers: { Authorization: `Bearer ${token}` },
                body: body ? JSON.stringify(body) : undefined,
            });
            if (!response.ok) {
                message.error(await readApiError(response, t('operationFailed')));
                return;
            }
            const data = await response.json();
            setResult(data);
            message.success(t('gyingSourceActionSucceeded'));
        } catch {
            message.error(t('networkError'));
        } finally {
            setRunning('');
        }
    };

    return (
        <div className="min-h-screen bg-[#f5f7fa] dark:bg-black">
            <div className="container mx-auto max-w-5xl px-4 py-8 lg:px-8">
                <Card>
                    <Title level={2} className="!mb-1">{t('gyingSourceTitle')}</Title>
                    <Text type="secondary">{t('gyingSourceHint')}</Text>

                    <div className="mt-7 grid gap-8 lg:grid-cols-2">
                        <section>
                            <Title level={4}>{t('gyingSourceIngest')}</Title>
                            <Form
                                layout="vertical"
                                initialValues={{ typeCode: 'mv', uploadPoster: true }}
                                onFinish={(values) => run('ingest', '/api/admin/gying-source/ingest', values)}
                            >
                                <Form.Item name="typeCode" label={t('category')} rules={[{ required: true }]}>
                                    <Select options={[
                                        { value: 'mv', label: t('movies') },
                                        { value: 'tv', label: t('tvShows') },
                                        { value: 'ac', label: t('anime') },
                                    ]} />
                                </Form.Item>
                                <Form.Item name="mid" label={t('gyingMovieId')} rules={[{ required: true }]}>
                                    <Input placeholder="EGER" maxLength={64} />
                                </Form.Item>
                                <Button
                                    type="primary"
                                    htmlType="submit"
                                    icon={<CloudDownloadOutlined />}
                                    loading={running === 'ingest'}
                                >
                                    {t('gyingSourceIngestAction')}
                                </Button>
                            </Form>
                        </section>

                        <section>
                            <Title level={4}>{t('gyingSourcePublish')}</Title>
                            <Form
                                form={resourceForm}
                                layout="vertical"
                                onFinish={(values) => run(
                                    'publish',
                                    `/api/admin/gying-source/resources/${values.resourceId}/publish`,
                                )}
                            >
                                <Form.Item name="resourceId" label={t('resourceId')} rules={[{ required: true }]}>
                                    <InputNumber min={1} precision={0} className="w-full" />
                                </Form.Item>
                                <Space wrap>
                                    <Button
                                        type="primary"
                                        htmlType="submit"
                                        icon={<CloudUploadOutlined />}
                                        loading={running === 'publish'}
                                    >
                                        {t('gyingSourcePublishAction')}
                                    </Button>
                                    <Button
                                        icon={<SyncOutlined />}
                                        loading={running === 'update'}
                                        onClick={() => {
                                            const resourceId = resourceForm.getFieldValue('resourceId');
                                            if (!resourceId) {
                                                message.warning(t('gyingResourceIdRequired'));
                                                return;
                                            }
                                            run(
                                                'update',
                                                `/api/admin/gying-source/resources/${resourceId}/update`,
                                            );
                                        }}
                                    >
                                        {t('gyingSourceUpdateAction')}
                                    </Button>
                                </Space>
                            </Form>
                        </section>
                    </div>

                    {result && (
                        <Alert
                            className="mt-8"
                            type="success"
                            showIcon
                            message={t('gyingSourceLastResult')}
                            description={<pre className="m-0 overflow-auto text-xs">{JSON.stringify(result, null, 2)}</pre>}
                        />
                    )}
                </Card>
            </div>
        </div>
    );
}
