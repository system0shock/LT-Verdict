import type { ShellTabKey } from './labels'

export const SHELL_PANEL_ID = 'shell-panel'
export const SHELL_PREFERENCE_KEY = 'ltv.shell'

export type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

export function resolveNewShell(search: string, storage?: StorageLike): boolean {
  const shell = new URLSearchParams(search).get('shell')
  if (shell === 'old') {
    try { storage?.setItem(SHELL_PREFERENCE_KEY, 'old') } catch {}
    return false
  }
  if (shell === 'new') {
    try { storage?.removeItem(SHELL_PREFERENCE_KEY) } catch {}
    return true
  }
  try { return storage?.getItem(SHELL_PREFERENCE_KEY) !== 'old' } catch { return true }
}

export function browserStorage(): StorageLike | undefined {
  // The only browser storage use of the app: the explicit old-shell choice (?shell=old).
  // eslint-disable-next-line no-restricted-syntax
  try { return window.localStorage } catch { return undefined }
}

export function shellTabId(key: ShellTabKey): string {
  return `shell-tab-${key}`
}
