/**
 * 全局点击波纹：按钮、菜单项、标签页、带 .ripple 类的元素被点击时，从点击位置扩散一圈。
 * 用事件委托实现，组件里不需要额外写代码。
 */
const SELECTOR = '.n-button, .n-menu-item-content, .n-tabs-tab, .n-radio-button, .ripple'

export function installRipple() {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  document.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return
    const target = (e.target as HTMLElement | null)?.closest<HTMLElement>(SELECTOR)
    if (!target || target.matches('[disabled], .n-button--disabled')) return
    const rect = target.getBoundingClientRect()
    const size = Math.hypot(rect.width, rect.height) * 2
    if (getComputedStyle(target).position === 'static') target.style.position = 'relative'
    const host = document.createElement('span')
    host.className = 'ripple-host'
    const wave = document.createElement('span')
    wave.className = 'ripple-wave'
    wave.style.width = wave.style.height = `${size}px`
    wave.style.left = `${e.clientX - rect.left - size / 2}px`
    wave.style.top = `${e.clientY - rect.top - size / 2}px`
    host.appendChild(wave)
    target.appendChild(host)
    window.setTimeout(() => host.remove(), 650)
  }, { passive: true })
}
