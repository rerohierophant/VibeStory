import { useState } from 'react';
import { Button } from '@/components/Button';

const inspirations = [
  { title: '灯塔熄灭以后', premise: '旧灯塔退役的最后一晚，一个穿黄雨衣的女孩借守塔人的灯，给即将归航的父亲发出两次闪光。' },
  { title: '明天的照片', premise: '街角照相馆洗出的照片总比现实早一天，学徒在新照片里看见师傅拖着行李离开，却没留下地址。' },
  { title: '云上的修理铺', premise: '高山上的钟表匠收到一朵漏雨的云，送修的孩子希望它能赶在傍晚前，给山脚的菜园下一场雨。' },
  { title: '末班渡船', premise: '暴雨前的最后一班渡船即将开走，船工发现桥边有人每天放一双干鞋，却从没上过船。' },
  { title: '影子请假了', premise: '开学典礼那天，一个女孩的影子偷偷跑进旧体育馆，她追过去，发现影子正在排练她不敢上台跳的舞。' },
  { title: '没有观众的首映', premise: '电影院关门前，放映员答应给一位老太太放映她年轻时拍的短片，但胶片里少了她最想看的那一卷。' },
  { title: '雪夜邮差', premise: '雪封山路，一位老邮差收到一封写给明年春天的信，地址是即将拆除的村口邮筒。' },
  { title: '海底洗衣店', premise: '海底城的洗衣店每天收工前都要回收陆地衣物，一名学徒却在从没见过的校服口袋里摸到自己的名字牌。' },
  { title: '三分钟的夏天', premise: '冬天的花店有一台只能维持三分钟夏天的风扇，店员要在风停前，让一株多年没开的花迎来第一只蜜蜂。' },
  { title: '不肯落地的风筝', premise: '小镇的风筝比赛结束后，一个男孩的纸鸢逆风飞回山坡，线的另一头传来已搬走的朋友约定的暗号。' },
  { title: '明早几点见', premise: '相邻两户人家轮流留纸条约早餐，直到新搬来的女孩发现，对门每晚亮着灯，白天却是一堵墙。' },
  { title: '交换声音', premise: '修录音机的女孩听见一卷空白磁带在模仿街坊的声音，她得在老街搬空前录下一声真正的道别。' },
  { title: '月亮失物招领', premise: '天文馆闭馆后，清洁工捡到一轮掌心大的月亮，第二天来认领它的却是一个怕黑的小孩。' },
  { title: '冰箱里的车站', premise: '厨师打开结霜的旧冰箱，听见故乡车站的广播，而那趟他一直没赶上的列车只等到饭店打烊。' },
  { title: '逆行的游鱼', premise: '纸鱼从画室的墙上游进雨水，年轻画师追着它穿过老巷，才发现它想回到一幅被涂掉的壁画里。' },
  { title: '最后一块拼图', premise: '搬家前，兄妹在旧拼图上发现一块没见过的窗户，窗里是妈妈从未给他们看过的少年时代。' },
  { title: '风暴里的茶馆', premise: '沙漠里起了风暴，一间茶馆只剩两位客人，他们的地图上画着同一口井，却在相反的方向。' },
  { title: '半格电的机器人', premise: '旧机器人只剩半格电，却坚持要去屋顶；跟着它的小孩发现，它每晚都在那里替邻居点亮楼梯灯。' },
  { title: '楼顶的稻田', premise: '暴雨把屋顶稻田淹得只剩几株，素不相识的邻居们各自带着一只锅上楼，想把稻苗救回家。' },
  { title: '雨停之前', premise: '雨中出现一家只在积水倒影里营业的书店，女学生翻到一本日记，最后一页正等她写下今天发生的事。' },
  { title: '纸做的狐狸', premise: '博物馆夜班保安看见折纸狐狸叼走展柜里的纸钥匙，循着脚印找到一扇藏在展板后的门。' },
  { title: '漂流瓶快递', premise: '海边快递员收到一只装着退货单的漂流瓶，收件人是童年的自己，包裹里还缺当年借给好友的弹珠。' },
  { title: '失约的乐队', premise: '地下通道的手风琴手停演一年后回来，发现有人替他占着老位置，每天只弹他没写完的那四个小节。' },
  { title: '星尘面包房', premise: '空间站的面包师第一次烤出故乡的味道，却恰逢停电，他和学徒决定把最后一盘生面团带到向阳的舷窗前。' },
  { title: '第七码头', premise: '送花女孩按地址来到只有六个泊位的码头，巡夜人说第七码头只在退潮时出现，而且今晚是最后一次。' },
  { title: '花园钥匙', premise: '独居老人收到一把邻居留下的钥匙，它打不开任何门，却能打开城里一盏从没亮过的路灯。' },
  { title: '少了一颗星', premise: '山顶观测员发现一颗星每天暗一截，与此同时，山下修车铺那个总来借望远镜的女孩停止了赴约。' },
  { title: '捡到一场雪', premise: '热带岛上的小卖部冰柜里落着一场小雪，老板的女儿想在融化前，给从没离开过海岛的奶奶堆一个雪人。' },
  { title: '电梯里的海风', premise: '旧写字楼的电梯在停电时停到不存在的一层，一名加班的设计师推开门，走进自己小时候画的海滨游乐场。' },
  { title: '晚一点熄灯', premise: '裁缝铺歇业前的最后一单，是给兄妹俩把父亲的旧外套改成两个布偶，可两个人都想保留那个破了洞的口袋。' },
];

