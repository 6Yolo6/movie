'use client';

import React, { useCallback, useEffect, useState, Suspense } from 'react';
import { Typography, Spin, Row, Col, Empty, Button } from 'antd';
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

interface PaginatedResult<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
  pages: number;
}

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

  if (!filterOptions) return <div className="flex justify-center p-20"><Spin size="large" /></div>;

  const buildOptions = (key: keyof FilterOptions) => [
    { label: t('all'), value: null },
    ...(filterOptions[key] || []).map((item) => ({ label: item, value: item })),
  ];

  return (
    <div className="container mx-auto px-3 sm:px-4 py-4 sm:py-8">
      <div className="flex justify-center mb-8">
        <div className="bg-gray-200 dark:bg-[#1f1f1f] p-1 rounded-full inline-flex overflow-x-auto">
          {categories.map(cat => (
            <Link
              key={cat.key}
              href={cat.value ? `/?category=${cat.value}` : '/'}
              className={`px-4 sm:px-6 py-1.5 sm:py-2 rounded-full text-xs sm:text-sm font-medium transition-all whitespace-nowrap ${(cat.value === category || (cat.value === null && !category && !keyword))
                ? 'bg-blue-600 text-white shadow-lg'
                : 'text-gray-600 hover:text-blue-600 dark:text-gray-400 dark:hover:text-white'
                }`}
            >
              {t(cat.labelKey)}
            </Link>
          ))}
        </div>
      </div>

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

      {!showFilters && !category && !keyword ? (
        <div className="flex flex-col gap-12">
          {categories.slice(1).map(cat => (
            <section key={cat.key}>
              <div className="flex items-center justify-between mb-6">
                <Title level={3} className="m-0 border-l-4 border-blue-500 pl-3">{t(cat.labelKey)}</Title>
                <Link href={`/?category=${cat.value}`} className="text-gray-400 hover:text-blue-400 text-sm">{t('viewAll')} &gt;</Link>
              </div>
              <MovieGrid params={new URLSearchParams({ category: cat.value || '' })} />
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
