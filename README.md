# WorldWay

Android-приложение VPN с поддержкой OpenVPN и WireGuard, автоматическим получением конфигураций с сервера, избранными конфигурациями и управлением аккаунтом.

## Установка

Скачайте APK в разделе [Releases](https://github.com/murtmarsel/WorldWay/releases/latest). Обновляйте существующее приложение поверх установленного, чтобы сохранить данные. Для совместимого обновления используется прежний ключ подписи.

## Сборка

Требуются JDK 21 и Android SDK 36. Укажите локальный путь Android SDK в неотслеживаемом файле local.properties и выполните:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug -Pksp.incremental=false
```

Версия задаётся в version.properties. Перед следующим выпуском увеличьте и VERSION_NAME, и VERSION_CODE.

## Выпуски

[Инструкция по GitHub Releases и GitHub Actions](GITHUB_UPDATES_RU.md). Workflow сборки и публикации расположен в .github/workflows/android-release.yml. Ключ подписи и его пароли настраиваются через GitHub Secrets и не должны добавляться в исходники.

Сервер приложения развёртывается отдельно; SMTP-реквизиты в APK не включаются.
