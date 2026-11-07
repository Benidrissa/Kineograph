#!/usr/bin/env node
// Renders every SVG test fixture in a real Chromium at chosen moments, and saves what it drew as the reference pictures
// that SvgBrowserComparisonTest compares our renderer with. See README.md.
//
// Node's standard library is all this needs. Chromium's debugging protocol can run over a pipe, which avoids a WebSocket client.
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'

const here = dirname(fileURLToPath(import.meta.url))
const repo = resolve(here, '../..')

const { values: args, positionals: filters } = parseArgs({
  allowPositionals: true,
  options: {
    chrome: { type: 'string', default: process.env.SVG_REFERENCE_CHROME ?? '' },
    profile: { type: 'string', default: process.env.SVG_REFERENCE_PROFILE ?? join(tmpdir(), 'kineograph-svg-reference-profile') },
    fonts: { type: 'string', default: process.env.SVG_REFERENCE_FONTS ?? '' },
    fixtures: { type: 'string', default: join(repo, 'core/media/src/test/resources/svg') },
    size: { type: 'string', default: '200' },
  },
})

function findChrome() {
  if (args.chrome) return args.chrome
  const root = join(homedir(), '.cache/ms-playwright')
  if (existsSync(root)) {
    for (const name of readdirSync(root).sort().reverse()) {
      const candidate = join(root, name, 'chrome-linux64/chrome')
      if (name.startsWith('chromium-') && existsSync(candidate)) return candidate
    }
  }
  throw new Error('Chromium not found. Pass --chrome=/path/to/chrome.')
}

// Android's generic font names must mean the same fonts here as under Robolectric, or every text fixture would differ only because
// the letters are different ones. prepare_fonts.py makes the folder this reads.
function findFonts() {
  const folder = args.fonts || join(args.profile + '-fonts')
  if (existsSync(join(folder, 'Roboto-Regular.ttf'))) return folder
  const jars = join(homedir(), '.gradle/caches/modules-2/files-2.1/org.robolectric/nativeruntime-dist-compat')
  throw new Error(`Text needs Robolectric's fonts. Run prepare_fonts.py on the jar under ${jars}, with ${folder} as its output, or pass --fonts=DIR.`)
}

function fontConfig(fontDir, cacheDir) {
  // A browser asks for Arial or Times when it means sans-serif or serif, so those names have to lead to the same fonts too.
  const families = {
    'KG Sans': ['sans-serif', 'sans', 'arial', 'helvetica', 'verdana', 'roboto'],
    'KG Serif': ['serif', 'times new roman', 'times', 'georgia'],
    'KG Mono': ['monospace', 'courier new', 'courier'],
    'KG Cursive': ['cursive', 'comic sans ms'],
  }
  const aliases = Object.entries(families)
    .flatMap(([family, names]) => names.map((name) => `<match target="pattern"><test name="family" qual="any" ignore-blanks="true"><string>${name}</string></test>
      <edit name="family" mode="assign" binding="same"><string>${family}</string></edit></match>`))
    .join('\n')
  mkdirSync(cacheDir, { recursive: true })
  const path = join(cacheDir, 'fonts.conf')
  writeFileSync(
    path,
    `<?xml version="1.0"?><!DOCTYPE fontconfig SYSTEM "fonts.dtd"><fontconfig>
    <dir>${fontDir}</dir><cachedir>${join(cacheDir, 'fontcache')}</cachedir>${aliases}
    <match target="font"><edit name="antialias" mode="assign"><bool>true</bool></edit>
      <edit name="hinting" mode="assign"><bool>false</bool></edit>
      <edit name="autohint" mode="assign"><bool>false</bool></edit>
      <edit name="rgba" mode="assign"><const>none</const></edit></match>
    </fontconfig>`,
  )
  return path
}

class Browser {
  constructor(chrome, profile, fontsConf, size) {
    this.next = 1
    this.pending = new Map()
    this.buffer = Buffer.alloc(0)
    mkdirSync(profile, { recursive: true })
    this.process = spawn(
      chrome,
      [
        '--headless=new',
        '--no-sandbox',
        '--disable-gpu',
        '--force-device-scale-factor=1',
        // The same antialiased, unhinted letters Android draws for an outline, placed at fractions of a pixel.
        '--font-render-hinting=none',
        '--disable-lcd-text',
        '--enable-font-subpixel-positioning',
        // Off the main thread, a running animation could still reach the picture after it has been paused.
        '--disable-threaded-animation',
        '--hide-scrollbars',
        '--mute-audio',
        '--no-first-run',
        '--disable-background-networking',
        '--disable-extensions',
        `--user-data-dir=${profile}`,
        `--window-size=${size},${size}`,
        '--remote-debugging-pipe',
        'about:blank',
      ],
      { stdio: ['ignore', 'ignore', 'ignore', 'pipe', 'pipe'], env: { ...process.env, FONTCONFIG_FILE: fontsConf } },
    )
    this.process.stdio[4].on('data', (chunk) => this.receive(chunk))
  }

