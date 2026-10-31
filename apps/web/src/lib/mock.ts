import type {
  Character,
  Episode,
  Project,
  ProviderSettings,
  SceneAsset,
  Shot,
  ShotImage,
  State,
} from './types';
import { uid } from './id';

const now = Date.now();
const hour = 3_600_000;

/** 示例剧本按「一、」「二、」这样的段落标题切分，每段即一集。 */
export const demoStory = `一、雨夜

暴雨把整条街浇成一条发亮的河。沈夜撑着黑伞站在便利店门口，等一个人。

三年了，她第一次回到这座城市。

她记得这个路口。三年前的那个晚上，继母把她的行李扔在这盏路灯下，说：「你妈留下的东西，一样都别想带走。」

二、便利店里

自动门滑开，穿校服的女孩抱着一盒牛奶走出来，撞在沈夜身上。

「对不起。」女孩抬头，愣住了，「姐姐，你……长得好像我妈妈。」

沈夜低头看她。那张脸和她十八岁时一模一样。

三、旧宅

铁门没锁。院子里的桂花树被雨打得东倒西歪。

客厅灯亮着。继母坐在沙发上数一叠单据，听见脚步声抬起头，脸上的表情从惊愕变成一种勉强的笑。

「你回来做什么？」

沈夜把一份文件放在茶几上，伞还在滴水。

「这栋房子的产权，是我妈的名字。明天之前，请你搬走。」

四、天亮

雨停的时候，天边裂开一道很浅的亮。

沈夜站在门口，没有回头。身后的门缓缓合上，发出很轻的一声响。

她终于觉得自己可以走了。`;

export const emptyStory = '';

function makeEpisode(index: number, title: string, story: string, breakdown: Episode['breakdown']): Episode {
  return {
    id: `ep_demo_${index}`,
    index,
    title,
    summary: story.split(/\n+/).map((p) => p.trim()).filter(Boolean)[0]?.slice(0, 40) ?? '',
    story,
    breakdown,
    createdAt: now - 5 * hour,
    updatedAt: now - 40 * 60_000,
  };
}

export const demoEpisodes: Episode[] = [
  makeEpisode(
    1,
    '雨夜',
    `暴雨把整条街浇成一条发亮的河。沈夜撑着黑伞站在便利店门口，等一个人。

三年了，她第一次回到这座城市。

她记得这个路口。三年前的那个晚上，继母把她的行李扔在这盏路灯下，说：「你妈留下的东西，一样都别想带走。」`,
    'done',
  ),
  makeEpisode(
    2,
    '便利店里',
    `自动门滑开，穿校服的女孩抱着一盒牛奶走出来，撞在沈夜身上。

「对不起。」女孩抬头，愣住了，「姐姐，你……长得好像我妈妈。」

沈夜低头看她。那张脸和她十八岁时一模一样。`,
    'done',
  ),
  makeEpisode(
    3,
    '旧宅',
    `铁门没锁。院子里的桂花树被雨打得东倒西歪。

客厅灯亮着。继母坐在沙发上数一叠单据，听见脚步声抬起头，脸上的表情从惊愕变成一种勉强的笑。

「你回来做什么？」

沈夜把一份文件放在茶几上，伞还在滴水。

「这栋房子的产权，是我妈的名字。明天之前，请你搬走。」`,
    'done',
  ),
  makeEpisode(
    4,
    '天亮',
    `雨停的时候，天边裂开一道很浅的亮。

沈夜站在门口，没有回头。身后的门缓缓合上，发出很轻的一声响。

她终于觉得自己可以走了。`,
    'done',
  ),
];

const demoProject: Project = {
  id: 'p_demo',
  name: '长夜将明',
  visualType: 'film',
  style: '冷色调、低饱和、35mm 胶片颗粒、夜景为主',
  createdAt: now - 5 * hour,
  updatedAt: now - 40 * 60_000,
  episodes: demoEpisodes,
};

const emptyProject: Project = {
  id: 'p_empty',
  name: '待开机的第二部',
  visualType: 'anime',
  style: '赛璐璐质感、高对比、夜雨',
  createdAt: now - 2 * 24 * hour,
  updatedAt: now - 2 * 24 * hour,
  episodes: [],
};

