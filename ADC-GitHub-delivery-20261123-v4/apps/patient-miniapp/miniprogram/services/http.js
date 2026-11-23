// Native wx.request adapter. No configured endpoint, credentials, logging or retries.
const MESSAGES = Object.freeze({
  NOT_CONFIGURED: '尚未配置技术服务地址',
  INVALID_CONFIGURATION: '技术服务配置无效',
  INVALID_REQUEST: '技术请求无效',
  TRANSPORT_UNAVAILABLE: '当前环境不支持网络请求',
  NETWORK_ERROR: '暂时无法连接技术服务',
  TIMEOUT: '连接超时，请稍后检查',
  INVALID_RESPONSE: '技术服务响应无法识别',
  HTTP_ERROR: '技术服务暂时无法处理请求'
});

class TechnicalHttpError extends Error {
  constructor(code, status) {
    super(MESSAGES[code] || MESSAGES.HTTP_ERROR);
    this.name = 'TechnicalHttpError';
    this.code = code;
    if (status !== undefined) this.status = status;
  }
}

function validateBaseUrl(value, allowLocalHttp) {
  if (value === '') return '';
  if (typeof value !== 'string' || /[\\\s?#@]/.test(value)) {
    throw new TechnicalHttpError('INVALID_CONFIGURATION');
  }
  const match = /^(https?):\/\/(\[[0-9a-fA-F:]+\]|[a-zA-Z0-9.-]+)(?::([0-9]+))?(\/[a-zA-Z0-9._~!$&'()*+,;=:@%/-]*)?$/.exec(value);
  if (!match || (match[3] && (+match[3] < 1 || +match[3] > 65535))) {
    throw new TechnicalHttpError('INVALID_CONFIGURATION');
  }
  const local = ['127.0.0.1', 'localhost', '[::1]'].includes(match[2].toLowerCase());
  if (match[1] !== 'https' && !(allowLocalHttp === true && local)) {
    throw new TechnicalHttpError('INVALID_CONFIGURATION');
  }
  return value.replace(/\/+$/, '');
}

function createHttpClient(options = {}) {
  const baseUrl = validateBaseUrl(options.baseUrl === undefined ? '' : options.baseUrl,
    options.allowLocalHttp);
  const timeout = options.timeoutMs === undefined ? 5000 : options.timeoutMs;
  if (!Number.isInteger(timeout) || timeout < 1) {
    throw new TechnicalHttpError('INVALID_CONFIGURATION');
  }
  const transport = options.request || ((typeof wx !== 'undefined' && typeof wx.request === 'function')
    ? wx.request.bind(wx) : null);

  function send(path, method, data, headers) {
    if (!baseUrl) return Promise.reject(new TechnicalHttpError('NOT_CONFIGURED'));
    const patientList = typeof path === 'string' && method === 'GET' &&
      /^\/api\/v1\/mini\/(?:appointments|visit-history)\?patientId=[1-9][0-9]{0,18}(?:&cursor=[1-9][0-9]{0,18})?$/.test(path);
    const visitDetail=typeof path==='string'&&method==='GET'&&/^\/api\/v1\/mini\/visit-history\/[1-9][0-9]{0,18}\?patientId=[1-9][0-9]{0,18}$/.test(path);
    const bookingOptions=typeof path==='string'&&method==='GET'&&/^\/api\/v1\/mini\/booking(?:-change)?-options\?clinicId=[1-9][0-9]{0,18}&date=\d{4}-\d{2}-\d{2}(?:&projectId=[1-9][0-9]{0,18})?(?:&originalAppointmentId=[1-9][0-9]{0,18})?$/.test(path);
    if (typeof path !== 'string' || (!visitDetail && !bookingOptions && !patientList && !/^\/[a-zA-Z0-9_/-]*(?:\?operation=op008)?$/.test(path)) ||
        path.startsWith('//') || path.includes('..')) {
      return Promise.reject(new TechnicalHttpError('INVALID_REQUEST'));
    }
    if (typeof transport !== 'function') {
      return Promise.reject(new TechnicalHttpError('TRANSPORT_UNAVAILABLE'));
    }
    return new Promise((resolve, reject) => {
      let settled = false;
      function fail(code, status, networkCategory) {
        if (!settled) { settled = true; const error=new TechnicalHttpError(code,status);if(networkCategory)error.networkCategory=networkCategory;reject(error); }
      }
      function success(response) {
        if (settled) return;
        if (!response || !Number.isInteger(response.statusCode) || response.statusCode < 100 || response.statusCode > 599) {
          fail('INVALID_RESPONSE'); return;
        }
        const status = response.statusCode;
        if (status < 200 || status >= 300) {
          if(status===409&&response.data?.code==='MINI_SELECTED_SLOT_UNAVAILABLE'){fail('MINI_SELECTED_SLOT_UNAVAILABLE',409);return;}
          // Never display response bodies/messages: they may contain sensitive details.
          fail(status >= 400 ? 'HTTP_' + status : 'HTTP_ERROR', status); return;
        }
        if (!response.data || typeof response.data !== 'object') {
          fail('INVALID_RESPONSE'); return;
        }
        settled = true;
        resolve(response.data);
      }
      try {
        transport({ url: baseUrl + path, method, timeout,
          ...(data === undefined ? {} : { data }),
          header: { Accept: 'application/json', ...headers }, dataType: 'json',
          success, fail(error) {
            const code = error && (error.errCode === 'TIMEOUT' ||
              (typeof error.errMsg === 'string' && /timeout/i.test(error.errMsg))) ? 'TIMEOUT' : 'NETWORK_ERROR';
            const detail=typeof error?.errMsg==='string'?error.errMsg:'';
            const category=/not in.*domain|domain.*list|合法域名/i.test(detail)?'DOMAIN':/ssl|tls|certificate|证书/i.test(detail)?'TLS':/connection|connect|连接/i.test(detail)?'CONNECTION':'UNKNOWN';
            fail(code,undefined,category);
          }
        });
      } catch (_) { fail('NETWORK_ERROR'); }
    });
  }
  return Object.freeze({
    get: (path, headers) => send(path, 'GET', undefined, headers),
    post: (path, data, headers) => send(path, 'POST', data, { 'Content-Type': 'application/json', ...headers }),
  });
}
module.exports = { createHttpClient, TechnicalHttpError };
