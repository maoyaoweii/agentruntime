"""训练 Jev-like 决策器：受限候选集合上的快速选择。

对应立项方案术语表：
    System-1 / Jev-like = 受限候选集合上的快速决策接口范式，
    不特指可本地部署的具体模型产品。
验收要求（方案 r6）：
    候选动作输入 / 选择输出可回放；第一版本地规则可用，决策模型可替换。

这个脚本产出线性打分模型的权重，Java 端用同一套特征做推理。
特征定义必须两边逐项一致，否则模型输出会与实际不符——见 FEATURES 常量。

产物：agent/src/main/assets/models/jev_decider.json
"""
import io
import json
import math
import os
import random

OUT = r'E:/agentruntime/agent/src/main/assets/models/jev_decider.json'

# 特征顺序：两端必须一致，Java 侧见 JevFeatures.java
FEATURES = [
    'exact_match',      # 候选标签与目标完全相同
    'goal_contains',    # 目标文本包含候选标签
    'label_contains',   # 候选标签包含目标文本
    'overlap',          # 字面重合率（最长公共子序列 / 目标长度）
    'clickable',        # 可点击
    'visible',          # 可见
    'editable',         # 可编辑（输入类目标有用）
    'area_ratio',       # 面积占屏比（越小越像控件，有上限）
    'center_bias',      # 越靠屏幕中心越优先
    'label_len',        # 标签长度归一化
]

# ---------------------------------------------------------------- 场景数据
# 每个场景：目标子串 + 候选列表 + 正确项下标。
# 候选格式 (label, clickable, visible, editable, area_ratio, center_bias)
# 数据是合成的：覆盖"精确命中 / 包含 / 干扰项 / 同名多候"四类难例。

def C(label, clickable=True, visible=True, editable=False, area=0.05, center=0.6):
    return (label, clickable, visible, editable, area, center)


SCENARIOS = [
    # 目标：发送
    ('发送', [C('发送'), C('取消'), C('输入消息', editable=True), C('聊天'), C('发送', center=0.2)], 0),
    ('发送', [C('取消'), C('发送'), C('标题', clickable=False)], 1),
    ('发送消息', [C('发送'), C('附件'), C('消息列表', clickable=False)], 0),
    # 目标：搜索
    ('搜索', [C('搜索'), C('返回'), C('搜索框', editable=True), C('设置')], 0),
    ('搜索', [C('返回'), C('搜索框', editable=True), C('搜索', clickable=False)], 1),
    ('搜索用户', [C('搜索'), C('用户列表', clickable=False), C('菜单')], 0),
    # 目标：TestUser（联系人行）
    ('TestUser', [C('TestUser'), C('TestUser2'), C('搜索框', editable=True)], 0),
    ('TestUser', [C('TestUser2'), C('TestUser')], 1),
    ('TestUser', [C('搜索框', editable=True), C('TestUser'), C('TestUser')], 1),
    # 目标：确定 / 确认
    ('确定', [C('确定'), C('取消')], 0),
    ('确认', [C('取消'), C('确定')], 1),
    ('确定', [C('确定', center=0.9), C('确定', center=0.5)], 1),
    # 目标：返回
    ('返回', [C('返回'), C('首页'), C('搜索')], 0),
    ('返回上一级', [C('关闭'), C('返回', center=0.3)], 1),
    # 目标：新建笔记
    ('新建', [C('新建笔记'), C('搜索'), C('菜单')], 0),
    ('新建', [C('搜索'), C('新建', center=0.95), C('设置')], 1),
    # 目标：附件
    ('附件', [C('附件'), C('发送'), C('输入框', editable=True)], 0),
    ('report.pdf', [C('report.pdf'), C('notes.txt'), C('取消')], 0),
    ('report.pdf', [C('notes.txt'), C('report.pdf', center=0.7)], 1),
    # 目标：时钟
    ('时钟', [C('时钟'), C('闹钟'), C('计时器'), C('秒表')], 0),
    ('闹钟', [C('时钟'), C('闹钟'), C('计时器')], 1),
    ('更多选项', [C('更多选项'), C('设置'), C('搜索')], 0),
    # 目标：设置
    ('设置', [C('设置'), C('帮助'), C('关于')], 0),
    ('Wi-Fi 状态', [C('Wi-Fi 状态', clickable=False), C('蓝牙'), C('设置')], 0),
    # 目标：输入框
    ('输入', [C('输入框', editable=True), C('发送'), C('标题', clickable=False)], 0),
    ('输入内容', [C('输入框', editable=True), C('列表', clickable=False)], 0),
    # 目标：关闭弹窗 / 允许
    ('允许', [C('允许'), C('拒绝')], 0),
    ('允许', [C('拒绝'), C('允许', center=0.4)], 1),
    ('关闭', [C('关闭'), C('确定')], 0),
    # 干扰：目标词出现在非点击文本里
    ('保存', [C('保存成功', clickable=False), C('保存')], 1),
    ('保存', [C('保存'), C('保存成功', clickable=False)], 0),
    # 干扰：不可见项
    ('下一步', [C('下一步', visible=False), C('下一步')], 1),
    ('下一步', [C('下一步'), C('下一步', visible=False)], 0),
    # 干扰：面积过大的容器
    ('列表', [C('列表', clickable=False, area=0.8), C('列表项')], 1),
    ('打开', [C('打开'), C('打开方式', area=0.6)], 0),
    # 中文长标签
    ('打开笔记应用写东西', [C('笔记'), C('新建笔记'), C('设置')], 0),
    ('查看所有笔记', [C('所有笔记'), C('新建笔记'), C('返回')], 0),
    ('切换到网格视图', [C('网格视图'), C('列表视图'), C('排序')], 0),
]


