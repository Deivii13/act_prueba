package au.com.shiftyjelly.pocketcasts.podcasts.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.LiveDataReactiveStreams
import androidx.lifecycle.ViewModel
import au.com.shiftyjelly.pocketcasts.models.entity.Folder
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.to.FolderItem
import au.com.shiftyjelly.pocketcasts.models.to.RefreshState
import au.com.shiftyjelly.pocketcasts.models.to.SignInState
import au.com.shiftyjelly.pocketcasts.models.type.PodcastsSortType
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.FolderManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.user.UserManager
import com.jakewharton.rxrelay2.BehaviorRelay
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Flowable.combineLatest
import io.reactivex.rxkotlin.Observables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.Optional
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

@HiltViewModel
class PodcastsViewModel
@Inject constructor(
    private val podcastManager: PodcastManager,
    private val episodeManager: EpisodeManager,
    private val folderManager: FolderManager,
    private val settings: Settings,
    userManager: UserManager
) : ViewModel(), CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    private val folderUuidObservable = BehaviorRelay.create<Optional<String>>().apply { accept(Optional.empty()) }

    data class FolderState(
        val folder: Folder?,
        val items: List<FolderItem>,
        val isSignedInAsPlus: Boolean
    )

    val signInState: LiveData<SignInState> = LiveDataReactiveStreams.fromPublisher(userManager.getSignInState())

    val folderState: LiveData<FolderState> = LiveDataReactiveStreams.fromPublisher(
        combineLatest(
            // monitor all subscribed podcasts, get the podcast in 'Episode release date' as the rest can be done in memory
            podcastManager.observePodcastsOrderByLatestEpisode(),
            // monitor all the folders
            folderManager.observeFolders()
                .switchMap { folders ->
                    if (folders.isEmpty()) {
                        Flowable.just(emptyList())
                    } else {
                        // monitor the folder podcasts
                        val observeFolderPodcasts = folders.map { folder ->
                            podcastManager
                                .observePodcastsInFolderOrderByUserChoice(folder)
                                .map { podcasts ->
                                    FolderItem.Folder(
                                        folder = folder,
                                        podcasts = podcasts
                                    )
                                }
                        }
                        Flowable.zip(observeFolderPodcasts) { results ->
                            results.toList().filterIsInstance<FolderItem.Folder>()
                        }
                    }
                },
            // monitor the folder uuid
            folderUuidObservable.toFlowable(BackpressureStrategy.LATEST),
            // monitor the home folder sort order
            settings.podcastSortTypeObservable.toFlowable(BackpressureStrategy.LATEST),
            // show folders for Plus users
            userManager.getSignInState()
        ) { podcasts, folders, folderUuidOptional, podcastSortOrder, signInState ->
            val folderUuid = folderUuidOptional.orElse(null)
            if (!signInState.isSignedInAsPlus) {
                FolderState(
                    items = buildPodcastItems(podcasts, podcastSortOrder),
                    folder = null,
                    isSignedInAsPlus = false
                )
            } else if (folderUuid == null) {
                FolderState(
                    items = buildHomeFolderItems(podcasts, folders, podcastSortOrder),
                    folder = null,
                    isSignedInAsPlus = true
                )
            } else {
                val openFolder = folders.firstOrNull { it.uuid == folderUuid }
                if (openFolder == null) {
                    FolderState(
                        items = emptyList(),
                        folder = null,
                        isSignedInAsPlus = true
                    )
                } else {
                    FolderState(
                        items = openFolder.podcasts.map { FolderItem.Podcast(it) },
                        folder = openFolder.folder,
                        isSignedInAsPlus = true
                    )
                }
            }
        }
            .doOnNext { adapterState = it.items.toMutableList() }
    )

    val folder: Folder?
        get() = folderState.value?.folder

    private fun buildHomeFolderItems(podcasts: List<Podcast>, folders: List<FolderItem>, podcastSortType: PodcastsSortType): List<FolderItem> {
        if (podcastSortType == PodcastsSortType.EPISODE_DATE_NEWEST_TO_OLDEST) {
            val items = mutableListOf<FolderItem>()
            val uuidToFolder = folders.associateBy({ it.uuid }, { it }).toMutableMap()
            for (podcast in podcasts) {
                if (podcast.folderUuid == null) {
                    items.add(FolderItem.Podcast(podcast))
                } else {
                    // add the folder in the position of the podcast with the latest release date
                    val folder = uuidToFolder.remove(podcast.folderUuid)
                    if (folder != null) {
                        items.add(folder)
                    }
                }
            }
            if (uuidToFolder.isNotEmpty()) {
                items.addAll(uuidToFolder.values)
            }
            return items
        } else {
            val folderUuids = folders.map { it.uuid }.toHashSet()
            val items = podcasts
                // add the podcasts not in a folder or if the folder doesn't exist
                .filter { podcast -> podcast.folderUuid == null || !folderUuids.contains(podcast.folderUuid) }
                .map { FolderItem.Podcast(it) }
                .toMutableList<FolderItem>()
                // add the folders
                .apply { addAll(folders) }

            return items.sortedWith(podcastSortType.folderComparator)
        }
    }

    private fun buildPodcastItems(podcasts: List<Podcast>, podcastSortType: PodcastsSortType): List<FolderItem> {
        val items = podcasts.map { podcast -> FolderItem.Podcast(podcast) }
        return when (podcastSortType) {
            PodcastsSortType.EPISODE_DATE_NEWEST_TO_OLDEST -> items
            else -> items.sortedWith(podcastSortType.folderComparator)
        }
    }

    val podcastUuidToBadge: LiveData<Map<String, Int>> =
        LiveDataReactiveStreams.fromPublisher(
            settings.podcastBadgeTypeObservable
                .toFlowable(BackpressureStrategy.LATEST)
                .switchMap { badgeType ->
                    return@switchMap when (badgeType) {
                        Settings.BadgeType.ALL_UNFINISHED -> episodeManager.getPodcastUuidToBadgeUnfinished()
                        Settings.BadgeType.LATEST_EPISODE -> episodeManager.getPodcastUuidToBadgeLatest()
                        else -> Flowable.just(emptyMap())
                    }
                }
        )

    // We only want the current badge type when loading for this observable or else it will rebind the adapter every time the badge changes. We use take(1) for this.
    val layoutChangedLiveData = LiveDataReactiveStreams.fromPublisher(Observables.combineLatest(settings.podcastLayoutObservable, settings.podcastBadgeTypeObservable.take(1)).toFlowable(BackpressureStrategy.LATEST))

    val refreshObservable: LiveData<RefreshState> = LiveDataReactiveStreams.fromPublisher(settings.refreshStateObservable.toFlowable(BackpressureStrategy.LATEST))

    var adapterState: MutableList<FolderItem> = mutableListOf()
    /**
     * User rearranges the grid with a drag
     */
    fun moveFolderItem(fromPosition: Int, toPosition: Int): List<FolderItem> {
        if (adapterState.isEmpty() || toPosition >= adapterState.size) {
            return adapterState
        }

        if (fromPosition < toPosition) {
            for (index in fromPosition until toPosition) {
                Collections.swap(adapterState, index, index + 1)
            }
        } else {
            for (index in fromPosition downTo toPosition + 1) {
                Collections.swap(adapterState, index, index - 1)
            }
        }
        return adapterState.toList()
    }

    fun commitMoves() {
        launch {
            saveSortOrder()
        }
    }

    fun refreshPodcasts() {
        podcastManager.refreshPodcasts("Pull down")
    }

    fun setFolderUuid(folderUuid: String?) {
        folderUuidObservable.accept(Optional.ofNullable(folderUuid))
    }

    fun isFolderOpen(): Boolean {
        return folderUuidObservable.value?.isPresent ?: false
    }

    private suspend fun saveSortOrder() {
        folderManager.updateSortPosition(adapterState)

        val folder = folder
        if (folder == null) {
            settings.setPodcastsSortType(sortType = PodcastsSortType.DRAG_DROP, sync = true)
        } else {
            folderManager.updateSortType(folderUuid = folder.uuid, podcastsSortType = PodcastsSortType.DRAG_DROP)
        }
    }

    fun updateFolderSort(uuid: String, podcastsSortType: PodcastsSortType) {
        launch {
            folderManager.updateSortType(folderUuid = uuid, podcastsSortType = podcastsSortType)
        }
    }
}