package ru.acestar.vpn.data.model

/**
 * Описание всех тарифов приложения.
 * trafficLimitBytes = null означает безлимитный трафик.
 * durationDays = null означает бессрочный тариф.
 */
enum class Tariff(
    val id: String,
    val displayName: String,
    val trafficLimitBytes: Long?,   // null = безлимит
    val durationDays: Int?,         // null = бессрочно
    val isUnlimited: Boolean = trafficLimitBytes == null
) {
    TRIAL(
        id = "Trial",
        displayName = "Пробный",
        trafficLimitBytes = null,   // безлимит по трафику
        durationDays = 7            // 7 дней
    ),
    UNLIMITED(
        id = "Unlimited",
        displayName = "Безлимитный",
        trafficLimitBytes = null,   // безлимит
        durationDays = null         // бессрочно
    ),
    BASIC(
        id = "Basic",
        displayName = "Базовый",
        trafficLimitBytes = 3L * 1024 * 1024 * 1024,   // 3 ГБ
        durationDays = 30
    ),
    ADVANCED(
        id = "Advanced",
        displayName = "Продвинутый",
        trafficLimitBytes = 10L * 1024 * 1024 * 1024,  // 10 ГБ
        durationDays = 30
    ),
    PREMIUM(
        id = "Premium",
        displayName = "Премиум",
        trafficLimitBytes = null,   // безлимит
        durationDays = 365
    ),
    HOUR(
        id = "Hour",
        displayName = "Часовой",
        trafficLimitBytes = 1L * 1024 * 1024 * 1024,   // 1 ГБ
        durationDays = null         // ограничен по трафику, не по времени
    );

    companion object {
        fun fromId(id: String): Tariff =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: TRIAL

        /** Форматирует лимит трафика в читаемый вид */
        fun formatTrafficLimit(bytes: Long?): String {
            if (bytes == null) return "Безлимитно"
            return when {
                bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} ГБ"
                bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} МБ"
                else -> "${bytes / 1024} КБ"
            }
        }

        /** Форматирует использованный трафик */
        fun formatUsed(bytes: Long): String {
            return when {
                bytes >= 1024L * 1024 * 1024 ->
                    String.format("%.2f ГБ", bytes / (1024.0 * 1024 * 1024))
                bytes >= 1024L * 1024 ->
                    String.format("%.1f МБ", bytes / (1024.0 * 1024))
                bytes >= 1024L ->
                    String.format("%.0f КБ", bytes / 1024.0)
                else -> "$bytes Б"
            }
        }
    }
}
