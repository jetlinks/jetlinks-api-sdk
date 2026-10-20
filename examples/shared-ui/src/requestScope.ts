export interface RequestTask {
  signal: AbortSignal
  current(): boolean
  commit(update: () => void): void
}

/** 同一操作只接收最新结果；应用切换后，即使传输忽略取消也不能回写旧状态。 */
export function createRequestScope() {
  const active = new Map<string, AbortController>()

  function cancel(key?: string) {
    if (key !== undefined) {
      active.get(key)?.abort()
      active.delete(key)
      return
    }
    active.forEach(controller => controller.abort())
    active.clear()
  }

  async function run(key: string, work: (task: RequestTask) => Promise<void>) {
    cancel(key)
    const controller = new AbortController()
    active.set(key, controller)
    const current = () => active.get(key) === controller && !controller.signal.aborted
    const task = {
      signal: controller.signal,
      current,
      commit(update: () => void) { if (current()) update() },
    }
    try {
      await work(task)
    } catch (error) {
      if (current()) throw error
    } finally {
      if (active.get(key) === controller) active.delete(key)
    }
  }

  return { run, cancel }
}
