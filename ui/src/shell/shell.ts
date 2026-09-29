import type { ShellTabKey } from './labels'

export const SHELL_PANEL_ID = 'shell-panel'

export function isNewShell(search: string): boolean {
  return new URLSearchParams(search).get('shell') === 'new'
}

export function shellTabId(key: ShellTabKey): string {
  return `shell-tab-${key}`
}
