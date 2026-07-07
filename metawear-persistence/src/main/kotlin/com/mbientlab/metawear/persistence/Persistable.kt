package com.mbientlab.metawear.persistence

import kotlin.reflect.KClass

/**
 * The flat `(f0..f3, accuracy)` packing stored in [SampleRecord].
 *
 * Field mapping by sensor kind:
 * ```
 * CartesianFloat          f0=x  f1=y  f2=z  f3=0   accuracy=0
 * Quaternion              f0=w  f1=x  f2=y  f3=z   accuracy=0
 * EulerAngles             f0=heading f1=pitch f2=roll f3=yaw  accuracy=0
 * CorrectedCartesianFloat f0=x  f1=y  f2=z  f3=0   accuracy=<value>
 * Float                   f0=v  f1=0  f2=0  f3=0   accuracy=0
 * Boolean                 f0=1|0 f1=0 f2=0  f3=0   accuracy=0
 * ```
 */
data class PersistedValues(
    val f0: Float,
    val f1: Float = 0f,
    val f2: Float = 0f,
    val f3: Float = 0f,
    /** CorrectedCartesianFloat calibration accuracy (0 for all other types). */
    val accuracy: Int = 0,
)

/**
 * A codec that stores sample type [S] in — and reconstructs it from — the flat
 * layout of [SampleRecord].
 *
 * Each supported sample type (including `Float` and `Boolean`, which cannot be
 * given new supertypes) gets one singleton codec object — see
 * `PersistableConformances.kt`. Store methods that operate on a specific
 * sample type take the codec object as an explicit argument.
 *
 * Adding support for a new sensor type means:
 * 1. Add the data class to `ValueTypes.kt` in `:metawear-protocol`.
 * 2. Add one codec object in `PersistableConformances.kt`.
 */
interface Persistable<S : Any> {

    /** String stored in [SessionRecord.sensorKind]. */
    val persistenceKind: String

    /** The sample class, used to select CSV columns for [PersistenceStore.exportTable]. */
    val sampleType: KClass<S>

    /** Pack a sample into the flat (f0–f3, accuracy) layout used by [SampleRecord]. */
    fun persistenceValues(sample: S): PersistedValues

    /** Reconstruct a sample from the flat layout. */
    fun fromPersistence(f0: Float, f1: Float, f2: Float, f3: Float, accuracy: Int): S
}
