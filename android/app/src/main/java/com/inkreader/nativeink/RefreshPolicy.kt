package com.inkreader.nativeink

/** Simulation timing only; all choices resolve to the same clean final image. */
internal object RefreshPolicy {
    enum class Scene { PAGE, LAYOUT, MENU, SURFACE, CLEAN }
    fun duration(mode:Int,scene:Scene):Long = when(scene) {
        Scene.CLEAN -> when(mode){1->1040L;2->680L;else->880L}
        Scene.SURFACE -> when(mode){1->600L;2->260L;else->420L}
        Scene.MENU -> when(mode){1->360L;2->140L;else->200L}
        // Fixed by product decision: a page turn is a 220 ms ink refresh, never an LCD slide.
        Scene.PAGE -> 220L
        Scene.LAYOUT -> when(mode){2->260L;else->420L}
    }
}
