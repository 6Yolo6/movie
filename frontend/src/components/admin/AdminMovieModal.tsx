'use client';

import { useEffect, useMemo, useState } from 'react';
import {
    App,
    Button,
    Col,
    Divider,
    Form,
    Image,
    Input,
    InputNumber,
    Modal,
    Row,
    Select,
    Space,
    Upload,
} from 'antd';
import { SaveOutlined, UploadOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import { useTranslation } from 'react-i18next';
import { api, readApiError } from '@/lib/api';
import type { MovieMetadata } from '@/types';

type Props = {
    open: boolean;
    movie: MovieMetadata | null;
    token: string;
    onCancel: () => void;
    onSaved: (movie: MovieMetadata) => void;
};

type FormValues = {
    id?: string;
    tmdbId?: number;
    tmdbType?: string;
    titleCn: string;
    titleEn?: string;
    seriesName?: string;
    season?: number;
    year?: number;
    runtime?: string;
    directors?: string[];
    actors?: string[];
    genres?: string[];
    regions?: string[];
    languages?: string[];
    releaseDates?: string;
    aliases?: string;
    category: string;
    posterUrl?: string;
    doubanScore?: number;
    imdbScore?: number;
    tmdbPopularity?: number;
    tmdbVoteAverage?: number;
    rtScore?: string;
    summary?: string;
    status: string;
    resourceStatus: string;
    popularity?: number;
    tmdbLastSyncAt?: string;
};

const tagSelectProps = {
    mode: 'tags' as const,
    tokenSeparators: [',', '，'],
    maxTagCount: 'responsive' as const,
};

export default function AdminMovieModal({ open, movie, token, onCancel, onSaved }: Props) {
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [form] = Form.useForm<FormValues>();
    const [saving, setSaving] = useState(false);
    const [posterFile, setPosterFile] = useState<File | null>(null);
    const [previewUrl, setPreviewUrl] = useState('');
    const posterUrl = Form.useWatch('posterUrl', form);

    const fileList = useMemo<UploadFile[]>(() => posterFile ? [{
        uid: 'poster-upload',
        name: posterFile.name,
        status: 'done',
    }] : [], [posterFile]);

    useEffect(() => {
        if (!open) return;
        form.resetFields();
        form.setFieldsValue(movie ? {
            ...movie,
            tmdbLastSyncAt: movie.tmdbLastSyncAt?.slice(0, 16),
        } : {
            category: 'mv',
            status: 'ACTIVE',
            resourceStatus: 'UNKNOWN',
            popularity: 0,
        });
        setPosterFile(null);
        setPreviewUrl('');
    }, [form, movie, open]);

    useEffect(() => () => {
        if (previewUrl) URL.revokeObjectURL(previewUrl);
    }, [previewUrl]);

    const selectPoster = (file: File) => {
        if (file.size > 8 * 1024 * 1024) {
            message.error(t('posterTooLarge'));
            return Upload.LIST_IGNORE;
        }
        if (!['image/jpeg', 'image/png', 'image/webp', 'image/avif'].includes(file.type)) {
            message.error(t('posterTypeInvalid'));
            return Upload.LIST_IGNORE;
        }
        setPosterFile(file);
        setPreviewUrl(URL.createObjectURL(file));
        return false;
    };

    const submit = async (values: FormValues) => {
        setSaving(true);
        try {
            const requestPath = movie ? `/api/admin/movies/${movie.id}` : '/api/admin/movies';
            const saveResponse = await api(requestPath, {
                method: movie ? 'PUT' : 'POST',
                headers: { Authorization: `Bearer ${token}` },
                body: JSON.stringify(values),
            });
            if (!saveResponse.ok) {
                message.error(await readApiError(saveResponse, t('movieSaveFailed')));
                return;
            }
            let savedMovie = await saveResponse.json() as MovieMetadata;

            if (posterFile) {
                const formData = new FormData();
                formData.append('file', posterFile);
                const uploadResponse = await api(`/api/admin/movies/${savedMovie.id}/poster`, {
                    method: 'POST',
                    headers: { Authorization: `Bearer ${token}` },
                    body: formData,
                });
                if (!uploadResponse.ok) {
                    message.warning(await readApiError(uploadResponse, t('posterUploadFailed')));
                    onSaved(savedMovie);
                    return;
                }
                savedMovie = await uploadResponse.json() as MovieMetadata;
            }

            message.success(movie ? t('movieUpdated') : t('movieCreated'));
            onSaved(savedMovie);
        } catch {
            message.error(t('networkError'));
        } finally {
            setSaving(false);
        }
    };

    const visiblePoster = previewUrl || posterUrl;

    return (
        <Modal
            title={movie ? t('editMovieMetadata') : t('createMovieMetadata')}
            open={open}
            onCancel={onCancel}
            footer={null}
            width={980}
            destroyOnHidden
        >
            <div className="max-h-[72vh] overflow-y-auto pr-2">
                <Form form={form} layout="vertical" onFinish={submit} requiredMark={false}>
                    <Row gutter={16}>
                        <Col xs={24} md={8}>
                            <Form.Item name="id" label={t('movieId')}>
                                <Input disabled={Boolean(movie)} maxLength={64} placeholder={t('movieIdAutoPlaceholder')} />
                            </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                            <Form.Item name="titleCn" label={t('titleCn')} rules={[{ required: true }]}>
                                <Input maxLength={255} />
                            </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                            <Form.Item name="titleEn" label={t('titleEn')}>
                                <Input maxLength={500} />
                            </Form.Item>
                        </Col>
                    </Row>

                    <Row gutter={16}>
                        <Col xs={12} md={4}>
                            <Form.Item name="category" label={t('category')} rules={[{ required: true }]}>
                                <Select options={[
                                    { value: 'mv', label: t('movies') },
                                    { value: 'tv', label: t('tvShows') },
                                    { value: 'ac', label: t('anime') },
                                ]} />
                            </Form.Item>
                        </Col>
                        <Col xs={12} md={4}>
                            <Form.Item name="year" label={t('year')}>
                                <InputNumber min={1800} max={3000} className="w-full" />
                            </Form.Item>
                        </Col>
                        <Col xs={12} md={4}>
                            <Form.Item name="runtime" label={t('runtime')}>
                                <Input />
                            </Form.Item>
                        </Col>
                        <Col xs={12} md={4}>
                            <Form.Item name="status" label={t('status')} rules={[{ required: true }]}>
                                <Select options={[
                                    { value: 'ACTIVE', label: t('activeRecord') },
                                    { value: 'DELETED', label: t('deleted') },
                                ]} />
                            </Form.Item>
                        </Col>
                        <Col xs={12} md={4}>
                            <Form.Item name="resourceStatus" label={t('resourceStatus')} rules={[{ required: true }]}>
                                <Select options={['UNKNOWN', 'TRAILER', 'AVAILABLE'].map((value) => ({
                                    value,
                                    label: t(`movieResourceStatus.${value}`, { defaultValue: value }),
                                }))} />
                            </Form.Item>
                        </Col>
                        <Col xs={12} md={4}>
                            <Form.Item name="popularity" label={t('popularity')}>
                                <InputNumber min={0} className="w-full" />
                            </Form.Item>
                        </Col>
                    </Row>

                    <Row gutter={16}>
                        <Col xs={24} md={8}>
                            <Form.Item name="seriesName" label={t('seriesName')}>
                                <Input />
                            </Form.Item>
                        </Col>
                        <Col xs={12} md={4}>
                            <Form.Item name="season" label={t('season')}>
                                <InputNumber min={0} className="w-full" />
                            </Form.Item>
                        </Col>
                        <Col xs={12} md={4}>
                            <Form.Item name="tmdbType" label={t('tmdbType')}>
                                <Select allowClear options={[
                                    { value: 'movie', label: 'movie' },
                                    { value: 'tv', label: 'tv' },
                                ]} />
                            </Form.Item>
                        </Col>
                        <Col xs={12} md={4}>
                            <Form.Item name="tmdbId" label={t('tmdbId')}>
                                <InputNumber min={1} className="w-full" />
                            </Form.Item>
                        </Col>
                        <Col xs={12} md={4}>
                            <Form.Item name="tmdbLastSyncAt" label={t('tmdbLastSyncAt')}>
                                <Input type="datetime-local" />
                            </Form.Item>
                        </Col>
                    </Row>

                    <Divider titlePlacement="left">{t('movieCredits')}</Divider>
                    <Row gutter={16}>
                        <Col xs={24} md={12}>
                            <Form.Item name="directors" label={t('director')}>
                                <Select {...tagSelectProps} />
                            </Form.Item>
                        </Col>
                        <Col xs={24} md={12}>
                            <Form.Item name="actors" label={t('actors')}>
                                <Select {...tagSelectProps} />
                            </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                            <Form.Item name="genres" label={t('genre')}>
                                <Select {...tagSelectProps} />
                            </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                            <Form.Item name="regions" label={t('region')}>
                                <Select {...tagSelectProps} />
                            </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                            <Form.Item name="languages" label={t('language')}>
                                <Select {...tagSelectProps} />
                            </Form.Item>
                        </Col>
                    </Row>

                    <Row gutter={16}>
                        <Col xs={24} md={12}>
                            <Form.Item name="releaseDates" label={t('releaseDates')}>
                                <Input />
                            </Form.Item>
                        </Col>
                        <Col xs={24} md={12}>
                            <Form.Item name="aliases" label={t('aliases')}>
                                <Input />
                            </Form.Item>
                        </Col>
                    </Row>

                    <Divider titlePlacement="left">{t('movieRatings')}</Divider>
                    <Row gutter={16}>
                        <Col xs={12} md={5}>
                            <Form.Item name="doubanScore" label={t('doubanScore')}>
                                <InputNumber min={0} max={10} step={0.1} className="w-full" />
                            </Form.Item>
                        </Col>
                        <Col xs={12} md={5}>
                            <Form.Item name="imdbScore" label={t('imdbScore')}>
                                <InputNumber min={0} max={10} step={0.1} className="w-full" />
                            </Form.Item>
                        </Col>
                        <Col xs={12} md={5}>
                            <Form.Item name="tmdbVoteAverage" label={t('tmdbVoteAverage')}>
                                <InputNumber min={0} max={10} step={0.1} className="w-full" />
                            </Form.Item>
                        </Col>
                        <Col xs={12} md={5}>
                            <Form.Item name="tmdbPopularity" label={t('tmdbPopularity')}>
                                <InputNumber min={0} step={0.1} className="w-full" />
                            </Form.Item>
                        </Col>
                        <Col xs={12} md={4}>
                            <Form.Item name="rtScore" label={t('rtScore')}>
                                <Input />
                            </Form.Item>
                        </Col>
                    </Row>

                    <Divider titlePlacement="left">{t('posterAndSummary')}</Divider>
                    <Row gutter={20} align="top">
                        <Col xs={24} md={7}>
                            <div className="mb-3 h-[210px] w-[140px] overflow-hidden rounded border border-gray-200 bg-gray-50 dark:border-white/10 dark:bg-white/5">
                                {visiblePoster ? (
                                    <Image
                                        src={visiblePoster}
                                        alt={t('posterPreview')}
                                        width={140}
                                        height={210}
                                        className="object-cover"
                                    />
                                ) : null}
                            </div>
                            <Upload
                                accept="image/jpeg,image/png,image/webp,image/avif"
                                beforeUpload={selectPoster}
                                onRemove={() => {
                                    setPosterFile(null);
                                    setPreviewUrl('');
                                }}
                                fileList={fileList}
                                maxCount={1}
                            >
                                <Button icon={<UploadOutlined />}>{t('uploadPoster')}</Button>
                            </Upload>
                        </Col>
                        <Col xs={24} md={17}>
                            <Form.Item name="posterUrl" label={t('posterUrl')}>
                                <Input placeholder="https://..." />
                            </Form.Item>
                            <Form.Item name="summary" label={t('summary')}>
                                <Input.TextArea autoSize={{ minRows: 6, maxRows: 12 }} />
                            </Form.Item>
                        </Col>
                    </Row>

                    <Space className="flex w-full justify-end pt-2">
                        <Button onClick={onCancel}>{t('cancel')}</Button>
                        <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={saving}>
                            {t('save')}
                        </Button>
                    </Space>
                </Form>
            </div>
        </Modal>
    );
}
