rootProject.name = "StreamPulse"

val disabled = listOf(
    "Miro-Temp",
    "Phisher-Temp",
    "temp_repos",
    "_patches",
)

val enabledPluginsFile = File(rootDir, "enabled_plugins.txt")
val enabledPlugins = if (enabledPluginsFile.exists()) {
    enabledPluginsFile
        .readText()
        .removePrefix("\uFEFF")
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .toSet()
} else {
    emptySet()
}

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        if (enabledPlugins.isEmpty() || enabledPlugins.contains(dir.name)) {
            include(dir.name)
        }
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