def lcs_len(a, b):
    if not a or not b:
        return 0
    prev = [0] * (len(b) + 1)
    for ca in a:
        cur = [0] * (len(b) + 1)
        for j, cb in enumerate(b, 1):
            cur[j] = prev[j - 1] + 1 if ca == cb else max(prev[j], cur[j - 1])
        prev = cur
    return prev[len(b)]


def featurize(goal, cand):
    label, clickable, visible, editable, area, center = cand
    g = (goal or '').strip()
    l = (label or '').strip()
    exact = 1.0 if l and l == g else 0.0
    goal_contains = 1.0 if (g and l and l in g) else 0.0
    label_contains = 1.0 if (g and l and g in l) else 0.0
    overlap = lcs_len(l, g) / max(len(g), 1)
    return [
        exact,
        goal_contains,
        label_contains,
        min(1.0, overlap),
        1.0 if clickable else 0.0,
        1.0 if visible else 0.0,
        1.0 if editable else 0.0,
        min(1.0, area * 4.0),
        max(0.0, min(1.0, center)),
        min(1.0, len(l) / 20.0),
    ]


def build_dataset():
    data = []
    for goal, cands, target in SCENARIOS:
        X = [featurize(goal, c) for c in cands]
        data.append((goal, X, target))
    return data


def softmax(scores):
    m = max(scores)
    exps = [math.exp(s - m) for s in scores]
    s = sum(exps)
    return [e / s for e in exps]


def train(epochs=400, lr=0.25, l2=1e-3):
    data = build_dataset()
    n_feat = len(FEATURES)
    w = [0.0] * n_feat
    rnd = random.Random(20261023)

    for ep in range(epochs):
        rnd.shuffle(data)
        for goal, X, target in data:
            scores = [sum(wi * xi for wi, xi in zip(w, x)) for x in X]
            p = softmax(scores)
            for i, x in enumerate(X):
                grad_scale = p[i] - (1.0 if i == target else 0.0)
                for k in range(n_feat):
                    w[k] -= lr * (grad_scale * x[k] + l2 * w[k])
    return w, data


def evaluate(w, data):
    correct = 0
    margins = []
    for goal, X, target in data:
        scores = [sum(wi * xi for wi, xi in zip(w, x)) for x in X]
        pred = max(range(len(scores)), key=lambda i: scores[i])
        if pred == target:
            correct += 1
        p = softmax(scores)
        margins.append(p[pred])
    return correct / len(data), sum(margins) / len(margins)


def main():
    w, data = train()
    acc, conf = evaluate(w, data)

    # 规则基线：完全匹配优先，其次包含，再次点击性
    def rule_pick(X):
        best, best_score = 0, -1e9
        for i, x in enumerate(X):
            s = x[0] * 3 + x[1] * 2 + x[2] * 1.5 + x[3] + x[4] * 0.5 + x[5] * 0.5
            if s > best_score:
                best, best_score = i, s
        return best

    rule_correct = sum(1 for _, X, t in data if rule_pick(X) == t) / len(data)

    print(f'样本 {len(data)} 场景，特征 {len(FEATURES)} 维')
    print(f'训练集准确率: {acc:.1%}  (规则基线 {rule_correct:.1%})')
    print(f'平均置信度: {conf:.3f}')
    for name, wi in sorted(zip(FEATURES, w), key=lambda kv: -abs(kv[1])):
        print(f'  {name:16s} {wi:+.3f}')

    model = {
        'version': '1.0',
        'kind': 'jev_like_linear_decider',
        'features': FEATURES,
        'weights': [round(x, 5) for x in w],
        'train_scenarios': len(data),
        'train_accuracy': round(acc, 4),
        'rule_baseline_accuracy': round(rule_correct, 4),
        'note': '合成场景训练；真实界面需用真机候选集重训',
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with io.open(OUT, 'w', encoding='utf-8') as f:
        json.dump(model, f, ensure_ascii=False, indent=1)
    print(f'写出 {OUT}  {os.path.getsize(OUT)/1024:.1f} KB')


if __name__ == '__main__':
    main()
