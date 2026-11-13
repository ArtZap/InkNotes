#!/usr/bin/env bash
# Релиз InkNotes одной командой: проверки → тесты → подписанный APK в release/.
#
#   tools/release.sh                  тесты + релизная сборка текущей версии
#   tools/release.sh --version 0.31   сначала поднять versionName до 0.31 и versionCode на 1
#   tools/release.sh --device         ещё и тесты на подключённом планшете (adb)
#   tools/release.sh --install        после сборки поставить релиз на планшет
#   tools/release.sh --skip-tests     только сборка (если тесты уже прогнаны)
#   tools/release.sh --allow-dirty    собирать с незакоммиченными изменениями
#   tools/release.sh --new-key        создать ключ подписи, если его ещё нет (один раз!)
#
# Результат: release/InkNotes-<версия>.apk, рядом .txt с коммитом, versionCode и SHA-256,
# полный лог сборки — release/build-<версия>.log. Папка release/ в git не попадает.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD

# ---------- параметры ----------
NEW_VERSION="" DEVICE=0 INSTALL=0 SKIP_TESTS=0 ALLOW_DIRTY=0 NEW_KEY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --version) NEW_VERSION="${2:?после --version нужна версия, например 0.31}"; shift 2 ;;
    --device) DEVICE=1; shift ;;
    --install) INSTALL=1; shift ;;
    --skip-tests) SKIP_TESTS=1; shift ;;
    --allow-dirty) ALLOW_DIRTY=1; shift ;;
    --new-key) NEW_KEY=1; shift ;;
    -h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Неизвестный параметр: $1 (см. --help)" >&2; exit 2 ;;
  esac
done

if [ -t 1 ]; then B=$'\e[1m' G=$'\e[32m' R=$'\e[31m' Y=$'\e[33m' N=$'\e[0m'; else B="" G="" R="" Y="" N=""; fi
step() { echo; echo "${B}==> $*${N}"; }
ok()   { echo "${G}✓ $*${N}"; }
warn() { echo "${Y}! $*${N}"; }
die()  { printf "%b\n" "${R}✗ $*${N}" >&2; exit 1; }

# ---------- окружение (как в README; уже заданные переменные не трогаем) ----------
step "Окружение"
[ -z "${ANDROID_HOME:-}" ] && [ -d /mnt/addmem/android-sdk ] && export ANDROID_HOME=/mnt/addmem/android-sdk
[ -z "${JAVA_HOME:-}" ] && [ -d /usr/lib/jvm/temurin-21-jdk-amd64 ] && export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
[ -z "${GRADLE_USER_HOME:-}" ] && [ -d /mnt/addmem/.gradle ] && export GRADLE_USER_HOME=/mnt/addmem/.gradle
if [ -z "${ANDROID_HOME:-}" ] && [ -f local.properties ]; then
  ANDROID_HOME=$(sed -n 's/^sdk\.dir=//p' local.properties | sed 's/\\:/:/g'); export ANDROID_HOME
fi
[ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ] || die "Не найден Android SDK: задайте ANDROID_HOME"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$JAVA" -version >/dev/null 2>&1 || die "Не найдена Java: задайте JAVA_HOME (нужна 17 или новее)"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
ADB="$ANDROID_HOME/platform-tools/adb"
BUILD_TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
APKSIGNER="${BUILD_TOOLS}apksigner"
# Временные файлы — в папке проекта, а не в /tmp: на ноутбуке /tmp маленький, и сборка
# (Gradle, компилятор Kotlin) падала с «No space left on device».
TMP_DIR="$PWD/.tmp"
rm -rf "$TMP_DIR" && mkdir -p "$TMP_DIR" || die "Не создать $TMP_DIR"
export TMPDIR="$TMP_DIR"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMP_DIR${JAVA_TOOL_OPTIONS:+ $JAVA_TOOL_OPTIONS}"
FREE_MB=$(df -Pm . | awk 'NR==2{print $4}')
[ "${FREE_MB:-0}" -ge 2048 ] || die "Мало места на диске с проектом: ${FREE_MB} МБ свободно, нужно хотя бы 2 ГБ.
  Освободить: ./gradlew clean, удалить старые release/build-*.log, кэш $GRADLE_USER_HOME/caches."
