'use client';

import { useCallback, useEffect, useState } from 'react';
import { App, Button, Col, Form, Input, Modal, Row, Select, Space } from 'antd';
import { PlusOutlined, SaveOutlined } from '@ant-design/icons';
import type { Rule } from 'antd/es/form';
import { api, readApiError } from '@/lib/api';
import type { MovieMetadata, ResourceLink } from '@/types';
import { useTranslation } from 'react-i18next';

type AdminResource = ResourceLink & { movieTitle?: string };

type Props = {
    open: boolean;
    resource: AdminResource | null;
    token: string;
    onCancel: () => void;
    onSaved: () => void;
    onCreateMovie?: () => void;
    createdMovie?: MovieMetadata | null;
};

type FormValues = {
    movieId: string;
    name: string;
    type: string;
    url: string;
    code?: string;
    provider?: string;
    quality?: string;
    subtitle?: string;
    fileSize?: string;
    versionNote?: string;
};

const PROVIDERS = ['BAIDU', 'QUARK', 'ALIYUN', 'XUNLEI', 'UC', '115', '123PAN', 'TIANYI', 'MOBILE', 'PIKPAK'];

const movieOption = (movie: MovieMetadata) => ({
    value: movie.id,
    label: `${movie.titleCn}${movie.year ? ` (${movie.year})` : ''} - ${movie.id}`,
});

