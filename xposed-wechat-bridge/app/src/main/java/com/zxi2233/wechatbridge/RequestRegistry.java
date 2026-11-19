package com.zxi2233.wechatbridge;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** In-memory, origin-bound and single-use authorization results. */
public final class RequestRegistry {
    private static final int MAX_ACTIVE_REQUESTS = 1;
    private static final long TERMINAL_RETENTION_MS = 30_000L;

    private static final class Entry {
        final String state;
        final String wireState;
        final String origin;
        final long createdAt;
        final long expiresAt;
        String status = AuthorizationSnapshot.WAITING_FOR_HOST;
        String accountSession;
        int wechatErrorCode;
        String wechatError;
        String errorCode;
        String errorMessage;
        long retainUntil = Long.MAX_VALUE;

        Entry(String state, String origin, long now) {
            this.state = state;
            this.wireState = BridgeContract.wireState(state);
            this.origin = origin;
            this.createdAt = now;
            this.expiresAt = now + BridgeContract.REQUEST_TTL_MS;
        }

        AuthorizationSnapshot snapshot() {
            return new AuthorizationSnapshot(state, status, expiresAt, accountSession,
                    wechatErrorCode, wechatError, errorCode, errorMessage);
        }
    }

    private final Map<String, Entry> byState = new HashMap<>();

    public synchronized AuthorizationSnapshot create(String origin, String state, long now)
            throws BridgeException {
        if (!BridgeContract.isValidState(state)) {
            throw new BridgeException(400, "invalid_state",
                    "state 必须是 16-128 位 URL 安全随机字符串。");
        }
        String normalizedOrigin = BridgeContract.normalizeOrigin(origin);
        purge(now);
        if (byState.containsKey(state)) {
            throw new BridgeException(409, "state_in_use", "这个授权请求已经存在。");
        }
        int active = 0;
        for (Entry entry : byState.values()) {
            if (!entry.snapshot().isTerminal()) active++;
        }
        if (active >= MAX_ACTIVE_REQUESTS) {
            throw new BridgeException(429, "too_many_requests", "已有过多待处理的授权请求。");
        }
        Entry entry = new Entry(state, normalizedOrigin, now);
        byState.put(state, entry);
        return entry.snapshot();
    }

    /** Returns waiting states repeatedly, but consumes a terminal result once. */
    public synchronized AuthorizationSnapshot poll(String origin, String state, long now) {
        Entry entry = matching(origin, state, now);
        if (entry == null) return null;
        expireIfNeeded(entry, now);
        AuthorizationSnapshot snapshot = entry.snapshot();
        if (snapshot.isTerminal()) byState.remove(state);
        return snapshot;
    }

    public synchronized AuthorizationSnapshot peek(String state, long now) {
        Entry entry = byState.get(state);
        if (entry == null) return null;
        expireIfNeeded(entry, now);
        return entry.snapshot();
    }

    public synchronized boolean matches(String wireState) {
        if (wireState == null) return false;
        for (Entry entry : byState.values()) {
            if (BridgeContract.sameWechatResponse(entry.wireState, wireState)) return true;
        }
        return false;
    }

    synchronized String originForInternal(String state) {
        Entry entry = byState.get(state);
        return entry == null ? null : entry.origin;
    }

    public synchronized AuthorizationSnapshot nextPending(long now) {
        purge(now);
        Entry oldest = null;
        for (Entry entry : byState.values()) {
            if (AuthorizationSnapshot.WAITING_FOR_HOST.equals(entry.status)
                    && (oldest == null || entry.createdAt < oldest.createdAt)) {
                oldest = entry;
            }
        }
        return oldest == null ? null : oldest.snapshot();
    }

    public synchronized boolean mark(String state, String expectedStatus,
                                     String nextStatus, long now) {
        Entry entry = byState.get(state);
        if (entry == null) return false;
        expireIfNeeded(entry, now);
        if (!expectedStatus.equals(entry.status)) return false;
        entry.status = nextStatus;
        return true;
    }

