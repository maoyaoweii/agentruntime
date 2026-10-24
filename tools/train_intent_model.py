"""训练内置意图分类模型（多项式朴素贝叶斯 + 字符 n-gram）。

产物：agent/src/main/assets/models/intent_nb.json
纯 Python 实现，不依赖 sklearn —— 一是本机没有，二是权重格式要能被 Java 直接读懂。
"""
import io
import json
import math
import os
import random
from collections import Counter, defaultdict

OUT = r'E:/agentruntime/agent/src/main/assets/models/intent_nb.json'

# ---------------------------------------------------------------- 训练语料
# 每个意图 30±5 条，覆盖不同说法：动词换、语序换、加冗余词、中英混说。
CORPUS = {
    'observe': ('sys.observe', [
        '观察当前设备环境', '看看这是什么手机', '查一下设备信息', '屏幕多大', '当前网络是什么',
        '电量还剩多少', '看看系统版本', '环境怎么样', '设备状况如何', '看看现在什么状态',
        '帮我看下设备参数', '查设备', 'observe', '看看屏幕分辨率', '现在什么网络',
        '设备是什么型号', '看看内存和屏幕', '查一下当前环境', '看下手机信息', '读一下设备状态',
        '现在电量多少', '看看是不是深色模式', '当前语言是什么', '屏幕密度多少', '看看旋转方向',
        '了解一下这台设备', '报告设备情况', '设备指纹是什么', '看看系统信息', '环境快照',
    ]),
    'screenshot': ('sys.screenshot', [
        '截图', '截个图', '屏幕截图', '帮我截屏', '保存当前画面', 'screenshot', '把屏幕拍下来',
        '截屏保存', '来一张截图', '抓个屏', '截下现在的屏幕', '屏幕存图', '拍个屏',
        '截一张图给我', '当前屏幕保存成图片', '存一张屏幕快照', '帮我把这个界面截下来',
        '截屏留证', '截图看一下', '抓取屏幕', '截屏一下', '把当前画面存下来',
        '我要截图', '截个屏', '屏幕抓图', 'capture screen', '截屏工具', '画面截取',
        '截图保存到本地', '拍一张屏幕',
    ]),
    'create_note': ('notes.create_note', [
        '新建笔记', '写一条笔记', '记一下', '打开笔记新建', '创建备忘录', 'note', '帮我记笔记',
        '在笔记里写', '添加一条笔记', '记录一条', '写个备忘', '笔记新建一条', '记到笔记里',
        '帮我建个笔记', '打开笔记应用写东西', '保存一条笔记', '写下来', '新建备忘录',
        '在备忘录里记', '记一下这件事', '创建一条记录', '写个便签', '便签新建',
        '笔记记一条内容', '把这段写进笔记', '帮我写个备忘', '新建文本笔记', '记录一下信息',
        '记笔记', '在笔记里添加内容',
    ]),
    'open_clock': ('sys.open_clock', [
        '打开时钟', '打开闹钟', '看时间', '启动时钟应用', 'clock', 'alarm', '打开时钟应用',
        '设个闹钟', '看看几点了', '打开表', '进入时钟', '闹钟在哪里', '时钟应用打开',
        '我要看时间', '打开时间应用', '启动闹钟', '调出时钟', '时钟菜单', '打开计时器',
        '看看时间', '打开系统时钟', '闹钟设置', '时钟界面', '打开闹铃', '查时间',
        '帮我开时钟', '时钟在哪里', '看下现在几点', '打开闹钟应用', 'clock app',
    ]),
    'read_wifi': ('agent_test.read_wifi', [
        '读取wifi状态', '看看wifi', '无线网状态', 'wifi开关状态', '查wifi', 'wifi 状态如何',
        '连的是哪个wifi', 'wifi 开没开', '无线网络情况', '读一下wifi', 'wifi 当前状态',
        '查看无线连接', 'wifi 连接了吗', '网络连接状态', '看看有没有连wifi', 'wifi 信号',
        '读wifi信息', '无线网开没开', 'wifi state', '查询wifi开关', '看下wifi设置',
        '检查无线网络', 'wifi 是否开启', '当前wifi名字', '读网络状态', 'wifi 相关设置',
        '打开设置看wifi', '无线状态查询', '看wifi有没有打开', '查询wifi状态',
    ]),
    'send_file': ('agent_test.send_file', [
        '发送文件', '把文件发给他', '传文件', '发送report.pdf', '发个文档', '文件发过去',
        '把这个pdf发给他', '传一个文件给测试用户', '发送附件', '发文件给联系人',
        '把文档发出去', '附件发送', '传个pdf', '发送report文件', '发个文件过去',
        '帮我把文件发给TestUser', '文件传输', '发送报告文件', '把pdf发给他',
        '发一个文档', '传文件给对方', '发送本地文件', '发个附件', '把文件传过去',
        '发送选中文件', '文件分享给他', '发档案', '把报告发过去', '发送文件给好友',
        '传文档给对方',
    ]),
    'send_text': ('agent_test.send_text', [
        '发送消息', '发条消息', '说一句话', '给他说', '发文本', '发一条文字', '发消息给他',
        '发句问候', '把这段话发出去', '发送文字消息', '发个信息', '说点什么发过去',
        '发一条短信', '发文字', '帮他发消息', '发送内容', '发消息给联系人',
        '把这句话发给他', '发一条消息', '发个文本消息', '传话给他', '发条信息过去',
        '发送一段话', '发消息通知他', '发个招呼', '发送聊天内容', '把文字发过去',
        '发消息给TestUser', '打字发过去', '发条文字消息',
    ]),
    'open_chat': ('agent_test.open_chat', [
        '打开聊天', '进入会话', '打开和某人的聊天', '找联系人聊天', '打开对话', '进入聊天页',
        '打开TestUser的聊天', '找到联系人', '打开聊天记录', '进入对话界面', '聊天打开',
        '打开会话窗口', '找到那个人的聊天', '进入聊天列表', '打开消息页', '聊天界面进入',
        '要和TestUser聊天', '打开聊天窗口', '进入消息中心', '打开对话页', '找聊天',
        '打开聊天应用', '进入和好友的对话', '点开聊天', '打开聊天室', '进入会话页',
        '打开聊天界面', '找到会话', '打开跟他的聊天', '进入聊天详情',
    ]),
    'browse': ('web.open_and_read', [
        '打开网页', '访问网站', '浏览这个链接', '看看这个网址', '打开浏览器', '读一下这个页面',
        '打开https://example.com', '访问http://test.com', '浏览网页内容', '把网页读出来',
        '打开一个网站', '看看页面写的什么', '读取页面文字', '打开链接', '浏览页面',
        '访问这个地址', '网页内容是什么', '读网页', '打开这个url', '看看网站内容',
        '浏览一下这个页面', '打开网页看看', '访问网页并读取', '抓取页面文本', '看看这个网页',
        '读一下网页内容', '打开网址', '进入网站', '浏览链接内容', '网页读一下',
    ]),
    'smoke': ('sys.tool_smoke', [
        '工具冒烟', '测试一下工具', '跑个冒烟测试', '检查动作是否正常', '冒烟一下',
        '验证所有动作', '跑工具链测试', '测试基础工具', '检查工具可用性', '冒烟测试工具',
        '把工具都跑一遍', '验证动作原语', '测试输入和点击', '检查是否能正常操作',
        '跑一遍工具冒烟', '自检工具链', '测试所有基础能力', '冒烟验证', '工具自检',
        '测试一下能不能操作', '检查点击输入是否正常', '跑一下工具测试', '验证工具链',
        '测试动作是否可用', '全量测试工具', '工具冒烟测试', '检查各项动作', '自检一遍',
        '测试基础动作', '跑冒烟',
    ]),
    'coord_smoke': ('sys.coordinate_smoke', [
        '坐标冒烟', '测试坐标动作', '滑动测试', '手势测试', '测试点击坐标', '跑坐标测试',
        '验证滑动是否正常', '测试长按', '检查手势注入', '坐标动作自检', '测试屏幕坐标',
        '滑动一下试试', '验证坐标点击', '测试手势能力', '跑一遍坐标冒烟', '测试触摸注入',
        '验证滑动和长按', '坐标能力测试', '测试触摸事件', '检查手势是否可用',
        '滑动验证', '长按测试', '测试坐标注入', '手势冒烟', '验证触摸操作',
        '测试屏幕手势', '坐标自检', '点击坐标测试', '滑动和长按测试', '坐标手势验证',
    ]),
    'visual_tap': ('vision.tap_target', [
        '点击那个按钮', '帮我点一下', '视觉定位点击', '找到按钮点它', '点屏幕上的发送',
        '看到那个图标点它', '点一下确定', '识别并点击', '找到这个元素点击', '视觉点击',
        '点那个红色的按钮', '帮我把这个点掉', '看图点击', '识别按钮位置并点', '点击画面里的按钮',
        '点一下屏幕上那个', '定位元素然后点击', '找到并点击', '视觉找元素点击', '点它',
        '按照片点位置', '点击界面上的图标', '识别后点击', '看着屏幕点一下', '点那个区域',
        '点击目标元素', '帮我点那个链接', '看到就点', '视觉方式点一下', '点图片里的按钮',
    ]),
}