  receive(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk])
    let end
    while ((end = this.buffer.indexOf(0)) >= 0) {
      const message = JSON.parse(this.buffer.subarray(0, end).toString('utf8'))
      this.buffer = this.buffer.subarray(end + 1)
      const waiter = this.pending.get(message.id)
      if (waiter) {
        this.pending.delete(message.id)
        if (message.error) waiter.reject(new Error(JSON.stringify(message.error)))
        else waiter.resolve(message.result)
      } else if (message.method === 'Page.loadEventFired') {
        this.onLoad?.()
      } else if (message.method === 'Animation.animationCreated') {
        this.created?.add(message.params.id)
      } else if (message.method === 'Animation.animationStarted') {
        this.created?.add(message.params.animation.id)
      }
    }
  }

  send(method, params = {}, sessionId) {
    const id = this.next++
    this.process.stdio[3].write(JSON.stringify({ id, method, params, sessionId }) + '\0')
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }))
  }

  close() {
    this.process.kill()
  }
}

// Scripts stay off, as they do in our renderer, so a script-driven file is compared as the static picture we promise.
// Evaluating from the debugger still works with them off.
const SEEK = (t) => `(() => {
  // Chromium gives each nested svg its own SMIL clock, so seeking only the outermost would leave the inner ones running.
  for (const svg of document.querySelectorAll('svg')) { svg.pauseAnimations(); svg.setCurrentTime(${t}) }
  for (const a of document.getAnimations()) { a.pause(); a.currentTime = ${t} * 1000 }
  void getComputedStyle(document.documentElement).opacity
  return document.getAnimations().length
})()`

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function readMoments(path) {
  const entries = []
  for (const raw of readFileSync(path, 'utf8').split('\n')) {
    const line = raw.replace(/#.*/, '').trim()
    if (!line) continue
    const [fixture, ...rest] = line.split(/\s+/)
    const times = rest.filter((part) => /^[0-9]/.test(part))
    entries.push({ fixture, times: times.length ? times : ['0'] })
  }
  return entries
}

function referenceName(fixture, time) {
  return `${fixture.replace(/\.svg$/, '')}@${time}.png`
}

async function main() {
  const fixtures = resolve(args.fixtures)
  const out = join(fixtures, 'reference')
  mkdirSync(out, { recursive: true })
  mkdirSync(args.profile, { recursive: true })
  const size = Number(args.size)
  const browser = new Browser(findChrome(), args.profile, fontConfig(findFonts(), args.profile), size)
  const { targetId } = await browser.send('Target.createTarget', { url: 'about:blank' })
  const { sessionId } = await browser.send('Target.attachToTarget', { targetId, flatten: true })
  const page = (method, params) => browser.send(method, params, sessionId)
  await page('Page.enable')
  await page('Runtime.enable')
  // Animations inside a use element's hidden copy are out of reach of the page's own API, so the debugger's list seeks them too.
  await page('Animation.enable')
  await page('Emulation.setDeviceMetricsOverride', { width: size, height: size, deviceScaleFactor: 1, mobile: false })
  await page('Emulation.setDefaultBackgroundColorOverride', { color: { r: 0, g: 0, b: 0, a: 0 } })
  await page('Emulation.setScriptExecutionDisabled', { value: true })

  const entries = readMoments(join(out, 'moments.txt'))
  const wanted = (name) => filters.length === 0 || filters.some((filter) => name.includes(filter))
  let written = 0
  let mismatched = 0
  for (const { fixture, times } of entries) {
    if (!wanted(fixture)) continue
    const source = join(fixtures, fixture)
    if (!existsSync(source)) throw new Error(`moments.txt names a missing fixture: ${fixture}`)
    browser.created = new Set()
    const loaded = new Promise((resolve) => (browser.onLoad = resolve))
    await page('Page.navigate', { url: pathToFileURL(source).href })
    await loaded
    await sleep(50)
    for (const time of times) {
      await page('Runtime.evaluate', { expression: SEEK(time) })
      const ids = [...browser.created]
      if (ids.length) {
        await page('Animation.setPaused', { animations: ids, paused: true }).catch(() => {})
        // Seeking to exactly zero here leaves an animation a little way in. A thousandth of a millisecond is close enough to it.
        await page('Animation.seekAnimations', { animations: ids, currentTime: Math.max(Number(time) * 1000, 0.001) }).catch(() => {})
      }
      await sleep(30)
      await page('Runtime.evaluate', { expression: SEEK(time) })
      await sleep(30)
      // Taking the picture twice shows whether seeking had settled, instead of trusting that it did.
      const first = await page('Page.captureScreenshot', { format: 'png', fromSurface: true })
      const second = await page('Page.captureScreenshot', { format: 'png', fromSurface: true })
      if (first.data !== second.data) {
        mismatched++
        console.error(`unsettled: ${fixture} at ${time}`)
      }
      writeFileSync(join(out, referenceName(fixture, time)), Buffer.from(second.data, 'base64'))
      written++
    }
  }
  browser.close()
  console.log(`${written} pictures written to ${out}${mismatched ? `, ${mismatched} unsettled` : ''}`)
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
