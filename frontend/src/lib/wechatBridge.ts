const DEFAULT_BASE_URL = 'http://127.0.0.1:38787';

export class WechatBridgeError extends Error {
  code: string;
  status: number;
  wechatErrorCode?: number;
  constructor(code: string, message: string, options: { status?: number; wechatErrorCode?: number; cause?: unknown } = {}) {
    super(message);
    this.name = 'WechatBridgeError';
    this.code = code;
    this.status = options.status || 0;
    this.wechatErrorCode = options.wechatErrorCode;
    if (options.cause !== undefined) (this as Error & { cause?: unknown }).cause = options.cause;
  }
}

function randomState() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  let binary = '';
  bytes.forEach(byte => { binary += String.fromCharCode(byte); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

async function bridgeRequest(path: string, options: RequestInit = {}) {
  let response: Response;
  try {
    response = await fetch(DEFAULT_BASE_URL + path, {
      mode: 'cors', credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer',
      ...options,
      headers: options.body ? { 'Content-Type': 'application/json' } : undefined,
    });
  } catch (cause) {
    if (options.signal?.aborted) throw cause;
    throw new WechatBridgeError('bridge_unavailable', '无法连接手机上的微信授权组件。请确认模块已启用，并重新打开小天才家长端。', { cause });
  }
  let body: any;
  try { body = await response.json(); }
  catch (cause) { throw new WechatBridgeError('invalid_bridge_response', '微信授权组件返回了无法识别的响应。', { status: response.status, cause }); }
  if (!response.ok) {
    throw new WechatBridgeError(body?.error?.code || 'bridge_http_error', body?.error?.message || `微信授权组件请求失败（HTTP ${response.status}）。`, { status: response.status });
  }
  return body;
}

export async function getWechatBridgeStatus(signal?: AbortSignal) {
  return bridgeRequest('/v1/status', { signal });
}

function delay(milliseconds: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const onAbort = () => { clearTimeout(timer); reject(signal.reason); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', onAbort); resolve(); }, milliseconds);
    signal.addEventListener('abort', onAbort, { once: true });
  });
}

export type WechatAuthorizationStatus = 'waiting_for_host' | 'awaiting_confirmation' | 'launching_wechat' | 'waiting_for_wechat' | 'logging_in' | 'authorized' | 'authorizing' | 'error';

export async function requestWechatAuthorization({
  signal,
  onStatus,
  timeoutMs = 125_000,
}: {
  signal?: AbortSignal;
  onStatus?: (status: WechatAuthorizationStatus) => void;
  timeoutMs?: number;
} = {}) {
  const controller = new AbortController();
  const abort = () => controller.abort(signal?.reason);
  signal?.addEventListener('abort', abort, { once: true });
  let state = '';
  let created = false;
  let terminal = false;
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);

  try {
    await getWechatBridgeStatus(controller.signal);
    state = randomState();
    let snapshot = await bridgeRequest('/v1/authorizations', {
      method: 'POST', body: JSON.stringify({ state }), signal: controller.signal,
    });
    created = true;
    onStatus?.(snapshot.status);
    while (true) {
      await delay(800, controller.signal);
      snapshot = await bridgeRequest(`/v1/authorizations/${state}`, { signal: controller.signal });
      if (snapshot.state !== state) throw new WechatBridgeError('state_mismatch', '微信授权返回的校验状态不一致，请重试。');
      onStatus?.(snapshot.status);
      if (snapshot.status === 'error') {
        terminal = true;
        throw new WechatBridgeError(snapshot.error?.code || 'authorization_failed', snapshot.error?.message || '微信授权失败。', { wechatErrorCode: snapshot.wechatErrorCode });
      }
      if (snapshot.status === 'authorized') {
        terminal = true;
        if (!snapshot.session) throw new WechatBridgeError('missing_session', '小天才登录成功，但没有返回账号会话。');
        return { session: String(snapshot.session), state };
      }
    }
  } catch (error) {
    if (timedOut) throw new WechatBridgeError('authorization_timeout', '等待微信授权超时，请重新发起。', { cause: error });
    if (signal?.aborted) throw new WechatBridgeError('aborted', '已取消微信授权。', { cause: error });
    throw error;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
    if (created && !terminal) {
      fetch(`${DEFAULT_BASE_URL}/v1/authorizations/${state}`, { method: 'DELETE', mode: 'cors', credentials: 'omit', cache: 'no-store' }).catch(() => {});
    }
  }
}
