// API base URL configurable via NEXT_PUBLIC_API_URL env var.
// Dev: set in frontend/.env.local (e.g. http://localhost:8880)
// Prod: leave empty to use relative URLs (served via reverse proxy)
const API_BASE = process.env.NEXT_PUBLIC_API_URL || '';
const AUTH_STORAGE_KEY = 'auth-storage';

export async function api(path: string, options: RequestInit = {}): Promise<Response> {
    const { headers: customHeaders, ...rest } = options;
    const response = await fetch(`${API_BASE}${path}`, {
        headers: {
            'Content-Type': 'application/json',
            ...(customHeaders as Record<string, string>),
        },
        ...rest,
    });

    if (response.status === 401 && typeof window !== 'undefined' && !path.startsWith('/api/auth/login')) {
        window.localStorage.removeItem(AUTH_STORAGE_KEY);
        window.dispatchEvent(new Event('auth:unauthorized'));

        if (!window.location.pathname.startsWith('/login')) {
            const redirect = encodeURIComponent(`${window.location.pathname}${window.location.search}`);
            window.location.href = `/login?redirect=${redirect}`;
        }
    }

    return response;
}
