package de.voci.auth
import java.util.concurrent.atomic.AtomicLong

/** Invalidates work immediately, even while refresh holds its serialization mutex. */
class SessionEpoch {
 private val value=AtomicLong()
 fun current()=value.get()
 fun invalidate()=value.incrementAndGet()
 fun valid(snapshot:Long)=snapshot==value.get()
}