export default function AdminResourceModal({
    open,
    resource,
    token,
    onCancel,
    onSaved,
    onCreateMovie,
    createdMovie,
}: Props) {
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [form] = Form.useForm<FormValues>();
    const type = Form.useWatch('type', form) || 'DISK';
    const [saving, setSaving] = useState(false);
    const [movieLoading, setMovieLoading] = useState(false);
    const [movieOptions, setMovieOptions] = useState<{ value: string; label: string }[]>([]);

    const loadMovies = useCallback(async (keyword = '') => {
        setMovieLoading(true);
        try {
            const query = new URLSearchParams({ page: '1', size: '30' });
            if (keyword.trim()) query.set('keyword', keyword.trim());
            const res = await api(`/api/admin/movies?${query}`, {
                headers: { Authorization: `Bearer ${token}` },
            });
            if (!res.ok) return;
            const data = await res.json();
            setMovieOptions((data.records || []).map(movieOption));
        } finally {
            setMovieLoading(false);
        }
    }, [token]);

    useEffect(() => {
        if (!open) return;
        form.resetFields();
        form.setFieldsValue(resource ? {
            movieId: resource.movieId,
            name: resource.name || '',
            type: resource.type || 'DISK',
            url: resource.url,
            code: resource.code,
            provider: resource.provider || 'BAIDU',
            quality: resource.quality,
            subtitle: resource.subtitle,
            fileSize: resource.fileSize,
            versionNote: resource.versionNote,
        } : { type: 'DISK', provider: 'BAIDU', name: '' });
        setMovieOptions(resource ? [{
            value: resource.movieId,
            label: `${resource.movieTitle || resource.movieId} - ${resource.movieId}`,
        }] : []);
        loadMovies();
    }, [form, loadMovies, open, resource]);

    useEffect(() => {
        if (!open || !createdMovie) return;
        const option = movieOption(createdMovie);
        setMovieOptions((current) => [option, ...current.filter((item) => item.value !== option.value)]);
        form.setFieldValue('movieId', createdMovie.id);
    }, [createdMovie, form, open]);

    const urlRules = (): Rule[] => {
        if (type === 'MAGNET') {
            return [{
                required: true,
            }, {
                validator: (_, value?: string) => !value || value.toLowerCase().startsWith('magnet:?xt=urn:btih:')
                    ? Promise.resolve()
                    : Promise.reject(new Error(t('magnetLinkRequired'))),
            }];
        }
        if (type === 'TORRENT') {
            return [{
                required: true,
            }, {
                validator: (_, value?: string) => !value || (/^https?:\/\//i.test(value) && value.toLowerCase().includes('.torrent'))
                    ? Promise.resolve()
                    : Promise.reject(new Error(t('torrentLinkRequired'))),
            }];
        }
        return [{ required: true, type: 'url' }];
    };

    const submit = async (values: FormValues) => {
        setSaving(true);
        try {
            const payload = {
                ...values,
                provider: values.type === 'DISK' ? values.provider : 'OTHER',
                code: values.type === 'DISK' ? values.code : '',
            };
            const res = await api(resource ? `/api/resources/${resource.id}` : '/api/resources/admin', {
                method: resource ? 'PUT' : 'POST',
                headers: { Authorization: `Bearer ${token}` },
                body: JSON.stringify(payload),
            });
            if (!res.ok) {
                message.error(await readApiError(res, t('resourceSaveFailed')));
                return;
            }
            message.success(resource ? t('resourceUpdated') : t('resourceCreated'));
            form.resetFields();
            onSaved();
        } catch {
            message.error(t('networkError'));
        } finally {
            setSaving(false);
        }
    };

    return (
        <Modal
            title={resource ? t('editResource') : t('createResource')}
            open={open}
            onCancel={onCancel}
            footer={null}
            width={780}
            destroyOnHidden
        >
            <Form form={form} layout="vertical" onFinish={submit} requiredMark={false}>
                <Form.Item name="movieId" label={t('movieTitle')} rules={[{ required: true }]}>
                    <Select
                        showSearch
                        filterOption={false}
                        loading={movieLoading}
                        options={movieOptions}
                        onSearch={loadMovies}
                        placeholder={t('selectMovie')}
                        notFoundContent={movieLoading ? t('loading') : t('noMoviesFound')}
                    />
                </Form.Item>
                {onCreateMovie && (
                    <Button className="mb-4" icon={<PlusOutlined />} onClick={onCreateMovie}>
                        {t('createAssociatedMovie')}
                    </Button>
                )}
                <Form.Item name="name" label={t('resourceName')} rules={[{ required: true }]}>
                    <Input />
                </Form.Item>
                <Row gutter={16}>
                    <Col xs={24} md={8}>
                        <Form.Item name="type" label={t('resourceType')} rules={[{ required: true }]}>
                            <Select options={['DISK', 'MAGNET', 'TORRENT', 'ONLINE'].map((value) => ({ value, label: value }))} />
                        </Form.Item>
                    </Col>
                    {type === 'DISK' && (
                        <Col xs={24} md={8}>
                            <Form.Item name="provider" label={t('provider')} rules={[{ required: true }]}>
                                <Select showSearch options={PROVIDERS.map((value) => ({ value, label: value }))} />
                            </Form.Item>
                        </Col>
                    )}
                    {type === 'DISK' && (
                        <Col xs={24} md={8}>
                            <Form.Item name="code" label={t('accessCode')}>
                                <Input />
                            </Form.Item>
                        </Col>
                    )}
                </Row>
                <Form.Item name="url" label={t('resourceURL')} rules={urlRules()}>
                    <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} />
                </Form.Item>
                <Row gutter={16}>
                    <Col xs={24} md={8}>
                        <Form.Item name="quality" label={t('quality')}><Input placeholder="4K / 1080P" /></Form.Item>
                    </Col>
                    <Col xs={24} md={8}>
                        <Form.Item name="subtitle" label={t('subtitle')}><Input /></Form.Item>
                    </Col>
                    <Col xs={24} md={8}>
                        <Form.Item name="fileSize" label={t('fileSize')}><Input placeholder="8.5GB" /></Form.Item>
                    </Col>
                </Row>
                <Form.Item name="versionNote" label={t('versionNote')}>
                    <Input placeholder={t('versionNotePlaceholder')} />
                </Form.Item>
                <Space className="flex w-full justify-end">
                    <Button onClick={onCancel}>{t('cancel')}</Button>
                    <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={saving}>
                        {t('save')}
                    </Button>
                </Space>
            </Form>
        </Modal>
    );
}
