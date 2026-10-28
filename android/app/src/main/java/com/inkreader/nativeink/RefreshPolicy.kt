package com.inkreader.nativeink

/** Simulation timing only; all choices resolve to the same clean final image. */
internal object RefreshPolicy {
    enum class Scene { PAGE, MENU, SURFACE, CLEAN }
    fun duration(mode:Int,scene:Scene):Long = when(scene) {
        Scene.CLEAN -> when(mode){1->1040L;2->680L;else->880L}
        Scene.SURFACE -> when(mode){1->600L;2->260L;else->420L}
        Scene.MENU -> when(mode){1->360L;2->140L;else->200L}
        Scene.PAGE -> when(mode){2->220L;else->460L}
    }
}