export const demoCharacters: Character[] = [
  {
    id: 'c_shenye',
    projectId: demoProject.id,
    name: '沈夜',
    description: '28 岁，短发，黑色长风衣，眼神冷静。三年前被赶出家门，如今带着律师函回来。',
    versions: [
      { id: 'v_sy_1', label: 'v1 基准', seed: '沈夜-基准-雨夜', createdAt: now - 4 * hour, source: 'generated' },
      { id: 'v_sy_2', label: 'v2 更近景', seed: '沈夜-近景-冷光', createdAt: now - 3 * hour, source: 'generated' },
    ],
    selectedVersionId: 'v_sy_2',
  },
  {
    id: 'c_jimu',
    projectId: demoProject.id,
    name: '继母',
    description: '45 岁，盘发，丝质家居服，指甲修得很短，习惯用笑掩盖算计。',
    versions: [
      { id: 'v_jm_1', label: 'v1 基准', seed: '继母-客厅-暖光', createdAt: now - 4 * hour, source: 'generated' },
    ],
    selectedVersionId: 'v_jm_1',
  },
  {
    id: 'c_girl',
    projectId: demoProject.id,
    name: '便利店女孩',
    description: '12 岁，校服，抱着牛奶盒，短发，和沈夜十八岁时极像。',
    versions: [
      { id: 'v_gl_1', label: 'v1 基准', seed: '女孩-便利店-雨', createdAt: now - 2 * hour, source: 'uploaded' },
    ],
    selectedVersionId: 'v_gl_1',
  },
];

export const demoScenes: SceneAsset[] = [
  {
    id: 's_street',
    projectId: demoProject.id,
    name: '雨夜街道',
    description: '便利店门前，路灯，积水倒影，冷蓝主光。',
    versions: [
      { id: 'v_st_1', label: 'v1 基准', seed: '街道-雨-冷蓝', createdAt: now - 4 * hour, source: 'generated' },
    ],
    selectedVersionId: 'v_st_1',
  },
  {
    id: 's_store',
    projectId: demoProject.id,
    name: '便利店',
    description: '暖白灯光，自动门，货架，玻璃反光。',
    versions: [
      { id: 'v_ss_1', label: 'v1 基准', seed: '便利店-暖白', createdAt: now - 4 * hour, source: 'generated' },
    ],
    selectedVersionId: 'v_ss_1',
  },
  {
    id: 's_house',
    projectId: demoProject.id,
    name: '旧宅客厅',
    description: '暖黄壁灯，深色家具，茶几上散着单据，窗外桂花树。',
    versions: [
      { id: 'v_sh_1', label: 'v1 基准', seed: '旧宅-客厅-暖黄', createdAt: now - 3 * hour, source: 'generated' },
    ],
    selectedVersionId: 'v_sh_1',
  },
];

function img(label: string, seed: string, hoursAgo: number): ShotImage {
  return { id: uid('img'), label, seed, createdAt: now - hoursAgo * hour };
}

type ShotSeed = Omit<
  Shot,
  'id' | 'projectId' | 'episodeId' | 'images' | 'selectedImageId' | 'imageState' | 'video'
> & { episodeIndex: number };

