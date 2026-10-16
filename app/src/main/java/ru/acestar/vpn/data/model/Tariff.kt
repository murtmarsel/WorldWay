package ru.acestar.vpn.data.model

/**
 * Описание всех тарифов приложения.
 * trafficLimitBytes = null означает безлимитный трафик.
 * durationDays = null и durationHours = null означает бессрочный тариф.
 */
enum class Tariff(
    val id: String,
    val displayName: String,
    val trafficLimitBytes: Long?,   // null = безлимит
    val durationDays: Int?,         // null = не ограничен по дням
    val durationHours: Int? = null, // для часовых тарифов
    val isUnlimited: Boolean = trafficLimitBytes == null
) {
    TRIAL(
        id = "Trial",
        displayName = "Пробный",
        trafficLimitBytes = null,
        durationDays = 7
    ),
    UNLIMITED(
        id = "Unlimited",
        displayName = "Безлимитный",
        trafficLimitBytes = null,
        durationDays = null
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
        trafficLimitBytes = null,
        durationDays = 365
    ),
    HOUR(
        id = "Hour",
        displayName = "Часовой",
        trafficLimitBytes = 1L * 1024 * 1024 * 1024,   // 1 ГБ
        durationDays = null,
        durationHours = 1                               // 1 час
    );

    companion object {
        fun fromId(id: String): Tariff =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: TRIAL

        /** Форматирует лимит трафика в читаемый вид (с дробной частью, без лишних нулей) */
        fun formatTrafficLimit(bytes: Long?): String {
            if (bytes == null) return "Безлимитно"
            return when {
                bytes >= 1024L * 1024 * 1024 -> "${trimDecimal(bytes / (1024.0 * 1024 * 1024))} ГБ"
                bytes >= 1024L * 1024 -> "${trimDecimal(bytes / (1024.0 * 1024))} МБ"
                else -> "${trimDecimal(bytes / 1024.0)} КБ"
            }
        }

        /** Форматирует число с 2 знаками после запятой, убирая хвостовые нули: 10.00→10, 3.50→3.5 */
        private fun trimDecimal(value: Double): String {
            val s = String.format(java.util.Locale.US, "%.2f", value)
            return s.trimEnd('0').trimEnd('.')
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
