/**
 * 用真实鼠标移动（page.mouse.move）验证 hover：走浏览器命中测试，
 * 与用户实际操作的路径一致。
 */
const puppeteer = require('puppeteer-core')

const BASE = 'http://localhost:8080'

async function main() {
  const browser = await puppeteer.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 900 })

  await page.goto(`${BASE}/console/login`, { waitUntil: 'domcontentloaded' })
  await page.evaluate(async () => {
    const res = await fetch('/api/console/v1/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin', password: 'admin' }),
    })
    if (!res.ok) return
    const body = await res.json()
    localStorage.setItem('lakehouse.console.token', body.accessToken)
  })

  await page.goto(`${BASE}/console/catalogs/paimon`, { waitUntil: 'networkidle2' })
  await new Promise((r) => setTimeout(r, 1000))

  const popperVisible = () =>
    page.evaluate(() =>
      [...document.querySelectorAll('.pc-field-tip-popper')].some((el) => {
        const style = getComputedStyle(el)
        const rect = el.getBoundingClientRect()
        return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0
      }),
    )

  const boxes = await page.evaluate(() =>
    [...document.querySelectorAll('.pc-tip-icon, .pc-field-label-icon')].map((el) => {
      const r = el.getBoundingClientRect()
      return {
        cls: el.className.toString().slice(0, 50),
        parent: (el.parentElement?.className || '').toString().slice(0, 50),
        x: r.left + r.width / 2,
        y: r.top + r.height / 2,
        w: Math.round(r.width),
        h: Math.round(r.height),
      }
    }),
  )

  const probes = []
  for (const box of boxes) {
    // 命中测试：这个坐标上最顶层的元素是什么
    const hit = await page.evaluate(
      ({ x, y }) => {
        const el = document.elementFromPoint(x, y)
        if (!el) return null
        const chain = []
        let cur = el
        while (cur && chain.length < 4) {
          chain.push(`${cur.tagName.toLowerCase()}.${(cur.className || '').toString().split(' ')[0]}`)
          cur = cur.parentElement
        }
        return chain.join(' < ')
      },
      { x: box.x, y: box.y },
    )

    await page.mouse.move(0, 0)
    await new Promise((r) => setTimeout(r, 200))
    await page.mouse.move(box.x, box.y, { steps: 4 })
    await new Promise((r) => setTimeout(r, 700))
    const hover = await popperVisible()

    await page.mouse.move(0, 0)
    await new Promise((r) => setTimeout(r, 250))

    probes.push({ ...box, x: Math.round(box.x), y: Math.round(box.y), hit, hover })
  }

  console.log(JSON.stringify({ probes }, null, 2))
  await browser.close()
}

main().catch((error) => {
  console.log(JSON.stringify({ error: String(error && error.message ? error.message : error) }))
  process.exit(1)
})
