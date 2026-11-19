const DEFAULT_BASE_URL = 'http://127.0.0.1:38787';
const PENDING_AUTHORIZATION_KEY = 'jz_wechat_authorization';
const PENDING_AUTHORIZATION_TTL_MS = 115_000;

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

export type WechatAuthorizationStatus = 'waiting_for_host' | 'awaiting_confirmation' | 'launching_wechat' | 'waiting_for_wechat' | 'logging_in' | 'authorized' | 'authorizing' | 'error';

type PendingAuthorization = {
  state: string;
  status: WechatAuthorizationStatus;
  expiresAt: number;
};

function readPendingAuthorization(): PendingAuthorization | null {
  try {
    const stored = sessionStorage.getItem(PENDING_AUTHORIZATION_KEY);
    if (!stored) return null;
    const pending = JSON.parse(stored) as PendingAuthorization;
    if (!/^[A-Za-z0-9_-]{16,128}$/.test(pending.state || '')
        || !Number.isFinite(pending.expiresAt)
        || pending.expiresAt <= Date.now()) {
      sessionStorage.removeItem(PENDING_AUTHORIZATION_KEY);
      return null;
    }
    return pending;
  } catch {
    return null;
  }
}

function savePendingAuthorization(pending: PendingAuthorization | null) {
  try {
    if (pending) sessionStorage.setItem(PENDING_AUTHORIZATION_KEY, JSON.stringify(pending));
    else sessionStorage.removeItem(PENDING_AUTHORIZATION_KEY);
  } catch { /* Storage is an optimization; the in-memory request still works. */ }
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

export function getPendingWechatAuthorization() {
  const pending = readPendingAuthorization();
  return pending ? { status: pending.status, expiresAt: pending.expiresAt } : null;
}

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
  const deadlineMs = Math.max(5_000, Number(timeoutMs) || 125_000);
  let timer: ReturnType<typeof setTimeout> | null = null;
  // Android freezes background browser timers while the native host is in
  // front. Start the deadline after we resume instead of expiring unseen.
  const pauseDeadline = () => {
    if (timer) clearTimeout(timer);
    timer = null;
  };
  const startDeadline = () => {
    pauseDeadline();
    timer = setTimeout(() => { timedOut = true; controller.abort(); }, deadlineMs);
  };
  const visibilityChanged = () => {
    if (document.visibilityState === 'visible') startDeadline();
    else pauseDeadline();
  };
  const pending = readPendingAuthorization();

  try {
    document.addEventListener('visibilitychange', visibilityChanged);
    startDeadline();
    await getWechatBridgeStatus(controller.signal);
    let snapshot: any;
    if (pending) {
      state = pending.state;
      onStatus?.(pending.status);
      try {
        snapshot = await bridgeRequest(`/v1/authorizations/${state}`, { signal: controller.signal });
      } catch (error) {
        if (!(error instanceof WechatBridgeError) || error.status !== 404) throw error;
        savePendingAuthorization(null);
        state = '';
      }
    }
    if (!state) {
      state = randomState();
      snapshot = await bridgeRequest('/v1/authorizations', {
        method: 'POST', body: JSON.stringify({ state }), signal: controller.signal,
      });
      created = true;
      savePendingAuthorization({
        state,
        status: snapshot.status,
        expiresAt: Number(snapshot.expiresAt) || Date.now() + PENDING_AUTHORIZATION_TTL_MS,
      });
      onStatus?.(snapshot.status);
    }
    while (true) {
      if (snapshot.status === 'error') {
        terminal = true;
        savePendingAuthorization(null);
        throw new WechatBridgeError(snapshot.error?.code || 'authorization_failed', snapshot.error?.message || '微信授权失败。', { wechatErrorCode: snapshot.wechatErrorCode });
      }
      if (snapshot.status === 'authorized') {
        terminal = true;
        savePendingAuthorization(null);
        if (!snapshot.session) throw new WechatBridgeError('missing_session', '小天才登录成功，但没有返回账号会话。');
        return { session: String(snapshot.session), state };
      }
      await delay(800, controller.signal);
      snapshot = await bridgeRequest(`/v1/authorizations/${state}`, { signal: controller.signal });
      if (snapshot.state !== state) throw new WechatBridgeError('state_mismatch', '微信授权返回的校验状态不一致，请重试。');
      onStatus?.(snapshot.status);
      savePendingAuthorization({
        state,
        status: snapshot.status,
        expiresAt: Date.now() + PENDING_AUTHORIZATION_TTL_MS,
      });
    }
  } catch (error) {
    if (timedOut) throw new WechatBridgeError('authorization_timeout', '等待微信授权超时，请重新发起。', { cause: error });
    if (signal?.aborted) throw new WechatBridgeError('aborted', '已取消微信授权。', { cause: error });
    throw error;
  } finally {
    pauseDeadline();
    document.removeEventListener('visibilitychange', visibilityChanged);
    signal?.removeEventListener('abort', abort);
    if (created && !terminal) {
      savePendingAuthorization(null);
      fetch(`${DEFAULT_BASE_URL}/v1/authorizations/${state}`, { method: 'DELETE', mode: 'cors', credentials: 'omit', cache: 'no-store' }).catch(() => {});
    }
  }
}