type Inspiration = (typeof inspirations)[number];

function drawInspirations(previous: Inspiration[] = []) {
  const pool = inspirations.filter(item => !previous.includes(item));
  return Array.from({ length: 3 }, () => pool.splice(Math.floor(Math.random() * pool.length), 1)[0]);
}

export function StoryInspirations({ onSelect, disabled }: {
  onSelect: (premise: string) => void;
  disabled: boolean;
}) {
  const [batch, setBatch] = useState(() => drawInspirations());

  return (
    <div role="group" aria-label="故事灵感" className="@container space-y-2 py-1">
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <span className="flex items-center gap-2 text-xs font-medium text-muted">
          <svg aria-hidden="true" className="size-4 text-accent-soft" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.25" strokeLinejoin="round">
            <path d="M7 2.5 8.4 6.6 12.5 8 8.4 9.4 7 13.5 5.6 9.4 1.5 8 5.6 6.6 7 2.5ZM13 2v4m-2-2h4" />
          </svg>
          故事灵感 <span className="font-normal">· 点击填入，替换当前想法</span>
        </span>
        <Button type="button" size="sm" variant="ghost" disabled={disabled} onClick={() => setBatch(drawInspirations(batch))}>
          <svg aria-hidden="true" className="size-3.5" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.25" strokeLinecap="round" strokeLinejoin="round">
            <path d="M13.5 6a5.8 5.8 0 0 0-10-2l-1 1m0-3v3h3m-3 5a5.8 5.8 0 0 0 10 2l1-1m0 3v-3h-3" />
          </svg>
          换一批
        </Button>
      </div>
      <ul className="grid gap-2 @min-[26rem]:grid-cols-3">
        {batch.map(({ title, premise }) => (
          <li key={title} className="min-w-0">
            <button type="button" disabled={disabled} onClick={() => onSelect(premise)}
              className="flex h-full w-full flex-col gap-1.5 rounded-card border border-stroke/30 bg-panel-2/35 px-3 py-2.5 text-left text-xs transition enabled:hover:border-accent-soft/50 enabled:hover:bg-hover/60 focus-visible:outline-2 focus-visible:outline-accent-soft disabled:cursor-not-allowed disabled:opacity-45">
              <span className="flex w-full items-center justify-between gap-2 font-medium text-fg">
                {title}<span aria-hidden="true" className="text-sm leading-none text-accent-soft">↗</span>
              </span>
              <span className="leading-5 text-muted">{premise}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
