'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, App, Button, Col, Form, Input, Modal, Row, Select, Space, Tag } from 'antd';
import type { InputRef } from 'antd';
import { CopyOutlined, PlusOutlined, SaveOutlined } from '@ant-design/icons';
import type { Rule } from 'antd/es/form';
import { api, readApiError } from '@/lib/api';
import type { MovieMetadata, ResourceLink } from '@/types';
import { useTranslation } from 'react-i18next';
import { inferResourceProvider, insertQuickParam, materializeQuickParam, normalizeResourceUrlWithCode, parseResourceQuickParams, readResourceClipboard, RESOURCE_QUICK_PARAMS } from '@/lib/resourceForm';

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
    bindMovieIds?: string[];
    bindingVersion?: string;
};

type BindingMovie = { id: string; titleCn?: string; titleEn?: string; season?: number; year?: number };
const bindingOption = (item: BindingMovie) => ({
    value: item.id,
    label: `${item.titleCn || item.titleEn || item.id}${item.season ? ` S${item.season}` : ''}${item.year ? ` (${item.year})` : ''} - ${item.id}`,
});

const PROVIDERS = ['BAIDU', 'QUARK', 'ALIYUN', 'XUNLEI', 'UC', '115', '123PAN', 'TIANYI', 'MOBILE', 'PIKPAK'];

