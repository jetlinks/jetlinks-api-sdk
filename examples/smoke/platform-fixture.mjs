/** Independent, loopback-only JetLinks protocol fixture. No SDK code is used here. */
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const alphabet = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
const hmac = (key, bytes) => crypto.createHmac('sha256', key).update(bytes).digest();
export const sign = (secret, values) => hmac(secret, Buffer.concat(values.map(value => {
  const bytes = Buffer.from(String(value)), length = Buffer.alloc(4);
  length.writeUInt32BE(bytes.length);
  return Buffer.concat([length, bytes]);
}))).toString('base64url');
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const check = (condition, message) => { if (!condition) throw Object.assign(new Error(message), { status: 401 }); };
const tokenId = () => crypto.randomBytes(24).toString('base64url');

// Decode the platform's binary at_ format, including the distinction between null and [] scopes.
function localToken(token, application) {
  check(token.startsWith('at_') && token.length <= 4096, 'Invalid local token');
  let number = 0n;
  for (const char of token.slice(3)) {
    const digit = alphabet.indexOf(char);
    check(digit >= 0, 'Invalid Base62');
    number = number * 62n + BigInt(digit);
  }
  let hex = number.toString(16);
  if (hex.length % 2) hex = '0' + hex;
  const bytes = Buffer.from(hex, 'hex');
  check(bytes.length > 30 && bytes[0] === 11 && bytes[9] === 1 && bytes[10] === 2, 'Invalid token purpose');
  check(Math.abs(Date.now() - Number(bytes.readBigInt64LE(1))) < 120000, 'Expired local token');
  let offset = 11;
  function string() {
    const size = bytes.readUInt16BE(offset); offset += 2;
    const value = bytes.subarray(offset, offset + size).toString(); offset += size;
    return value;
  }
  check(string() === application.key, 'Token key mismatch');
  const explicit = bytes[offset++], count = bytes.readUInt16BE(offset); offset += 2;
  check(explicit === 0 || explicit === 1, 'Invalid scope flag');
  const scopes = Array.from({ length: count }, string);
  check(string() === '' && offset + 16 === bytes.length, 'Invalid token length/binding');
  const derived = hmac(application.secret, 'application-access/self-signed/v1');
  check(crypto.timingSafeEqual(hmac(derived, bytes.subarray(0, offset)).subarray(0, 16), bytes.subarray(offset)), 'Token signature mismatch');
  return { kind: 'local', scopes: explicit ? scopes : null };
}

