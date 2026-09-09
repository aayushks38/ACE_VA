package com.ace.app.brain

/**
 * Authoritative provider delegating directly to GemmaBrainManager to ensure
 * exactly ONE shared LocalBrain instance exists per app process.
 */
object BrainProvider {
    fun getBrain(): LocalBrain {
        return GemmaBrainManager.getBrain()
    }
}
