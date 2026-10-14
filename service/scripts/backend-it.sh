#!/bin/bash
# 업무 백엔드 통합 시험(Testcontainers)을 WSL(Ubuntu-24.04) 안에서 돌린다. Windows 쪽 파일은 바꾸지 않고 WSL 사용자 폴더의 복사본에서 돌린다.
# 필요: WSL에 Docker(docker.io, 사용자가 docker 그룹), 인터넷(처음 한 번 JDK 21·Gradle·시험용 이미지를 받음)
#   wsl -d Ubuntu-24.04 -- bash /mnt/c/Contest/service/scripts/backend-it.sh              # 전체
#   wsl -d Ubuntu-24.04 -- bash /mnt/c/Contest/service/scripts/backend-it.sh '*TeamStart*' # 일부
# 결과: ~/it/test.log 끝의 "N tests completed, M failed", 자세한 것은 ~/it/backend/build/reports/tests/test/index.html
set -e
SRC=/mnt/c/Contest/service/backend
if [ ! -x "$HOME/jdk21/bin/java" ]; then
  mkdir -p "$HOME/jdk21"
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" | tar -xz -C "$HOME/jdk21" --strip-components=1
fi
mkdir -p "$HOME/it"
rm -rf "$HOME/it/backend"
cp -r "$SRC" "$HOME/it/backend"
rm -rf "$HOME/it/backend/build" "$HOME/it/backend/.gradle"
cd "$HOME/it/backend"
sed -i 's/\r$//' gradlew
chmod +x gradlew
export JAVA_HOME="$HOME/jdk21" PATH="$HOME/jdk21/bin:/usr/local/bin:/usr/bin:/bin"
if [ -n "$1" ]; then ./gradlew test --continue --tests "$1" 2>&1 | tee "$HOME/it/test.log"; else ./gradlew test --continue 2>&1 | tee "$HOME/it/test.log"; fi
