type Child = Node | string | null | undefined | false;
type Attrs = {
  class?: string;
  text?: string;
  title?: string;
  style?: Partial<CSSStyleDeclaration>;
  dataset?: Record<string, string>;
  [event: `on${string}`]: ((ev: never) => void) | undefined;
} & Record<string, unknown>;

/** 작은 DOM 생성 도우미: h('button', { class: 'btn', onclick: … }, '텍스트') */
export function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Attrs = {}, ...children: Child[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === null) continue;
    if (k === 'class') el.className = v as string;
    else if (k === 'text') el.textContent = v as string;
    else if (k === 'style') Object.assign(el.style, v);
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v as EventListener);
    else if (typeof v === 'boolean') {
      if (v) el.setAttribute(k, '');
    } else el.setAttribute(k, String(v));
  }
  for (const c of children) if (c !== null && c !== undefined && c !== false) el.append(c);
  return el;
}

/** 바뀌었을 때만 DOM 을 건드림 (매 프레임 호출해도 싸게) */
export function setText(el: HTMLElement, text: string): void {
  if (el.textContent !== text) el.textContent = text;
}

export function toggleClass(el: HTMLElement, cls: string, on: boolean): void {
  if (el.classList.contains(cls) !== on) el.classList.toggle(cls, on);
}

export function show(el: HTMLElement, visible: boolean): void {
  const d = visible ? '' : 'none';
  if (el.style.display !== d) el.style.display = d;
}

export function mmss(ms: number): string {
  const s = Math.ceil(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
