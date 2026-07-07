package com.mbientlab.metawear.app.data

import com.mbientlab.metawear.persistence.BoolPersistable
import com.mbientlab.metawear.persistence.CartesianFloatPersistable
import com.mbientlab.metawear.persistence.CorrectedCartesianFloatPersistable
import com.mbientlab.metawear.persistence.EulerAnglesPersistable
import com.mbientlab.metawear.persistence.FloatPersistable
import com.mbientlab.metawear.persistence.Persistable
import com.mbientlab.metawear.persistence.QuaternionPersistable

/** Lookup from a stored session's `sensorKind` back to its sample codec. */
object Persistables {

    /**
     * The codec whose `persistenceKind` matches [kind], or `null` for an
     * unknown discriminator. The cast is safe by construction: each codec's
     * kind string is unique and the erased type is only used with samples the
     * same codec produced.
     */
    @Suppress("UNCHECKED_CAST")
    fun forKind(kind: String): Persistable<Any>? = when (kind) {
        CartesianFloatPersistable.persistenceKind -> CartesianFloatPersistable
        QuaternionPersistable.persistenceKind -> QuaternionPersistable
        EulerAnglesPersistable.persistenceKind -> EulerAnglesPersistable
        CorrectedCartesianFloatPersistable.persistenceKind -> CorrectedCartesianFloatPersistable
        FloatPersistable.persistenceKind -> FloatPersistable
        BoolPersistable.persistenceKind -> BoolPersistable
        else -> null
    } as Persistable<Any>?
}