echo "SDK:  $ANDROID_HOME"
echo "Java: $("$JAVA" -version 2>&1 | head -1)"
ok "окружение в порядке"

# ---------- ключ подписи ----------
step "Ключ подписи"
if [ ! -f keystore.properties ]; then
  if [ "$NEW_KEY" = 1 ]; then
    mkdir -p keystore
    [ -e keystore/inknotes-release.jks ] && die "keystore/inknotes-release.jks уже есть, а keystore.properties нет — восстановите пароли, новый ключ не создаю"
    PASS=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
    "$KEYTOOL" -genkeypair -v -keystore keystore/inknotes-release.jks -alias inknotes \
      -keyalg RSA -keysize 4096 -validity 36500 -storepass "$PASS" -keypass "$PASS" \
      -dname "CN=InkNotes" >/dev/null
    printf 'storeFile=keystore/inknotes-release.jks\nstorePassword=%s\nkeyAlias=inknotes\nkeyPassword=%s\n' "$PASS" "$PASS" > keystore.properties
    chmod 600 keystore.properties keystore/inknotes-release.jks
    warn "Создан новый ключ. Сохраните keystore/ и keystore.properties в надёжном месте:"
    warn "без них обновить уже установленный релиз будет нельзя."
  else
    die "Нет keystore.properties — релиз будет без подписи. Верните файл с ключом или запустите с --new-key (только если релиза ещё никто не ставил)."
  fi
fi
STORE=$(sed -n 's/^storeFile=//p' keystore.properties)
[ -f "$STORE" ] || die "Ключ $STORE из keystore.properties не найден"
ok "ключ: $STORE"

# ---------- планшет: что на нём стоит (до сборки, чтобы не ждать зря) ----------
# Отладочная и релизная сборки подписаны разными ключами: одна поверх другой не встаёт,
# а удаление приложения удаляет тетради. Поэтому сверяем ключ заранее.
hexsha() { tr -d ': ' | tr 'A-F' 'a-f'; }
installed_cert() {
  local path tmp
  path=$("$ADB" shell pm path dev.inknotes 2>/dev/null | sed -n 's/^package://p' | grep base.apk | tr -d '\r' | head -1)
  [ -n "$path" ] || return 0
  tmp=$(mktemp -d) || die "Не создать временную папку (нет места?)"
  local cert
  cert=$("$ADB" pull "$path" "$tmp/base.apk" >/dev/null 2>&1 \
    && "$APKSIGNER" verify --print-certs "$tmp/base.apk" 2>/dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | hexsha)
  rm -rf "$tmp"
  # Приложение есть, а ключ не прочитать: «ставим как есть» тут опасно — лучше остановиться.
  [ -n "$cert" ] || die "InkNotes на планшете есть, но не удалось прочитать его ключ (adb pull или apksigner). Проверьте место на диске и запустите снова."
  echo "$cert"
}
if [ "$DEVICE" = 1 ] || [ "$INSTALL" = 1 ]; then
  step "Планшет"
  [ "$DEVICE" = 1 ] && [ "$INSTALL" = 1 ] && die "--device и --install вместе нельзя: тесты ставят на планшет отладочную сборку, а релиз подписан другим ключом и поверх неё не встанет."
  [ -x "$ADB" ] || die "Нет adb в $ANDROID_HOME/platform-tools"
  [ "$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l)" -ge 1 ] || die "Планшет не подключён (adb devices пуст)"
  ON_TABLET=""
  if [ -x "$APKSIGNER" ]; then ON_TABLET=$(installed_cert) || exit 1; fi
  RELEASE_CERT=$(sed -n 's/^storePassword=//p' keystore.properties | {
    read -r sp; "$KEYTOOL" -list -v -keystore "$STORE" -alias "$(sed -n 's/^keyAlias=//p' keystore.properties)" -storepass "$sp" 2>/dev/null; } \
    | sed -n 's/^[[:space:]]*SHA256: //p' | head -1 | hexsha)
  if [ -z "$ON_TABLET" ]; then
    ok "InkNotes на планшете нет — ставим как есть"
  elif [ "$ON_TABLET" = "$RELEASE_CERT" ]; then
    ok "на планшете релизная сборка"
    [ "$DEVICE" = 1 ] && die "На планшете релиз, а тесты ставят отладочную сборку с другим ключом — не встанет. Запустите без --device (или на другом устройстве)."
  else
    ok "на планшете отладочная сборка (там ваши тетради)"
    [ "$INSTALL" = 1 ] && die "Релиз не встанет поверх отладочной сборки: у них разные ключи. Чтобы перейти на релиз — сначала
  перенесите тетради (Поделиться → файл), удалите приложение и запустите снова с --install.
  Для друзей --install не нужен: отправьте им файл из release/."
  fi
