import { Mark } from '../gen/catchnyang/v1/game_pb';
import type { StudentState } from '../state/game-store';
import { h, setText, toggleClass } from './dom';

interface Row {
  root: HTMLElement;
  paper: HTMLElement;
  o: HTMLElement;
  x: HTMLElement;
  answer: HTMLElement;
  check: HTMLInputElement;
}

export interface OmrPanelOptions {
  /** 냥이: 정답 표시 + 문항 선택(도구) + 고치기 / 감독관: 읽기만 (시험지와 다른 칸 표시) */
  mode: 'cat' | 'proctor';
  onRemark?: (question: number) => void;
}

/** 점수에 쓰이는 답: OMR 에 마킹돼 있으면 그 값, 아니면 시험지 답 (서버 Student.effectiveAnswer 와 같음) */
export function effectiveAnswer(st: StudentState, q: number): boolean {
  const m = st.omr[q];
  return m === Mark.EMPTY || m === undefined ? st.paper[q] : m === Mark.O;
}

/**
 * 시험지(연필 답) + OMR 카드 한 장. 문항마다: 번호 · 시험지 답 · OMR 두 칸(O/X) · (냥이) 아는 정답.
 * 냥이는 모르는 문항에 체크해서 도구로 확인하고, 아는데 틀린 문항을 눌러 고친다.
 */
export class OmrPanel {
  readonly el: HTMLElement;
  readonly selected = new Set<number>();
  private readonly rows: Row[] = [];
  private readonly title: HTMLElement;
  private maxSelect = 0;

  constructor(private readonly questionCount: number, private readonly opts: OmrPanelOptions) {
    this.title = h('div', { class: 'omr-title' });
    const grid = h('div', { class: 'omr-grid' });
    for (let q = 0; q < questionCount; q++) grid.append(this.row(q));
    this.el = h('div', { class: `omr omr-${opts.mode}` }, this.title, grid);
  }

  private row(q: number): HTMLElement {
    const check = h('input', { type: 'checkbox', class: 'omr-check', title: '도구로 확인할 문항' });
    check.addEventListener('change', () => {
      if (check.checked) {
        if (this.selected.size >= this.maxSelect) {
          check.checked = false;
          return;
        }
        this.selected.add(q);
      } else {
        this.selected.delete(q);
      }
    });
    const paper = h('span', { class: 'omr-paper' });
    const o = h('span', { class: 'bubble', text: 'O' });
    const x = h('span', { class: 'bubble', text: 'X' });
    const answer = h('span', { class: 'omr-answer' });
    const root = h('div', { class: 'omr-row' },
      h('span', { class: 'omr-num', text: String(q + 1) }), paper, h('span', { class: 'omr-bubbles' }, o, x), answer, check);
    root.addEventListener('click', (ev) => {
      if (ev.target === check) return;
      if (root.classList.contains('fixable')) this.opts.onRemark?.(q);
    });
    this.rows.push({ root, paper, o, x, answer, check });
    return root;
  }

  /**
   * @param known 냥이가 아는 정답 (감독관은 undefined)
   * @param maxSelect 도구로 지금 한 번에 확인할 수 있는 문항 수 (0 = 선택 불가)
   */
  update(title: string, st: StudentState | undefined, known?: Map<number, boolean>, maxSelect = 0, locked = false): void {
    setText(this.title, title);
    this.maxSelect = maxSelect;
    // 이미 알게 된 문항은 선택에서 빼고, 선택 가능 수가 줄었으면 나중에 고른 것부터 버림
    for (const q of [...this.selected]) if (known?.has(q)) this.selected.delete(q);
    while (this.selected.size > maxSelect) this.selected.delete([...this.selected].pop()!);

    for (let q = 0; q < this.questionCount; q++) {
      const r = this.rows[q];
      const paper = st?.paper[q];
      const mark = st?.omr[q] ?? Mark.EMPTY;
      setText(r.paper, paper === undefined ? '·' : paper ? 'O' : 'X');
      toggleClass(r.o, 'filled', mark === Mark.O);
      toggleClass(r.x, 'filled', mark === Mark.X);
      toggleClass(r.root, 'pending', mark === Mark.EMPTY);

      if (this.opts.mode === 'cat') {
        const k = known?.get(q);
        const isKnown = k !== undefined;
        const wrong = isKnown && !!st && effectiveAnswer(st, q) !== k;
        setText(r.answer, isKnown ? `정답 ${k ? 'O' : 'X'}` : '');
        toggleClass(r.answer, 'ok', isKnown && !wrong);
        toggleClass(r.answer, 'bad', wrong);
        toggleClass(r.root, 'fixable', wrong && !locked);
        r.check.style.visibility = isKnown ? 'hidden' : 'visible';
        r.check.disabled = locked || isKnown || (maxSelect === 0 && !r.check.checked);
        if (!this.selected.has(q) && r.check.checked) r.check.checked = false;
      } else {
        // 감독관: 시험지와 다르게 마킹된 칸 = 고친 흔적일 수도, 학생 실수일 수도
        const differs = !!st && mark !== Mark.EMPTY && (mark === Mark.O) !== paper;
        toggleClass(r.root, 'differs', differs);
        r.check.style.display = 'none';
        setText(r.answer, differs ? '다름' : '');
      }
    }
  }

  clearSelection(): void {
    this.selected.clear();
    this.rows.forEach((r) => (r.check.checked = false));
  }
}
