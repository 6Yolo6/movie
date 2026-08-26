'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
    App,
    Button,
    Card,
    Empty,
    Image,
    Input,
    Popconfirm,
    Space,
    Switch,
    Table,
    Tag,
    Tooltip,
    Typography,
} from 'antd';
import {
    DatabaseOutlined,
    DeleteOutlined,
    EditOutlined,
    EyeOutlined,
    PictureOutlined,
    PlusOutlined,
    ReloadOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { api, readApiError } from '@/lib/api';
import { useAuthStore } from '@/store/authStore';
import type { MovieMetadata } from '@/types';
import AdminMovieModal from '@/components/admin/AdminMovieModal';

const { Title, Text } = Typography;

const compactText = (value?: string) => value || '-';
const delay = (milliseconds: number) => new Promise((resolve) => setTimeout(resolve, milliseconds));

type WorkflowJob = {
    jobId: string;
    status: string;
    errors?: string[];
};

export default function AdminMoviesPage() {
    const { user, token } = useAuthStore();
    const router = useRouter();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [movies, setMovies] = useState<MovieMetadata[]>([]);
    const [loading, setLoading] = useState(true);
    const [page, setPage] = useState(1);
    const [total, setTotal] = useState(0);
    const [keyword, setKeyword] = useState('');
    const [includeDeleted, setIncludeDeleted] = useState(false);
    const [modalOpen, setModalOpen] = useState(false);
    const [editingMovie, setEditingMovie] = useState<MovieMetadata | null>(null);
    const [running, setRunning] = useState('');

    const fetchMovies = useCallback(async () => {
        if (!token) {
            setLoading(false);
            return;
        }
        setLoading(true);
        try {
            const query = new URLSearchParams({
                page: String(page),
                size: '20',
                includeDeleted: String(includeDeleted),
            });
            if (keyword) query.set('keyword', keyword);
            const res = await api(`/api/admin/movies?${query}`, {
                headers: { Authorization: `Bearer ${token}` },
            });
            if (!res.ok) {
                message.error(await readApiError(res, t('moviesLoadFailed')));
                return;
            }
            const data = await res.json();
            setMovies(data.records || []);
            setTotal(data.total || 0);
        } catch {
            message.error(t('networkError'));
        } finally {
            setLoading(false);
        }
    }, [includeDeleted, keyword, message, page, t, token]);

    useEffect(() => {
        if (!user) return;
        if (user.role !== 'ADMIN') {
            message.error(t('adminAccessRequired'));
            router.push('/');
            return;
        }
        fetchMovies();
    }, [fetchMovies, message, router, t, user]);

    const runGyingJob = async (key: string, path: string) => {
        if (!token) return;
        setRunning(key);
        try {
            const response = await api(path, {
                method: 'POST',
                headers: { Authorization: `Bearer ${token}` },
            });
            if (!response.ok) {
                message.error(await readApiError(response, t('operationFailed')));
                return;
            }
            const started = await response.json() as WorkflowJob;
            for (let attempt = 0; attempt < 180; attempt += 1) {
                const jobResponse = await api(`/api/admin/gying-source/jobs/${started.jobId}`, {
                    headers: { Authorization: `Bearer ${token}` },
                });
                if (!jobResponse.ok) {
                    message.error(await readApiError(jobResponse, t('operationFailed')));
                    return;
                }
                const job = await jobResponse.json() as WorkflowJob;
                if (job.status === 'SUCCEEDED') {
                    message.success(t('gyingSourceActionSucceeded'));
                    await fetchMovies();
                    return;
                }
                if (job.status === 'FAILED') {
                    message.error(job.errors?.join('; ') || t('operationFailed'));
                    return;
                }
                await delay(1500);
            }
            message.error(t('gyingSourceJobTimeout'));
        } catch {
            message.error(t('networkError'));
        } finally {
            setRunning('');
        }
    };

    const deleteMovie = async (movie: MovieMetadata) => {
        if (!token) return;
        const res = await api(`/api/admin/movies/${movie.id}`, {
            method: 'DELETE',
            headers: { Authorization: `Bearer ${token}` },
        });
        if (!res.ok) {
            message.error(await readApiError(res, t('movieDeleteFailed')));
            return;
        }
        message.success(t('movieDeleted'));
        fetchMovies();
    };

    const showText = (value?: string) => (
        <Tooltip title={value || undefined}>
            <span className="block truncate">{compactText(value)}</span>
        </Tooltip>
    );

    const columns: ColumnsType<MovieMetadata> = [
        {
            title: t('poster'),
            dataIndex: 'posterUrl',
            width: 78,
            fixed: 'left',
            render: (value?: string) => value ? (
                <Image src={value} alt="" width={44} height={66} className="rounded object-cover" preview />
            ) : <div className="h-[66px] w-11 rounded bg-gray-100 dark:bg-white/10" />,
        },
        {
            title: t('movieTitle'),
            dataIndex: 'titleCn',
            width: 220,
            fixed: 'left',
            render: (_: string, record) => (
                <div className="min-w-0">
                    <Link href={`/movie/${record.id}`} className="block truncate font-medium text-blue-600 hover:underline">
                        {record.titleCn || record.id}
                    </Link>
                    {record.titleEn && (
                        <Tooltip title={record.titleEn}>
                            <Text type="secondary" className="block truncate text-xs">{record.titleEn}</Text>
                        </Tooltip>
                    )}
                </div>
            ),
        },
        { title: t('movieId'), dataIndex: 'id', width: 180, render: showText },
        {
            title: t('category'),
            dataIndex: 'category',
            width: 90,
            render: (value?: string) => <Tag>{value || '-'}</Tag>,
        },
        { title: t('year'), dataIndex: 'year', width: 84, render: (value?: number) => value || '-' },
        {
            title: 'TMDB',
            width: 150,
            render: (_: unknown, record) => record.tmdbId ? `${record.tmdbType || '-'} / ${record.tmdbId}` : '-',
        },
        {
            title: t('seriesName'),
            dataIndex: 'seriesName',
            width: 170,
            render: showText,
        },
        {
            title: t('genre'),
            dataIndex: 'genres',
            width: 180,
            render: (value?: string[]) => showText(value?.join(' / ')),
        },
        {
            title: t('summary'),
            dataIndex: 'summary',
            width: 260,
            render: showText,
        },
        {
            title: t('doubanScore'),
            dataIndex: 'doubanScore',
            width: 100,
            render: (value?: number) => value ?? '-',
        },
        {
            title: t('resourceStatus'),
            dataIndex: 'resourceStatus',
            width: 130,
            render: (value?: string) => <Tag color={value === 'AVAILABLE' ? 'green' : value === 'TRAILER' ? 'orange' : 'default'}>{value || '-'}</Tag>,
        },
        {
            title: t('status'),
            dataIndex: 'status',
            width: 100,
            render: (value?: string) => <Tag color={value === 'DELETED' ? 'red' : 'green'}>{value || 'ACTIVE'}</Tag>,
        },
        {
            title: t('updatedAt'),
            dataIndex: 'updatedAt',
            width: 180,
            render: (value?: string) => value ? new Date(value).toLocaleString() : '-',
        },
        {
            title: t('actions'),
            key: 'actions',
            width: 220,
            fixed: 'right',
            render: (_: unknown, record) => (
                <Space size={4}>
                    <Tooltip title={t('viewDetails')}>
                        <Button
                            type="text"
                            icon={<EyeOutlined />}
                            aria-label={t('viewDetails')}
                            onClick={() => router.push(`/movie/${record.id}`)}
                        />
                    </Tooltip>
                    <Tooltip title={t('edit')}>
                        <Button
                            type="text"
                            icon={<EditOutlined />}
                            aria-label={t('edit')}
                            onClick={() => {
                                setEditingMovie(record);
                                setModalOpen(true);
                            }}
                        />
                    </Tooltip>
                    <Tooltip title={t('movieRepairPoster')}>
                        <Button
                            type="text"
                            icon={<PictureOutlined />}
                            loading={running === `poster-${record.id}`}
                            disabled={Boolean(running && running !== `poster-${record.id}`)}
                            aria-label={t('movieRepairPoster')}
                            onClick={() => runGyingJob(
                                `poster-${record.id}`,
                                `/api/admin/gying-source/movies/${record.id}/poster/repair`,
                            )}
                        />
                    </Tooltip>
                    {['tv', 'ac'].includes(record.category) && (
                        <Tooltip title={t('gyingSourceEnsureSeasons')}>
                            <Button
                                type="text"
                                icon={<DatabaseOutlined />}
                                loading={running === `seasons-${record.id}`}
                                disabled={Boolean(running && running !== `seasons-${record.id}`)}
                                aria-label={t('gyingSourceEnsureSeasons')}
                                onClick={() => runGyingJob(
                                    `seasons-${record.id}`,
                                    `/api/admin/gying-source/movies/${record.id}/seasons/ensure?maxPages=20`,
                                )}
                            />
                        </Tooltip>
                    )}
                    {record.status !== 'DELETED' && (
                        <Popconfirm
                            title={t('deleteMovieTitle')}
                            description={t('deleteMovieDescription')}
                            okText={t('delete')}
                            cancelText={t('cancel')}
                            okType="danger"
                            onConfirm={() => deleteMovie(record)}
                        >
                            <Tooltip title={t('delete')}>
                                <Button type="text" danger icon={<DeleteOutlined />} aria-label={t('delete')} />
                            </Tooltip>
                        </Popconfirm>
                    )}
                </Space>
            ),
        },
    ];

    return (
        <div className="min-h-screen bg-[#f5f7fa] dark:bg-black">
            <div className="container mx-auto px-4 py-8 lg:px-8">
                <Card>
                    <div className="mb-5 flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
                        <div>
                            <Title level={2} className="!mb-1">{t('movieMetadataManagement')}</Title>
                            <Text type="secondary">{t('movieMetadataManagementHint')}</Text>
                        </div>
                        <Space wrap>
                            <Button icon={<ReloadOutlined />} onClick={fetchMovies} loading={loading}>
                                {t('refresh')}
                            </Button>
                            <Button
                                icon={<PictureOutlined />}
                                loading={running === 'repair-posters'}
                                disabled={Boolean(running && running !== 'repair-posters')}
                                onClick={() => runGyingJob(
                                    'repair-posters',
                                    '/api/admin/gying-source/posters/repair?limit=50',
                                )}
                            >
                                {t('movieRepairMissingPosters')}
                            </Button>
                            <Button
                                type="primary"
                                icon={<PlusOutlined />}
                                onClick={() => {
                                    setEditingMovie(null);
                                    setModalOpen(true);
                                }}
                            >
                                {t('createMovieMetadata')}
                            </Button>
                        </Space>
                    </div>

                    <div className="mb-4 grid grid-cols-1 gap-3 sm:flex sm:flex-wrap sm:items-center">
                        <Input.Search
                            allowClear
                            enterButton
                            placeholder={t('searchMovieMetadata')}
                            className="w-full sm:!w-[340px]"
                            onSearch={(value) => {
                                setKeyword(value.trim());
                                setPage(1);
                            }}
                        />
                        <Space>
                            <Switch
                                checked={includeDeleted}
                                onChange={(checked) => {
                                    setIncludeDeleted(checked);
                                    setPage(1);
                                }}
                            />
                            <Text>{t('includeDeleted')}</Text>
                        </Space>
                    </div>

                    <Table
                        columns={columns}
                        dataSource={movies}
                        rowKey="id"
                        loading={loading}
                        scroll={{ x: 2100 }}
                        locale={{ emptyText: <Empty description={t('noMoviesFound')} /> }}
                        onRow={(record) => ({
                            onDoubleClick: () => router.push(`/movie/${record.id}`),
                        })}
                        pagination={{
                            current: page,
                            pageSize: 20,
                            total,
                            onChange: setPage,
                            showTotal: (count) => t('totalMovies', { count }),
                        }}
                    />
                </Card>
            </div>

            <AdminMovieModal
                open={modalOpen}
                movie={editingMovie}
                token={token || ''}
                onCancel={() => setModalOpen(false)}
                onSaved={() => {
                    setModalOpen(false);
                    fetchMovies();
                }}
            />
        </div>
    );
}
