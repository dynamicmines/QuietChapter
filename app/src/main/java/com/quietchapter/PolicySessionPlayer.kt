package com.quietchapter

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Standard session controls always reach policy, including Pause during automatic pause. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PolicySessionPlayer(
    player: Player,
    private val userPlayback: (Boolean) -> Unit,
    private val userStop: () -> Unit,
    private val changingMedia: () -> Unit,
    private val chapterNavigation: (Boolean) -> Unit,
    private val manualSeek: () -> Unit = {},
) : ForwardingSimpleBasePlayer(player) {
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        userPlayback(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        userStop()
        return super.handleStop()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> chapterNavigation(true)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> chapterNavigation(false)
            else -> { manualSeek(); return super.handleSeek(mediaItemIndex, positionMs, seekCommand) }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        changingMedia()
        return super.handleSetMediaItems(items, startIndex, startPositionMs)
    }

    override fun handleAddMediaItems(index: Int, items: List<MediaItem>): ListenableFuture<*> {
        changingMedia()
        return super.handleAddMediaItems(index, items)
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        changingMedia()
        return super.handleRemoveMediaItems(fromIndex, toIndex)
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, items: List<MediaItem>): ListenableFuture<*> {
        changingMedia()
        return super.handleReplaceMediaItems(fromIndex, toIndex, items)
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        changingMedia()
        return super.handleMoveMediaItems(fromIndex, toIndex, newIndex)
    }

    override fun getState(): State {
        val state = super.getState()
        val commands = state.availableCommands.buildUpon()
            .remove(Player.COMMAND_SET_AUDIO_ATTRIBUTES)
            .remove(Player.COMMAND_SET_REPEAT_MODE)
            .remove(Player.COMMAND_SET_SHUFFLE_MODE)
        if (state.playlist.isNotEmpty()) commands.addAll(Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        return state.buildUpon().setAvailableCommands(commands.build()).build()
    }

    fun refresh() = invalidateState()
}