    public synchronized boolean fail(String state, String errorCode,
                                     String message, long now) {
        Entry entry = byState.get(state);
        if (entry == null || entry.snapshot().isTerminal()) return false;
        entry.status = AuthorizationSnapshot.ERROR;
        entry.errorCode = errorCode;
        entry.errorMessage = message;
        entry.accountSession = null;
        entry.retainUntil = now + TERMINAL_RETENTION_MS;
        return true;
    }

    /**
     * Claims the matching WeChat callback. The opaque code never leaves the
     * host process; only a successful host login can complete the request.
     */
    public synchronized boolean claimWechat(String wireState, int errorCode,
                                            String error, long now) {
        Entry match = null;
        for (Entry entry : byState.values()) {
            if (BridgeContract.sameWechatResponse(entry.wireState, wireState)) {
                match = entry;
                break;
            }
        }
        if (match == null) return false;
        if (match.snapshot().isTerminal() || now >= match.expiresAt) {
            fail(match.state, "expired", "授权请求已过期，请重新发起。", now);
            return true;
        }
        match.wechatErrorCode = errorCode;
        match.wechatError = error;
        if (errorCode != 0) {
            match.status = AuthorizationSnapshot.ERROR;
            match.errorCode = errorCode == -2 ? "wechat_cancelled" : "wechat_error";
            match.errorMessage = errorCode == -2
                    ? "你取消了微信授权。"
                    : (error == null || error.isEmpty() ? "微信授权失败。" : error);
            match.accountSession = null;
            match.retainUntil = now + TERMINAL_RETENTION_MS;
        } else {
            // Keep the two-minute bridge deadline while XTC exchanges the
            // one-time code and downloads the account and watch snapshot.
            match.status = AuthorizationSnapshot.LOGGING_IN;
        }
        return true;
    }

    public synchronized boolean completeWithAccount(String wireState, String accountSession,
                                                    int wechatErrorCode, String wechatError,
                                                    long now) {
        Entry match = matchingWireState(wireState);
        if (match == null || !AuthorizationSnapshot.LOGGING_IN.equals(match.status)) return false;
        if (accountSession == null || accountSession.isEmpty()) {
            fail(match.state, "missing_host_session", "小天才登录成功，但没有返回可用的账号会话。", now);
            return true;
        }
        match.status = AuthorizationSnapshot.AUTHORIZED;
        match.accountSession = accountSession;
        match.wechatErrorCode = wechatErrorCode;
        match.wechatError = wechatError;
        match.retainUntil = now + TERMINAL_RETENTION_MS;
        return true;
    }

    public synchronized boolean failLogin(String wireState, String errorCode,
                                          String message, long now) {
        Entry match = matchingWireState(wireState);
        if (match == null || match.snapshot().isTerminal()) return false;
        return fail(match.state, errorCode, message, now);
    }

    private Entry matchingWireState(String wireState) {
        if (wireState == null) return null;
        for (Entry entry : byState.values()) {
            if (BridgeContract.sameWechatResponse(entry.wireState, wireState)) return entry;
        }
        return null;
    }

    public synchronized boolean cancel(String origin, String state, long now) {
        Entry entry = matching(origin, state, now);
        if (entry == null) return false;
        byState.remove(state);
        return true;
    }

    private Entry matching(String origin, String state, long now) {
        if (!BridgeContract.isValidState(state) || origin == null) return null;
        Entry entry = byState.get(state);
        if (entry == null) return null;
        final String normalized;
        try {
            normalized = BridgeContract.normalizeOrigin(origin);
        } catch (BridgeException error) {
            return null;
        }
        if (!entry.origin.equals(normalized)) return null;
        expireIfNeeded(entry, now);
        if (entry.snapshot().isTerminal() && now > entry.retainUntil) {
            byState.remove(state);
            return null;
        }
        return entry;
    }

    private void expireIfNeeded(Entry entry, long now) {
        if (!entry.snapshot().isTerminal() && now >= entry.expiresAt) {
            fail(entry.state, "expired", "授权请求已过期，请重新发起。", now);
        }
    }

    private void purge(long now) {
        Iterator<Map.Entry<String, Entry>> iterator = byState.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            expireIfNeeded(entry, now);
            if (entry.snapshot().isTerminal() && now > entry.retainUntil) iterator.remove();
        }
    }
}

