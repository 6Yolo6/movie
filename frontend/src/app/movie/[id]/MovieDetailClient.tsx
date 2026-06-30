'use client';

import React, { useState, useMemo } from 'react';
import { Card, Tag, Typography, Descriptions, Button, Space, Switch, Tabs, Modal, Form, Input, Select, App, Divider, Tooltip, Dropdown, MenuProps, Popconfirm } from 'antd';
import { DownloadOutlined, StarFilled, CloudUploadOutlined, CopyOutlined, PlayCircleOutlined, LinkOutlined, DownOutlined, UpOutlined, CheckOutlined, HeartOutlined, HeartFilled, WarningOutlined, EditOutlined } from '@ant-design/icons';
import { MovieDetailDTO, MovieMetadata, ResourceLink } from '@/types';
import { useAuthStore } from '@/store/authStore';
import { api, readApiError } from '@/lib/api';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import CommentSection from '@/components/CommentSection';
import { useTranslation } from 'react-i18next';

const { Title, Paragraph, Text } = Typography;
const { Option } = Select;

interface LinkLabels {
    expand: string;
    collapse: string;
}

interface RenderLinkListProps {
    items: string[];
    type: string;
    limit?: number;
    labels: LinkLabels;
}

interface ResourceFormValues {
    name: string;
    type: string;
    url: string;
    code?: string;
    provider?: string;
    quality?: string;
    subtitle?: string;
    fileSize?: string;
    versionNote?: string;
}

const RenderLinkList = ({ items, type: _type, limit = 0, labels }: RenderLinkListProps) => {
    const [expanded, setExpanded] = useState(false);

    if (!items || items.length === 0) return <span>-</span>;

    const shouldCollapse = limit > 0 && items.length > limit;
    const displayItems = shouldCollapse && !expanded ? items.slice(0, limit) : items;

    return (
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <Space wrap size={[0, 4]} separator={<span className="text-gray-300">/</span>}>
                {displayItems.map((item, idx) => (
                    <Link key={idx} href={`/?keyword=${encodeURIComponent(item)}`} className="text-blue-600 dark:text-blue-400 hover:underline cursor-pointer transition-colors">
                        {item}
                    </Link>
                ))}
            </Space>
            {shouldCollapse && (
                <Button
                    type="link"
                    size="small"
                    onClick={() => setExpanded(!expanded)}
                    className="p-0 h-auto text-xs text-gray-400 hover:text-blue-500 ml-1"
                >
                    {expanded ? `[${labels.collapse}]` : `[${labels.expand} ${items.length - limit}+]`}
                </Button>
            )}
        </div>
    );
};

