'use client';

import React, { useState, useEffect, useMemo } from 'react';
import { Alert, Button, Spin, Empty, Segmented } from 'antd';
import { FireOutlined, HeartFilled } from '@ant-design/icons';
import { api, readApiError } from '@/lib/api';
import { HotItem } from '@/types';
import MovieCard from '@/components/MovieCard';
import { useTranslation } from 'react-i18next';

type Period = 'day' | 'week' | 'month' | 'all';
type HotMode = 'favorite' | 'tmdb';

export default function HotPage() {
    const [mode, setMode] = useState<HotMode>('favorite');
    const [period, setPeriod] = useState<Period>('all');
    const [refresh, setRefresh] = useState(0);
    const { t } = useTranslation();
    const query = mode === 'tmdb'
        ? '/api/favorites/tmdb-hot?limit=30'
        : '/api/favorites/hot?period=' + period + '&limit=30';
    const request = useMemo(() => ({ query, refresh, t }), [query, refresh, t]);
    const [snapshot, setSnapshot] = useState<{
        request: typeof request | null;
        items: HotItem[];
        error: string | null;
    }>({ request: null, items: [], error: null });
    // Derive loading immediately; a new query never displays the old ranking.
    const items = snapshot.request?.query === query ? snapshot.items : [];
    const loading = snapshot.request !== request;
    const error = snapshot.request === request ? snapshot.error : null;

    useEffect(() => {
        const controller = new AbortController();
        let active = true;
        const fail = (error: string) => {
            if (active) setSnapshot(previous => ({ request,
                items: previous.request?.query === request.query ? previous.items : [], error }));
        };
        void (async () => {
            try {
                const response = await api(request.query, { signal: controller.signal });
                if (!response.ok) {
                    fail(await readApiError(response, request.t('hotLoadFailed')));
                    return;
                }
                const items: HotItem[] = await response.json();
                if (active) setSnapshot({ request, items, error: null });
            } catch {
                fail(request.t('networkError'));
            }
        })();
        // Guard even when a transport ignores abort or JSON parsing finishes late.
        return () => { active = false; controller.abort(); };
    }, [request]);

    const periodOptions = [
        { label: t('hotAll'), value: 'all' },
        { label: t('hotMonth'), value: 'month' },
        { label: t('hotWeek'), value: 'week' },
        { label: t('hotDay'), value: 'day' },
    ];

    const modeOptions = [
        { label: t('hotByFavorites'), value: 'favorite', icon: <HeartFilled /> },
        { label: t('hotByTmdb'), value: 'tmdb', icon: <FireOutlined /> },
    ];

    return (
        <div className="min-h-screen bg-[#f5f7fa] dark:bg-black">
            <div className="container mx-auto px-4 lg:px-8 py-8">
                {/* Header */}
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 mb-8">
                    <div className="flex items-center gap-3">
                        <FireOutlined className="text-3xl text-orange-500" />
                        <h1 className="text-3xl font-bold text-gray-900 dark:text-gray-100">{t('hotTitle')}</h1>
                    </div>
                    <div className="flex flex-col sm:flex-row gap-3">
                        <Segmented
                            options={modeOptions}
                            value={mode}
                            onChange={(val) => setMode(val as HotMode)}
                            size="large"
                            className="bg-gray-200/60 dark:bg-zinc-800"
                        />
                        {mode === 'favorite' && (
                            <Segmented
                                options={periodOptions}
                                value={period}
                                onChange={(val) => setPeriod(val as Period)}
                                size="large"
                                className="bg-gray-200/60 dark:bg-zinc-800"
                            />
                        )}
                    </div>
                </div>

                {/* Content */}
                {error && <Alert className="!mb-4" type="error" showIcon title={error}
                    action={<Button onClick={() => setRefresh(value => value + 1)}>{t('retry')}</Button>} />}
                <Spin spinning={loading}>
                    {items.length === 0 && !loading && !error ? (
                        <Empty
                            className="py-20"
                            description={t('hotEmpty')}
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                        />
                    ) : (
                        <div className="grid grid-cols-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3 sm:gap-4 lg:gap-6">
                            {items.map((item, idx) => (
                                <div key={item.movie.id} className="relative">
                                    {/* Rank Badge */}
                                    {idx < 3 && (
                                        <div className={`absolute -top-2 -left-2 z-10 w-8 h-8 rounded-full flex items-center justify-center text-white text-sm font-bold shadow-lg ${
                                            idx === 0 ? 'bg-gradient-to-br from-yellow-400 to-orange-500' :
                                            idx === 1 ? 'bg-gradient-to-br from-gray-300 to-gray-500' :
                                            'bg-gradient-to-br from-orange-400 to-red-600'
                                        }`}>
                                            {idx + 1}
                                        </div>
                                    )}
                                    <MovieCard movie={item.movie} offsetPopularityBadge={idx < 3} />
                                </div>
                            ))}
                        </div>
                    )}
                </Spin>
            </div>
        </div>
    );
}
