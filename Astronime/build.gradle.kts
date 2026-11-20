// use an integer for version numbers
version = 10

cloudstream {
    description = "Astronime - anime subtitle Indonesia, runtime-clean parser, player_ajax, Turbovid HLS, and Hydrax/Abyss resolver flow"
    authors = listOf("7Escanor")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )

    language = "id"
    isCrossPlatform = false
    iconUrl = "https://t0.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://astronime.id&size=256"
}
