package com.masterhub.sheeda

enum class SheedaState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    HAPPY,
    SLEEP,
    WALK_LEFT,
    WALK_RIGHT,
    DRAG,
    FALL,
    ONFLOOR
}

object SheedaSkin {

    val idleFrames = intArrayOf(
        R.drawable.sheeda_stand_0,
        R.drawable.sheeda_stand_1,
        R.drawable.sheeda_stand_2,
        R.drawable.sheeda_stand_3,
        R.drawable.sheeda_stand_4,
        R.drawable.sheeda_stand_5
    )

    val listeningFrames = intArrayOf(
        R.drawable.sheeda_listening_0,
        R.drawable.sheeda_listening_1,
        R.drawable.sheeda_listening_2,
        R.drawable.sheeda_listening_3
    )

    val thinkingFrames = intArrayOf(
        R.drawable.sheeda_thinking_0,
        R.drawable.sheeda_thinking_1,
        R.drawable.sheeda_thinking_2,
        R.drawable.sheeda_thinking_3
    )

    val speakingFrames = intArrayOf(
        R.drawable.sheeda_speaking_0,
        R.drawable.sheeda_speaking_1,
        R.drawable.sheeda_speaking_2,
        R.drawable.sheeda_speaking_3
    )

    val happyFrames = intArrayOf(
        R.drawable.sheeda_happy_0,
        R.drawable.sheeda_happy_1,
        R.drawable.sheeda_happy_2,
        R.drawable.sheeda_happy_3
    )

    val sleepFrames = intArrayOf(
        R.drawable.sheeda_sleep_0,
        R.drawable.sheeda_sleep_1,
        R.drawable.sheeda_sleep_2,
        R.drawable.sheeda_sleep_3
    )

    val walkLeftFrames = intArrayOf(
        R.drawable.sheeda_leftwalk_0,
        R.drawable.sheeda_leftwalk_1,
        R.drawable.sheeda_leftwalk_2,
        R.drawable.sheeda_leftwalk_3
    )

    val walkRightFrames = intArrayOf(
        R.drawable.sheeda_rightwalk_0,
        R.drawable.sheeda_rightwalk_1,
        R.drawable.sheeda_rightwalk_2,
        R.drawable.sheeda_rightwalk_3
    )

    val dragFrame = R.drawable.sheeda_drag_0
    val fallFrame = R.drawable.sheeda_fall_0
    val onFloorFrame = R.drawable.sheeda_onfloor_0

    fun getFramesForState(state: SheedaState): IntArray {
        return when (state) {
            SheedaState.IDLE -> idleFrames
            SheedaState.LISTENING -> listeningFrames
            SheedaState.THINKING -> thinkingFrames
            SheedaState.SPEAKING -> speakingFrames
            SheedaState.HAPPY -> happyFrames
            SheedaState.SLEEP -> sleepFrames
            SheedaState.WALK_LEFT -> walkLeftFrames
            SheedaState.WALK_RIGHT -> walkRightFrames
            SheedaState.DRAG -> intArrayOf(dragFrame)
            SheedaState.FALL -> intArrayOf(fallFrame)
            SheedaState.ONFLOOR -> intArrayOf(onFloorFrame)
        }
    }
}
