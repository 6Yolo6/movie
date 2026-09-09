import React from 'react';
import { Card } from 'antd';
import { PlayCircleOutlined, FireFilled } from '@ant-design/icons';
import Link from 'next/link';
import { MovieMetadata } from '@/types';

interface MovieCardProps {
    movie: MovieMetadata;
    highlightKeyword?: string;
    offsetPopularityBadge?: boolean;
}

const HighlightText: React.FC<{ text: string; keyword?: string }> = ({ text, keyword }) => {
    if (!keyword || !text) return <>{text}</>;
    const regex = new RegExp(`(${keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi');
    const parts = text.split(regex);
    return (
        <>
            {parts.map((part, i) =>
                regex.test(part)
                    ? <mark key={i} className="bg-yellow-300/80 dark:bg-yellow-500/40 text-inherit rounded-sm px-0.5">{part}</mark>
                    : <React.Fragment key={i}>{part}</React.Fragment>
            )}
        </>
    );
};

const MovieCard: React.FC<MovieCardProps> = ({ movie, highlightKeyword }) => {
    const popularity = Math.max(movie.popularity || 0, Number(movie.tmdbPopularity || 0));
    const ratings = [
        movie.doubanScore > 0 ? { value: movie.doubanScore, source: '豆瓣', icon: '豆', color: 'bg-emerald-500 text-white' } : null,
        movie.imdbScore > 0 ? { value: movie.imdbScore, source: 'IMDb', icon: 'IMDb', color: 'bg-yellow-400 text-black' } : null,
        movie.tmdbVoteAverage && movie.tmdbVoteAverage > 0 ? { value: movie.tmdbVoteAverage, source: 'TMDB', icon: 'T', color: 'bg-sky-500 text-white' } : null,
    ].filter(Boolean).slice(0, 2) as { value: number; source: string; icon: string; color: string }[];
    const compactNumber = (value: number) => value >= 10000 ? `${(value / 10000).toFixed(1)}万` : value >= 1000 ? `${(value / 1000).toFixed(1)}千` : String(Math.round(value * 10) / 10);

    return (
        <Link href={`/movie/${movie.id}`}>
            <Card
                hoverable
                cover={
                    <div className="relative group overflow-hidden">
                        <img
                            alt={movie.titleCn}
                            src={movie.posterUrl || 'https://via.placeholder.com/300x450'}
                            className="h-[220px] sm:h-[280px] md:h-[380px] w-full object-cover transition-transform duration-500 group-hover:scale-110"
                        />
                        {/* Overlay Gradient on Hover */}
                        <div className="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity duration-300 flex items-center justify-center">
                            <PlayCircleOutlined className="text-5xl text-white/90 drop-shadow-lg" />
                        </div>

                        <div className="absolute bottom-0 left-0 right-0 p-2 bg-gradient-to-t from-black via-black/80 to-transparent pt-9">
                            <div className="flex items-center gap-2 text-[11px] text-white/95">
                                {ratings.map(rating => <span key={rating.source} title={rating.source} className="inline-flex items-center gap-1"><span className={`inline-flex h-3.5 min-w-3.5 items-center justify-center rounded-sm px-0.5 text-[8px] font-bold leading-none ${rating.color}`}>{rating.icon}</span><span className="font-semibold">{rating.value.toFixed(1)}</span></span>)}
                                {popularity > 0 && <span className="inline-flex items-center gap-0.5 text-orange-300"><FireFilled className="text-[10px]" />{compactNumber(popularity)}</span>}
                            </div>
                        </div>
                    </div>
                }
                styles={{ body: { padding: 0, display: 'none' } }}
                className="bg-zinc-900 border-zinc-800 overflow-hidden shadow-lg"
            />
            {/* External Info Line: Year / Region / Genres */}
            <div className="mt-2 text-center">
                <div className="text-gray-700 dark:text-gray-300 text-sm font-medium truncate px-1">
                    <HighlightText text={movie.titleCn} keyword={highlightKeyword} />
                </div>
                <div className="text-gray-400 text-xs truncate px-1">
                    {[
                        movie.year,
                        movie.regions?.join('/'),
                        movie.genres?.join('/')
                    ].filter(Boolean).join(' / ')}
                </div>
            </div>
        </Link>
    );
};

export default MovieCard;
