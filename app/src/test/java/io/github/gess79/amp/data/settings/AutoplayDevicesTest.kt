package io.github.gess79.amp.data.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoplayDevicesTest {

    @Test
    fun roundTrip_keepsAddressNameAndAllowed() {
        val stored = encodeAutoplayDevices(
            listOf(
                AutoplayDevice("AA:BB", "Car", allowed = true),
                AutoplayDevice("CC:DD", "Kitchen speaker", allowed = false),
            ),
        )
        val decoded = decodeAutoplayDevices(stored)
        assertThat(decoded.map { it.address }).containsExactly("AA:BB", "CC:DD").inOrder()
        assertThat(decoded.first { it.address == "CC:DD" }.allowed).isFalse()
    }

    @Test
    fun decode_blankIsEmpty() {
        assertThat(decodeAutoplayDevices(null)).isEmpty()
        assertThat(decodeAutoplayDevices("")).isEmpty()
    }
}
