import { getTechnicalJson, TechnicalRequestError } from './http.js';
export async function checkBackend(fetcher = globalThis.fetch) {
 const payload = await getTechnicalJson('/api/technical/ping', {fetcher});
 if (!payload || payload.service !== 'adc-backend' || payload.status !== 'UP') throw new TechnicalRequestError('INVALID_RESPONSE');
 return payload;
}
