// API base URL configurable via NEXT_PUBLIC_API_URL env var.
// Dev: set in frontend/.env.local (e.g. http://localhost:8880)
// Prod: leave empty to use relative URLs (served via reverse proxy)
const API_BASE = process.env.NEXT_PUBLIC_API_URL || '';

export function api(path: string, options: RequestInit = {}): Promise<Response> {
    const { headers: customHeaders, ...rest } = options;
    return fetch(`${API_BASE}${path}`, {
        headers: {
            'Content-Type': 'application/json',
            ...(customHeaders as Record<string, string>),
        },
        ...rest,
    });
}
