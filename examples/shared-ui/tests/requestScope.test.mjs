import assert from 'node:assert/strict'
import test from 'node:test'
import { createRequestScope } from '../src/requestScope.ts'
import { api } from '../src/api.ts'

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

test('replacing a request rejects late commits even when cancellation is ignored', async () => {
  const scope = createRequestScope(), old = deferred(), current = deferred()
  let value, oldSignal
  const first = scope.run('result', async task => {
    oldSignal = task.signal
    const response = await old.promise
    task.commit(() => { value = response })
  })
  const second = scope.run('result', async task => {
    const response = await current.promise
    task.commit(() => { value = response })
  })
  assert.equal(oldSignal.aborted, true)
  old.resolve('old')
  await first
  current.resolve('current')
  await second
  assert.equal(value, 'current')
})

test('switching contexts cancels all channels and suppresses obsolete errors', async () => {
  const scope = createRequestScope(), response = deferred()
  let committed = false, signal
  const request = scope.run('authorization', async task => {
    signal = task.signal
    await response.promise
    task.commit(() => { committed = true })
  })
  scope.cancel()
  assert.equal(signal.aborted, true)
  response.reject(new Error('obsolete transport failure'))
  await request
  assert.equal(committed, false)
  await assert.rejects(scope.run('authorization', async () => { throw new Error('current failure') }), /current failure/)
})

test('normal API requests forward their cancellation signal to fetch', async t => {
  const controller = new AbortController()
  t.mock.method(globalThis, 'fetch', async (_url, options) => {
    assert.equal(options.signal, controller.signal)
    return new Response('{"ok":true}', { headers: { 'Content-Type': 'application/json' } })
  })
  assert.deepEqual(await api('/test', 'POST', {}, controller.signal), { ok: true })
})
