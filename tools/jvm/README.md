# Проверки на JVM без Android SDK

Математика приложения (`app/src/main/java/dev/inknotes/math`, кроме `MathNotes.kt`) — чистый Kotlin. Её тесты гоняются за секунды прямо `kotlinc`, без Gradle и Android SDK. Так удобно работать в облачной песочнице, где SDK нет.

| Скрипт | Что делает |
|---|---|
| `setup.sh` | Скачивает kotlinc 2.2.21 в `build/kotlinc` (один раз; `build/` не в репозитории) |
| `test.sh [Классы…]` | Компилирует математику и её тесты, запускает все `*Test` пакета `dev.inknotes.math` (или перечисленные) |
| `tune.sh имя=значение,… Классы…` | Те же тесты с изменёнными порогами `Tune` (после `test.sh`): что сломает порог, видно до правки |
| `translation.sh` | `TranslationTest`: русский текст только первым аргументом `tr()` |

- `junit/Shim.kt` — маленькая замена JUnit: `@Test`, `@Before`, `Assert`, `Assume`, `Runner`. Если тесту понадобится ещё что-то из JUnit, добавь сюда.
- `junit/Toggle.kt` — запуск с изменёнными `Tune`.
- **Стенд формул CROHME.** `tools/formula-bench.sh --fetch` скачивает данные (≈60 МБ, проверка хэша) в `build/crohme`. После этого `test.sh` гоняет и `FormulaBenchTest`, и `MetamorphicTest`. Регрессия формул роняет тест. Базовая линия лежит в `app/src/test/resources/formula-outcomes.tsv`; обновляется через `tools/formula-bench.sh --update` и только при намеренном изменении.
- **Что здесь не проверяется**, а проверяется только в CI или на устройстве:
  - компиляция Android-кода (Compose, `MathNotes`, редактор);
  - `androidTest`;
  - ML Kit.

  После правок UI жди зелёный CI.

Переменные: `KOTLINC` — путь к своему kotlinc, `OUT` — куда компилировать.
