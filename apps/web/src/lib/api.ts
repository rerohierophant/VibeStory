import type { components } from '../../../../packages/contracts/src/api';
export type Schema = components['schemas'];

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}
let csrf: { headerName: string; token: string } | undefined;
let csrfRequest: Promise<void> | undefined;
async function prepareCsrf() {
  if (csrf) return;
  csrfRequest ??= fetch('/api/auth/csrf', { credentials: 'same-origin' }).then(async (response) => {
    if (!response.ok) throw new ApiError(response.status, '无法连接后端');
    csrf = await response.json();
  }).finally(() => { csrfRequest = undefined; });
  await csrfRequest;
}
async function request(path: string, method: string, body?: unknown, signal?: AbortSignal): Promise<Response> {
  if (method !== 'GET') await prepareCsrf();
  const headers = new Headers();
  if (method !== 'GET' && csrf) headers.set(csrf.headerName, csrf.token);
  if (body !== undefined && !(body instanceof FormData)) headers.set('Content-Type', 'application/json');
  let response: Response;
  try {
    response = await fetch(path, { method, headers, credentials: 'same-origin', signal,
      body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body) });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new ApiError(0, '后端连接中断，提交结果可能已保存，请先刷新查看状态');
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    if (response.status === 401) csrf = undefined;
    throw new ApiError(response.status, problem.detail ?? (response.status === 404 ? '资源不存在或不属于当前账号' : '请求失败，请稍后重试'));
  }
  if (path === '/api/auth/login' || path === '/api/auth/register' || path === '/api/auth/logout') csrf = undefined;
  return response;
}

export async function api<T>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<T> {
  const response = await request(path, method, body, signal);
  return response.status === 204 ? undefined as T : response.json();
}

export async function streamAgentReply(path: string, body: FormData, onEvent: (event: Schema['ReplyEvent']) => void) {
  const response = await request(path, 'POST', body);
  if (!response.body || !response.headers.get('content-type')?.startsWith('application/x-ndjson'))
    throw new ApiError(0, '未收到对话流，请刷新后查看状态');
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  const interrupted = () => new ApiError(0, '对话连接中断，已提交的消息可在会话中查看');
  let pending = '';
  let transcript: Schema['Transcript'] | undefined;
  try {
    while (true) {
      const { done, value } = await reader.read().catch(() => { throw interrupted(); });
      if (done) {
        if (transcript) return transcript;
        throw interrupted();
      }
      pending += value;
      let end: number;
      while ((end = pending.indexOf('\n')) >= 0) {
        const line = pending.slice(0, end);
        pending = pending.slice(end + 1);
        const event = JSON.parse(line) as Schema['ReplyEvent'];
        if (event.type === 'error') throw new ApiError(event.status ?? 500, event.text ?? '消息发送失败');
        if (event.type === 'complete' && event.transcript) transcript = event.transcript;
        else onEvent(event);
      }
    }
  } finally {
    await reader.cancel().catch(() => {});
  }
}
export async function backendAvailable() {
  const abort = new AbortController();
  const timer = window.setTimeout(() => abort.abort(), 3000);
  try { await api('/api/auth/csrf', 'GET', undefined, abort.signal); return true; }
  catch { return false; }
  finally { window.clearTimeout(timer); }
}
