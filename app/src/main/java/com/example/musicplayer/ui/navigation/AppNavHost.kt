package com.example.musicplayer.ui.navigation

import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.musicplayer.data.model.Song
import com.example.musicplayer.ui.AppViewModel
import com.example.musicplayer.ui.components.AddToPlaylistDialog
import com.example.musicplayer.ui.components.LocalOpenSettings
import com.example.musicplayer.ui.components.LocalSongActions
import com.example.musicplayer.ui.components.SongActions
import com.example.musicplayer.ui.components.pluralize
import com.example.musicplayer.ui.detail.AlbumDetailScreen
import com.example.musicplayer.ui.detail.ArtistDetailScreen
import com.example.musicplayer.ui.library.FolderDetailScreen
import com.example.musicplayer.ui.detail.PlaylistDetailScreen
import com.example.musicplayer.ui.library.AlbumsScreen
import com.example.musicplayer.ui.library.ArtistsScreen
import com.example.musicplayer.ui.library.FoldersScreen
import com.example.musicplayer.ui.library.PlaylistsScreen
import com.example.musicplayer.ui.library.SongsScreen
import com.example.musicplayer.ui.player.MiniPlayer
import com.example.musicplayer.ui.player.NowPlayingScreen
import com.example.musicplayer.ui.search.SearchScreen
import com.example.musicplayer.ui.settings.MetadataReviewScreen
import com.example.musicplayer.ui.settings.SettingsScreen
import com.example.musicplayer.ui.settings.SongInfoDialog
import kotlinx.coroutines.launch

private object Routes {
    const val SONGS = "songs"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val PLAYLISTS = "playlists"
    const val FOLDERS = "folders"
    const val FOLDER = "folder?path={path}"
    const val ALBUM = "album/{id}"
    const val ARTIST = "artist/{id}"
    const val PLAYLIST = "playlist/{id}"
    const val SEARCH = "search"
    const val NOW_PLAYING = "now_playing"
    const val SETTINGS = "settings"
    const val METADATA_REVIEW = "metadata_review"

    fun album(id: Long) = "album/$id"
    fun artist(id: Long) = "artist/$id"
    fun playlist(id: Long) = "playlist/$id"
    fun folder(path: String) = "folder?path=${android.net.Uri.encode(path)}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private fun tabs(showAlbums: Boolean) = listOfNotNull(
    Tab(Routes.FOLDERS, "Folders", Icons.Filled.Folder),
    Tab(Routes.SONGS, "Songs", Icons.Filled.MusicNote),
    Tab(Routes.ALBUMS, "Albums", Icons.Filled.Album).takeIf { showAlbums },
    Tab(Routes.ARTISTS, "Artists", Icons.Filled.Person),
    Tab(Routes.PLAYLISTS, "Playlists", Icons.AutoMirrored.Filled.PlaylistPlay),
)

@Composable
fun MusicAppRoot(vm: AppViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showAlbums by vm.showAlbumsTab.collectAsStateWithLifecycle()
    val tabs = remember(showAlbums) { tabs(showAlbums) }
    val isTopLevel = tabs.any { it.route == route }
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var addingToPlaylist by remember { mutableStateOf<List<Song>?>(null) }
    var infoSong by remember { mutableStateOf<Song?>(null) }

    fun toast(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    val songActions = remember(vm, nav) {
        SongActions(
            playNext = { songs ->
                vm.player.playNext(songs)
                toast(if (songs.size == 1) "Playing \"${songs[0].title}\" next" else "Playing ${songs.size} songs next")
            },
            addToQueue = { songs ->
                vm.player.addToQueue(songs)
                toast(if (songs.size == 1) "Added \"${songs[0].title}\" to queue" else "Added ${pluralize(songs.size, "song")} to queue")
            },
            addToPlaylist = { songs -> addingToPlaylist = songs },
            goToAlbum = { song -> nav.navigate(Routes.album(song.albumId)) },
            goToArtist = { song -> nav.navigate(Routes.artist(song.artistId)) },
            showMessage = ::toast,
            showInfo = { song -> infoSong = song },
        )
    }

    CompositionLocalProvider(
        LocalSongActions provides songActions,
        LocalOpenSettings provides { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (route != Routes.NOW_PLAYING) {
                    Column(if (isTopLevel) Modifier else Modifier.navigationBarsPadding()) {
                        MiniPlayer(vm.player, playerState) { nav.navigate(Routes.NOW_PLAYING) }
                        if (isTopLevel) BottomTabs(nav, route, tabs)
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Routes.FOLDERS,
                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            ) {
                val toSearch = { nav.navigate(Routes.SEARCH) }
                val toAlbum = { id: Long -> nav.navigate(Routes.album(id)) }
                val toArtist = { id: Long -> nav.navigate(Routes.artist(id)) }
                val back: () -> Unit = { nav.popBackStack() }

                composable(Routes.SONGS) { SongsScreen(vm, toSearch) }
                composable(Routes.ALBUMS) { AlbumsScreen(vm, toSearch, toAlbum) }
                composable(Routes.ARTISTS) { ArtistsScreen(vm, toSearch, toArtist) }
                composable(Routes.FOLDERS) { FoldersScreen(vm, toSearch) { nav.navigate(Routes.folder(it)) } }
                composable(Routes.FOLDER, arguments = listOf(navArgument("path") { type = NavType.StringType })) {
                    FolderDetailScreen(vm, it.arguments?.getString("path").orEmpty(), back) { p -> nav.navigate(Routes.folder(p)) }
                }
                composable(Routes.PLAYLISTS) {
                    PlaylistsScreen(vm, toSearch) { nav.navigate(Routes.playlist(it)) }
                }
                composable(Routes.ALBUM, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    AlbumDetailScreen(vm, it.arguments!!.getLong("id"), back, toArtist)
                }
                composable(Routes.ARTIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    ArtistDetailScreen(vm, it.arguments!!.getLong("id"), back, toAlbum)
                }
                composable(Routes.PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    PlaylistDetailScreen(vm, it.arguments!!.getLong("id"), back)
                }
                composable(Routes.SETTINGS) { SettingsScreen(vm, back) { nav.navigate(Routes.METADATA_REVIEW) } }
                composable(Routes.METADATA_REVIEW) { MetadataReviewScreen(vm, back) }
                composable(Routes.SEARCH) { SearchScreen(vm, back, toAlbum, toArtist) }
                composable(
                    Routes.NOW_PLAYING,
                    enterTransition = { slideInVertically { it } },
                    exitTransition = { slideOutVertically { it } },
                    popExitTransition = { slideOutVertically { it } },
                ) {
                    NowPlayingScreen(vm.player, playerState, onClose = back)
                }
            }
        }

        infoSong?.let { song ->
            SongInfoDialog(vm, song, onDismiss = { infoSong = null }, onMessage = ::toast)
        }

        addingToPlaylist?.let { songs ->
            AddToPlaylistDialog(
                playlists = playlists,
                onDismiss = { addingToPlaylist = null },
                onPick = { playlist ->
                    vm.addToPlaylist(playlist.id, songs)
                    addingToPlaylist = null
                    toast("Added to \"${playlist.name}\"")
                },
                onCreateNew = { name ->
                    vm.createPlaylist(name, songs)
                    addingToPlaylist = null
                    toast("Created \"${name.trim()}\"")
                },
            )
        }
    }
}

@Composable
private fun BottomTabs(nav: NavHostController, currentRoute: String?, tabs: List<Tab>) {
    NavigationBar {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}
