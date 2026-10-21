import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import test from 'node:test'
import ts from 'typescript'
import { computed, effectScope, ref, watch } from 'vue'
import { createRequestScope } from '../src/requestScope.ts'

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

// 执行实际组件的 setup，控制传输完成顺序；无 DOM 的响应式状态使用真实 Vue。
function component(t, request, stream = async () => {}) {
  const source = fs.readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
    .match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
  const code = ts.transpileModule(source + '\nglobalThis.appState={apps,selected,result,diagnostics,busy,error,stream,run,invoke,refreshDiagnostics,loadReceipt,receipt,login};', {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText
  const unmount = [], redirects = [], scope = effectScope()
  const context = vm.createContext({
    computed, ref, watch, createRequestScope, api: request, ndjson: stream,
    onMounted() {}, onUnmounted(callback) { unmount.push(callback) },
    window: { location: { assign(url) { redirects.push(url) } } },
    AbortController, URLSearchParams,
  })
  scope.run(() => vm.runInContext(code, context))
  t.after(() => { unmount.forEach(callback => callback()); scope.stop() })
  const state = context.appState
  state.apps.value = [{ contextId: 'A' }, { contextId: 'B' }]
  state.selected.value = 'A'
  return { state, redirects }
}

test('old application results cannot overwrite the new application or clear its busy state', async t => {
  const calls = []
  const { state } = component(t, (path, _method, _body, signal) => {
    if (path.endsWith('/diagnostics')) return Promise.resolve({ application: path.split('/')[2] })
    const response = deferred()
    calls.push({ ...response, signal })
    return response.promise
  })
  await Promise.resolve()
  const old = state.run(state.invoke)
  state.selected.value = 'B'
  const current = state.run(state.invoke)
  assert.equal(calls[0].signal.aborted, true)
  calls[0].resolve({ application: 'A' })
  await old
  assert.equal(state.result.value, undefined)
  assert.equal(state.busy.value, true)
  calls[1].resolve({ application: 'B' })
  await current
  assert.equal(state.result.value.application, 'B')
  assert.equal(state.diagnostics.value.application, 'B')
  assert.equal(state.busy.value, false)
})

test('late diagnostics errors from a previous application stay out of the current view', async t => {
  const response = deferred()
  const { state } = component(t, () => response.promise)
  await Promise.resolve()
  const old = state.run(state.refreshDiagnostics)
  state.selected.value = 'B'
  response.reject(new Error('A transport failed'))
  await old
  assert.equal(state.error.value, '')
  assert.equal(state.diagnostics.value, undefined)
})

test('selecting another receipt preserves the latest detail on reverse completion', async t => {
  const responses = [deferred(), deferred()]
  let index = 0
  const { state } = component(t, () => responses[index++].promise)
  await Promise.resolve()
  const old = state.run(() => state.loadReceipt({ recordId: 'old' }))
  const current = state.run(() => state.loadReceipt({ recordId: 'current' }))
  responses[1].resolve({ recordId: 'current' })
  await current
  responses[0].resolve({ recordId: 'old' })
  await old
  assert.equal(state.receipt.value.recordId, 'current')
})

test('switching applications ignores late stream chunks and login redirects', async t => {
  const stream = deferred(), redirect = deferred()
  let emit, signal
  const { state, redirects } = component(t, () => redirect.promise, async (_path, _body, receive, abort) => {
    emit = receive; signal = abort
    await stream.promise
  })
  await Promise.resolve()
  state.stream.value = true
  const request = state.run(state.invoke)
  const login = state.run(() => state.login('third-party'))
  state.selected.value = 'B'
  assert.equal(signal.aborted, true)
  emit({ application: 'A' })
  stream.resolve()
  redirect.resolve({ url: 'https://old.example/callback' })
  await Promise.all([request, login])
  assert.equal(state.result.value, undefined)
  assert.deepEqual(redirects, [])
})
