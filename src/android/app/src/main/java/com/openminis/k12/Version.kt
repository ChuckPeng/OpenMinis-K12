package com.openminis.k12

/**
 * K12 SemVer — independent of upstream Minis.
 * Formula and tag rules: `docs/k12/conventions.md`.
 *
 * versionCode = MAJOR×1_000_000 + MINOR×1_000 + PATCH×10 + STAGE
 * STAGE 1–8 = milestone pre-release on this X.Y.Z line; 9 = GA.
 */
object Version {
    const val NAME = "1.0.0-m2"
    const val CODE = 1_000_003

    /** Upstream OpenMinis Android baseline recorded at this milestone. */
    const val UPSTREAM_VERSION_NAME = "1.15"
    const val UPSTREAM_VERSION_CODE = 37

    /**
     * Device API integer the app speaks (heartbeat / pair).
     * OpenAPI `info.version` remains the contract revision (e.g. 0.0.0-r16).
     */
    const val DEVICE_API = 1

    /** Lowest server `device_api_version` this build will accept. */
    const val DEVICE_API_MIN = 1

    /** Git tag to apply after the M2 PR merges. */
    const val MILESTONE_TAG = "v1.0.0-m2"

    fun matchesCodeFormula(): Boolean {
        val major = 1
        val minor = 0
        val patch = 0
        val stage = 3 // m2
        return CODE == major * 1_000_000 + minor * 1_000 + patch * 10 + stage
    }
}
