package com.mbientlab.metawear.persistence

import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.model.CorrectedCartesianFloat
import com.mbientlab.metawear.model.EulerAngles
import com.mbientlab.metawear.model.Quaternion
import kotlin.reflect.KClass

// One codec object per supported sample type: Float, Boolean, CartesianFloat,
// CorrectedCartesianFloat, Quaternion, EulerAngles.

/** `CartesianFloat` ↔ (x, y, z, 0, 0). */
object CartesianFloatPersistable : Persistable<CartesianFloat> {
    override val persistenceKind: String = "cartesian"
    override val sampleType: KClass<CartesianFloat> = CartesianFloat::class
    override fun persistenceValues(sample: CartesianFloat): PersistedValues =
        PersistedValues(sample.x, sample.y, sample.z)
    override fun fromPersistence(f0: Float, f1: Float, f2: Float, f3: Float, accuracy: Int): CartesianFloat =
        CartesianFloat(x = f0, y = f1, z = f2)
}

/** `Quaternion` ↔ (w, x, y, z, 0). */
object QuaternionPersistable : Persistable<Quaternion> {
    override val persistenceKind: String = "quaternion"
    override val sampleType: KClass<Quaternion> = Quaternion::class
    override fun persistenceValues(sample: Quaternion): PersistedValues =
        PersistedValues(sample.w, sample.x, sample.y, sample.z)
    override fun fromPersistence(f0: Float, f1: Float, f2: Float, f3: Float, accuracy: Int): Quaternion =
        Quaternion(w = f0, x = f1, y = f2, z = f3)
}

/** `EulerAngles` ↔ (heading, pitch, roll, yaw, 0). */
object EulerAnglesPersistable : Persistable<EulerAngles> {
    override val persistenceKind: String = "euler"
    override val sampleType: KClass<EulerAngles> = EulerAngles::class
    override fun persistenceValues(sample: EulerAngles): PersistedValues =
        PersistedValues(sample.heading, sample.pitch, sample.roll, sample.yaw)
    override fun fromPersistence(f0: Float, f1: Float, f2: Float, f3: Float, accuracy: Int): EulerAngles =
        EulerAngles(heading = f0, pitch = f1, roll = f2, yaw = f3)
}

/** `CorrectedCartesianFloat` ↔ (x, y, z, 0, accuracy). */
object CorrectedCartesianFloatPersistable : Persistable<CorrectedCartesianFloat> {
    override val persistenceKind: String = "corrected-cartesian"
    override val sampleType: KClass<CorrectedCartesianFloat> = CorrectedCartesianFloat::class
    override fun persistenceValues(sample: CorrectedCartesianFloat): PersistedValues =
        PersistedValues(sample.x, sample.y, sample.z, accuracy = sample.accuracy)
    override fun fromPersistence(f0: Float, f1: Float, f2: Float, f3: Float, accuracy: Int): CorrectedCartesianFloat =
        CorrectedCartesianFloat(x = f0, y = f1, z = f2, accuracy = accuracy)
}

/** `Float` ↔ (value, 0, 0, 0, 0). */
object FloatPersistable : Persistable<Float> {
    override val persistenceKind: String = "float"
    override val sampleType: KClass<Float> = Float::class
    override fun persistenceValues(sample: Float): PersistedValues =
        PersistedValues(sample)
    override fun fromPersistence(f0: Float, f1: Float, f2: Float, f3: Float, accuracy: Int): Float =
        f0
}

/** `Boolean` ↔ (1|0, 0, 0, 0, 0). */
object BoolPersistable : Persistable<Boolean> {
    override val persistenceKind: String = "bool"
    override val sampleType: KClass<Boolean> = Boolean::class
    override fun persistenceValues(sample: Boolean): PersistedValues =
        PersistedValues(if (sample) 1f else 0f)
    override fun fromPersistence(f0: Float, f1: Float, f2: Float, f3: Float, accuracy: Int): Boolean =
        f0 != 0f
}
