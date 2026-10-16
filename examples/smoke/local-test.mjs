/** Runs the same acceptance scenarios against both executable Java services. Node >= 20. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import fs from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { createFixture } from './platform-fixture.mjs';

const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
async function freePort() {
  const server = http.createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
async function stop(child) {
  if (!child || child.exitCode !== null) return;
  const exited = once(child, 'exit'); child.kill('SIGTERM');
  const timer = setTimeout(() => child.kill('SIGKILL'), 5000);
  await exited; clearTimeout(timer);
}

for (const kind of (process.env.EXAMPLE_KINDS || 'plain-java,spring-boot').split(',')) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'jetlinks-' + kind + '-'));
  const configFile = path.join(directory, 'example.local.json'), port = await freePort(), base = 'http://127.0.0.1:' + port;
  const fixture = await createFixture({ port: 0, exampleUrl: base, examplePort: port, configFile, directory });
  let child, passed = 0;
  async function launch() {
    const jar = `sdk-java/examples/${kind}/target/${kind}-2.0.0-SNAPSHOT.jar`;
    const args = kind === 'spring-boot' ? ['-Dexample.config=' + configFile, '-jar', jar] : ['-jar', jar, configFile];
    const log = createWriteStream(path.join(directory, 'service.log'), { flags: 'a' });
    child = spawn(java, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.pipe(log); child.stderr.pipe(log);
    child.on('error', error => { console.error(error); });
    for (let n = 0; n < 300; n++) {
      if (child.exitCode !== null) throw new Error('Service exited; see ' + directory);
      try { if ((await fetch(base + '/example-api/info')).ok) return; } catch { /* startup */ }
      await pause(100);
    }
    throw new Error('Service startup timeout; see ' + directory);
  }
  async function request(route, input, options = {}) {
    const response = await fetch(base + route, { ...(input !== undefined && { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) }), ...options });
    assert.ok(response.ok, `${route}: ${response.status} ${await response.clone().text()}`);
    return response.json();
  }
  const operation = (context, action, body) => request('/example-api/applications/' + context + '/' + action, body);
  const scenario = async (label, test) => { await test(); passed++; console.log(`PASS ${kind}: ${label}`); };
  try {
    await launch();
    await scenario('shared UI and public contract', async () => {
      assert.match(await (await fetch(base)).text(), /JetLinks SDK/);
      assert.equal((await request('/example-api/info')).language, 'java');
      const smoke = spawn(process.execPath, ['--test', 'examples/smoke/smoke.test.mjs'], { env: { ...process.env, EXAMPLE_URL: base }, stdio: ['ignore', 'ignore', 'pipe'] });
      let errors = ''; smoke.stderr.on('data', b => errors += b);
      assert.equal((await once(smoke, 'exit'))[0], 0, errors);
    });
    await scenario('API OAuth2/local, sync/reactive, coalesced refresh', async () => {
      const inputs = Array.from({ length: 20 }, () => ({ authMode: 'oauth2', path: '/fixture/echo' }));
      const values = await Promise.all(inputs.map(input => operation('api', 'requests', input)));
      assert.ok(values.every(v => v.result.auth === 'machine'));
      assert.equal(fixture.counters.machineTokens.api, 1);
      for (const authMode of ['local', 'oauth2']) for (const client of ['reactive', 'sync']) {
        const value = await operation('api', 'requests', { authMode, client, path: '/fixture/echo', method: 'POST', body: { message: '你好' } });
        assert.equal(value.result.applicationId, 'api-app'); assert.equal(value.result.body.message, '你好');
      }
      const diagnostics = await operation('api', 'diagnostics');
      assert.equal(diagnostics.tokenRefreshes, 1); assert.ok(diagnostics.tokenCacheHits >= 2);
    });
    await scenario('API notification channel isolation and persisted duplicate receipt', async () => {
      const eventId = 'shared-api-event';
      assert.equal((await fixture.notify('api', 'device.online', { eventId })).status, 204);
      assert.equal((await fixture.notify('api2', 'device.online', { eventId })).status, 204);
      assert.equal((await fixture.notify('api', 'device.online', { replay: eventId })).status, 204);
      for (const channelId of ['api', 'api2']) {
        const notices = await request('/example-api/notifications?channelId=' + channelId);
        assert.ok(notices.data.every(n => n.channelId === channelId));
        assert.ok(notices.data.some(n => n.accepted && n.applicationId === 'api-app'));
      }
      const repeated = await request('/example-api/notifications?channelId=api');
      assert.ok(repeated.data.some(n => n.duplicate));
      const filtered = await request('/example-api/notifications?channelId=api&type=device.online&eventId=shared-api-event&status=duplicate');
      assert.equal(filtered.total, 1); assert.equal(filtered.data[0].eventId, 'shared-api-event');
      assert.equal((await fixture.notify('api', 'device.online', { tamper: true })).status, 401);
    });
    let east, west;
    await scenario('same business application ID in two regions', async () => {
      assert.equal((await fixture.notify('east')).status, 204); assert.equal((await fixture.notify('west')).status, 204);
      const apps = await request('/example-api/applications');
      east = apps.find(a => a.platformId === 'east').contextId; west = apps.find(a => a.platformId === 'west').contextId;
      assert.equal(apps.filter(a => a.applicationId === 'same-app').length, 2);
      for (const [context, region] of [[east, 'east'], [west, 'west']]) for (const authMode of ['local', 'oauth2']) {
        const value = await operation(context, 'requests', { authMode, path: '/fixture/echo' }); assert.equal(value.result.region, region);
      }
      assert.ok(!JSON.stringify(apps).includes('secret'));
    });
    await scenario('subscription management and actual notification delivery', async () => {
      const body = { name: 'SDK fixture subscription', scopes: [{ eventTypes: ['device.online'], configuration: {} }], channelProvider: 'http-hook', channelConfiguration: { url: base + '/jetlinks/events/api' } };
      const subscription = (op, extra = {}) => operation('api', 'subscriptions', { operation: op, ...extra });
      const id = await subscription('create', { body }); assert.equal(typeof id, 'string');
      assert.equal((await subscription('get', { id })).state, 'disabled');
      assert.equal((await subscription('validate', { body })).valid, true);
      await subscription('update', { id, body: { ...body, name: 'Updated' } });
      await subscription('enable', { id }); assert.equal((await subscription('get', { id })).state, 'enabled');
      assert.equal((await fixture.notify('api', 'device.online')).status, 204);
      assert.equal((await subscription('tasks', { id })).data[0].state, 'success');
      assert.equal((await subscription('records', { id })).data[0].attempt, 1);
      assert.equal(typeof (await subscription('payload', { id, taskId: 'task-1', attempt: 1 })).rawBody, 'string');
      assert.ok(Array.isArray(await subscription('events'))); assert.ok(Array.isArray(await subscription('channels')));
      await subscription('disable', { id }); await subscription('delete', { id });
      assert.equal((await subscription('query')).total, 0);
      const forbidden = await fetch(base + '/example-api/applications/api/requests', { method: 'POST', body: JSON.stringify({ method: 'POST', path: '/application/api-app/subscriptions/_query', body: {} }) });
      assert.equal(forbidden.status, 403); // Preserve the platform permission rejection.
    });
    await scenario('PKCE login, platform entry, one-use state/code, local session', async () => {
      for (const [context, entry] of [['api', 'application'], [east, 'application'], [east, 'platform']]) {
        const response = await fetch(base + '/example-api/applications/' + context + '/login', { method: 'POST', body: JSON.stringify({ entry }) });
        assert.equal(response.status, 200); const cookie = response.headers.get('set-cookie').split(';')[0], login = await response.json();
        const callback = await fetch(login.url, { headers: { Cookie: cookie }, redirect: 'manual' });
        assert.equal(callback.status, 302);
        const sessionCookie = callback.headers.get('set-cookie').split(';')[0];
        const session = await request('/example-api/session', undefined, { headers: { Cookie: sessionCookie } });
        assert.equal(session.authenticated, true); assert.equal(session.contextId, context);
        assert.equal(session.context.subject.id, 'user-1');
        assert.equal((await fetch(login.url, { headers: { Cookie: cookie }, redirect: 'manual' })).status, 401);
        await request('/example-api/session', undefined, { method: 'DELETE', headers: { Cookie: sessionCookie } });
        assert.equal((await request('/example-api/session', undefined, { headers: { Cookie: sessionCookie } })).authenticated, false);
      }
      const start = await fetch(base + '/example-api/applications/' + east + '/login', { method: 'POST', body: '{}' });
      const cookie = start.headers.get('set-cookie').split(';')[0], url = new URL((await start.json()).url); url.searchParams.set('state', 'tampered');
      assert.equal((await fetch(url, { headers: { Cookie: cookie } })).status, 401);
    });
    await scenario('authorization facts pagination, synchronization, role queries', async () => {
      const snapshot = await operation(east, 'authorization', {});
      assert.equal(snapshot.members.length, 101); assert.ok(snapshot.roles.length > 0);
      assert.ok(fixture.counters.factPages >= 5);
      for (const op of ['roleAuthorization', 'roleMenus', 'assets']) assert.ok(Array.isArray(await operation(east, 'authorization', { operation: op, roleId: 'role-1' })));
    });
    await scenario('signed capabilities/custom command/NDJSON/errors/concurrency/cancel', async () => {
      const capabilities = await (await fixture.command('east', null)).json();
      assert.ok(capabilities[0].capabilities.includes('Echo'));
      const echo = await (await fixture.command('east', 'Echo', { message: '你好', applicationId: 'spoof' })).json();
      assert.equal(echo.message, '你好'); assert.equal(echo.applicationId, 'same-app');
      const unknown = await fixture.command('east', 'Missing'); assert.equal(unknown.status, 404); await unknown.text();
      const bad = await fixture.command('east', 'Echo', {}, { tamper: true }); assert.equal(bad.status, 401); await bad.text();
      const stream = await fixture.command('east', 'WatchUsage', { limit: 3 }, { stream: true });
      const lines = (await stream.text()).trim().split('\n').map(JSON.parse); assert.equal(lines.length, 3);
      const controllers = Array.from({ length: 8 }, () => new AbortController());
      const responses = await Promise.all(controllers.map(c => fixture.command('east', 'WatchUsage', { limit: 100 }, { stream: true, signal: c.signal })));
      assert.ok(responses.every(r => r.status === 200));
      const busy = await fixture.command('east', 'Echo'); assert.equal(busy.status, 429); await busy.text();
      await Promise.all(responses.map(r => r.body.cancel())); controllers.forEach(c => c.abort());
      await pause(250);
      assert.equal((await operation(east, 'diagnostics')).commandsInFlight, 0);
      const records = await operation(east, 'commands'); assert.ok(records.records.length > 0);
      assert.ok(records.records.every(r => r.platformId === 'east'));
      assert.equal((await operation(west, 'commands')).records.length, 0);
    });
    await scenario('HTTP stream cancellation propagates to platform', async () => {
      const response = await fetch(base + '/example-api/applications/api/requests', { method: 'POST', body: JSON.stringify({ path: '/fixture/stream', stream: true }) });
      const reader = response.body.getReader(); assert.ok(!(await reader.read()).done); await reader.cancel();
      await pause(250); assert.ok(fixture.counters.cancelledStreams >= 1);
    });
    let replay;
    await scenario('synced does not rotate; rotate/disable/delete/recreate/late events', async () => {
      const before = fixture.applications.get('east').revision;
      assert.equal((await fixture.notify('east', 'application.credential.synced')).status, 204);
      assert.equal(fixture.applications.get('east').revision, before);
      replay = (await fixture.notify('east', 'application.updated')).eventId;
      assert.equal((await fixture.notify('east', 'application.credential.rotated')).status, 204);
      assert.equal((await request('/example-api/applications/' + east)).keyRevision, 2);
      assert.equal((await operation(east, 'requests', { authMode: 'local', path: '/fixture/echo' })).result.region, 'east');
      await fixture.notify('east', 'application.disabled');
      assert.equal((await request('/example-api/applications/' + east)).enabled, false);
      assert.equal((await fetch(base + '/example-api/applications/' + east + '/requests', { method: 'POST', body: '{}' })).status, 400);
      await fixture.notify('east', 'application.enabled');
      await fixture.notify('east', 'application.deleted');
      await fixture.notify('east', 'application.updated', { replay });
      assert.equal((await request('/example-api/applications/' + east)).deleted, true);
      assert.equal((await fixture.notify('east', 'application.created', { recreate: true })).status, 204);
      const recreated = await request('/example-api/applications/' + east); assert.equal(recreated.deleted, false); assert.equal(recreated.keyRevision, 1);
      assert.equal((await operation(east, 'requests', { path: '/fixture/echo' })).result.region, 'east');
      assert.equal((await request('/example-api/applications/' + west)).keyRevision, 1);
    });
    await scenario('restart restores application credentials and inbox dedupe', async () => {
      await stop(child); await launch();
      assert.equal((await operation(east, 'requests', { authMode: 'local', path: '/fixture/echo' })).result.region, 'east');
      assert.equal((await fixture.notify('east', 'application.updated', { replay })).status, 204);
      const receipts = await request('/example-api/notifications?platformId=east');
      assert.ok(receipts.data.some(n => n.eventId === replay && n.duplicate));
      assert.ok(receipts.data.every(n => n.platformId === 'east'));
    });
    console.log(`${kind}: ${passed} end-to-end scenarios passed (logs: ${directory})`);
  } finally { await stop(child); await fixture.close(); }
}
