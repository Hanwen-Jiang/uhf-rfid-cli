#!/usr/bin/env zsh
# list_tags.zsh —— 拉取云端标签库并美化输出（macOS / Linux）
#
# 用法：
#   ./cloud/list_tags.zsh               # 表格
#   ./cloud/list_tags.zsh --card        # 卡片（一条一块）
#   ./cloud/list_tags.zsh --unnamed     # 只看没命名的
#   ./cloud/list_tags.zsh --sort seen   # 按读到次数排（默认按名称）
#   ./cloud/list_tags.zsh --json        # 原样 JSON
#
# key 的取法（按顺序）：
#   1. 环境变量 RFID_API_KEY
#   2. $RFID_KEY_FILE
#   3. ~/.rfid-key
#   4. Z:/rfid/.tools/cf-key.txt（WSL 下能直接读 Windows 盘）
#
# 需要 curl 和 python3（macOS 装了 Xcode 命令行工具就有 python3）。
# 格式化**必须**用 python3 而不是纯 shell：中文字符是双宽，
# printf '%-10s' 按字符数补空格，中文列会参差不齐。

set -euo pipefail

BASE="${RFID_BASE:-https://rfid-tags.root-60a.workers.dev}"
MODE=table
UNNAMED=0
SORT=name

while (( $# )); do
  case "$1" in
    --card)    MODE=card ;;
    --json)    MODE=json ;;
    --unnamed) UNNAMED=1 ;;
    --sort)    shift; SORT="${1:-name}" ;;
    --sort=*)  SORT="${1#*=}" ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) print -u2 "不认识的参数：$1"; exit 2 ;;
  esac
  shift
done

# ---- 找 key
KEY="${RFID_API_KEY:-}"
if [[ -z "$KEY" && -n "${RFID_KEY_FILE:-}" && -r "$RFID_KEY_FILE" ]]; then
  KEY="$(<"$RFID_KEY_FILE")"
fi
if [[ -z "$KEY" && -r "$HOME/.rfid-key" ]]; then
  KEY="$(<"$HOME/.rfid-key")"
fi
if [[ -z "$KEY" && -r "Z:/rfid/.tools/cf-key.txt" ]]; then
  KEY="$(<Z:/rfid/.tools/cf-key.txt)"
fi
KEY="${KEY//[$'\r\n\t ']/}"      # 去掉换行和空格

if [[ -z "$KEY" ]]; then
  print -u2 "找不到 API key。设环境变量 RFID_API_KEY，或写到 ~/.rfid-key。"
  exit 1
fi

URL="$BASE/api/tags"
(( UNNAMED )) && URL="$URL?unnamed=1"

# User-Agent 不能省 —— Cloudflare 会 403 掉没有 UA 的请求
JSON="$(curl -sS -f \
  -H "Authorization: Bearer $KEY" \
  -H 'User-Agent: uhf-rfid-cli/1.0' \
  "$URL")" || { print -u2 "请求失败"; exit 1; }

if [[ "$MODE" == json ]]; then
  print -r -- "$JSON"
  exit 0
fi

# ---- 格式化交给 python3（按显示宽度对齐）
print -r -- "$JSON" | python3 -c '
import json, sys, unicodedata

mode = sys.argv[1]
order = sys.argv[2]

def width(s):
    """字符串的显示宽度：东亚宽字符算 2。"""
    return sum(2 if unicodedata.east_asian_width(c) in "WF" else 1 for c in str(s))

def pad(s, n):
    s = str(s)
    return s + " " * max(0, n - width(s))

data = json.load(sys.stdin)
tags = data.get("tags", [])
if not tags:
    print("（库里没有标签）")
    sys.exit(0)

key = {
    "name":    lambda t: (0, t.get("name") or "\uffff"),
    "seen":    lambda t: (0, -t.get("seenCount", 0)),
    "written": lambda t: (0, -t.get("writeCount", 0)),
    "suffix":  lambda t: (0, t.get("suffix", "")),
}.get(order, lambda t: (0, t.get("name") or "\uffff"))
tags.sort(key=key)

def name_of(t):
    return t.get("name") or "(未命名)"

if mode == "card":
    for i, t in enumerate(tags, 1):
        print("%2d. %s" % (i, name_of(t)))
        bits = ["后 5 位 %s" % t.get("suffix", "")]
        if t.get("status") == "anomaly":
            bits.append("异常")
        if t.get("seenCount"):
            bits.append("读到 %d 次" % t["seenCount"])
        if t.get("writeCount"):
            bits.append("写过 %d 次" % t["writeCount"])
        if t.get("operator"):
            bits.append(t["operator"])
        print("    " + "  ·  ".join(bits))
        print("    EPC  " + t.get("epc", ""))
        print("    TID  " + (t.get("tid") or ""))
        print()
else:
    head = ["名称", "后5位", "读到", "写过", "使用者", "EPC"]
    rows = [[name_of(t),
             t.get("suffix", ""),
             t.get("seenCount", 0),
             t.get("writeCount", 0),
             t.get("operator") or "",
             t.get("epc", "")] for t in tags]
    cols = list(zip(*([head] + rows)))
    w = [max(width(c) for c in col) for col in cols]
    print("  ".join(pad(h, x) for h, x in zip(head, w)).rstrip())
    print("  ".join("-" * x for x in w))
    for r in rows:
        print("  ".join(pad(c, x) for c, x in zip(r, w)).rstrip())

n = len(tags)
tail = "共 %d 条" % n
u = sum(1 for t in tags if not t.get("name"))
a = sum(1 for t in tags if t.get("status") == "anomaly")
if u: tail += "，%d 条未命名" % u
if a: tail += "，%d 条异常" % a
print()
print(tail)
' "$MODE" "$SORT"
