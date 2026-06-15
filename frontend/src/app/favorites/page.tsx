'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { Spin, Empty, Button, App } from 'antd';
import { HeartOutlined, HeartFilled } from '@ant-design/icons';
import { api } from '@/lib/api';
import { FavoriteItem } from '@/types';
import { useAuthStore } from '@/store/authStore';
import MovieCard from '@/components/MovieCard';
import { useTranslation } from 'react-i18next';
import Link from 'next/link';

export default function FavoritesPage() {
    const { token } = useAuthStore();
    const { message } = App.useApp();
    const { t } = useTranslation();
    const [items, setItems] = useState<FavoriteItem[]>([]);
    const [loading, setLoading] = useState(true);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);

    const fetchFavorites = useCallback(async (p: number) => {
        if (!token) return;
        setLoading(true);
        try {
            const res = await api(`/api/favorites/mine?page=${p}&size=30`, {
                headers: { Authorization: `Bearer ${token}` },
            });
            if (res.ok) {
                const data = await res.json();
                setItems(prev => p === 1 ? data.records : [...prev, ...data.records]);
                setTotal(data.total);
                setPage(p);
            }
        } catch {
            message.error(t('networkError'));
        } finally {
            setLoading(false);
        }
    }, [token, message, t]);

    useEffect(() => {
        if (token) fetchFavorites(1);
        else setLoading(false);
    }, [token, fetchFavorites]);

    const handleRemove = async (movieId: string) => {
        if (!token) return;
        try {
            const res = await api(`/api/favorites/toggle?movieId=${movieId}`, {
                method: 'POST',
                headers: { Authorization: `Bearer ${token}` },
            });
            if (res.ok) {
                setItems(prev => prev.filter(i => i.movie.id !== movieId));
                setTotal(prev => prev - 1);
                message.success(t('favoriteRemoved'));
            }
        } catch {
            message.error(t('networkError'));
        }
    };

    if (!token) {
        return (
            <div className="min-h-screen bg-[#f5f7fa] dark:bg-black flex items-center justify-center">
                <div className="text-center">
                    <HeartOutlined className="text-5xl text-gray-300 dark:text-gray-600 mb-4" />
                    <p className="text-gray-500 dark:text-gray-400 mb-4">{t('loginToViewFavorites')}</p>
                    <Link href="/login">
                        <Button type="primary">{t('signIn')}</Button>
                    </Link>
                </div>
            </div>
        );
    }

    return (
        <div className="min-h-screen bg-[#f5f7fa] dark:bg-black">
            <div className="container mx-auto px-4 lg:px-8 py-8">
                {/* Header */}
                <div className="flex items-center gap-3 mb-8">
                    <HeartFilled className="text-3xl text-red-500" />
                    <h1 className="text-3xl font-bold text-gray-900 dark:text-gray-100">{t('myFavorites')}</h1>
                    <span className="text-gray-400 text-lg">({total})</span>
                </div>

                {/* Content */}
                <Spin spinning={loading && items.length === 0}>
                    {items.length === 0 && !loading ? (
                        <Empty
                            className="py-20"
                            description={t('noFavorites')}
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                        >
                            <Link href="/">
                                <Button type="primary">{t('discoverMovies')}</Button>
                            </Link>
                        </Empty>
                    ) : (
                        <>
                            <div className="grid grid-cols-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3 sm:gap-4 lg:gap-6">
                                {items.map(item => (
                                    <MovieCard key={item.movie.id} movie={item.movie} />
                                ))}
                            </div>

                            {/* Load More */}
                            {page * 30 < total && (
                                <div className="text-center mt-8">
                                    <Button
                                        onClick={() => fetchFavorites(page + 1)}
                                        loading={loading}
                                        size="large"
                                    >
                                        {t('loadMore')}
                                    </Button>
                                </div>
                            )}
                        </>
                    )}
                </Spin>
            </div>
        </div>
    );
}