const shotSeed: ShotSeed[] = [
  // 第一集 · 雨夜
  {
    episodeIndex: 1, seq: 1, sceneName: '雨夜街道', shotSize: '全景', cameraMove: '固定',
    description: '暴雨中的街道，路灯在积水里拉出长影，一个撑黑伞的身影从画面右侧入。',
    dialogue: '', durationMs: 4000, status: 'confirmed', characterNames: ['沈夜'],
  },
  {
    episodeIndex: 1, seq: 2, sceneName: '雨夜街道', shotSize: '中景', cameraMove: '推',
    description: '沈夜站在便利店门口，伞沿滴水。镜头缓慢推近，她抬头看灯。',
    dialogue: '', durationMs: 3500, status: 'confirmed', characterNames: ['沈夜'],
  },
  {
    episodeIndex: 1, seq: 3, sceneName: '雨夜街道', shotSize: '近景', cameraMove: '固定',
    description: '闪回：三年前的同一盏路灯下，行李箱被扔在积水的路面上。',
    dialogue: '', durationMs: 3000, status: 'confirmed', characterNames: ['沈夜'],
  },
  // 第二集 · 便利店里
  {
    episodeIndex: 2, seq: 1, sceneName: '便利店', shotSize: '中景', cameraMove: '固定',
    description: '自动门滑开，女孩抱牛奶盒走出来，撞上沈夜，牛奶盒晃了一下。',
    dialogue: '对不起。', durationMs: 3000, status: 'confirmed', characterNames: ['便利店女孩', '沈夜'],
  },
  {
    episodeIndex: 2, seq: 2, sceneName: '便利店', shotSize: '特写', cameraMove: '固定',
    description: '女孩抬头，看清沈夜的脸，瞳孔微微放大。',
    dialogue: '姐姐，你……长得好像我妈妈。', durationMs: 4500, status: 'confirmed', characterNames: ['便利店女孩'],
  },
  {
    episodeIndex: 2, seq: 3, sceneName: '便利店', shotSize: '近景', cameraMove: '拉',
    description: '沈夜的表情从平静到震动，镜头缓缓拉远，把她和整条空街一起收进画面。',
    dialogue: '', durationMs: 4000, status: 'draft', characterNames: ['沈夜'],
  },
  // 第三集 · 旧宅
  {
    episodeIndex: 3, seq: 1, sceneName: '旧宅客厅', shotSize: '全景', cameraMove: '移',
    description: '铁门虚掩，镜头沿着湿漉漉的院子横移，桂花树在雨里摇晃，客厅灯亮着。',
    dialogue: '', durationMs: 5000, status: 'confirmed', characterNames: [],
  },
  {
    episodeIndex: 3, seq: 2, sceneName: '旧宅客厅', shotSize: '中景', cameraMove: '固定',
    description: '继母坐在沙发上数单据，听见脚步声，抬头。',
    dialogue: '你回来做什么？', durationMs: 3500, status: 'confirmed', characterNames: ['继母'],
  },
  {
    episodeIndex: 3, seq: 3, sceneName: '旧宅客厅', shotSize: '近景', cameraMove: '固定',
    description: '一份文件被放上茶几，纸角压住单据。伞尖还在滴水，水珠落在地板上。',
    dialogue: '', durationMs: 3000, status: 'confirmed', characterNames: ['沈夜'],
  },
  {
    episodeIndex: 3, seq: 4, sceneName: '旧宅客厅', shotSize: '中景', cameraMove: '推',
    description: '沈夜直视继母，说出台词。镜头极慢推进，背景虚化。',
    dialogue: '这栋房子的产权，是我妈的名字。明天之前，请你搬走。', durationMs: 6000, status: 'draft', characterNames: ['沈夜', '继母'],
  },
  // 第四集 · 天亮
  {
    episodeIndex: 4, seq: 1, sceneName: '旧宅客厅', shotSize: '特写', cameraMove: '固定',
    description: '继母的手在单据上收紧，指节发白。',
    dialogue: '', durationMs: 2500, status: 'draft', characterNames: ['继母'],
  },
  {
    episodeIndex: 4, seq: 2, sceneName: '雨夜街道', shotSize: '远景', cameraMove: '固定',
    description: '雨停，天边裂开一道浅亮。沈夜站在门口，门在身后缓缓合上。',
    dialogue: '', durationMs: 5000, status: 'draft', characterNames: ['沈夜'],
  },
];

export function buildDemoShots(): Shot[] {
  return shotSeed.map((base, index) => {
    const generated = index < 4;
    const hasVideo = index < 2;
    const images = generated
      ? [img(`候选 1`, `${base.sceneName}-${base.seq}-a`, 2), img(`候选 2`, `${base.sceneName}-${base.seq}-b`, 1)]
      : [];

    const { episodeIndex, ...rest } = base;

    return {
      ...rest,
      id: `shot_${episodeIndex}_${base.seq}`,
      projectId: demoProject.id,
      episodeId: `ep_demo_${episodeIndex}`,
      images,
      selectedImageId: generated ? images[1]?.id : undefined,
      imageState: 'idle',
      video: {
        ready: hasVideo,
        basedOn: hasVideo ? images[1]?.id : undefined,
        stale: index === 1,
        state: 'idle',
        attempts: hasVideo ? 1 : 0,
      },
    };
  });
}

export const defaultSettings: ProviderSettings = {
  provider: 'mock',
  baseUrl: '',
  token: '',
  imageModel: 'mock-image-v1',
  videoModel: 'mock-video-v1',
};

export function initialState(): State {
  return {
    user: null,
    projects: [demoProject, emptyProject],
    customStyles: [],
    shots: buildDemoShots(),
    characters: demoCharacters,
    scenes: demoScenes,
    settings: defaultSettings,
  };
}

export { demoProject, emptyProject };