# ---------------------------------------------------------------- 特征
def tokenize(text):
    """字符 unigram + bigram。中文没有空格，字符 n-gram 是最稳的切法。"""
    s = ''.join(text.lower().split())
    toks = []
    for i, ch in enumerate(s):
        toks.append(ch)
        if i + 1 < len(s):
            toks.append(s[i:i + 2])
    return toks


def train():
    classes = sorted(CORPUS.keys())
    idx = {c: i for i, c in enumerate(classes)}

    train_docs = defaultdict(list)
    test_docs = defaultdict(list)
    rnd = random.Random(20261023)
    for c, (_, samples) in CORPUS.items():
        s = list(samples)
        rnd.shuffle(s)
        k = max(1, int(len(s) * 0.15))
        test_docs[c] = s[:k]
        train_docs[c] = s[k:]

    # 统计
    class_counts = Counter()
    token_counts = defaultdict(Counter)
    vocab_counter = Counter()

    for c, docs in train_docs.items():
        for d in docs:
            class_counts[c] += 1
            for t in tokenize(d):
                token_counts[c][t] += 1
                vocab_counter[t] += 1

    # 只保留出现 >= 2 次的 token，控制体积；上限 1500
    kept = [t for t, n in vocab_counter.most_common() if n >= 2][:1500]
    vocab = {t: i for i, t in enumerate(kept)}
    V = len(vocab)

    total_docs = sum(class_counts.values())
    alpha = 0.5
    # 每个类的 token 总数（用于分母）
    class_token_total = {c: sum(token_counts[c].values()) for c in classes}
    denom = {c: class_token_total[c] + alpha * V for c in classes}

    # 未登录词的默认 log 概率：用 alpha / denom 的平均
    default_logp = []
    prior = []
    for c in classes:
        prior.append(round(math.log(class_counts[c] / total_docs), 4))
        default_logp.append(round(math.log(alpha / denom[c]), 4))

    # 稀疏权重：token_index -> {class_index: logp}
    weights = {}
    for t, ti in vocab.items():
        row = {}
        for c in classes:
            cnt = token_counts[c][t]
            if cnt == 0:
                continue
            lp = math.log((cnt + alpha) / denom[c])
            # 与 default 差异太小就不存，省体积
            if abs(lp - default_logp[idx[c]]) > 0.02:
                row[str(idx[c])] = round(lp, 3)
        if row:
            weights[str(ti)] = row

    model = {
        'version': '1.0',
        'alpha': alpha,
        'classes': classes,
        'skills': [CORPUS[c][0] for c in classes],
        'prior': prior,
        'default_logp': default_logp,
        'tokens': kept,
        'weights': weights,
        'trained_docs': total_docs,
        'vocab_size': V,
    }

    # ------------------------------------------------------------ 评估
    def predict(text, m):
        toks = tokenize(text)
        scores = list(m['prior'])
        for t in toks:
            ti = m['tokens'].index(t) if t in vocab else -1
            for ci in range(len(classes)):
                if ti >= 0 and str(ti) in m['weights'] and str(ci) in m['weights'][str(ti)]:
                    scores[ci] += m['weights'][str(ti)][str(ci)]
                else:
                    scores[ci] += m['default_logp'][ci]
        best = max(range(len(scores)), key=lambda i: scores[i])
        mx = max(scores)
        exps = [math.exp(s - mx) for s in scores]
        conf = exps[best] / sum(exps)
        return classes[best], round(conf, 4)

    total = 0
    correct = 0
    mistakes = []
    for c, docs in test_docs.items():
        for d in docs:
            total += 1
            pred, conf = predict(d, model)
            if pred == c:
                correct += 1
            else:
                mistakes.append((d, c, pred, conf))

    print(f'保留词表 {V} 个 token，训练样本 {total_docs} 条')
    print(f'留出验证：{correct}/{total} = {correct / total:.1%}')
    if mistakes:
        print('错例：')
        for d, c, p, conf in mistakes[:8]:
            print(f'  {d}  期望={c} 预测={p} conf={conf}')

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with io.open(OUT, 'w', encoding='utf-8') as f:
        json.dump(model, f, ensure_ascii=False, separators=(',', ':'))
    size = os.path.getsize(OUT)
    print(f'写出 {OUT}  {size / 1024:.1f} KB')
    return correct / total


if __name__ == '__main__':
    acc = train()
    raise SystemExit(0 if acc >= 0.8 else 1)