export async function createFixture({ port = 19090, exampleUrl = 'http://127.0.0.1:8090', directory = 'data/local-fixture', configFile = 'example.local.json', examplePort = 8090 } = {}) {
  await fs.mkdir(directory, { recursive: true });
  const keys = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const privateFile = path.resolve(directory, 'receiver.pem');
  await fs.writeFile(privateFile, keys.privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
  const applications = new Map([
    ['api', { region: 'api', id: 'api-app', key: 'api-key', secret: 'api-secret-fixture-only', revision: 1, enabled: true }],
    ['api2', { region: 'api2', id: 'api-app', key: 'api-key', secret: 'api-secret-fixture-only', revision: 1, enabled: true }],
    ...['east', 'west'].map(region => [region, { region, id: 'same-app', key: region + '-key-1', secret: region + '-secret-fixture-only-1', revision: 1, enabled: true, createTime: Date.now() }]),
  ]);
  const tokens = new Map(), codes = new Map(), subscriptions = new Map(), notices = new Map();
  const counters = { machineTokens: {}, localRequests: 0, requests: 0, cancelledStreams: 0, factPages: 0 };
  let clock = Date.now(), server;
  const origin = () => 'http://127.0.0.1:' + server.address().port;
  const base = region => origin() + '/gateway/' + region;
  const json = (res, value, status = 200, raw = false) => {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(raw ? value : { status, result: value }));
  };
  function authorize(req, app) {
    check(app.enabled, 'Application disabled');
    const bearer = (req.headers.authorization || '').replace(/^Bearer /, '');
    if (bearer === 'fixture-admin') return { kind: 'management', application: app.id };
    if (bearer === 'fixture-user') return { kind: 'platform-user', application: app.id };
    if (bearer.startsWith('at_')) { counters.localRequests++; return { ...localToken(bearer, app), application: app.id }; }
    const entry = tokens.get(bearer);
    check(entry && entry.region === app.region && entry.revision === app.revision, 'Unknown or obsolete token');
    return entry;
  }
  function issueCode(app, redirect, state, challenge, platform) {
    const code = tokenId();
    codes.set(code, { region: app.region, redirect, challenge });
    const url = new URL(redirect);
    url.searchParams.set('code', code);
    if (state) url.searchParams.set('state', state);
    if (platform) {
      url.searchParams.set('platformId', app.region); url.searchParams.set('applicationId', app.id);
    }
    return url.toString();
  }
  function page(data, body) {
    const query = body.length ? JSON.parse(body) : {}, size = query.pageSize || 100, index = query.pageIndex || 0;
    return { total: data.length, pageIndex: index, pageSize: size, data: data.slice(index * size, (index + 1) * size) };
  }

  async function notify(region, type = 'application.created', options = {}) {
    const app = applications.get(region);
    if (type === 'application.credential.rotated') {
      app.revision++; app.key = `${region}-key-${app.revision}`; app.secret = `${region}-secret-fixture-only-${app.revision}`;
    }
    if (type === 'application.created' || type === 'application.enabled') app.enabled = true;
    if (type === 'application.disabled' || type === 'application.deleted') app.enabled = false;
    if (options.recreate) { app.revision = 1; app.key = region + '-reborn'; app.secret = region + '-secret-reborn'; }
    const api = region.startsWith('api'), id = options.eventId || tokenId();
    let event = notices.get(options.replay);
    if (!event) {
      const occurredAt = options.occurredAt || (clock = Math.max(Date.now(), clock + 1));
      if (options.recreate) app.createTime = occurredAt;
      event = { id, type, occurredAt, subject: 'unrelated-device-id', data: api ? { message: '你好', application: { id: 'payload-is-not-identity' } } : {
        application: { id: app.id, templateId: 'template', createTime: app.createTime, apiBaseUrl: options.apiBaseUrl || base(region), state: app.enabled ? 'enabled' : 'disabled' },
        payload: {},
      } };
      if (!api && ['application.created', 'application.credential.rotated', 'application.credential.synced'].includes(type)) {
        event.data.payload.credential = { accessKey: app.key, keyRevision: app.revision, keyId: 'receiver', encryptedAccessSecret: crypto.publicEncrypt({ key: keys.publicKey, oaepHash: 'sha256', padding: crypto.constants.RSA_PKCS1_OAEP_PADDING }, Buffer.from(app.secret)).toString('base64') };
      }
      notices.set(id, event);
    }
    const raw = Buffer.from(JSON.stringify(event)), timestamp = String(Date.now()), keyId = api ? app.key : 'notify';
    const headers = { 'Content-Type': 'application/json', 'X-Signature-Version': '1', 'X-Application-Id': app.id, 'X-Key-Id': keyId, 'X-Timestamp': timestamp, 'X-Event-Id': event.id };
    if (!api) headers['X-Platform-Id'] = region;
    headers['X-Signature'] = sign(api ? app.secret : 'notification-secret-fixture-only', ['1', app.id, api ? '' : region, keyId, timestamp, event.id, sha(raw)]);
    if (options.tamper) headers['X-Signature'] = 'tampered';
    const response = await fetch(exampleUrl + (api ? '/jetlinks/events/' + region : '/jetlinks/events'), { method: 'POST', headers, body: raw });
    return { eventId: event.id, status: response.status, body: await response.text() };
  }

  async function command(region, commandId, input = {}, options = {}) {
    const app = applications.get(region), target = commandId === null ? '/api/capabilities' : '/api/commands/1/' + commandId;
    const method = commandId === null ? 'GET' : 'POST', requestId = crypto.randomBytes(16).toString('hex'), timestamp = String(Date.now());
    const contentType = commandId === null ? '' : 'application/json', accept = options.stream ? 'application/x-ndjson' : 'application/json';
    const credential = { key: app.key, secret: app.secret };
    const signature = sign(credential.secret, ['http-command', '1', 'request', app.id, region, credential.key, timestamp, requestId, method, target, contentType, accept]);
    const response = await fetch(exampleUrl + target, { method, headers: { ...(contentType && { 'Content-Type': contentType }), Accept: accept, 'X-Signature-Version': '1', 'X-Platform-Id': region, 'X-Application-Id': app.id, 'X-Key-Id': credential.key, 'X-Timestamp': timestamp, 'X-Request-Id': requestId, 'X-Signature': options.tamper ? 'bad' : signature }, ...(commandId !== null && { body: options.rawBody ?? JSON.stringify(input) }), signal: options.signal });
    if (!options.tamper) {
      for (const [name, value] of Object.entries({ 'x-signature-version': '1', 'x-platform-id': region, 'x-application-id': app.id, 'x-key-id': credential.key, 'x-request-id': requestId })) check(response.headers.get(name) === value, 'Response identity mismatch: ' + name);
      const expected = sign(credential.secret, ['http-command', '1', 'response', app.id, region, credential.key, response.headers.get('x-timestamp'), requestId, signature, String(response.status), response.headers.get('content-type')]);
      check(response.headers.get('x-signature') === expected, 'Command response signature mismatch');
    }
    return response;
  }

  server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://localhost');
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const raw = Buffer.concat(chunks), body = raw.toString();
      if (url.pathname === '/__fixture/notify') { const n = JSON.parse(body); return json(res, await notify(n.region, n.type, n), 200, true); }
      if (url.pathname === '/__fixture/counters') return json(res, counters, 200, true);
      if (url.pathname === '/__fixture/command') { const n = JSON.parse(body), r = await command(n.region, n.commandId, n.input, n); return json(res, { status: r.status, body: await r.text() }, 200, true); }
      const match = url.pathname.match(/^\/gateway\/([^/]+)(\/.*)$/);
      check(match && applications.has(match[1]), 'Unknown platform route');
      const app = applications.get(match[1]), route = match[2];
      if (route === '/oauth2/token') {
        const form = new URLSearchParams(body), basic = req.headers.authorization?.startsWith('Basic ');
        check(!(basic && form.has('client_id')), 'Mixed OAuth client authentication');
        const [key, secret] = basic ? Buffer.from(req.headers.authorization.slice(6), 'base64').toString().split(':') : [form.get('client_id'), form.get('client_secret')];
        check(app.enabled && key === app.key && secret === app.secret, 'Invalid client credentials');
        const kind = form.get('grant_type') === 'authorization_code' ? 'user' : 'machine';
        check(kind === 'user' || form.get('grant_type') === 'client_credentials', 'Unsupported grant');
        if (kind === 'user') {
          const code = codes.get(form.get('code')); codes.delete(form.get('code'));
          check(code && code.region === app.region && code.redirect === form.get('redirect_uri'), 'Invalid code/redirect');
          if (code.challenge) check(crypto.createHash('sha256').update(form.get('code_verifier') || '').digest('base64url') === code.challenge, 'PKCE rejected');
        } else counters.machineTokens[app.region] = (counters.machineTokens[app.region] || 0) + 1;
        const token = tokenId(); tokens.set(token, { region: app.region, revision: app.revision, application: app.id, kind });
        return json(res, { access_token: token, token_type: 'Bearer', expires_in: 120 }, 200, true);
      }
      const identity = authorize(req, app); counters.requests++;
      if (route === '/oauth2/authorize' || route.endsWith('/_redirect')) {
        check(identity.kind === 'platform-user', 'Platform user required');
        const platform = route.endsWith('/_redirect'), redirect = platform ? exampleUrl + '/example-api/login/callback' : url.searchParams.get('redirect_uri');
        if (!platform) check(url.searchParams.get('client_id') === app.key && url.searchParams.get('code_challenge_method') === 'S256', 'Invalid authorization request');
        const location = issueCode(app, redirect, platform ? null : url.searchParams.get('state'), platform ? null : url.searchParams.get('code_challenge'), platform);
        return json(res, platform ? { location } : location);
      }
      if (route === '/authorize/context') return json(res, { application: { id: app.id }, subject: { id: identity.kind === 'user' ? 'user-1' : app.id, type: identity.kind === 'user' ? 'user' : 'application' }, region: app.region });
      if (route === '/fixture/echo') return json(res, { applicationId: app.id, region: app.region, auth: identity.kind, scopes: identity.scopes ?? null, body: body ? JSON.parse(body) : null });
      if (route === '/fixture/stream') {
        res.writeHead(200, { 'Content-Type': 'application/x-ndjson' }); let i = 0;
        const timer = setInterval(() => res.write(JSON.stringify({ index: i++, message: '你好' }) + '\n'), 50);
        res.on('close', () => { clearInterval(timer); counters.cancelledStreams++; }); return;
      }
      if (route.includes('/subscriptions')) {
        if (identity.kind !== 'management') throw Object.assign(new Error('Subscription management permission required'), { status: 403 });
        const tail = route.split('/subscriptions')[1], data = body ? JSON.parse(body) : {};
        if (tail === '/events') return json(res, [{ id: 'fixture', name: 'Fixture events', events: [{ id: 'device.online', name: 'Online', version: '1', schema: {} }] }]);
        if (tail === '/channels') return json(res, [{ id: 'http-hook' }]);
        if (tail === '/_validate') return json(res, { valid: data.channelProvider === 'http-hook', issues: [] });
        if (tail === '/_query') return json(res, page([...subscriptions.values()], raw));
        if (!tail && req.method === 'POST') { const id = tokenId(); subscriptions.set(id, { ...data, id, applicationId: app.id, state: 'disabled' }); return json(res, id); }
        const [id, operation] = tail.slice(1).split('/'), subscription = subscriptions.get(id);
        if (!subscription) return json(res, { message: 'Unknown subscription' }, 404, true);
        if (tail.endsWith('/payload')) return json(res, { body: { id: 'delivery-1' }, rawBody: '{"id":"delivery-1"}', contentType: 'application/json' });
        if (operation === 'tasks') return json(res, page([{ id: 'task-1', subscriptionId: id, state: 'success' }], raw));
        if (operation === 'records') return json(res, page([{ taskId: 'task-1', attempt: 1, state: 'success' }], raw));
        if (operation === '_enable' || operation === '_disable') { subscription.state = operation === '_enable' ? 'enabled' : 'disabled'; return json(res, 1); }
        if (req.method === 'DELETE') { subscriptions.delete(id); return json(res, 1); }
        if (req.method === 'PUT') { subscriptions.set(id, { ...subscription, ...data }); return json(res, 1); }
        return json(res, subscription);
      }
      if (route.endsWith('/credential/_sync')) { await notify(app.region, 'application.credential.synced'); return json(res, true); }
      if (route.endsWith('/asset-accesses')) return json(res, [{ assetType: 'device', policy: 'read', region: app.region }]);
      if (route.endsWith('/_query')) {
        counters.factPages++;
        const data = route.startsWith('/user/detail/') ? Array.from({ length: 101 }, (_, i) => ({ id: 'user-' + i, region: app.region })) : route === '/role/_query' ? [{ id: 'role-1', applicationId: app.id, region: app.region }] : [{ id: 'fact-1', userId: 'user-1', roleId: 'role-1', region: app.region }];
        return json(res, page(data, raw));
      }
      json(res, { message: 'Unsupported fixture route' }, 404, true);
    } catch (error) { json(res, { message: error.message }, error.status || 500, true); }
  });
  await new Promise(resolve => server.listen(port, '127.0.0.1', resolve));
  const config = { host: '127.0.0.1', port: examplePort, publicBaseUrl: exampleUrl, database: path.relative(process.cwd(), path.resolve(directory, 'example-db')), uiDir: path.resolve('examples/shared-ui/dist'), apiApplications: ['api', 'api2'].map(region => { const a = applications.get(region); return { contextId: region, applicationId: a.id, apiBaseUrl: base(region), accessKey: a.key, accessSecret: a.secret, managementToken: 'fixture-admin', userToken: 'fixture-user' }; }), templates: ['east', 'west'].map(platformId => ({ platformId, templateId: 'template', notificationKeyId: 'notify', notificationSecret: 'notification-secret-fixture-only', receiverKeyId: 'receiver', privateKeyFile: privateFile, userToken: 'fixture-user' })) };
  await fs.writeFile(configFile, JSON.stringify(config, null, 2), { mode: 0o600 });
  return { origin: origin(), config, applications, counters, notify, command, close: () => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }) };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const fixture = await createFixture({ exampleUrl: process.env.EXAMPLE_URL || 'http://127.0.0.1:8090', examplePort: Number(process.env.EXAMPLE_PORT || 8090), port: Number(process.env.FIXTURE_PORT || 19090) });
  console.log(`Local fixture: ${fixture.origin}; synthetic configuration: example.local.json`);
  process.on('SIGINT', async () => { await fixture.close(); process.exit(0); });
}
