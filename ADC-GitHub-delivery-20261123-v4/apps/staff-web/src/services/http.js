// Transport errors expose stable technical codes, never arbitrary server text.
export class TechnicalRequestError extends Error {
 constructor(code, status = null) { super(code); this.name = 'TechnicalRequestError'; this.code = code; this.status = status; }
}
export async function getTechnicalJson(path, { fetcher = globalThis.fetch, timeoutMs = 5000 } = {}) {
 if (typeof path !== 'string' || !/^\/api\/technical\/[a-z0-9-]+$/.test(path)) throw new TechnicalRequestError('INVALID_ENDPOINT');
 if (!Number.isInteger(timeoutMs) || timeoutMs <= 0 || timeoutMs > 2147483647) throw new TechnicalRequestError('INVALID_TIMEOUT');
 let response;
 try { response = await fetcher(path, {method:'GET', headers:{Accept:'application/json'}, signal:AbortSignal.timeout(timeoutMs)}); }
 catch (error) { throw new TechnicalRequestError(['TimeoutError','AbortError'].includes(error?.name) ? 'TIMEOUT' : 'NETWORK_ERROR'); }
 if (!response || typeof response.ok !== 'boolean') throw new TechnicalRequestError('INVALID_RESPONSE');
 if (!response.ok) {
  const status = Number.isInteger(response.status) && response.status >= 400 && response.status <= 599 ? response.status : null;
  throw new TechnicalRequestError(status ? `HTTP_${status}` : 'HTTP_ERROR', status);
 }
 try { return await response.json(); }
 catch (error) {
  const timedOut = ['TimeoutError','AbortError'].includes(error?.name);
  throw new TechnicalRequestError(timedOut ? 'TIMEOUT' : 'INVALID_RESPONSE');
 }
}
export function technicalErrorMessage(error) {
 return error?.code === 'TIMEOUT' ? '连接超时，请稍后重试' : '暂时无法连接技术服务，请检查后端是否启动';
}