fi

# ---------- git ----------
step "Состояние git"
if git rev-parse --git-dir >/dev/null 2>&1; then
  BRANCH=$(git rev-parse --abbrev-ref HEAD)
  if [ -n "$(git status --porcelain)" ]; then
    git status --short
    [ "$ALLOW_DIRTY" = 1 ] && warn "есть незакоммиченные изменения (--allow-dirty)" \
      || die "Есть незакоммиченные изменения: закоммитьте их или добавьте --allow-dirty"
  fi
  COMMIT=$(git rev-parse --short HEAD)
  ok "ветка $BRANCH, коммит $COMMIT"
else
  BRANCH="?" COMMIT="?"; warn "не git-репозиторий"
fi

# ---------- версия ----------
GRADLE_FILE=app/build.gradle.kts
read_version() {
  VERSION_NAME=$(sed -n 's/^ *versionName *= *"\(.*\)".*/\1/p' "$GRADLE_FILE" | head -1)
  VERSION_CODE=$(sed -n 's/^ *versionCode *= *\([0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1)
}
read_version
if [ -n "$NEW_VERSION" ]; then
  step "Новая версия $NEW_VERSION"
  NEXT_CODE=$((VERSION_CODE + 1))
  sed -i "s/^\( *versionName *= *\)\".*\"/\1\"$NEW_VERSION\"/; s/^\( *versionCode *= *\)[0-9]*/\1$NEXT_CODE/" "$GRADLE_FILE"
  read_version
  ok "versionName $VERSION_NAME, versionCode $VERSION_CODE (не забудьте закоммитить и обновить справку)"
fi
step "Версия $VERSION_NAME (versionCode $VERSION_CODE)"
mkdir -p release
HISTORY=release/history.txt
if [ -f "$HISTORY" ]; then
  LAST_CODE=$(awk '{print $2}' "$HISTORY" | sort -n | tail -1)
  if [ -n "$LAST_CODE" ] && [ "$VERSION_CODE" -eq "$LAST_CODE" ]; then
    # The same code installs over the last release (only a lower one is refused): a rebuild, e.g. with a key added.
    warn "versionCode $VERSION_CODE уже собирался: этот APK встанет поверх него, но на других устройствах обновление с тем же номером могут не предложить."
  elif [ -n "$LAST_CODE" ] && [ "$VERSION_CODE" -lt "$LAST_CODE" ]; then
    LAST_NAME=$(awk -v c="$LAST_CODE" '$2==c{print $1}' "$HISTORY" | tail -1)
    warn "уже был релиз с versionCode $LAST_CODE ($LAST_NAME): поверх него этот APK не установится как обновление."
    warn "Поднимите версию: tools/release.sh --version <новая>"
    if [ -t 0 ]; then read -r -p "Всё равно собрать? [y/N] " a; [ "$a" = y ] || exit 1; else exit 1; fi
  fi
fi
ok "версия годится"

LOG="$ROOT/release/build-$VERSION_NAME.log"
: > "$LOG"
gradle() { ./gradlew --console=plain "$@" 2>&1 | tee -a "$LOG"; return "${PIPESTATUS[0]}"; }

# ---------- тесты ----------
if [ "$SKIP_TESTS" = 0 ]; then
  step "Юнит-тесты и сборка инструментальных тестов"
  gradle :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin \
    || die "Тесты не прошли. Отчёт: app/build/reports/tests/testDebugUnitTest/index.html, лог: $LOG"
  ok "юнит-тесты прошли"

  if [ "$DEVICE" = 1 ]; then
    step "Тесты на планшете"
    [ -x "$ADB" ] || die "Нет adb в $ANDROID_HOME/platform-tools"
    [ "$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l)" -ge 1 ] || die "Планшет не подключён (adb devices пуст)"
    # Как в README: ставим поверх, не удаляя приложение, чтобы тетради остались.
    gradle :app:installDebug :app:installDebugAndroidTest || die "Не удалось установить на планшет"
    OUT=$("$ADB" shell am instrument -w dev.inknotes.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee -a "$LOG")
    echo "$OUT" | tail -5
    echo "$OUT" | grep -q "^OK (" || die "Тесты на планшете не прошли, подробности в $LOG"
    ok "тесты на планшете прошли"
  fi
else
  warn "тесты пропущены (--skip-tests)"
fi

# ---------- сборка ----------
step "Релизная сборка"
gradle :app:assembleRelease || die "Сборка не удалась, лог: $LOG"
APK=""
for f in app/build/outputs/apk/release/*.apk; do
  case "$f" in *unsigned*) ;; *) [ -f "$f" ] && APK=$f && break ;; esac
done
[ -n "$APK" ] || die "APK не найден в app/build/outputs/apk/release (не подписался?)"
ok "собран $APK"

# ---------- проверка подписи ----------
step "Проверка подписи"
if [ -x "$APKSIGNER" ]; then
  CERTS=$("$APKSIGNER" verify --print-certs "$APK" 2>&1) || die "Подпись не проходит проверку:\n$CERTS"
  echo "$CERTS" | grep -q "CN=Android Debug" && die "APK подписан отладочным ключом — это не релиз"
  CERT_SHA=$(echo "$CERTS" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
  ok "подпись в порядке, сертификат SHA-256: $CERT_SHA"
  if [ -f "$HISTORY" ] && ! grep -q "$CERT_SHA" "$HISTORY"; then
    warn "этот ключ отличается от ключа прошлых релизов — у друзей обновление не встанет поверх!"
  fi
else
  CERT_SHA="?"; warn "apksigner не найден, подпись не проверена"
fi

# ---------- результат ----------
step "Результат"
OUT_APK="release/InkNotes-$VERSION_NAME.apk"
cp "$APK" "$OUT_APK"
# R8 shortens names: the map turns a crash trace from this APK back into readable code.
MAPPING=app/build/outputs/mapping/release/mapping.txt
if [ -f "$MAPPING" ]; then
  cp "$MAPPING" "release/InkNotes-$VERSION_NAME-mapping.txt"
  ok "карта имён для разбора падений: release/InkNotes-$VERSION_NAME-mapping.txt"
fi
SHA=$(sha256sum "$OUT_APK" | awk '{print $1}')
SIZE=$(du -h "$OUT_APK" | awk '{print $1}')
cat > "release/InkNotes-$VERSION_NAME.txt" <<EOF
InkNotes $VERSION_NAME (versionCode $VERSION_CODE)
Собран:     $(date '+%Y-%m-%d %H:%M')
Ветка:      $BRANCH, коммит $COMMIT
APK SHA-256: $SHA
Сертификат:  $CERT_SHA
EOF
grep -v "^$VERSION_NAME $VERSION_CODE " "$HISTORY" 2>/dev/null > "$HISTORY.tmp" || true
echo "$VERSION_NAME $VERSION_CODE $COMMIT $SHA $CERT_SHA" >> "$HISTORY.tmp"
mv "$HISTORY.tmp" "$HISTORY"
ok "$OUT_APK ($SIZE)"
echo "    SHA-256 $SHA"

if [ "$INSTALL" = 1 ]; then
  step "Установка на планшет"
  if ! "$ADB" install -r "$OUT_APK"; then
    warn "Не встало поверх. Если там сборка с другим ключом — сначала перенесите"
    warn "тетради (Поделиться → файл), потом удалите приложение и поставьте релиз."
    exit 1
  fi
  ok "установлено"
fi

echo
echo "${G}${B}Готово: $OUT_APK${N}"
