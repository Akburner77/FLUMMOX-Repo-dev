cloudstream {
    description = "BingeCloud — Movies & TV Series"
    authors = listOf("FlummoxGamer")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
}

android {
    namespace = "com.flummox.bingecloud"
}

dependencies {
    implementation("com.github.recloudstream:cloudstream:-SNAPSHOT")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
