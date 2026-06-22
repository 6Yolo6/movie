import React from 'react';
import { Card } from 'antd';
import { PlayCircleOutlined, HeartFilled } from '@ant-design/icons';
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

const MovieCard: React.FC<MovieCardProps> = ({ movie, highlightKeyword, offsetPopularityBadge = false }) => {
    const popularity = movie.popularity || 0;

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

                        {/* Top Right: Douban Score */}
                        <div className="absolute top-2 right-2">
                            <span className="bg-black/60 backdrop-blur-md text-white text-xs px-2 py-1 rounded border border-white/20">
                                {movie.doubanScore > 0 ? `${movie.doubanScore}` : 'N/A'}
                            </span>
                        </div>

                        {/* Top Left: Popularity (if > 0) */}
                        {popularity > 0 && (
                            <div className={`absolute top-2 ${offsetPopularityBadge ? 'left-10' : 'left-2'}`}>
                                <span className="bg-red-500/80 backdrop-blur-md text-white text-xs px-2 py-1 rounded-full border border-white/20 flex items-center gap-1">
                                    <HeartFilled className="text-[10px]" />
                                    {popularity}
                                </span>
                            </div>
                        )}

                        {/* Bottom Info Overlay */}
                        <div className="absolute bottom-0 left-0 right-0 p-2 bg-gradient-to-t from-black via-black/80 to-transparent pt-8">
                            <h3 className="text-white text-sm font-bold truncate">
                                <HighlightText text={movie.titleCn} keyword={highlightKeyword} />
                            </h3>
                        </div>
                    </div>
                }
                styles={{ body: { padding: 0, display: 'none' } }}
                className="bg-zinc-900 border-zinc-800 overflow-hidden shadow-lg"
            />
            {/* External Info Line: Year / Region / Genres */}
            <div className="mt-2 text-center">
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
