package de.tromuwelle.android

/** OpenSubsonic getAlbumList2 and search3 use a maximum of 100 items per page. */
internal const val SUBSONIC_PAGE_SIZE = 100

/**
 * Only offset-based endpoints can be paged. getRandomSongs returns a new
 * unordered sample on every call and getAlbum/getArtist return their full list.
 * Treating those as paged results used to duplicate queue items indefinitely.
 */
internal fun canPageSubsonic(section: String, artistId: String, albumId: String,
                             search: String): Boolean = when (section) {
    "albums" -> artistId.isBlank()
    "songs" -> albumId.isBlank() && search.isNotBlank()
    else -> false
}
