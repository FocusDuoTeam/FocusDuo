package dev.focusduo

/** Demo is opt-in; starting the application never silently selects fake data. */
data class LaunchOptions(
    val demo: Boolean = false,
    val width: Int = 1280,
    val height: Int = 800,
    val help: Boolean = false,
) {
    companion object {
        fun parse(args: Array<String>): LaunchOptions {
            var result = LaunchOptions()
            args.forEach { argument ->
                result = when {
                    argument == "--demo" -> result.copy(demo = true)
                    argument == "--help" || argument == "-h" -> result.copy(help = true)
                    argument.startsWith("--width=") -> result.copy(width = dimension(argument, 840))
                    argument.startsWith("--height=") -> result.copy(height = dimension(argument, 600))
                    else -> throw IllegalArgumentException("Неизвестный аргумент: $argument. Используйте --help.")
                }
            }
            return result
        }

        private fun dimension(argument: String, minimum: Int): Int {
            val value = argument.substringAfter('=').toIntOrNull()
            require(value != null && value in minimum..4096) {
                "Размер окна должен быть целым числом от $minimum до 4096: $argument"
            }
            return value
        }
    }
}
