import { expect, test } from '@playwright/test'

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
    else if (request.method() === 'GET' && path === '/api/jobs') body = { jobs: [] }
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
