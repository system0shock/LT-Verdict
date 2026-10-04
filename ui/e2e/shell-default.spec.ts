import { expect, test, type Page } from '@playwright/test'
import { SHELL_LABELS } from '../src/shell/labels'
import { SHELL_PREFERENCE_KEY, resolveNewShell } from '../src/shell/shell'

// The shell choice: new by default, ?shell=old opts into the old interface and is remembered,
// ?shell=new forgets the choice. These tests start from an empty browser profile on purpose:
// the project config otherwise pre-selects the old shell for the older specs.
test.use({ storageState: { cookies: [], origins: [] } })

async function fixtureApi(page: Page) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/runs') body = { runs: [], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
}

const stored = (page: Page) => page.evaluate((key) => window.localStorage.getItem(key), SHELL_PREFERENCE_KEY)
const expectNew = async (page: Page) => {
  await expect(page.getByRole('tablist', { name: SHELL_LABELS.navLabel })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Application' })).toHaveCount(0)
}
const expectOld = async (page: Page) => {
  await expect(page.getByRole('navigation', { name: 'Application' })).toBeVisible()
  await expect(page.getByRole('tablist')).toHaveCount(0)
}

class FakeStorage {
  data = new Map<string, string>()
  getItem(key: string) { return this.data.get(key) ?? null }
  setItem(key: string, value: string) { this.data.set(key, value) }
  removeItem(key: string) { this.data.delete(key) }
}
const brokenStorage = {
  getItem: () => { throw new Error('blocked') },
  setItem: () => { throw new Error('blocked') },
  removeItem: () => { throw new Error('blocked') },
}

test.describe('resolveNewShell', () => {
  test('is new without a flag and without a stored choice', () => {
    expect(resolveNewShell('', new FakeStorage())).toBe(true)
    expect(resolveNewShell('?theme=old', new FakeStorage())).toBe(true)
  })

  test('shell=old selects the old shell and stores the choice; later visits keep it', () => {
    const storage = new FakeStorage()

    expect(resolveNewShell('?shell=old', storage)).toBe(false)
    expect(storage.getItem(SHELL_PREFERENCE_KEY)).toBe('old')
    expect(resolveNewShell('', storage)).toBe(false)
  })

  test('shell=new wins over a stored choice and forgets it', () => {
    const storage = new FakeStorage()
    storage.setItem(SHELL_PREFERENCE_KEY, 'old')

    expect(resolveNewShell('?shell=new', storage)).toBe(true)
    expect(storage.getItem(SHELL_PREFERENCE_KEY)).toBeNull()
    expect(resolveNewShell('', storage)).toBe(true)
  })

  test('the URL value wins over the stored choice and unknown values fall back to the stored choice', () => {
    const storage = new FakeStorage()
    storage.setItem(SHELL_PREFERENCE_KEY, 'old')

    expect(resolveNewShell('?shell=NEW', storage)).toBe(false)
    expect(resolveNewShell('?shell=', storage)).toBe(false)
    expect(storage.getItem(SHELL_PREFERENCE_KEY)).toBe('old')
    expect(resolveNewShell('?shell=new%20', storage)).toBe(false)
    expect(resolveNewShell('?shell=new%26shell=old', storage)).toBe(false)
    expect(storage.getItem(SHELL_PREFERENCE_KEY)).toBe('old')
  })

  test('with a repeated shell parameter the first one wins', () => {
    expect(resolveNewShell('?shell=old&shell=new', new FakeStorage())).toBe(false)
    expect(resolveNewShell('?shell=new&shell=old', new FakeStorage())).toBe(true)
  })

  test('a blocked or missing storage never throws and gives the default', () => {
    expect(resolveNewShell('', brokenStorage)).toBe(true)
    expect(resolveNewShell('?shell=old', brokenStorage)).toBe(false)
    expect(resolveNewShell('?shell=new', brokenStorage)).toBe(true)
    expect(resolveNewShell('', undefined)).toBe(true)
    expect(resolveNewShell('?shell=old', undefined)).toBe(false)
  })

  test('a stored value other than old is ignored', () => {
    const storage = new FakeStorage()
    storage.setItem(SHELL_PREFERENCE_KEY, 'whatever')

    expect(resolveNewShell('', storage)).toBe(true)
  })
})

test('opens the new shell by default', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/')

  await expectNew(page)
  expect(await stored(page)).toBeNull()
  await expect(page.getByRole('link', { name: SHELL_LABELS.legacyLink })).toHaveAttribute('href', /\?shell=old$/)
})

test('shell=old opens the old interface and is remembered across reloads and plain visits', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=old')
  await expectOld(page)
  expect(await stored(page)).toBe('old')

  await page.goto('/')
  await expectOld(page)
  await page.reload()
  await expectOld(page)
})

test('shell=new forgets the old-shell choice', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=old')
  await expectOld(page)

  await page.goto('/?shell=new')
  await expectNew(page)
  expect(await stored(page)).toBeNull()
  await page.goto('/')
  await expectNew(page)
})

test('the legacy link in the new shell switches to the old interface and stays there', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/')
  await page.getByRole('link', { name: SHELL_LABELS.legacyLink }).click()

  await expectOld(page)
  await page.goto('/')
  await expectOld(page)
})

test('without usable browser storage the default still works and ?shell=old applies to that visit', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(window, 'localStorage', { get() { throw new Error('blocked') } })
  })
  await fixtureApi(page)
  await page.goto('/')
  await expectNew(page)

  await page.goto('/?shell=old')
  await expectOld(page)
})
