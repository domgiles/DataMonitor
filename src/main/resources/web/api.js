// REST client for the Java backend (see ApiHandler.java). The access token travels in an
// HttpOnly cookie set when the page was opened from the console link.

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

export async function api(method, path, body) {
  const res = await fetch(`/api/${path}`, {
    method,
    credentials: 'same-origin',
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : {},
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.status === 204) return null;
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(data.error || `${res.status} ${res.statusText}`, res.status);
  return data;
}

export const getDashboard = ()            => api('GET', 'dashboard');
export const addModule    = (module)      => api('POST', 'modules', module);
export const updateModule = (id, module)  => api('PUT', `modules/${encodeURIComponent(id)}`, module);
export const deleteModule = (id)          => api('DELETE', `modules/${encodeURIComponent(id)}`);
export const validateSql  = (request)     => api('POST', 'validate', request);
