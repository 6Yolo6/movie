'use client';

import { useEffect, useState } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import { Alert, Button, Skeleton } from 'antd';
import { useTranslation } from 'react-i18next';
import { api } from '@/lib/api';

interface RelatedMovie {
    id: string; titleCn?: string; titleEn?: string; year?: number;
    posterUrl?: string; genres?: string[]; rating?: number;
}

export default function RelatedMovies({ movieId }: { movieId: string }) {
    const { t } = useTranslation();
    const [attempt, setAttempt] = useState(0);
    const [result, setResult] = useState<{ id: string; movies: RelatedMovie[]; error: boolean } | null>(null);
    useEffect(() => {
        const controller = new AbortController();
        setResult(null);
        void (async () => {
            try {
                const response = await api(`/api/movies/${encodeURIComponent(movieId)}/recommendations`, { signal: controller.signal });
                if (!response.ok) throw new Error('Recommendations unavailable');
                const movies = await response.json();
                if (!Array.isArray(movies)) throw new Error('Invalid recommendations');
                if (!controller.signal.aborted) setResult({ id: movieId, movies, error: false });
            } catch {
                if (!controller.signal.aborted) setResult({ id: movieId, movies: [], error: true });
            }
        })();
        return () => controller.abort();
    }, [movieId, attempt]);
    const current = result?.id === movieId ? result : null;
    return <section className="mt-10" aria-label={t('relatedMovies')} data-testid="related-movies" aria-busy={!current}>
        <h2 className="mb-4 text-xl font-semibold">{t('relatedMovies')}</h2>
        {!current ? <Skeleton active paragraph={{ rows: 3 }} /> : current.error ?
            <Alert type="warning" title={t('relatedMoviesFailed')} action={<Button size="small" onClick={() => setAttempt(value => value + 1)}>{t('retry')}</Button>} /> :
            current.movies.length === 0 ? <p className="text-sm text-gray-500">{t('noRelatedMovies')}</p> :
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-4 lg:grid-cols-8">
                {current.movies.map(movie => <Link key={movie.id} href={`/movie/${encodeURIComponent(movie.id)}`} target="_blank" rel="noopener noreferrer" className="min-w-0 group">
                    <div className="relative aspect-[2/3] overflow-hidden rounded-lg bg-gray-100 dark:bg-zinc-800">
                        {movie.posterUrl && <Image src={movie.posterUrl} alt={movie.titleCn || movie.titleEn || movie.id} fill unoptimized sizes="(max-width: 640px) 45vw, (max-width: 1024px) 22vw, 12vw" className="object-cover transition-transform group-hover:scale-105" />}
                        {!!movie.rating && <span className="absolute bottom-2 right-2 rounded bg-black/70 px-1.5 text-xs text-white">{movie.rating.toFixed(1)}</span>}
                    </div>
                    <p className="mb-1 mt-2 truncate text-sm font-medium text-gray-700 group-hover:text-blue-600 dark:text-gray-200">{movie.titleCn || movie.titleEn}</p>
                    <p className="m-0 truncate text-xs text-gray-500">{[movie.year, ...(movie.genres || []).slice(0, 2)].filter(Boolean).join(' · ')}</p>
                </Link>)}
            </div>}
    </section>;
}
