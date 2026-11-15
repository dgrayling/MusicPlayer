# MusicPlayer

An Android music player (Kotlin, Jetpack Compose, Media3) for local files, built for messy libraries.

- Browse by folder, song, album, artist and playlist; queue, shuffle, move files between folders.
- **Metadata clean-up** that never touches your files: corrections live in the app's database.
  - Offline: strips junk (YouTube ids, "[320kbps]", "(Official Video)"), reads artist/title/track from
    file names and folders, recovers mis-encoded tags, detects playlist folders and swapped title/artist tags,
    and unifies artist spellings across the library.
  - Online (background, Wi-Fi only by default): looks songs up on [MusicBrainz](https://musicbrainz.org)
    (+ Cover Art Archive, iTunes as a fallback for art/genre), one lookup per album, with caches that
    generalize what one song teaches to the rest of the library. Uncertain matches wait for review.

## Build

Requires JDK 17 and the Android SDK.

```sh
./gradlew assembleDebug        # build
./gradlew testDebugUnitTest    # unit tests (Robolectric)
scripts/dev-sync.sh            # rebuild + install on a connected device on every change
```

MusicBrainz asks clients to identify themselves. Put a contact (email or project URL) in
`~/.gradle/gradle.properties`:

```properties
musicbrainzContact=you@example.com
```

## License

MIT
