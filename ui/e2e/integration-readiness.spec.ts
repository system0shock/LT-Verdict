import { expect, test } from '@playwright/test'

test('Grafana render failure keeps a safe source link and the local verdict', async ({ page }) => {
  let renders = 0
  await page.route('**/api/grafana', route => route.fulfill({ json: { profiles: [{ id: 'grafana', base_url: 'https://grafana.example/' }] } }))
  await page.route('**/grafana-link?*', route => route.fulfill({ json: { source_link: 'https://grafana.example/d-solo/demo?panelId=1' } }))
  await page.route('**/grafana-render?*', async route => {
    renders++
    expect(route.request().method()).toBe('POST')
    await route.fulfill({ json: { source_link: 'https://grafana.example/d-solo/demo?panelId=1', png_base64: null, failure_code: 'GRAFANA_RENDER_UNAVAILABLE' } })
  })
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'grafana-fixture.jtl', mimeType: 'text/csv', buffer: Buffer.from('timeStamp,elapsed,label,success\n1767225600000,100,grafana-test,true\n') })
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
  await page.getByText('Grafana evidence', { exact: true }).click()
  await page.getByLabel('Dashboard UID', { exact: true }).fill('demo')
  await page.getByRole('button', { name: 'Prepare link', exact: true }).click()
  await expect(page.getByRole('link', { name: 'Open source panel' })).toHaveAttribute('rel', 'noopener noreferrer')
  expect(renders).toBe(0)
  await page.getByRole('button', { name: 'Render PNG', exact: true }).click()
  await expect(page.getByText(/Render unavailable: GRAFANA_RENDER_UNAVAILABLE/)).toBeVisible()
  await expect(page.getByRole('link', { name: 'Open source panel' })).toBeVisible()
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
  expect(renders).toBe(1)
})

test('an unknown Jenkins trigger can only reconcile and never posts another trigger', async ({ page }) => {
  const unknown = {
    attempt_id: 'attempt-1', status: 'TRIGGER_UNKNOWN', build_number: null,
    failure_code: 'JENKINS_TRIGGER_NOT_FOUND', artifact: null,
  }
  let triggerPosts = 0
  let reconcilePosts = 0
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'integration-test', max_upload_bytes: 1_000_000 }
    else if (path === '/api/runs') body = { runs: [], next_after: null }
    else if (path === '/api/sources') body = { profiles: [], postgres_profiles: [] }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path === '/api/grafana') body = { profiles: [] }
    else if (path === '/api/jenkins') body = { profiles: [{
      id: 'perf', controller: 'https://jenkins.example/', job_path: 'job/perf',
      parameter_names: ['SCENARIO'], artifact_paths: ['run/results.jtl'],
    }] }
    else if (path === '/api/jenkins/perf/attempts' && request.method() === 'GET') body = { attempts: [unknown] }
    else if (path.endsWith('/trigger')) {
      triggerPosts++
      body = unknown
    } else if (path.endsWith('/attempt-1/reconcile')) {
      reconcilePosts++
      body = { attempt: unknown, run: null }
    } else throw new Error(`Unexpected UI request ${request.method()} ${path}`)
    await route.fulfill({ json: body })
  })

  await page.goto('/')
  await expect(page.getByText('Исход запуска неизвестен.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Запустить Jenkins job' })).toBeDisabled()
  await page.getByRole('button', { name: 'Сверить запуск с Jenkins' }).click()

  await expect.poll(() => reconcilePosts).toBe(1)
  expect(triggerPosts).toBe(0)
})