export default function MovieDetailClient({ data }: { data: MovieDetailDTO }) {
    const { movie, resources } = data;
    const { user, token } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const { t, i18n } = useTranslation();

    // Modal State
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [form] = Form.useForm();
    const resourceType = Form.useWatch('type', form) || 'DISK';
    const [resourceItems, setResourceItems] = useState(resources);
    const [editingResource, setEditingResource] = useState<ResourceLink | null>(null);
    const isP2PType = resourceType === 'MAGNET' || resourceType === 'TORRENT';

    // Summary Expanded State
    const [summaryExpanded, setSummaryExpanded] = useState(false);

    // Favorite State
    const [isFavorited, setIsFavorited] = useState(false);
    const [favoriteCount, setFavoriteCount] = useState(movie.popularity || 0);

    React.useEffect(() => {
        setResourceItems(resources);
    }, [resources]);

    React.useEffect(() => {
        if (!token || !movie.id) return;
        api(`/api/resources/mine?movieId=${movie.id}&page=1&size=100`, {
            headers: { Authorization: `Bearer ${token}` },
        })
            .then(res => res.ok ? res.json() : null)
            .then(data => {
                if (!data?.records) return;
                setResourceItems(prev => {
                    const merged = new Map<number, ResourceLink>();
                    prev.forEach(item => merged.set(item.id, item));
                    data.records.forEach((item: ResourceLink) => merged.set(item.id, item));
                    return Array.from(merged.values());
                });
            })
            .catch(() => {});
    }, [movie.id, token]);

    React.useEffect(() => {
        if (token && movie.id) {
            api(`/api/favorites/check?movieId=${movie.id}`, {
                headers: { Authorization: `Bearer ${token}` },
            })
                .then(res => res.json())
                .then((d: { favorited: boolean; count: number }) => {
                    setIsFavorited(d.favorited);
                    setFavoriteCount(d.count);
                })
                .catch(() => {});
        } else if (movie.id) {
            api(`/api/favorites/count?movieId=${movie.id}`)
                .then(res => res.json())
                .then((d: { count: number }) => setFavoriteCount(d.count))
                .catch(() => {});
        }
    }, [token, movie.id]);

    const toggleFavorite = async () => {
        if (!token) {
            message.error(t('loginRequired'));
            return;
        }
        try {
            const res = await api(`/api/favorites/toggle?movieId=${movie.id}`, {
                method: 'POST',
                headers: { Authorization: `Bearer ${token}` },
            });
            if (res.ok) {
                const d = await res.json();
                setIsFavorited(d.favorited);
                setFavoriteCount(d.count);
                message.success(d.favorited ? t('favoriteAdded') : t('favoriteRemoved'));
            } else {
                const errText = await readApiError(res, t('operationFailed'));
                message.error(`${t('operationFailed')}: ${errText}`);
            }
        } catch {
            message.error(t('networkError'));
        }
    };

    const [relatedSeasons, setRelatedSeasons] = useState<MovieMetadata[]>([]);

    React.useEffect(() => {
        if (movie.seriesName) {
            api(`/api/movies/series?name=${encodeURIComponent(movie.seriesName)}`)
                .then(res => res.json())
                .then((data: MovieMetadata[]) => {
                    // Sort by season
                    if (Array.isArray(data)) {
                        setRelatedSeasons(data.sort((a, b) => (a.season || 0) - (b.season || 0)));
                    }
                })
                .catch(console.error);
        }
    }, [movie.seriesName]);

    const seasonItems: MenuProps['items'] = relatedSeasons.map(s => ({
        key: s.id,
        label: (
            <Link href={`/movie/${s.id}`} className={`flex justify-between items-center gap-4 ${s.id === movie.id ? 'text-blue-500 font-bold' : ''}`}>
                <span>{s.season ? (i18n.language === 'cn' ? `第 ${s.season} ${t('season')}` : `${t('season')} ${s.season}`) : s.titleCn}</span>
                {s.id === movie.id && <CheckOutlined />}
            </Link>
        )
    }));

    type LanguageKey = 'cn' | 'en';
    const providers: Record<string, { cn: string, en: string }> = {
        "BAIDU": { cn: t('百度网盘'), en: t('baiduNetdisk') },
        "XUNLEI": { cn: t('迅雷云盘'), en: t('xunleiCloud') },
        "QUARK": { cn: t('夸克网盘'), en: t('quarkCloud') },
        "ALIYUN": { cn: t('阿里云盘'), en: t('aliyunDrive') },
        "OTHER": { cn: t('其他'), en: t('other') }
    };

    const sortedResourceItems = useMemo(() => {
        return [...resourceItems].sort((a, b) => {
            const aOwn = user?.id != null && a.uploaderId === user.id;
            const bOwn = user?.id != null && b.uploaderId === user.id;
            if (aOwn !== bOwn) return aOwn ? -1 : 1;
            return new Date(b.createdAt || 0).getTime() - new Date(a.createdAt || 0).getTime();
        });
    }, [resourceItems, user?.id]);

    // Group Disk Resources by Provider
    const diskResources = useMemo(() => sortedResourceItems.filter(r => r.type === 'DISK'), [sortedResourceItems]);
    const groupedDiskResources = useMemo(() => {
        const groups: Record<string, ResourceLink[]> = {};
        diskResources.forEach(r => {
            const p = r.provider || 'OTHER';
            if (!groups[p]) groups[p] = [];
            groups[p].push(r);
        });
        return groups;
    }, [diskResources]);

    const p2pResources = useMemo(() => sortedResourceItems.filter(r => r.type !== 'DISK'), [sortedResourceItems]);

    const handleCopy = (text: string) => {
        navigator.clipboard.writeText(text);
        message.success(t('copy') + " Success");
    };

    const openCreateResource = () => {
        setEditingResource(null);
        form.resetFields();
        form.setFieldsValue({ type: 'DISK', provider: 'BAIDU' });
        setIsModalOpen(true);
    };

    const openEditResource = (resource: ResourceLink) => {
        setEditingResource(resource);
        form.setFieldsValue({
            name: resource.name,
            type: resource.type || 'DISK',
            url: resource.url,
            code: resource.code,
            provider: resource.provider || 'BAIDU',
            quality: resource.quality,
            subtitle: resource.subtitle,
            fileSize: resource.fileSize,
            versionNote: resource.versionNote,
        });
        setIsModalOpen(true);
    };

    const handleSubmit = async (values: ResourceFormValues) => {
        if (!token) {
            message.error(t('loginToComment', { interpolation: { escapeValue: false } })); // Reusing translation key for login prompt
            return;
        }
        setSubmitting(true);
        try {
            const payload = {
                movieId: movie.id,
                ...values,
                type: values.type || 'DISK',
                provider: values.type === 'DISK' ? (values.provider || 'OTHER') : 'OTHER',
                code: values.type === 'DISK' ? (values.code || '') : '',
            };
            const res = await api(editingResource ? `/api/resources/${editingResource.id}` : '/api/resources', {
                method: editingResource ? 'PUT' : 'POST',
                headers: {
                    'Authorization': `Bearer ${token}`
                },
                body: JSON.stringify(payload)
            });

            if (res.ok) {
                message.success(editingResource ? t('resourceUpdated') : t('resourceSubmittedSuccessfully'));
                if (editingResource) {
                    setResourceItems(prev => prev.map(item => (
                        item.id === editingResource.id
                            ? {
                                ...item,
                                ...payload,
                                auditStatus: user?.role === 'ADMIN' ? item.auditStatus : 0,
                                linkStatus: 'NORMAL',
                                reportCount: 0,
                            }
                            : item
                    )));
                }
                setIsModalOpen(false);
                setEditingResource(null);
                form.resetFields();
                router.refresh();
            } else {
                const msg = await readApiError(res, t('submissionFailed'));
                message.error(`${t('submissionFailed')}: ${msg}`);
            }
        } catch {
            message.error(t('networkError'));
        } finally {
            setSubmitting(false);
        }
    };

    const getLinkPlaceholder = () => {
        switch (resourceType) {
            case 'MAGNET':
                return 'magnet:?xt=urn:btih:...';
            case 'TORRENT':
                return 'https://example.com/movie.torrent';
            case 'ONLINE':
                return 'https://example.com/watch';
            default:
                return 'https://pan.example.com/s/...';
        }
    };

    const getUrlRules = () => {
        if (resourceType === 'MAGNET') {
            return [
                { required: true },
                {
                    validator: (_: unknown, value?: string) => {
                        if (!value || value.toLowerCase().startsWith('magnet:?xt=urn:btih:')) {
                            return Promise.resolve();
                        }
                        return Promise.reject(new Error(t('magnetLinkRequired')));
                    },
                },
            ];
        }
        if (resourceType === 'TORRENT') {
            return [
                { required: true },
                {
                    validator: (_: unknown, value?: string) => {
                        const lowerValue = value?.toLowerCase() || '';
                        if (!value || ((lowerValue.startsWith('http://') || lowerValue.startsWith('https://')) && lowerValue.includes('.torrent'))) {
                            return Promise.resolve();
                        }
                        return Promise.reject(new Error(t('torrentLinkRequired')));
                    },
                },
            ];
        }
        return [{ required: true, type: 'url' as const }];
    };

    const handleReportInvalid = async (item: ResourceLink) => {
        if (!token) {
            message.error(t('loginRequired'));
            return;
        }
        try {
            const res = await api(`/api/resources/${item.id}/report`, {
                method: 'POST',
                headers: { Authorization: `Bearer ${token}` },
                body: JSON.stringify({ reason: t('defaultReportReason') }),
            });
            if (!res.ok) {
                const errText = await readApiError(res, t('operationFailed'));
                message.error(`${t('operationFailed')}: ${errText}`);
                return;
            }
            const data: { linkStatus: string; reportCount: number } = await res.json();
            setResourceItems(prev => prev.map(resource => (
                resource.id === item.id
                    ? { ...resource, linkStatus: data.linkStatus, reportCount: data.reportCount }
                    : resource
            )));
            message.success(t('resourceReported'));
        } catch {
            message.error(t('networkError'));
        }
    };

    const renderLinkStatusTag = (item: ResourceLink) => {
        if (!item.linkStatus || item.linkStatus === 'NORMAL') return null;
        const color = item.linkStatus === 'INVALID' ? 'red' : 'orange';
        const label = item.linkStatus === 'INVALID' ? t('invalid') : t('suspectedInvalid');
        return <Tag color={color} className="m-0">{label}{item.reportCount ? ` ${item.reportCount}` : ''}</Tag>;
    };

    // Render Resource Card
    const renderResourceCard = (item: ResourceLink, index: number) => (
        <Card key={index} size="small" className="bg-white/50 dark:bg-zinc-900/50 backdrop-blur-sm border-gray-200 dark:border-zinc-800 hover:border-blue-400 dark:hover:border-blue-500/50 transition-all shadow-sm">
            <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3">
                <div className="flex flex-col gap-1 w-full sm:w-auto overflow-hidden">
                    <div className="flex items-center gap-2">
                        <Tag color={item.type === 'DISK' ? 'green' : (item.type === 'MAGNET' ? 'purple' : 'blue')} className="m-0 font-bold border-0">
                            {item.type === 'DISK' ? (providers[item.provider as keyof typeof providers]?.[i18n.language as LanguageKey] || item.provider) : item.type}
                        </Tag>
                        {renderLinkStatusTag(item)}
                        <Text className="text-gray-800 dark:text-gray-200 font-medium truncate text-base">
                            {item.name || t('resource')}
                        </Text>
                    </div>
                    <Space className="text-gray-500 dark:text-gray-400 text-xs truncate w-full pl-1" separator="|">
                        <Text ellipsis={{ tooltip: item.url }} className="text-blue-600 dark:text-blue-400 max-w-[200px] sm:max-w-md">
                            {item.url}
                        </Text>
                        {item.code && (
                            <Space className="bg-orange-100 dark:bg-orange-900/30 px-2 py-0.5 rounded text-orange-600 dark:text-orange-400">
                                <span>{t('accessCode')}: <span className="font-mono font-bold">{item.code}</span></span>
                                <CopyOutlined className="cursor-pointer hover:scale-110 transition-transform" onClick={() => handleCopy(item.code)} />
                            </Space>
                        )}
                        {[item.quality, item.subtitle, item.fileSize].filter(Boolean).map(meta => (
                            <Tag key={meta} className="m-0 border-0 bg-gray-100 dark:bg-zinc-800 text-gray-500 dark:text-gray-300">{meta}</Tag>
                        ))}
                        <span className="text-gray-400">{item.createdAt ? new Date(item.createdAt).toLocaleDateString() : '-'}</span>
                    </Space>
                    {item.versionNote && <Text type="secondary" className="text-xs pl-1">{item.versionNote}</Text>}
                </div>
                <Space>
                    {user?.id === item.uploaderId && (
                        <Tooltip title={t('editResource')}>
                            <Button size="small" type="text" icon={<EditOutlined />} onClick={() => openEditResource(item)} />
                        </Tooltip>
                    )}
                    <Popconfirm
                        title={t('reportInvalidResource')}
                        description={t('confirmReportInvalid')}
                        onConfirm={() => handleReportInvalid(item)}
                        okText={t('submit')}
                        cancelText={t('cancel')}
                    >
                        <Tooltip title={t('reportInvalidResource')}>
                            <Button size="small" type="text" icon={<WarningOutlined />} />
                        </Tooltip>
                    </Popconfirm>
                    <Tooltip title={t('copy')}>
                        <Button size="small" type="text" icon={<CopyOutlined />} onClick={() => handleCopy(item.url)} />
                    </Tooltip>
                    <Button type="primary" size="small" icon={<DownloadOutlined />} href={item.url} target="_blank" className="bg-blue-600">
                        {t('downloadResources')}
                    </Button>
                </Space>
            </div>
        </Card>
    );

    const diskTabItems = Object.keys(groupedDiskResources).map(providerKey => ({
        key: providerKey,
        label: providers[providerKey]?.[i18n.language as LanguageKey] || providerKey,
        children: (
            <div className="flex flex-col gap-3 animate-fade-in">
                {groupedDiskResources[providerKey].map(renderResourceCard)}
            </div>
        )
    }));

    // Add "All" tab if multiple providers
    if (Object.keys(groupedDiskResources).length > 1) {
        diskTabItems.unshift({
            key: 'ALL',
            label: t('all'),
            children: (
                <div className="flex flex-col gap-3 animate-fade-in">
                    {diskResources.map(renderResourceCard)}
                </div>
            )
        });
    }

    return (
        <div className="min-h-screen bg-[#f5f7fa] dark:bg-black font-sans pb-12">

            {/* 1. Blur Background Banner */}
            <div className="absolute top-0 left-0 w-full h-[500px] overflow-hidden z-0">
                <div className="absolute inset-0 bg-gradient-to-b from-transparent to-[#f5f7fa] dark:to-black z-10" />
                <div
                    className="w-full h-full bg-cover bg-center opacity-30 dark:opacity-20 blur-3xl scale-110"
                    style={{ backgroundImage: `url(${movie.posterUrl || 'https://via.placeholder.com/300x450'})` }}
                />
            </div>

            <div className="container mx-auto px-4 lg:px-8 relative z-10 pt-8">
                <div className="flex flex-col md:flex-row gap-8 lg:gap-12">

                    {/* Left: Poster & Rating */}
                    <div className="w-full md:w-[300px] flex-shrink-0 flex flex-col gap-4 items-center md:items-stretch">
                        <div className="w-[200px] md:w-full rounded-lg overflow-hidden shadow-2xl hover:shadow-3xl transition-shadow duration-300 border-4 border-white dark:border-zinc-800 md:rotate-1 md:hover:rotate-0">
                            <img
                                alt={movie.titleCn}
                                src={movie.posterUrl || 'https://via.placeholder.com/300x450'}
                                className="w-full h-auto object-cover"
                            />
                        </div>

                        {/* Rating Card */}
                        <div className="bg-white/80 dark:bg-zinc-900/80 backdrop-blur rounded-xl p-4 shadow-sm border border-white/20">
                            <div className="text-center mb-3 text-gray-400 text-xs uppercase tracking-wider">{t('ratings')}</div>
                            <div className="flex justify-around items-center">
                                <div className="text-center">
                                    <div className="text-2xl font-bold font-serif text-gray-800 dark:text-white flex items-center justify-center gap-1">
                                        {movie.doubanScore || '-'} <StarFilled className="text-yellow-400 text-sm" />
                                    </div>
                                    <div className="text-xs text-gray-500">豆瓣</div>
                                </div>
                                <div className="w-px h-8 bg-gray-200 dark:bg-zinc-700" />
                                <div className="text-center">
                                    <div className="text-2xl font-bold font-serif text-gray-800 dark:text-white flex items-center justify-center gap-1">
                                        {movie.imdbScore || '-'} <span className="text-yellow-500 text-xs font-sans">IMDb</span>
                                    </div>
                                    <div className="text-xs text-gray-500">{t('score')}</div>
                                </div>
                                {movie.rtScore && (
                                    <>
                                        <div className="w-px h-8 bg-gray-200 dark:bg-zinc-700" />
                                        <div className="text-center">
                                            <div className="text-2xl font-bold font-serif text-red-500 dark:text-red-400 flex items-center justify-center gap-1">
                                                {movie.rtScore}
                                            </div>
                                            <div className="text-xs text-gray-500">{t('rtScore')}</div>
                                        </div>
                                    </>
                                )}
                            </div>
                        </div>
                    </div>

                    {/* Right: Info Area */}
                    <div className="flex-1 min-w-0">
                        <div className="flex flex-col h-full justify-start">
                            {/* Header */}
                            <div className="mb-6">
                                <div className="flex justify-between items-start">
                                    <div>
                                        <h1 className="text-2xl sm:text-3xl lg:text-5xl font-bold text-gray-900 dark:text-gray-100 mb-2 leading-tight">
                                            {movie.titleCn}
                                        </h1>

                                        <h2 className="text-base sm:text-lg lg:text-2xl text-gray-500 dark:text-gray-400 font-light flex items-center flex-wrap gap-2 sm:gap-3">
                                            {movie.titleEn}
                                            <span className="text-sm bg-gray-200 dark:bg-zinc-800 px-2 py-0.5 rounded text-gray-600 dark:text-gray-300 font-bold">{movie.year}</span>

                                            {relatedSeasons.length > 1 && (
                                                <Dropdown menu={{ items: seasonItems }} trigger={['click']} placement="bottomLeft">
                                                    <span className="text-sm bg-blue-600/10 dark:bg-blue-500/20 border border-blue-600/20 text-blue-600 dark:text-blue-400 px-3 py-0.5 rounded-full font-bold cursor-pointer hover:bg-blue-600/20 transition-all flex items-center gap-1">
                                                        {movie.season ? (i18n.language === 'cn' ? `第 ${movie.season} ${t('season')}` : `${t('season')} ${movie.season}`) : t('selectSeason')}
                                                        <DownOutlined className="text-xs" />
                                                    </span>
                                                </Dropdown>
                                            )}
                                        </h2>
                                    </div>

                                    {/* Action Buttons - desktop */}
                                    <Space className="hidden md:flex">
                                        <Button
                                            type={isFavorited ? 'primary' : 'default'}
                                            danger={isFavorited}
                                            icon={isFavorited ? <HeartFilled /> : <HeartOutlined />}
                                            onClick={toggleFavorite}
                                            className={isFavorited ? '' : 'text-red-500 border-red-300 hover:!text-red-600 hover:!border-red-500'}
                                        >
                                            {t('favorite')} {favoriteCount > 0 ? favoriteCount : ''}
                                        </Button>
                                        <Switch
                                            checkedChildren="中"
                                            unCheckedChildren="En"
                                            defaultChecked
                                            onChange={(checked) => i18n.changeLanguage(checked ? 'cn' : 'en')}
                                            className="bg-gray-300"
                                        />
                                    </Space>
                                </div>

                                {/* Action Buttons - mobile */}
                                <div className="flex md:hidden items-center gap-2 mt-3">
                                    <Button
                                        type={isFavorited ? 'primary' : 'default'}
                                        ghost={!isFavorited}
                                        danger={isFavorited}
                                        size="small"
                                        icon={isFavorited ? <HeartFilled /> : <HeartOutlined />}
                                        onClick={toggleFavorite}
                                    >
                                        {t('favorite')}{favoriteCount > 0 ? ` ${favoriteCount}` : ''}
                                    </Button>
                                </div>

                                {/* Tags Row */}
                                <div className="mt-4 flex flex-wrap gap-2 items-center">
                                    {movie.genres?.map(g => (
                                        <Link href={`/?keyword=${encodeURIComponent(g)}`} key={g}>
                                            <Tag className="px-3 py-1 text-sm border-0 bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-300 rounded-full cursor-pointer hover:bg-blue-100 dark:hover:bg-blue-900/50 transition-colors">
                                                {g}
                                            </Tag>
                                        </Link>
                                    ))}
                                    {movie.languages?.map(lang => (
                                        <Tag key={lang} className="px-2 py-1 text-xs border-0 bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-300 rounded-full">
                                            {lang}
                                        </Tag>
                                    ))}
                                    <span className="text-gray-400 text-sm mx-1">•</span>
                                    <span className="text-gray-500 dark:text-gray-400 flex items-center gap-1">
                                        <PlayCircleOutlined /> {movie.runtime}
                                    </span>
                                    <span className="text-gray-400 text-sm mx-1">•</span>
                                    <span className="text-gray-500 dark:text-gray-400">
                                        {movie.category === 'mv' ? t('movies') : movie.category === 'tv' ? t('tvShows') : t('anime')}
                                    </span>
                                    {movie.status && (
                                        <>
                                            <span className="text-gray-400 text-sm mx-1">•</span>
                                            <Tag
                                                className={`rounded-full border-0 text-xs font-bold ${
                                                    movie.status === 'ACTIVE'
                                                        ? 'bg-green-100 dark:bg-green-900/30 text-green-600 dark:text-green-400'
                                                        : movie.status === 'ENDED'
                                                        ? 'bg-gray-100 dark:bg-gray-800 text-gray-500 dark:text-gray-400'
                                                        : 'bg-orange-100 dark:bg-orange-900/30 text-orange-600 dark:text-orange-400'
                                                }`}
                                            >
                                                {movie.status === 'ACTIVE' ? t('active') : movie.status === 'ENDED' ? t('ended') : t('cancelled')}
                                            </Tag>
                                        </>
                                    )}
                                </div>
                            </div>

                            {/* Main Details Grid */}
                            <div className="bg-white/60 dark:bg-zinc-900/60 backdrop-blur rounded-2xl p-4 sm:p-6 lg:p-8 shadow-sm border border-white/50 dark:border-zinc-800/50 mb-8">
                                <Descriptions column={{ xs: 1, sm: 2, lg: 2 }} colon={false} styles={{ label: { color: '#888', minWidth: '80px' }, content: { fontWeight: 500 } }}>
                                    <Descriptions.Item label={t('director')}>
                                        <RenderLinkList items={movie.directors} type="director" labels={{ expand: t('expand'), collapse: t('collapse') }} />
                                    </Descriptions.Item>
                                    <Descriptions.Item label={t('region')}>
                                        <RenderLinkList items={movie.regions} type="region" labels={{ expand: t('expand'), collapse: t('collapse') }} />
                                    </Descriptions.Item>
                                    {movie.languages?.length > 0 && (
                                        <Descriptions.Item label={t('language')}>
                                            <div className="flex flex-wrap gap-1">
                                                {movie.languages.map(lang => (
                                                    <Tag key={lang} className="m-0 border-0 bg-gray-100 dark:bg-zinc-800 text-gray-600 dark:text-gray-300 rounded">{lang}</Tag>
                                                ))}
                                            </div>
                                        </Descriptions.Item>
                                    )}
                                    <Descriptions.Item label={t('releaseDate')}>
                                        {movie.releaseDates || '-'}
                                    </Descriptions.Item>
                                    {movie.aliases && (
                                        <Descriptions.Item label={t('aliases')} span={2}>
                                            <span className="text-gray-500 dark:text-gray-400">{movie.aliases}</span>
                                        </Descriptions.Item>
                                    )}
                                </Descriptions>

                                {/* Separate Actors Row for full width */}
                                <div className="mt-4 flex flex-col md:flex-row gap-2 md:gap-4 border-t border-gray-100 dark:border-zinc-800/50 pt-4">
                                    <span className="text-[#888] font-normal min-w-[80px]">{t('cast')}</span>
                                    <div className="flex-1">
                                        <RenderLinkList items={movie.actors} type="actor" limit={10} labels={{ expand: t('expand'), collapse: t('collapse') }} />
                                    </div>
                                </div>

                                <Divider className="my-6 border-gray-200 dark:border-zinc-800" />

                                <div className="relative">
                                    <Title level={5} className="text-gray-400 uppercase tracking-widest text-xs mb-3 font-bold">{t('synopsis')}</Title>
                                    <div className={`relative overflow-hidden transition-all duration-500 ease-in-out ${summaryExpanded ? 'max-h-[1000px]' : 'max-h-[100px]'}`}>
                                        <Paragraph className="text-gray-600 dark:text-gray-300 text-lg leading-relaxed m-0 text-justify">
                                            {movie.summary}
                                        </Paragraph>
                                        {!summaryExpanded && (
                                            <div className="absolute bottom-0 left-0 w-full h-16 bg-gradient-to-t from-white/90 dark:from-black/90 to-transparent pointer-events-none" />
                                        )}
                                    </div>
                                    <Button
                                        type="text"
                                        size="small"
                                        onClick={() => setSummaryExpanded(!summaryExpanded)}
                                        className="mt-2 text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-900/20"
                                        icon={summaryExpanded ? <UpOutlined /> : <DownOutlined />}
                                    >
                                        {summaryExpanded ? t('collapse') : t('expand')}
                                    </Button>
                                </div>
                            </div>

                            {/* Resources Section */}
                            <div>
                                <div className="flex items-center gap-3 mb-4 flex-wrap">
                                    <div className="w-1 h-6 bg-blue-500 rounded-full" />
                                    <Title level={3} className="!m-0">{t('downloadResources')}</Title>
                                    <Tag className="rounded-full bg-gray-100 dark:bg-zinc-800 border-0">{sortedResourceItems.length} {t('itemsCount')}</Tag>
                                    {user && (
                                        <Button
                                            type="primary"
                                            icon={<CloudUploadOutlined />}
                                            onClick={openCreateResource}
                                            className="ml-auto bg-blue-600"
                                            size="small"
                                        >
                                            {t('shareResource')}
                                        </Button>
                                    )}
                                </div>
                                <Tabs
                                    defaultActiveKey="disk"
                                    type="card"
                                    className="custom-tabs"
                                    items={[
                                        {
                                            key: 'disk',
                                            label: <span className="px-2">{t('cloudDisk')}</span>,
                                            children: (
                                                <div className="bg-white dark:bg-zinc-900 p-4 rounded-b-lg rounded-tr-lg border border-gray-100 dark:border-zinc-800 shadow-sm min-h-[200px]">
                                                    {diskResources.length > 0 ? (
                                                        <Tabs items={diskTabItems} size="small" tabPlacement="top" type="line" className="inner-tabs" />
                                                    ) : (
                                                        <div className="flex flex-col items-center justify-center py-10 text-gray-400">
                                                            <CloudUploadOutlined className="text-4xl mb-2 opacity-50" />
                                                            <Text type="secondary">{t('noDisk')}</Text>
                                                        </div>
                                                    )}
                                                </div>
                                            )
                                        },
                                        {
                                            key: 'p2p',
                                            label: <span className="px-2">{t('magnetTorrent')}</span>,
                                            children: (
                                                <div className="flex flex-col gap-3 bg-white dark:bg-zinc-900 p-4 rounded-b-lg rounded-tr-lg border border-gray-100 dark:border-zinc-800 shadow-sm min-h-[200px]">
                                                    {p2pResources.length > 0 ? p2pResources.map(renderResourceCard) : (
                                                        <div className="flex flex-col items-center justify-center py-10 text-gray-400">
                                                            <LinkOutlined className="text-4xl mb-2 opacity-50" />
                                                            <Text type="secondary">{t('noP2P')}</Text>
                                                        </div>
                                                    )}
                                                </div>
                                            )
                                        },
                                        {
                                            key: 'comments',
                                            label: <span className="px-2">{t('comments')}</span>,
                                            children: (
                                                <div className="bg-white dark:bg-zinc-900 p-4 rounded-b-lg rounded-tr-lg border border-gray-100 dark:border-zinc-800 shadow-sm">
                                                    <CommentSection relateId={movie.id} />
                                                </div>
                                            ),
                                        }
                                    ]}
                                />
                            </div>
                        </div>
                    </div>
                </div>

                {/* Upload Modal */}
                <Modal
                    title={<span className="text-lg font-bold">{editingResource ? t('editResource') : t('shareResource')}</span>}
                    open={isModalOpen}
                    onCancel={() => {
                        setIsModalOpen(false);
                        setEditingResource(null);
                    }}
                    footer={null}
                    className="top-20"
                >
                    <Form
                        form={form}
                        layout="vertical"
                        onFinish={handleSubmit}
                        requiredMark={false}
                        initialValues={{ type: 'DISK', provider: 'BAIDU' }}
                    >
                        <Form.Item name="name" label={t('resourceName')} rules={[{ required: true }]}>
                            <Input placeholder="e.g. 4K Remastered Version" className="rounded-md" />
                        </Form.Item>
                        <Form.Item name="type" label={t('resourceType')} rules={[{ required: true }]}>
                            <Select className="rounded-md">
                                <Option value="DISK">{t('cloudDisk')}</Option>
                                <Option value="MAGNET">{t('magnet')}</Option>
                                <Option value="TORRENT">{t('torrent')}</Option>
                                <Option value="ONLINE">{t('onlinePlay')}</Option>
                            </Select>
                        </Form.Item>
                        {isP2PType && (
                            <Text type="secondary" className="block -mt-2 mb-3">
                                {t('p2pUploadHint')}
                            </Text>
                        )}
                        <Form.Item
                            name="url"
                            label={t('linkUrl')}
                            rules={getUrlRules()}
                        >
                            <Input placeholder={getLinkPlaceholder()} className="rounded-md" />
                        </Form.Item>
                        {resourceType === 'DISK' && (
                            <Form.Item name="code" label={t('accessCode')}>
                                <Input placeholder={t('optional')} className="rounded-md" />
                            </Form.Item>
                        )}
                        {resourceType === 'DISK' && (
                            <Form.Item name="provider" label={t('provider')} rules={[{ required: true }]}>
                                <Select className="rounded-md">
                                    <Option value="BAIDU">{providers.BAIDU[i18n.language as LanguageKey]}</Option>
                                    <Option value="QUARK">{providers.QUARK[i18n.language as LanguageKey]}</Option>
                                    <Option value="XUNLEI">{providers.XUNLEI[i18n.language as LanguageKey]}</Option>
                                    <Option value="ALIYUN">{providers.ALIYUN[i18n.language as LanguageKey]}</Option>
                                    <Option value="OTHER">{providers.OTHER[i18n.language as LanguageKey]}</Option>
                                </Select>
                            </Form.Item>
                        )}
                        <Space className="w-full" size="middle">
                            <Form.Item name="quality" label={t('quality')} className="flex-1">
                                <Input placeholder="4K / 1080P" className="rounded-md" />
                            </Form.Item>
                            <Form.Item name="subtitle" label={t('subtitle')} className="flex-1">
                                <Input placeholder={t('optional')} className="rounded-md" />
                            </Form.Item>
                            <Form.Item name="fileSize" label={t('fileSize')} className="flex-1">
                                <Input placeholder="8.5GB" className="rounded-md" />
                            </Form.Item>
                        </Space>
                        <Form.Item name="versionNote" label={t('versionNote')}>
                            <Input placeholder={t('versionNotePlaceholder')} className="rounded-md" />
                        </Form.Item>
                        <Divider />
                        <div className="flex justify-end gap-3">
                            <Button onClick={() => {
                                setIsModalOpen(false);
                                setEditingResource(null);
                            }} className="rounded-md">{t('cancel')}</Button>
                            <Button type="primary" htmlType="submit" loading={submitting} className="rounded-md bg-blue-600">{t('submit')}</Button>
                        </div>
                    </Form>
                </Modal>
            </div>
        </div>
    );
}
