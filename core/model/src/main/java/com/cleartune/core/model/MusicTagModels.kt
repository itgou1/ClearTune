package com.cleartune.core.model

/** Separate credentials and directory mapping for the tag service, scoped to a music account. */
data class MusicTagSettings(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val sourceRoot: String = "",
    val targetRoot: String = "/app/media",
    val source: String = "netease",
    val allowHttp: Boolean = false,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    override fun toString(): String = "MusicTagSettings(configured=$configured)"
}

enum class MusicTagField(val wireName: String, val label: String) {
    TITLE("title", "标题"), ARTIST("artist", "艺术家"), ALBUM("album", "专辑"),
    YEAR("year", "年份"), GENRE("genre", "流派"), LYRICS("lyrics", "歌词"),
    COVER("album_img", "封面"),
}

data class MusicTagCandidate(
    val id: String,
    val source: String,
    val values: Map<MusicTagField, String>,
)

data class MusicTagSnapshot(val path: String, val values: Map<MusicTagField, String>)

/** Server paths are POSIX paths, independent of the Android or development host filesystem. */
object MusicTagPath {
    fun resolve(path: String, sourceRoot: String, targetRoot: String): String {
        fun clean(value: String): String {
            require(value.none { it.code < 32 || it == '\\' } && "\${" !in value) { "路径含有不支持的字符" }
            require(value.split('/').none { it == ".." || it == "." }) { "路径不能包含相对跳转" }
            return value.trimEnd('/')
        }
        val original = clean(path)
        val target = clean(targetRoot)
        require(target.startsWith('/') && target.isNotBlank() && target != "/") { "请配置 Music Tag 音乐目录" }
        val source = clean(sourceRoot)
        val relative = if (original.startsWith('/')) {
            require(source.startsWith('/') && source.isNotBlank() && original.startsWith("$source/")) {
                "歌曲为绝对路径，请在设置中配置匹配的服务器音乐目录"
            }
            original.removePrefix("$source/")
        } else original
        require(relative.isNotBlank() && !relative.startsWith('/') && !relative.endsWith('/')) { "歌曲路径无效" }
        require(relative.split('/').none(String::isBlank)) { "歌曲路径无效" }
        return "$target/$relative"
    }
}
