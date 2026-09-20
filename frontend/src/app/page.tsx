'use client';

import React, { useCallback, useEffect, useState, Suspense } from 'react';
import { Typography, Spin, Row, Col, Empty, Button, Carousel } from 'antd';
import { FireFilled, RightOutlined, StarFilled } from '@ant-design/icons';
import { useSearchParams, useRouter } from 'next/navigation';
import MovieCard from '@/components/MovieCard';
import { MovieMetadata } from '@/types';
import Link from 'next/link';
import { api } from '@/lib/api';
import { useTranslation } from 'react-i18next';

const { Title } = Typography;

const categories = [
  { key: 'all', labelKey: 'home', value: null },
  { key: 'mv', labelKey: 'movies', value: 'mv' },
  { key: 'tv', labelKey: 'tvShows', value: 'tv' },
  { key: 'ac', labelKey: 'anime', value: 'ac' },
];

const sorts = [
  { labelKey: 'latest', value: 'time' },
  { labelKey: 'rating', value: 'rating' },
];

interface FilterOptions {
  genres: string[];
  regions: string[];
  languages: string[];
  years: string[];
}

interface PaginatedResult<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
  pages: number;
}

const FilterRow = ({
  label,
  options,
  value,
  onChange,
}: {
  label: string;
  options: { label: string; value: string | null }[];
  value: string | null;
  onChange: (val: string | null) => void;
}) => (
  <div className="flex gap-1 sm:gap-2 items-start mb-2 sm:mb-3 text-xs sm:text-sm">
    <span className="text-gray-500 font-medium min-w-[48px] sm:min-w-[64px] shrink-0 pt-0.5">{label}</span>
    <div className="flex flex-wrap gap-2">
      {options.map((opt) => {
        const isSelected = (!value && opt.value === null) || value === opt.value;
        return (
          <span
            key={opt.value || '__all__'}
            onClick={() => onChange(opt.value)}
            className={`px-3 py-1 rounded cursor-pointer transition-colors ${isSelected ? 'bg-zinc-800 text-white font-bold dark:bg-white dark:text-zinc-900' : 'text-gray-600 hover:text-blue-600 dark:text-gray-400 dark:hover:text-white'}`}
          >
            {opt.label}
          </span>
        );
      })}
    </div>
  </div>
);

const compactNumber = (value: number) => {
  if (value >= 10000) return `${(value / 10000).toFixed(1)}万`;
  if (value >= 1000) return `${(value / 1000).toFixed(1)}千`;
  return String(Math.round(value * 10) / 10);
};

