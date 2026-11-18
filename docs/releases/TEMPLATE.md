# Overdrive <version>

Template for `docs/releases/<tag>.md`. Replace every `<...>` placeholder. Delete a section's
content only if it is empty, and keep the heading. Russian first, English second.

---

## Что нового

<Два-три коротких пункта: что добавлено или изменено в этой версии.>

## What is new

<Two or three short items: what was added or changed in this version.>

---

## Что попробовать

1. <Конкретный шаг на планшете или на велосипеде, с ожидаемым результатом.>
2. <Следующий шаг.>

## What you can try

1. <A concrete step on the tablet or on the bike, with the expected result.>
2. <Next step.>

---

## Ещё не проверено на железе

<Явно перечислите, что не проверялось на реальном оборудовании: трэнер, пульсометр, Click, планшет.
Если все проверки этой версии выполнены на устройстве, так и напишите.>

## Not tested on hardware yet

<State explicitly what has not been tested on real hardware: trainer, heart-rate strap, Click,
tablet. If every check for this version was run on the device, say so.>

---

## Известные проблемы

- <Проблема: что происходит, при каких условиях. Если известного обхода нет, так и напишите.>

## Known problems

- <Problem: what happens and under which conditions. If there is no known workaround, say so.>

---

## Установка

- Планшет: `overdrive-ride-<version>.apk`.
- Телефон: `overdrive-companion-<version>.apk`.
- Обновление поверх предыдущей версии: удалять приложение не нужно, данные сохраняются.

## Installing

- Tablet: `overdrive-ride-<version>.apk`.
- Phone: `overdrive-companion-<version>.apk`.
- Updating over the previous version: no uninstall is needed, data is kept.

---

## Контрольные суммы

Файлы `SHA256SUMS` приложены к релизу. Проверка: `sha256sum -c SHA256SUMS`.

## Checksums

`SHA256SUMS` is attached to the release. Verify with `sha256sum -c SHA256SUMS`.
