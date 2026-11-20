package com.zxi2233.wechatbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class RequestRegistryTest {
    private static final long NOW = 1_000_000L;
    private static final String ORIGIN = "http://192.168.1.8:8787";
    private static final String STATE = "state_12345678901234567890";

    private final RequestRegistry registry = new RequestRegistry();

    @Test
    public void hostDeepLinkRegistersBeforeTheBrowserPolls() throws Exception {
        registry.acceptTransfer(STATE, NOW);
        assertTrue(registry.bindTransfer(ORIGIN, STATE, NOW + 1));
        AuthorizationSnapshot waiting = registry.poll(ORIGIN, STATE, NOW + 1);
        assertEquals(AuthorizationSnapshot.WAITING_FOR_HOST, waiting.status);
        assertTrue(registry.claimWechat(
                BridgeContract.wireState(STATE), 0, null, NOW + 2));
        assertEquals(AuthorizationSnapshot.LOGGING_IN,
                registry.poll(ORIGIN, STATE, NOW + 3).status);
        assertNull(registry.poll("http://192.168.1.9:8787", STATE, NOW + 4));
    }

    @Test
    public void resultIsBoundToOriginAndConsumedOnce() throws Exception {
        registry.create(ORIGIN, STATE, NOW);
        assertNull(registry.poll("http://192.168.1.9:8787", STATE, NOW));
        assertEquals(AuthorizationSnapshot.WAITING_FOR_HOST,
                registry.poll(ORIGIN, STATE, NOW).status);
        assertTrue(registry.claimWechat(BridgeContract.wireState(STATE), 0, null, NOW + 100));
        assertEquals(AuthorizationSnapshot.LOGGING_IN,
                registry.peek(ORIGIN, STATE, NOW + 150).status);
        assertTrue(registry.completeWithAccount(BridgeContract.wireState(STATE),
                "{\"ticket\":\"host-session\"}", 0, null, NOW + 175));

        AuthorizationSnapshot result = registry.poll(ORIGIN, STATE, NOW + 200);
        assertEquals(AuthorizationSnapshot.AUTHORIZED, result.status);
        assertEquals("{\"ticket\":\"host-session\"}", result.accountSession);
        assertNull(registry.poll(ORIGIN, STATE, NOW + 300));
    }

    @Test
    public void hostLoginStateIsNotOwnedByBridge() {
        assertFalse(registry.claimWechat("login_state", 0, null, NOW));
        assertFalse(registry.claimWechat("relative_state_1", 0, null, NOW));
    }

    @Test
    public void officialLoginStateIsOwnedOnlyWithThePendingRequestState() throws Exception {
        registry.create(ORIGIN, STATE, NOW);
        assertFalse(registry.claimOfficialWechat("unrelated_state_123456789", 0, null, NOW));
        assertTrue(registry.claimOfficialWechat(STATE, 0, null, NOW + 100));
        assertEquals(AuthorizationSnapshot.LOGGING_IN,
                registry.peek(ORIGIN, STATE, NOW + 150).status);
        assertTrue(registry.completeWithAccount(BridgeContract.wireState(STATE),
                "{\"ticket\":\"host-session\"}", 0, null, NOW + 175));
    }

    @Test
    public void successfulLoginCanReturnOnlyToTheRequestingPageOnce() throws Exception {
        String returnUrl = ORIGIN + "/#/account?source=wechat";
        registry.create(ORIGIN, STATE, NOW);
        assertTrue(registry.setReturnTargets(
                ORIGIN, STATE, returnUrl, "", "", NOW));
        registry.claimWechat(BridgeContract.wireState(STATE), 0, null, NOW + 100);
        assertTrue(registry.completeWithAccount(BridgeContract.wireState(STATE),
                "{\"ticket\":\"host-session\"}", 0, null, NOW + 200));
        assertEquals(returnUrl, registry.claimReturnTargetForInternal(STATE).url);
        assertNull(registry.claimReturnTargetForInternal(STATE));
    }

    @Test
    public void returnUrlMustStayOnTheAuthorizedOrigin() throws Exception {
        registry.create(ORIGIN, STATE, NOW);
        assertFalse(registry.setReturnTargets(
                ORIGIN, STATE, "https://evil.example/steal", "", "", NOW));
        assertFalse(registry.setReturnTargets(
                ORIGIN, STATE, "javascript:alert(1)", "", "", NOW));
        assertNull(registry.claimReturnTargetForInternal(STATE));
    }

    @Test
    public void successfulLoginCanReturnToANativeCallerOnce() throws Exception {
        String returnUri = "jzclient://wechat-auth/complete";
        registry.create(ORIGIN, STATE, NOW);
        assertTrue(registry.setReturnTargets(ORIGIN, STATE,
                ORIGIN + "/account", returnUri, "com.zxi.example", NOW));
        registry.claimWechat(BridgeContract.wireState(STATE), 0, null, NOW + 100);
        RequestRegistry.InternalCompletion completion =
                registry.completeWithAccountForInternal(
                        BridgeContract.wireState(STATE),
                        "{\"ticket\":\"host-session\"}", 0, null, NOW + 200);

        assertEquals(returnUri, completion.returnTarget.uri);
        assertEquals("com.zxi.example", completion.returnTarget.packageName);
        assertNull(registry.claimReturnTargetForInternal(STATE));
    }

    @Test
    public void deepLinkReturnTargetSurvivesBrowserBinding() throws Exception {
        String returnUrl = ORIGIN + "/#/account";
        String returnUri = "jzclient://wechat-auth/complete";
        registry.acceptTransfer(
                STATE, returnUrl, returnUri, "com.zxi.example", NOW);
        assertTrue(registry.bindTransfer(ORIGIN, STATE, NOW + 1));
        registry.claimOfficialWechat(STATE, 0, null, NOW + 100);
        RequestRegistry.InternalCompletion completion =
                registry.completeWithAccountForInternal(
                        BridgeContract.wireState(STATE),
                        "{\"ticket\":\"host-session\"}", 0, null, NOW + 200);

        assertEquals(returnUrl, completion.returnTarget.url);
        assertEquals(returnUri, completion.returnTarget.uri);
        assertEquals("com.zxi.example", completion.returnTarget.packageName);
    }

    @Test
    public void browserRequestCannotReplaceDeepLinkReturnTargets() throws Exception {
        String returnUrl = ORIGIN + "/#/account";
        String returnUri = "jzclient://wechat-auth/complete";
        registry.acceptTransfer(
                STATE, returnUrl, returnUri, "com.zxi.example", NOW);
        assertTrue(registry.bindTransfer(ORIGIN, STATE, NOW + 1));
        registry.claimOfficialWechat(STATE, 0, null, NOW + 100);
        RequestRegistry.InternalCompletion completion =
                registry.completeWithAccountForInternal(
                        BridgeContract.wireState(STATE),
                        "{\"ticket\":\"host-session\"}", 0, null, NOW + 200);

        assertEquals(returnUrl, completion.returnTarget.url);
        assertEquals(returnUri, completion.returnTarget.uri);
    }

    @Test
    public void nativeReturnTargetsRejectUnsafeSchemesAndPackages() {
        assertEquals("", BridgeContract.validateReturnUri("https://example.com/return"));
        assertEquals("", BridgeContract.validateReturnUri("javascript:alert(1)"));
        assertEquals("", BridgeContract.validateReturnUri("intent://example.com"));
        assertEquals("jzclient://auth/complete",
                BridgeContract.validateReturnUri("jzclient://auth/complete"));
        assertEquals("", BridgeContract.validateReturnPackage("../unsafe"));
        assertEquals("", BridgeContract.validateReturnPackage(
                BridgeContract.MODULE_PACKAGE));
        assertEquals("com.zxi.example",
                BridgeContract.validateReturnPackage("com.zxi.example"));
    }

    @Test
    public void nativeLoginAlwaysHasANullWireState() {
        assertTrue(BridgeContract.sameWechatResponse(null, null));
        assertFalse(BridgeContract.sameWechatResponse(null, "jzwb1.web-state"));
        assertTrue(BridgeContract.sameWechatResponse("jzwb1.web-state", "jzwb1.web-state"));
    }

    @Test
    public void browserHostHeaderAllowsAnySafeHostname() {
        assertEquals("127.0.0.1:38787", BridgeContract.validateHost("127.0.0.1:38787"));
        assertEquals("jz-desktop.local:8787", BridgeContract.validateHost("jz-desktop.local:8787"));
        assertNull(BridgeContract.validateHost(null));
        assertNull(BridgeContract.validateHost("https://example.com/path"));
        assertNull(BridgeContract.validateHost("user@example.com"));
    }

    @Test
    public void onlyExplicitBridgeResponseStatesAreClassified() {
        assertTrue(BridgeContract.shouldIntercept("login_state"));
        assertTrue(BridgeContract.shouldIntercept(BridgeContract.wireState(STATE)));
        assertFalse(BridgeContract.shouldIntercept("relative_state_1"));
        assertFalse(BridgeContract.shouldIntercept(null));
    }

    @Test
    public void lateResponseNeverReleasesCode() throws Exception {
        registry.create(ORIGIN, STATE, NOW);
        assertTrue(registry.claimWechat(BridgeContract.wireState(STATE),
                0, null, NOW + BridgeContract.REQUEST_TTL_MS));
        AuthorizationSnapshot result = registry.poll(ORIGIN, STATE,
                NOW + BridgeContract.REQUEST_TTL_MS);
        assertEquals(AuthorizationSnapshot.ERROR, result.status);
        assertEquals("expired", result.errorCode);
        assertNull(result.accountSession);
    }

    @Test
    public void cancelledWechatResponseContainsNoCode() throws Exception {
        registry.create(ORIGIN, STATE, NOW);
        registry.claimWechat(BridgeContract.wireState(STATE),
                -2, "cancel", NOW + 100);
        AuthorizationSnapshot result = registry.poll(ORIGIN, STATE, NOW + 200);
        assertEquals("wechat_cancelled", result.errorCode);
        assertEquals(-2, result.wechatErrorCode);
        assertNull(result.accountSession);
    }

    @Test
    public void cancelRemovesRequestAndItsFutureResult() throws Exception {
        registry.create(ORIGIN, STATE, NOW);
        assertFalse(registry.cancel("http://other.example", STATE, NOW));
        assertTrue(registry.cancel(ORIGIN, STATE, NOW));
        assertNull(registry.poll(ORIGIN, STATE, NOW));
        assertFalse(registry.claimWechat(BridgeContract.wireState(STATE),
                0, null, NOW));
    }

    @Test
    public void onlyOneActiveAuthorizationIsAllowed() throws Exception {
        registry.create(ORIGIN, STATE, NOW);
        BridgeException error = assertThrows(BridgeException.class, () ->
                registry.create(ORIGIN, "another_123456789012345678", NOW));
        assertEquals(429, error.httpStatus);
    }

    @Test
    public void duplicateAndInvalidStatesAreRejected() throws Exception {
        registry.create(ORIGIN, STATE, NOW);
        assertEquals(409, assertThrows(BridgeException.class, () ->
                registry.create(ORIGIN, STATE, NOW)).httpStatus);
        assertEquals(400, assertThrows(BridgeException.class, () ->
                registry.create(ORIGIN, "short", NOW)).httpStatus);
        assertEquals(400, assertThrows(BridgeException.class, () ->
                registry.create(ORIGIN, "has/slash_1234567890", NOW)).httpStatus);
    }

    @Test
    public void originsAreNormalizedAndRestricted() throws Exception {
        assertEquals("http://example.com", BridgeContract.normalizeOrigin("HTTP://EXAMPLE.COM:80"));
        assertEquals("https://example.com:8443",
                BridgeContract.normalizeOrigin("https://example.com:8443"));
        assertEquals(403, assertThrows(BridgeException.class, () ->
                BridgeContract.normalizeOrigin("null")).httpStatus);
        assertEquals(403, assertThrows(BridgeException.class, () ->
                BridgeContract.normalizeOrigin("https://example.com/path")).httpStatus);
        assertEquals(403, assertThrows(BridgeException.class, () ->
                BridgeContract.normalizeOrigin("intent://example.com")).httpStatus);
    }
}

