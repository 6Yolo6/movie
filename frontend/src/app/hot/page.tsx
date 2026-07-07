'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { Spin, Empty, Segmented } from 'antd';
import { FireOutlined, HeartFilled } from '@ant-design/icons';
import { api } from '@/lib/api';
import { HotItem } from '@/types';
import MovieCard from '@/components/MovieCard';
import { useTranslation } from 'react-i18next';

type Period = 'day' | 'week' | 'month' | 'all';
type HotMode = 'favorite' | 'tmdb';

export default function HotPage() {
    const [mode, setMode] = useState<HotMode>('favorite');
    const [period, setPeriod] = useState<Period>('all');
    const [items, setItems] = useState<HotItem[]>([]);
    const [loading, setLoading] = useState(true);
    const { t } = useTranslation();

    const fetchHot = useCallback(async (nextMode: HotMode, p: Period) => {
        setLoading(true);
        try {
            const path = nextMode === 'tmdb'
                ? '/api/favorites/tmdb-hot?limit=30'
                : `/api/favorites/hot?period=${p}&limit=30`;
            const res = await api(path);
            if (res.ok) {
                const data: HotItem[] = await res.json();
                setItems(data);
            } else {
                setItems([]);
            }
        } catch {
            setItems([]);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchHot(mode, period);
    }, [mode, period, fetchHot]);

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
                <Spin spinning={loading}>
                    {items.length === 0 && !loading ? (
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