const RecentHotCarousel = () => {
  const { t } = useTranslation();
  const [movies, setMovies] = useState<MovieMetadata[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    api('/api/movies/list?page=1&size=8&sort=recent_hot')
      .then(async (response) => {
        if (!response.ok) throw new Error(`Recent hot request failed: ${response.status}`);
        return response.json() as Promise<PaginatedResult<MovieMetadata>>;
      })
      .then((data) => {
        if (active) setMovies(data.records || []);
      })
      .catch(() => {
        if (active) setMovies([]);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  if (loading) {
    return <div className="mb-10 h-[300px] sm:h-[360px] animate-pulse rounded-3xl bg-gray-200 dark:bg-zinc-900" />;
  }
  if (movies.length === 0) return null;

  return (
    <section className="mb-10 min-w-0 max-w-full overflow-hidden" aria-labelledby="recent-hot-title">
      <div className="mb-4 flex items-end justify-between gap-4">
        <div>
          <Title id="recent-hot-title" level={2} className="!mb-1 !text-xl sm:!text-2xl">
            <FireFilled className="mr-2 text-orange-500" />
            {t('recentHot')}
          </Title>
          <p className="m-0 text-xs text-gray-500 sm:text-sm dark:text-gray-400">{t('recentHotHint')}</p>
        </div>
        <Link href="/hot" className="shrink-0 text-sm text-blue-600 hover:text-blue-500 dark:text-blue-400">
          {t('viewHot')} <RightOutlined className="text-xs" />
        </Link>
      </div>

      <Carousel
        autoplay={movies.length > 1}
        autoplaySpeed={5000}
        arrows={movies.length > 1}
        dots={movies.length > 1}
        pauseOnHover
        className="max-w-full overflow-hidden rounded-3xl shadow-xl"
      >
        {movies.map((movie) => {
          const heat = Math.max(movie.popularity || 0, Number(movie.tmdbPopularity || 0));
          const score = Math.max(movie.doubanScore || 0, movie.imdbScore || 0, movie.tmdbVoteAverage || 0);
          return (
            <div key={movie.id}>
              <Link href={`/movie/${movie.id}`} className="group block">
                <article className="relative h-[300px] w-full max-w-full overflow-hidden rounded-3xl bg-zinc-950 sm:h-[360px]">
                  <div
                    className="absolute inset-0 scale-105 bg-cover bg-center opacity-45 blur-xl transition-transform duration-700 group-hover:scale-110"
                    style={{ backgroundImage: `url(${movie.posterUrl || 'https://via.placeholder.com/300x450'})` }}
                  />
                  <div className="absolute inset-0 bg-gradient-to-r from-black via-black/85 to-black/30" />
                  <div className="absolute inset-0 bg-gradient-to-t from-black/80 via-transparent to-transparent" />

                  <div className="relative z-10 flex h-full min-w-0 items-center gap-5 px-5 py-7 sm:gap-10 sm:px-10 lg:px-14">
                    <img
                      src={movie.posterUrl || 'https://via.placeholder.com/300x450'}
                      alt={movie.titleCn}
                      className="hidden h-[280px] w-[187px] shrink-0 rounded-xl object-cover shadow-2xl ring-1 ring-white/20 transition-transform duration-500 group-hover:scale-[1.03] sm:block"
                    />
                    <div className="min-w-0 max-w-3xl text-white">
                      <div className="mb-3 flex flex-wrap items-center gap-2 text-xs sm:text-sm">
                        <span className="rounded-full bg-orange-500/90 px-3 py-1 font-semibold">{t('recentHotBadge')}</span>
                        {movie.category && (
                          <span className="rounded-full border border-white/20 bg-white/10 px-3 py-1 backdrop-blur">
                            {movie.category === 'mv' ? t('movies') : movie.category === 'tv' ? t('tvShows') : t('anime')}
                          </span>
                        )}
                      </div>
                      <h2 className="mb-1 line-clamp-2 text-2xl font-bold leading-tight drop-shadow sm:text-4xl lg:text-5xl">{movie.titleCn}</h2>
                      <p className="mb-4 truncate text-sm text-white/65 sm:text-base">
                        {[movie.titleEn, movie.year, movie.regions?.slice(0, 2).join('/')].filter(Boolean).join(' · ')}
                      </p>
                      <div className="mb-4 flex flex-wrap items-center gap-3 text-sm">
                        {score > 0 && (
                          <span className="inline-flex items-center gap-1 font-semibold text-yellow-300">
                            <StarFilled /> {score.toFixed(1)}
                          </span>
                        )}
                        {heat > 0 && (
                          <span className="inline-flex items-center gap-1 text-orange-300">
                            <FireFilled /> {compactNumber(heat)}
                          </span>
                        )}
                        {movie.genres?.slice(0, 3).map((genre) => (
                          <span key={genre} className="rounded border border-white/15 bg-white/10 px-2 py-0.5 text-white/80">{genre}</span>
                        ))}
                      </div>
                      {movie.summary && (
                        <p
                          className="hidden max-w-2xl overflow-hidden text-sm leading-6 text-white/70 sm:block"
                          style={{ display: '-webkit-box', WebkitLineClamp: 3, WebkitBoxOrient: 'vertical' }}
                        >
                          {movie.summary}
                        </p>
                      )}
                      <span className="mt-4 inline-flex items-center gap-2 rounded-full bg-blue-600 px-5 py-2 text-sm font-semibold shadow-lg transition-colors group-hover:bg-blue-500">
                        {t('viewDetails')} <RightOutlined />
                      </span>
                    </div>
                  </div>
                </article>
              </Link>
            </div>
          );
        })}
      </Carousel>
    </section>
  );
};

const MovieGrid = ({ params, highlightKeyword }: { params: URLSearchParams; highlightKeyword?: string }) => {
  const { t } = useTranslation();
  const [movies, setMovies] = useState<MovieMetadata[]>([]);
  const [loading, setLoading] = useState(true);
  const [hasMore, setHasMore] = useState(true);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const pageSize = 30;

  const paramString = params.toString();

  const loadMovies = useCallback(async (p: number = 1) => {
    setLoading(true);
    try {
      const query = new URLSearchParams(paramString);
      query.set('page', String(p));
      query.set('size', String(pageSize));
      const res = await api(`/api/movies/list?${query.toString()}`);
      const data: PaginatedResult<MovieMetadata> = await res.json();
      setMovies(prev => p === 1 ? data.records : [...prev, ...data.records]);
      setHasMore(p * pageSize < data.total);
      setPage(p);
      setTotal(data.total);
    } catch (err) {
      console.error(err);
      setHasMore(false);
    } finally {
      setLoading(false);
    }
  }, [paramString]);

  useEffect(() => {
    loadMovies(1);
  }, [loadMovies]);

  if (loading && movies.length === 0) return <div className="flex justify-center p-20"><Spin size="large" /></div>;
  if (movies.length === 0) return (
    <div className="py-20">
      <Empty
        description={
          highlightKeyword
            ? <span>{t('noResultsFor')} &quot;<strong>{highlightKeyword}</strong>&quot;</span>
            : t('noMoviesFound')
        }
      />
    </div>
  );

  return (
    <div>
      {paramString && total > 0 && (
        <div className="mb-4 text-sm text-gray-600 dark:text-gray-400">
          {highlightKeyword
            ? <>{t('found')} <strong className="text-blue-600 dark:text-blue-400">{total}</strong> {t('resultsFor')} &quot;<strong>{highlightKeyword}</strong>&quot;</>
            : <><strong className="text-blue-600 dark:text-blue-400">{total}</strong> {t('results')}</>
          }
        </div>
      )}
      <Row gutter={[12, 16]} className="sm:!ml-0 sm:!mr-0">
        {movies.map(movie => (
          <Col xs={8} sm={8} md={6} lg={4} xl={4} xxl={3} key={movie.id}>
            <MovieCard movie={movie} highlightKeyword={highlightKeyword} />
          </Col>
        ))}
      </Row>
      {hasMore && (
        <div className="text-center mt-8">
          <Button loading={loading} onClick={() => loadMovies(page + 1)}>
            {t('loadMore')}
          </Button>
        </div>
      )}
    </div>
  );
};

const HomePageContent = () => {
  const { t } = useTranslation();
  const searchParams = useSearchParams();
  const router = useRouter();

  const [filterOptions, setFilterOptions] = useState<FilterOptions | null>(null);

  const category = searchParams.get('category');
  const keyword = searchParams.get('keyword');
  const genre = searchParams.get('genre');
  const region = searchParams.get('region');
  const language = searchParams.get('language');
  const year = searchParams.get('year');
  const sort = searchParams.get('sort') || 'time';

  useEffect(() => {
    const query = category ? `?category=${encodeURIComponent(category)}` : '';
    api(`/api/movies/filters${query}`)
      .then(res => res.json())
      .then(data => setFilterOptions(data))
      .catch(console.error);
  }, [category]);

  const updateParam = (key: string, val: string | null) => {
    const sp = new URLSearchParams(searchParams.toString());
    if (val) sp.set(key, val);
    else sp.delete(key);
    router.push(`/?${sp.toString()}`);
  };

  const showFilters = !!category || !!keyword || !!genre || !!region || !!language || !!year;
  const isLandingPage = !showFilters && !category && !keyword;

  if (!filterOptions) return <div className="flex justify-center p-20"><Spin size="large" /></div>;

  const buildOptions = (key: keyof FilterOptions) => [
    { label: t('all'), value: null },
    ...(filterOptions[key] || []).map((item) => ({ label: item, value: item })),
  ];

  return (
    <div className="container mx-auto px-3 sm:px-4 py-4 sm:py-8">
      {isLandingPage && <RecentHotCarousel />}

      {keyword && (
        <div className="mb-6 text-center">
          <Title level={3}>{t('searchTitle')}: &quot;{keyword}&quot;</Title>
        </div>
      )}

      {(showFilters || category) && (
        <div className="mb-8 bg-white p-4 rounded-xl border border-gray-200 shadow-sm dark:bg-[#141414] dark:border-zinc-800">
          <FilterRow label={t('genre')} options={buildOptions('genres')} value={genre} onChange={(v) => updateParam('genre', v)} />
          <FilterRow label={t('region')} options={buildOptions('regions')} value={region} onChange={(v) => updateParam('region', v)} />
          <FilterRow label={t('language')} options={buildOptions('languages')} value={language} onChange={(v) => updateParam('language', v)} />
          <FilterRow label={t('year')} options={buildOptions('years')} value={year} onChange={(v) => updateParam('year', v)} />

          <div className="flex gap-2 items-center text-sm border-t border-gray-200 dark:border-zinc-800 pt-3 mt-1">
            <span className="text-gray-500 font-medium min-w-[64px]">{t('sort')}</span>
            <div className="flex gap-4">
              {sorts.map(s => (
                <span
                  key={s.value}
                  onClick={() => updateParam('sort', s.value)}
                  className={`cursor-pointer ${sort === s.value ? 'text-blue-500 font-bold' : 'text-gray-600 hover:text-blue-600 dark:text-gray-400 dark:hover:text-white'}`}
                >
                  {t(s.labelKey)}
                </span>
              ))}
            </div>
          </div>
        </div>
      )}

      {isLandingPage ? (
        <div className="flex flex-col gap-12">
          {categories.slice(1).map(cat => (
            <section key={cat.key}>
              <div className="flex items-center justify-between mb-6">
                <Title level={3} className="m-0 border-l-4 border-blue-500 pl-3">{t(cat.labelKey)}</Title>
                <Link href={`/?category=${cat.value}`} className="text-gray-400 hover:text-blue-400 text-sm">{t('viewAll')} &gt;</Link>
              </div>
              <MovieGrid params={new URLSearchParams({ category: cat.value || '', sort: 'featured' })} />
            </section>
          ))}
        </div>
      ) : (
        <div className="min-h-[60vh]">
          <MovieGrid params={new URLSearchParams(searchParams.toString())} highlightKeyword={keyword || undefined} />
        </div>
      )}
    </div>
  );
};

export default function Home() {
  return (
    <div className="min-h-screen bg-gray-50 text-gray-900 dark:bg-[#0a0a0a] dark:text-gray-100 transition-colors">
      <Suspense fallback={<div className="flex justify-center p-20"><Spin size="large" /></div>}>
        <HomePageContent />
      </Suspense>
    </div>
  );
}
