import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rmdir, unlink, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let directory;
let bridge;
let moduleFile;

test.before(async () => {
  const cache = path.join(frontend, 'node_modules', '.cache');
  await mkdir(cache, { recursive: true });
  directory = await mkdtemp(path.join(cache, 'wechat-bridge-checks-'));
  moduleFile = path.join(directory, 'wechat-bridge.mjs');
  const result = await build({
    absWorkingDir: frontend,
    stdin: {
      contents: `export * from './src/lib/wechatBridge';`,
      loader: 'ts', resolveDir: frontend, sourcefile: 'wechat-bridge-entry.ts',
    },
    bundle: true, platform: 'node', format: 'esm', target: 'node18',
    packages: 'external', outfile: moduleFile, write: false,
  });
  await writeFile(moduleFile, result.outputFiles[0].contents);
  bridge = await import(pathToFileURL(moduleFile).href);
});

test.after(async () => {
  if (moduleFile) await unlink(moduleFile).catch(() => {});
  if (directory) await rmdir(directory);
});

test('返回网页后即使没有 visibilitychange 也会继续取得授权结果', async t => {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', {
    url: 'http://192.168.1.8:8787/#/account',
    pretendToBeVisual: true,
  });
  const originalGlobals = new Map();
  for (const [name, value] of [
    ['window', dom.window],
    ['document', dom.window.document],
    ['sessionStorage', dom.window.sessionStorage],
    ['crypto', dom.window.crypto],
    ['btoa', dom.window.btoa],
  ]) {
    originalGlobals.set(name, Object.getOwnPropertyDescriptor(globalThis, name));
    Object.defineProperty(globalThis, name, { value, writable: true, configurable: true });
  }

  const state = 'state_12345678901234567890';
  dom.window.sessionStorage.setItem('jz_wechat_authorization', JSON.stringify({
    state, status: 'waiting_for_host', expiresAt: Date.now() + 60_000, handoffStarted: true,
  }));
  let polls = 0;
  Object.defineProperty(globalThis, 'fetch', {
    configurable: true,
    writable: true,
    value: async url => {
      if (url.endsWith('/v1/status')) return json({ ok: true });
      if (url.endsWith(`/v1/authorizations/${state}`)) {
        polls++;
        return json({
          ok: true,
          state,
          status: 'authorized',
          expiresAt: Date.now() + 60_000,
          session: '{"ticket":"host-session"}',
        });
      }
      throw new Error(`unexpected request: ${url}`);
    },
  });

  t.after(() => {
    dom.window.close();
    for (const [name, descriptor] of originalGlobals) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else Reflect.deleteProperty(globalThis, name);
    }
  });

  const statuses = [];
  const result = await bridge.requestWechatAuthorization({
    resumeOnly: true,
    timeoutMs: 2_000,
    onStatus: status => statuses.push(status),
  });
  assert.equal(result.session, '{"ticket":"host-session"}');
  assert.ok(polls >= 1);
  assert.deepEqual(statuses.at(-1), 'authorized');
  assert.equal(dom.window.sessionStorage.getItem('jz_wechat_authorization'), null);
});

function json(body) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
