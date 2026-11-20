'use client';

import { useEffect, useState } from 'react';
import { api } from '@/lib/api';

interface Snapshot<T> { url: string; data: T | null; loading: boolean; error: boolean; updatedAt: Date | null }

export function useMonitoringData<T>(url: string, refresh: number, parse: (value: unknown) => T) {
    const [snapshot, setSnapshot] = useState<Snapshot<T>>({ url, data: null, loading: true, error: false, updatedAt: null });
    useEffect(() => {
        const controller = new AbortController();
        let active = true;
        setSnapshot(previous => ({ url, loading: true, error: false,
            data: previous.url === url ? previous.data : null,
            updatedAt: previous.url === url ? previous.updatedAt : null }));
        void (async () => {
            try {
                const response = await api(url, { signal: controller.signal, cache: 'no-store' });
                if (!response.ok) throw new Error('Monitoring request failed');
                const data = parse(await response.json());
                if (active) setSnapshot({ url, data, loading: false, error: false, updatedAt: new Date() });
            } catch {
                if (active) setSnapshot(previous => ({ ...previous, loading: false, error: true }));
            }
        })();
        return () => { active = false; controller.abort(); };
    }, [url, refresh, parse]);
    // A new filter must never display rows or a total from the previous query.
    return snapshot.url === url ? snapshot : { url, data: null, loading: true, error: false, updatedAt: null };
}
