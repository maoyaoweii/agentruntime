"""把 HuggingFace tokenizer.json 转成 Android 端好解析的紧凑格式。

原因：org.json 解析 3.5MB 会建整棵对象树，Android 上内存与耗时都不可接受。
转换后 vocab.txt / merges.txt 是逐行格式，边读边建索引，峰值内存可控。
"""
import io
import json
import os

SRC = r'E:/agentruntime/agent/src/main/assets/models/smolvlm/tokenizer.json'
OUT_DIR = r'E:/agentruntime/agent/src/main/assets/models/smolvlm'

with io.open(SRC, encoding='utf-8') as f:
    d = json.load(f)

model = d['model']
vocab = model['vocab']          # token -> id
merges = model['merges']        # ["a b", ...]
added = d.get('added_tokens', [])

# vocab.txt：按 id 升序，每行一个 token（换行用转义，避免破坏行格式）
items = sorted(vocab.items(), key=lambda kv: kv[1])
max_id = items[-1][1]
lines = [''] * (max_id + 1)
for tok, tid in items:
    lines[tid] = tok.replace('\n', '\\n').replace('\r', '\\r')

vocab_path = os.path.join(OUT_DIR, 'vocab.txt')
with io.open(vocab_path, 'w', encoding='utf-8', newline='\n') as f:
    for i, line in enumerate(lines):
        if line == '' and i not in [t for _, t in items]:
            # 空洞（不应出现）用占位，保证行号 == id
            f.write('<unused%d>\n' % i)
        else:
            f.write(line + '\n')

# merges.txt
merges_path = os.path.join(OUT_DIR, 'merges.txt')
with io.open(merges_path, 'w', encoding='utf-8', newline='\n') as f:
    for m in merges:
        if isinstance(m, list):
            f.write(m[0] + ' ' + m[1] + '\n')
        else:
            f.write(m + '\n')

# meta.json：特殊 token 与预处理参数
special = {}
for t in added:
    special[t['content']] = t['id']

meta = {
    'vocab_size': len(vocab),
    'merges_count': len(merges),
    'added_tokens': special,
    'model_type': model.get('type'),
    'byte_fallback': bool(model.get('byte_fallback', False)),
    'pre_tokenizer': 'digits+bytelevel',
}
meta_path = os.path.join(OUT_DIR, 'tokenizer_meta.json')
with io.open(meta_path, 'w', encoding='utf-8') as f:
    json.dump(meta, f, ensure_ascii=False, indent=1)

for p in (vocab_path, merges_path, meta_path):
    print(f'{os.path.getsize(p)/1024:9.1f} KB  {os.path.basename(p)}')

print('vocab entries', len(vocab), 'merges', len(merges), 'added', len(special))
print('special sample', dict(list(special.items())[:8]))
