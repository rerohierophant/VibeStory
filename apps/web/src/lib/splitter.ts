import type { CameraMove, ShotSize } from './types';

export interface ShotDraft {
  description: string;
  dialogue: string;
  sceneName: string;
  shotSize: ShotSize;
  cameraMove: CameraMove;
  durationMs: number;
}

/** 场景库为空时的兜底词表，只用于 mock 拆解。 */
const fallbackScenes = [
  { name: '雨夜街道', keywords: ['街道', '路口', '路灯', '雨', '积水', '伞', '门口'] },
  { name: '便利店', keywords: ['便利店', '货架', '关东煮', '店员', '纸碗', '自动门', '牛奶'] },
  { name: '旧宅客厅', keywords: ['客厅', '沙发', '茶几', '继母', '院子', '桂花', '单据'] },
];

const cameraMoves: CameraMove[] = ['固定', '推', '拉', '移'];

/** 一镜的正文上限。超过就按句切开，避免一镜里塞五六句。 */
const MAX_SHOT_CHARS = 48;

/** 「她说。」这类引出语必须跟旁边的台词同镜，不能自己占一镜。 */
const LEAD_IN = /(说|问|道|喊|答|嘟囔|开口)$/;

/** 句末切分，但保留「。」后面的引号，让「她说：「……」」留在同一句。 */
function splitSentences(paragraph: string): string[] {
  return paragraph
    .split(/(?<=[。！？])(?!」)/)
    .map((s) => s.trim())
    .filter(Boolean);
}

/**
 * 「「要一份萝卜。」她说。」是同一句话的两半，分句后可能变成三截：
 * 台词 / 引出语 / 尾巴。短引出语并回上一句，落单的台词并回下一句。
 */
function mergeAttribution(sentences: string[]): string[] {
  const out: string[] = [];
  let carry = '';

  for (const sentence of sentences) {
    const t = sentence.trim();
    const core = t.replace(/[「」。！？\s]/g, '');
    const isAttribution = core.length > 0 && core.length <= 6 && LEAD_IN.test(core);

    if (isAttribution && out.length > 0) {
      out[out.length - 1] += t;
      continue;
    }

    // 单独一截的台词（含「」但没有落地的描写）先挂着，等下一句引出语
    const isBareQuote = /^「[^」]*」$/.test(t) || (t.includes('「') && !t.includes('」'));
    if (isBareQuote) {
      carry += t;
      continue;
    }

    out.push(carry + t);
    carry = '';
  }

  if (carry) out.push(carry);
  return out;
}

function pickScene(text: string, scenes: { name: string; keywords: string[] }[], last: string): string {
  let best = last;
  let bestHits = 0;
  for (const scene of scenes) {
    const hits = scene.keywords.filter((k) => text.includes(k)).length;
    if (hits > bestHits) {
      best = scene.name;
      bestHits = hits;
    }
  }
  return best;
}

/**
 * Mock 拆解器：按空行分段 → 段内按句聚合到一镜（台词并入同一镜）。
 * 真接模型后，这里换成 TextProvider 的输出，但产出的结构不变。
 */
export function splitIntoShots(
  story: string,
  sceneOptions?: { name: string; keywords?: string[] }[],
): ShotDraft[] {
  const scenes = sceneOptions?.length
    ? sceneOptions.map((s) => ({
        name: s.name,
        keywords: s.keywords ?? [s.name, ...s.name.split(/[的，、\s]+/).filter((w) => w.length >= 2)],
      }))
    : fallbackScenes;

  const paragraphs = story
    .split(/\n+/)
    .map((p) => p.trim())
    .filter((p) => p.length > 0 && !/^[一二三四五六七八九十]+、/.test(p));

  const drafts: ShotDraft[] = [];
  let lastScene = '';

  for (const paragraph of paragraphs) {
    const sentences = mergeAttribution(splitSentences(paragraph));
    const chunks: string[] = [];
    let current = '';

    for (const sentence of sentences) {
      // 上一句是「她说」这类引出语，下一句直接并进来，哪怕超长也不拆
      if (current && !LEAD_IN.test(current)) {
        if (current.length + sentence.length > MAX_SHOT_CHARS) {
          chunks.push(current);
          current = sentence;
          continue;
        }
      }
      current += sentence;
    }
    if (current) chunks.push(current);

    for (const chunk of chunks) {
      const quote = chunk.match(/「([^」]+)」/);
      // 「要一份萝卜。」她说。 —— 引出语在台词后面，剥掉台词后只剩一个「她说」
      const rest = chunk.replace(/「[^」]*」/g, '').trim();
      const restCore = rest.replace(/[。！？：:，,、]$/, '');
      const bareLeadIn = restCore.length > 0 && restCore.length <= 8 && !/[。！？]/.test(restCore) && LEAD_IN.test(restCore);
      const description = bareLeadIn ? '' : rest.replace(/[，、：:]$/, '');
      const index = drafts.length;

      const sceneName = pickScene(chunk, scenes, lastScene || scenes[0].name);
      lastScene = sceneName;

      // 有台词的多半是人物近景，纯环境的用全景铺陈
      const shotSize: ShotSize = quote ? '近景' : index === 0 ? '全景' : '中景';

      drafts.push({
        // 台词前面没有可描述的落点（只有「她说」），给一个最小的镜头说明
        description: (description || (quote ? '人物近景，说出台词' : chunk)).slice(0, 120),
        dialogue: quote?.[1] ?? '',
        sceneName,
        shotSize,
        cameraMove: cameraMoves[index % cameraMoves.length],
        durationMs: quote ? 4000 : 3000 + (index % 3) * 1000,
      });
    }
  }

  return drafts;
}
