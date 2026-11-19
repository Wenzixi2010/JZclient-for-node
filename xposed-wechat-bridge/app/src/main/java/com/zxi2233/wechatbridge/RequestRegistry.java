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
        String code;
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
            return new AuthorizationSnapshot(state, status, expiresAt, code,
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
        entry.code = null;
        entry.retainUntil = now + TERMINAL_RETENTION_MS;
        return true;
    }

    /** True means this response belongs to the bridge and must not enter XTC login. */
    public synchronized boolean completeWechat(String wireState, String code,
                                               int errorCode, String error, long now) {
        Entry match = null;
        for (Entry entry : byState.values()) {
            if (entry.wireState.equals(wireState)) {
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
        match.retainUntil = now + TERMINAL_RETENTION_MS;
        if (errorCode == 0 && code != null && !code.isEmpty()) {
            match.status = AuthorizationSnapshot.AUTHORIZED;
            match.code = code;
        } else {
            match.status = AuthorizationSnapshot.ERROR;
            match.errorCode = errorCode == -2 ? "wechat_cancelled" : "wechat_error";
            match.errorMessage = errorCode == -2
                    ? "你取消了微信授权。"
                    : (error == null || error.isEmpty() ? "微信授权失败。" : error);
            match.code = null;
        }
        return true;
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

