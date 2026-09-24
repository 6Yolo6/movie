// API base URL configurable via NEXT_PUBLIC_API_URL env var.
// Dev: set in frontend/.env.local (e.g. http://localhost:8880)
// Prod: leave empty to use relative URLs (served via reverse proxy)
const API_BASE = process.env.NEXT_PUBLIC_API_URL || '';
const AUTH_STORAGE_KEY = 'auth-storage';

function readPersistedAuthToken(): string | null {
    if (typeof window === 'undefined') {
        return null;
    }

    try {
        const raw = window.localStorage.getItem(AUTH_STORAGE_KEY);
        if (!raw) {
            return null;
        }

        const persisted = JSON.parse(raw) as { state?: { token?: unknown } };
        const token = persisted?.state?.token;
        return typeof token === 'string' && token.trim() ? token : null;
    } catch {
        return null;
    }
}

export async function api(path: string, options: RequestInit = {}): Promise<Response> {
    const { headers: customHeaders, ...rest } = options;
    const headers = new Headers(customHeaders);
    const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData;

    if (!isFormData && !headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json');
    }

    // Callers may still supply an explicit Authorization header. Otherwise, use
    // the token persisted by the Zustand auth store so new pages cannot
    // accidentally issue unauthenticated requests after a browser refresh.
    if (!headers.has('Authorization') && !path.startsWith('/api/auth/login')) {
        const token = readPersistedAuthToken();
        if (token) {
            headers.set('Authorization', `Bearer ${token}`);
        }
    }

    const response = await fetch(`${API_BASE}${path}`, {
        ...rest,
        headers,
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

export async function readApiError(response: Response, fallback = 'Operation failed'): Promise<string> {
    try {
        const contentType = response.headers.get('content-type') || '';
        if (contentType.includes('application/json')) {
            const data = await response.json();
            return data?.message || data?.error || fallback;
        }
        if (contentType.includes('text/html')) {
            return response.status >= 500 ? '服务暂时不可用，请稍后重试' : fallback;
        }
        const text = await response.text();
        return text || fallback;
    } catch {
        return fallback;
    }
}
