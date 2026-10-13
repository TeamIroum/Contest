#!/usr/bin/env bash
# AI 기여자 표시 검사. CI(no-ai-coauthor.yml)와 로컬 커밋 훅(.githooks/commit-msg)이 같은 기준을 쓴다.
#   CI:   BASE, HEAD 사이의 커밋과 PR_BODY를 검사
#   훅:   bash .github/scripts/check-ai-coauthor.sh --message <커밋 메시지 파일>
set -euo pipefail

AI='claude|anthropic|openai|chatgpt|\bgpt[-_ ]?[0-9a-z]*\b|codex|copilot|gemini|cursor(agent)?|devin|aider'
# 공동 작성자 줄: Co-authored-by: 이름 <메일>
COAUTHOR="^[[:space:]]*co-authored-by:.*(${AI})"
# 도구가 자동으로 붙이는 서명
SIGNATURE='generated with \[?(claude code|chatgpt|codex|copilot|gemini|cursor)'

fail=0
report() { echo "::error::$1"; fail=1; }

check_message() {   # $1: 설명, stdin: 메시지
  local where="$1" msg
  msg="$(cat)"
  if grep -Eiq "$COAUTHOR" <<<"$msg"; then
    report "$where: AI를 공동 작성자로 올린 줄이 있습니다 → $(grep -Ei "$COAUTHOR" <<<"$msg" | head -1)"
  fi
  if grep -Eiq "$SIGNATURE" <<<"$msg"; then
    report "$where: AI 도구 자동 서명이 있습니다 → $(grep -Ei "$SIGNATURE" <<<"$msg" | head -1)"
  fi
}

if [[ "${1:-}" == "--message" ]]; then
  check_message "커밋 메시지" < "$2"
  if (( fail )); then
    echo "AI 도구를 공동 작성자로 올리지 않습니다. 해당 줄을 지우고 다시 커밋하세요." >&2
  fi
  exit $fail
fi

# 새 브랜치 push처럼 BASE가 없으면 HEAD 하나만 본다
if [[ -z "${BASE:-}" || "$BASE" =~ ^0+$ ]]; then
  range="$HEAD^!"
else
  range="$BASE..$HEAD"
fi

while IFS= read -r sha; do
  [[ -z "$sha" ]] && continue
  who="$(git log -1 --format='%an <%ae>%n%cn <%ce>' "$sha")"
  if grep -Eiq "$AI" <<<"$who"; then
    report "커밋 ${sha:0:7}: AI 도구가 작성자·커미터로 되어 있습니다 → $(tr '\n' ' ' <<<"$who")"
  fi
  check_message "커밋 ${sha:0:7}" < <(git log -1 --format=%B "$sha")
done < <(git rev-list "$range")

if [[ "${EVENT:-}" == "pull_request" && -n "${PR_BODY:-}" ]]; then
  check_message "PR 본문" <<<"$PR_BODY"
fi

if (( fail )); then
  echo "AI 도구(Claude, ChatGPT·Codex, Copilot, Gemini 등)는 기여자로 올리지 않습니다. 해당 줄을 지운 뒤 다시 올려 주세요."
  exit 1
fi
echo "AI 기여자 표시 없음"
