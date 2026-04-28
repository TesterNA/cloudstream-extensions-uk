// use an integer for version numbers
version = 8

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "HDrezka — фільми, серіали, мультфільми та аніме."
    authors = listOf("CakesTwix")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Cartoon",
        "Anime"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=rezka-ua.in&sz=%size%"
}