const movieOption = (movie: MovieMetadata) => ({
    value: movie.id,
    label: `${movie.titleCn}${movie.year ? ` (${movie.year})` : ''} - ${movie.id}`,
    titleCn: movie.titleCn,
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
    const nameInputRef = useRef<InputRef>(null);
    const type = Form.useWatch('type', form) || 'DISK';
    const url = Form.useWatch('url', form);
    const [saving, setSaving] = useState(false);
    const [movieLoading, setMovieLoading] = useState(false);
    const [movieOptions, setMovieOptions] = useState<{ value: string; label: string; titleCn?: string }[]>([]);
    const [quickParams, setQuickParams] = useState(RESOURCE_QUICK_PARAMS);
    const [bindCandidates, setBindCandidates] = useState<{ value: string; label: string }[]>([]);
    const [bindLoading, setBindLoading] = useState(false);
    const [bindingOptions, setBindingOptions] = useState<{ value: string; label: string }[]>([]);
    const [bindingsReady, setBindingsReady] = useState(false);
    const [bindingsError, setBindingsError] = useState(false);
    const [bindingsRetry, setBindingsRetry] = useState(0);
    const [quickBindingInput, setQuickBindingInput] = useState('');
    const [quickBindingAdding, setQuickBindingAdding] = useState(false);
    const bindRequest = useRef(0);
    const primaryMovieId = Form.useWatch('movieId', form);
    const selectedBindingIds = Form.useWatch('bindMovieIds', form) || [];
    const allBindingOptions = Array.from(new Map([...bindingOptions, ...bindCandidates]
        .filter(item => item.value !== primaryMovieId).map(item => [item.value, item])).values());

    useEffect(() => {
        if (type === 'DISK') {
            const provider = inferResourceProvider(url);
            if (provider) form.setFieldValue('provider', provider);
        }
    }, [form, type, url]);

    const pasteClipboard = async () => {
        try {
            const parsed = await readResourceClipboard();
            form.setFieldsValue({
                ...(parsed.url ? { url: parsed.url } : {}),
                ...(parsed.code ? { code: parsed.code } : {}),
                ...(parsed.name ? { name: parsed.name } : {}),
                ...(parsed.provider ? { provider: parsed.provider } : {}),
            });
            message.success(t('resourceClipboardPasted'));
        } catch (error) {
            message.error(error instanceof Error ? error.message : t('resourceClipboardFailed'));
        }
    };

    const loadBindCandidates = useCallback(async (keyword = '') => {
        const movieId = form.getFieldValue('movieId');
        if (!movieId) return;
        const requestId = ++bindRequest.current;
        setBindLoading(true);
        try {
            const query = new URLSearchParams({ movieId, limit: '50' });
            if (keyword.trim()) query.set('keyword', keyword.trim());
            const response = await api(`/api/resources/bind-candidates?${query}`);
            if (!response.ok) return;
            const items = await response.json();
            if (requestId === bindRequest.current) setBindCandidates((items || []).map(bindingOption));
        } catch {
            // Existing selections stay intact if candidate search fails.
        } finally {
            if (requestId === bindRequest.current) setBindLoading(false);
        }
    }, [form]);

    const addBindingOption = (option: { value: string; label: string }) => {
        if (!option.value || option.value === primaryMovieId) return false;
        const current = form.getFieldValue('bindMovieIds') || [];
        if (current.includes(option.value)) {
            message.info(t('resourceBindQuickAddDuplicate'));
            return false;
        }
        form.setFieldValue('bindMovieIds', [...current, option.value]);
        setBindCandidates((items) => Array.from(new Map([...items, option].map(item => [item.value, item])).values()));
        return true;
    };

    const addBindingByInput = async () => {
        const query = quickBindingInput.trim();
        if (!query || !primaryMovieId || quickBindingAdding) return;
        setQuickBindingAdding(true);
        try {
            const baseQuery = new URLSearchParams({ movieId: primaryMovieId, limit: '100' });
            const keywordQuery = new URLSearchParams({ movieId: primaryMovieId, keyword: query, limit: '100' });
            const [baseResponse, keywordResponse] = await Promise.all([
                api(`/api/resources/bind-candidates?${baseQuery}`),
                api(`/api/resources/bind-candidates?${keywordQuery}`),
            ]);
            if (!baseResponse.ok || !keywordResponse.ok) {
                message.error(t('resourceBindQuickAddNotFound'));
                return;
            }
            const [baseItems, keywordItems] = await Promise.all([baseResponse.json(), keywordResponse.json()]);
            const sameSeriesIds = new Set((baseItems || []).map((item: BindingMovie) => item.id));
            const candidates: BindingMovie[] = Array.from(new Map(
                [...(baseItems || []), ...(keywordItems || []).filter((item: BindingMovie) => sameSeriesIds.has(item.id))]
                    .map((item: BindingMovie) => [item.id, item])
            ).values());
            const normalized = query.toLocaleLowerCase();
            const exactId = candidates.find(item => item.id.toLocaleLowerCase() === normalized);
            const exactTitles = candidates.filter(item => [item.titleCn, item.titleEn]
                .some(title => title?.trim().toLocaleLowerCase() === normalized));
            const match = exactId || (exactTitles.length === 1 ? exactTitles[0] : undefined)
                || (candidates.length === 1 ? candidates[0] : undefined);
            if (!match) {
                message.warning(t(candidates.length > 1 ? 'resourceBindQuickAddAmbiguous' : 'resourceBindQuickAddNotFound'));
                return;
            }
            if (addBindingOption(bindingOption(match))) setQuickBindingInput('');
        } catch {
            message.error(t('networkError'));
        } finally {
            setQuickBindingAdding(false);
        }
    };

    const insertParameter = (parameter: string) => {
        const input = nameInputRef.current?.input;
        const current = form.getFieldValue('name') || '';
        const selectedMovieId = form.getFieldValue('movieId');
        const movieTitle = movieOptions.find(item => item.value === selectedMovieId)?.titleCn;
        const resolvedParameter = materializeQuickParam(parameter, movieTitle);
        if (!resolvedParameter) return;
        const result = insertQuickParam(current, resolvedParameter, input?.selectionStart, input?.selectionEnd);
        form.setFieldValue('name', result.value);
        requestAnimationFrame(() => {
            input?.focus();
            input?.setSelectionRange(result.cursor, result.cursor);
        });
    };

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
        api('/api/resources/form-config').then(async response => {
            if (!response.ok) return;
            const data = await response.json();
            if (Array.isArray(data.quickParams) && data.quickParams.length) {
                setQuickParams(parseResourceQuickParams(data.quickParams.join(',')));
            }
        }).catch(() => undefined);
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
            bindMovieIds: [],
            bindingVersion: undefined,
        } : { type: 'DISK', provider: 'BAIDU', name: '' });
        setMovieOptions(resource ? [{
            value: resource.movieId,
            label: `${resource.movieTitle || resource.movieId} - ${resource.movieId}`,
            titleCn: resource.movieTitle,
        }] : []);
        loadMovies();
    }, [form, loadMovies, open, resource]);

    useEffect(() => {
        bindRequest.current += 1;
        setBindCandidates([]);
        if (open && form.getFieldValue('movieId')) loadBindCandidates();
        return () => { bindRequest.current += 1; };
    }, [form, loadBindCandidates, open, resource]);

    useEffect(() => {
        if (!open) return;
        const controller = new AbortController();
        setBindingOptions([]);
        setBindingsReady(!resource);
        setBindingsError(false);
        if (!resource) return;
        api(`/api/resources/${resource.id}/bindings`, {
            headers: { Authorization: `Bearer ${token}` }, signal: controller.signal,
        }).then(async response => {
            if (!response.ok) throw new Error('bindings unavailable');
            const data = await response.json();
            if (controller.signal.aborted) return;
            if (!Array.isArray(data.bindMovieIds) || !Array.isArray(data.bindings) || !data.bindingVersion || data.resource?.id !== resource.id) {
                throw new Error('invalid bindings response');
            }
            setBindingOptions(data.bindings.map(bindingOption));
            form.setFieldsValue({
                movieId: data.resource.movieId, name: data.resource.name || '', type: data.resource.type,
                url: data.resource.url, code: data.resource.code, provider: data.resource.provider,
                quality: data.resource.quality, subtitle: data.resource.subtitle,
                fileSize: data.resource.fileSize, versionNote: data.resource.versionNote,
                bindMovieIds: data.bindMovieIds, bindingVersion: data.bindingVersion,
            });
            setBindingsReady(true);
        }).catch(() => {
            if (!controller.signal.aborted) setBindingsError(true);
        });
        return () => controller.abort();
    }, [form, open, resource, token, bindingsRetry]);

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
        if (resource && !bindingsReady) return;
        setSaving(true);
        try {
            const payload = {
                ...values,
                url: normalizeResourceUrlWithCode(values.url, values.code) || values.url,
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
            const result = resource ? await res.json() : null;
            message.success(resource ? t('resourceBindingSaved', {
                updated: (result?.updatedBindings ?? 0) + 1, added: result?.boundCount ?? 0,
            }) : t('resourceCreated'));
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
            <Form form={form} layout="vertical" onFinish={submit} requiredMark={false} disabled={saving || Boolean(resource && !bindingsReady)}>
                <Form.Item name="movieId" label={t('movieTitle')} rules={[{ required: true }]}>
                    <Select
                        showSearch
                        filterOption={false}
                        loading={movieLoading}
                        options={movieOptions}
                        onSearch={loadMovies}
                        onChange={() => { form.setFieldValue('bindMovieIds', []); setBindCandidates([]); }}
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
                    <Input ref={nameInputRef} />
                </Form.Item>
                <Space wrap className="mb-3">
                    <span className="text-gray-500">{t('resourceQuickParams')}</span>
                    <Button icon={<CopyOutlined />} onClick={pasteClipboard}>{t('resourcePasteAll')}</Button>
                    {quickParams.map((parameter) => (
                        <Tag key={parameter} className="cursor-pointer" onClick={() => insertParameter(parameter)}>
                            {parameter === '\u005b\u5f71\u7247\u540d\u005d'
                                ? (movieOptions.find(item => item.value === form.getFieldValue('movieId'))?.titleCn || parameter)
                                : parameter}
                        </Tag>
                    ))}
                </Space>
                <Row gutter={16}>
                    <Col xs={24} md={8}>
                        <Form.Item name="type" label={t('resourceType')} rules={[{ required: true }]}>
                            <Select options={['DISK', 'MAGNET', 'TORRENT', 'ONLINE'].map((value) => ({ value, label: value }))} />
                        </Form.Item>
                    </Col>
                    {type === 'DISK' && (
                        <Col xs={24} md={8}>
                            <Form.Item name="provider" label={t('provider')} rules={[{ required: true }]}>
                                <Select disabled={!inferResourceProvider(url)} options={PROVIDERS.map((value) => ({ value, label: value }))} />
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
                <Form.Item name="bindingVersion" hidden><Input /></Form.Item>
                {resource && bindingsError && (
                    <Alert type="error" showIcon className="!mb-4" title={t('resourceBindingsLoadFailed')}
                        action={<Button size="small" disabled={false} onClick={() => setBindingsRetry(value => value + 1)}>{t('retry')}</Button>} />
                )}
                <Form.Item
                    name="bindMovieIds"
                    label={t('resourceBindSeries')}
                    extra={(
                        <div>
                            {resource ? <div>{t('resourceBindingEditHint')}</div> : null}
                            <Space.Compact className="mt-2 w-full">
                                <Input
                                    value={quickBindingInput}
                                    onChange={(event) => setQuickBindingInput(event.target.value)}
                                    onPressEnter={addBindingByInput}
                                    disabled={!primaryMovieId || Boolean(resource && !bindingsReady)}
                                    placeholder={t('resourceBindQuickAddPlaceholder')}
                                />
                                <Button
                                    icon={<PlusOutlined />}
                                    loading={quickBindingAdding}
                                    disabled={!primaryMovieId || !quickBindingInput.trim() || Boolean(resource && !bindingsReady)}
                                    onClick={addBindingByInput}
                                >
                                    {t('resourceBindQuickAdd')}
                                </Button>
                            </Space.Compact>
                            <div className="mt-1 text-xs text-gray-500">{t('resourceBindQuickAddHint')}</div>
                            {selectedBindingIds.length > 0 && (
                                <div className="mt-1 text-xs text-gray-500">{t('resourceBindSelectedCount', { count: selectedBindingIds.length })}</div>
                            )}
                        </div>
                    )}
                >
                    <Select
                        mode="multiple"
                        showSearch
                        filterOption={false}
                        loading={bindLoading || Boolean(resource && !bindingsReady && !bindingsError)}
                        disabled={Boolean(resource && !bindingsReady)}
                        options={allBindingOptions}
                        onSearch={loadBindCandidates}
                        onFocus={() => loadBindCandidates()}
                        placeholder={t('resourceBindSeriesPlaceholder')}
                    />
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
                    <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={saving} disabled={Boolean(resource && !bindingsReady)}>
                        {t('save')}
                    </Button>
                </Space>
            </Form>
        </Modal>
    );
}
