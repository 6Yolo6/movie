import { notFound } from 'next/navigation';
import Link from 'next/link';
import { MovieDetailDTO } from '@/types';
import MovieDetailClient from './MovieDetailClient';

const API_BASE = process.env.NEXT_PUBLIC_API_URL || '';

async function getMovie(id: string): Promise<{ data: MovieDetailDTO | null; status: number }> {
    try {
        const res = await fetch(`${API_BASE}/api/movies/${id}`, {
            cache: 'no-store',
        });

        if (!res.ok) {
            return { data: null, status: res.status };
        }

        return { data: await res.json(), status: res.status };
    } catch (error) {
        console.error('Failed to fetch movie', error);
        return { data: null, status: -1 };
    }
}

export default async function MoviePage({ params }: { params: Promise<{ id: string }> }) {
    const { id } = await params;
    const { data, status } = await getMovie(id);

    if (!data) {
        if (status === 404) {
            return notFound();
        }
        return (
            <div className="min-h-screen flex flex-col items-center justify-center bg-gray-50 dark:bg-black">
                <div className="text-6xl mb-4">⚠️</div>
                <h1 className="text-2xl font-bold text-gray-800 dark:text-gray-200 mb-2">
                    Service Unavailable
                </h1>
                <p className="text-gray-500 dark:text-gray-400 mb-6">
                    {status === -1
                        ? 'Backend server is not reachable. Please try again later.'
                        : `Server returned an error (${status}). Please try again later.`
                    }
                </p>
                <Link href="/" className="text-blue-600 hover:underline">Back to Home</Link>
            </div>
        );
    }

    return <MovieDetailClient data={data} />;
}
